use std::sync::{
    Arc, Mutex, OnceLock,
    atomic::{AtomicU64, Ordering},
};

use anyhow::{Context as _, Result};
use aurelia_core::{
    audio::{self, AudioState},
    media_controls::{MediaControlsState, MediaEvent},
    models::NowPlayingPayload,
};

const PROGRESS_REPORT_INTERVAL_SECONDS: u64 = 10;

#[derive(Clone, Debug)]
pub struct PlaybackItem {
    pub server_url: String,
    pub token: String,
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub album_id: Option<String>,
    pub container: Option<String>,
    pub duration_seconds: u32,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct PlaybackSnapshot {
    pub is_playing: bool,
    pub is_finished: bool,
    pub position_seconds: f64,
    pub did_auto_advance: bool,
    pub auto_advanced_token: Option<u64>,
}

#[derive(Clone)]
pub struct PlaybackController {
    audio: Arc<AudioState>,
    media_controls: Arc<MediaControlsState>,
    current: Arc<Mutex<Option<CurrentPlayback>>>,
    prepared: Arc<Mutex<Option<PreparedPlayback>>>,
    prepare_generation: Arc<AtomicU64>,
    prepare_lock: Arc<tokio::sync::Mutex<()>>,
    seek_generation: Arc<AtomicU64>,
    seek_lock: Arc<tokio::sync::Mutex<()>>,
    command_generation: Arc<tokio::sync::watch::Sender<u64>>,
    command_lock: Arc<tokio::sync::Mutex<()>>,
    reports: Arc<OnceLock<tokio::sync::mpsc::Sender<Report>>>,
}

#[derive(Clone, Debug)]
struct CurrentPlayback {
    item: PlaybackItem,
    last_reported_second: u64,
}

#[derive(Clone, Debug)]
struct PreparedPlayback {
    token: u64,
    item: PlaybackItem,
}

impl PlaybackController {
    pub fn new() -> Self {
        let media_controls = Arc::new(MediaControlsState::new());
        if let Err(error) = media_controls.init(None) {
            tracing::warn!("Could not initialize desktop media controls: {error}");
        }

        Self {
            audio: Arc::new(AudioState::new()),
            media_controls,
            current: Arc::new(Mutex::new(None)),
            prepared: Arc::new(Mutex::new(None)),
            prepare_generation: Arc::new(AtomicU64::new(0)),
            prepare_lock: Arc::new(tokio::sync::Mutex::new(())),
            seek_generation: Arc::new(AtomicU64::new(0)),
            seek_lock: Arc::new(tokio::sync::Mutex::new(())),
            command_generation: Arc::new(tokio::sync::watch::channel(0).0),
            command_lock: Arc::new(tokio::sync::Mutex::new(())),
            reports: Arc::new(OnceLock::new()),
        }
    }

    /// Invalidate any in-flight preparation and return the generation for its replacement.
    pub fn begin_prepare(&self) -> u64 {
        *self.prepared.lock().expect("playback state poisoned") = None;
        self.prepare_generation.fetch_add(1, Ordering::SeqCst) + 1
    }

    pub async fn prepare_next(
        &self,
        prepared: Option<(u64, PlaybackItem)>,
        generation: u64,
    ) -> Result<()> {
        let _guard = self.prepare_lock.lock().await;
        if self.prepare_generation.load(Ordering::SeqCst) != generation {
            return Ok(());
        }

        let Some((token, item)) = prepared else {
            audio::audio_clear_prepared_next(&self.audio).await?;
            return Ok(());
        };
        let stream_url = aurelia_core::build_desktop_stream_url(
            item.server_url.clone(),
            item.token.clone(),
            item.id.clone(),
            item.container.clone(),
        );
        audio::audio_prepare_next(&self.audio, stream_url, item.token.clone()).await?;

        if self.prepare_generation.load(Ordering::SeqCst) != generation {
            audio::audio_clear_prepared_next(&self.audio).await?;
            return Ok(());
        }
        *self.prepared.lock().expect("playback state poisoned") =
            Some(PreparedPlayback { token, item });
        Ok(())
    }

    /// Called synchronously by the command issuer, before spawning async work.
    pub fn begin_command(&self) -> u64 {
        self.begin_prepare();
        self.begin_seek();
        self.command_generation
            .send_modify(|value| *value = value.wrapping_add(1));
        *self.command_generation.borrow()
    }

    pub async fn play(&self, item: PlaybackItem, volume: f32, generation: u64) -> Result<()> {
        let mut changes = self.command_generation.subscribe();
        let _guard = self.command_lock.lock().await;
        if *changes.borrow_and_update() != generation {
            return Ok(());
        }
        tokio::select! {
            biased;
            _ = changes.changed() => {
                self.stop_local().await?;
                Ok(())
            }
            result = self.play_local(item, volume) => result,
        }
    }

    async fn play_local(&self, item: PlaybackItem, volume: f32) -> Result<()> {
        self.stop_local().await?;

        audio::audio_init(&self.audio)
            .await
            .context("Could not initialize the audio output")?;
        audio::audio_set_volume(&self.audio, volume)
            .await
            .context("Could not set the playback volume")?;

        let stream_url = aurelia_core::build_desktop_stream_url(
            item.server_url.clone(),
            item.token.clone(),
            item.id.clone(),
            item.container.clone(),
        );
        audio::audio_play(&self.audio, stream_url, None, item.token.clone())
            .await
            .with_context(|| format!("Could not play {}", item.title))?;

        self.enqueue_report(Report::Start(item.clone()));

        let cover_url = item.album_id.as_ref().and_then(|album_id| {
            aurelia_core::build_image_url(
                item.server_url.clone(),
                item.token.clone(),
                album_id.clone(),
                "Primary".to_string(),
                Some(400),
                Some(90),
            )
            .ok()
            .flatten()
        });
        self.media_controls
            .update_now_playing(NowPlayingPayload {
                title: item.title.clone(),
                artist: Some(item.artist.clone()),
                album: Some(item.album.clone()),
                duration: Some(f64::from(item.duration_seconds)),
                cover_url,
            })
            .ok();
        self.media_controls
            .set_playback_status(true, Some(0.0))
            .ok();

        *self.current.lock().expect("playback state poisoned") = Some(CurrentPlayback {
            item,
            last_reported_second: 0,
        });
        Ok(())
    }

    pub async fn pause(&self) -> Result<()> {
        audio::audio_pause(&self.audio).await?;
        let position = audio::audio_get_position(&self.audio).await?;
        self.report_progress(position, true);
        self.media_controls
            .set_playback_status(false, Some(position))
            .ok();
        Ok(())
    }

    pub async fn resume(&self) -> Result<()> {
        audio::audio_resume(&self.audio).await?;
        let position = audio::audio_get_position(&self.audio).await?;
        self.report_progress(position, false);
        self.media_controls
            .set_playback_status(true, Some(position))
            .ok();
        Ok(())
    }

    pub fn begin_seek(&self) -> u64 {
        self.seek_generation.fetch_add(1, Ordering::SeqCst) + 1
    }

    pub async fn seek(&self, position_seconds: f64, generation: u64) -> Result<()> {
        let _guard = self.seek_lock.lock().await;
        if self.seek_generation.load(Ordering::SeqCst) != generation {
            return Ok(());
        }
        audio::audio_seek(&self.audio, position_seconds).await?;
        if self.seek_generation.load(Ordering::SeqCst) != generation {
            return Ok(());
        }
        let is_paused = !audio::audio_is_playing(&self.audio).await?;
        self.report_progress(position_seconds, is_paused);
        self.media_controls
            .set_playback_status(!is_paused, Some(position_seconds))
            .ok();
        Ok(())
    }

    pub async fn set_volume(&self, volume: f32) -> Result<()> {
        audio::audio_set_volume(&self.audio, volume).await
    }

    pub async fn stop(&self, generation: u64) -> Result<()> {
        let _guard = self.command_lock.lock().await;
        if *self.command_generation.borrow() != generation {
            return Ok(());
        }
        self.stop_local().await
    }

    async fn stop_local(&self) -> Result<()> {
        self.begin_prepare();
        let current = self.current.lock().expect("playback state poisoned").take();
        let position = audio::audio_get_position(&self.audio).await.unwrap_or(0.0);

        // Reports cannot delay stopping the local output.
        let _ = audio::audio_stop(&self.audio).await;
        if let Some(current) = current {
            self.enqueue_report(Report::Stop(current.item, seconds_to_ticks(position)));
        }

        self.media_controls.clear_now_playing().ok();
        Ok(())
    }

    pub async fn poll(&self) -> Result<PlaybackSnapshot> {
        let tick = audio::audio_poll_position(&self.audio).await;
        let (is_playing, is_finished, position_seconds, did_auto_advance) = match tick {
            Some(tick) => (
                tick.is_playing,
                tick.is_finished,
                tick.position,
                tick.did_auto_advance,
            ),
            None => (
                audio::audio_is_playing(&self.audio).await.unwrap_or(false),
                audio::audio_is_finished(&self.audio).await.unwrap_or(false),
                audio::audio_get_position(&self.audio).await.unwrap_or(0.0),
                false,
            ),
        };
        let auto_advanced_token = if did_auto_advance {
            self.finish_gapless_transition()
        } else {
            None
        };

        let second = position_seconds.max(0.0).floor() as u64;
        let should_report = {
            let mut current = self.current.lock().expect("playback state poisoned");
            current.as_mut().is_some_and(|current| {
                if is_playing
                    && second.saturating_sub(current.last_reported_second)
                        >= PROGRESS_REPORT_INTERVAL_SECONDS
                {
                    current.last_reported_second = second;
                    true
                } else {
                    false
                }
            })
        };
        if should_report {
            self.report_progress(position_seconds, false);
            self.media_controls
                .set_playback_status(true, Some(position_seconds))
                .ok();
        }

        Ok(PlaybackSnapshot {
            is_playing,
            is_finished,
            position_seconds,
            did_auto_advance,
            auto_advanced_token,
        })
    }

    pub fn pop_media_event(&self) -> Option<MediaEvent> {
        self.media_controls.pop_event()
    }

    fn report_progress(&self, position_seconds: f64, is_paused: bool) {
        let item = self
            .current
            .lock()
            .expect("playback state poisoned")
            .as_ref()
            .map(|current| current.item.clone());
        let Some(item) = item else {
            return;
        };

        self.enqueue_report(Report::Progress(
            item,
            seconds_to_ticks(position_seconds),
            is_paused,
        ));
    }

    fn enqueue_report(&self, report: Report) {
        let sender = self.reports.get_or_init(|| {
            let (sender, mut receiver) = tokio::sync::mpsc::channel::<Report>(32);
            tokio::spawn(async move {
                while let Some(report) = receiver.recv().await {
                    if let Err(error) = report.send().await {
                        tracing::debug!("Playback report failed: {error}");
                    }
                }
            });
            sender
        });
        if sender.try_send(report).is_err() {
            tracing::warn!("Playback report queue is full; discarding report");
        }
    }

    fn finish_gapless_transition(&self) -> Option<u64> {
        let prepared = self
            .prepared
            .lock()
            .expect("playback state poisoned")
            .take()?;
        let previous = self
            .current
            .lock()
            .expect("playback state poisoned")
            .replace(CurrentPlayback {
                item: prepared.item.clone(),
                last_reported_second: 0,
            });

        if let Some(previous) = previous {
            let ticks = seconds_to_ticks(f64::from(previous.item.duration_seconds));
            self.enqueue_report(Report::Stop(previous.item, ticks));
        }
        self.enqueue_report(Report::Start(prepared.item.clone()));
        let cover_url = prepared.item.album_id.as_ref().and_then(|album_id| {
            aurelia_core::build_image_url(
                prepared.item.server_url.clone(),
                prepared.item.token.clone(),
                album_id.clone(),
                "Primary".to_string(),
                Some(400),
                Some(90),
            )
            .ok()
            .flatten()
        });
        self.media_controls
            .update_now_playing(NowPlayingPayload {
                title: prepared.item.title,
                artist: Some(prepared.item.artist),
                album: Some(prepared.item.album),
                duration: Some(f64::from(prepared.item.duration_seconds)),
                cover_url,
            })
            .ok();
        self.media_controls
            .set_playback_status(true, Some(0.0))
            .ok();
        Some(prepared.token)
    }
}

fn seconds_to_ticks(seconds: f64) -> i64 {
    (seconds.max(0.0) * 10_000_000.0).round() as i64
}

enum Report {
    Start(PlaybackItem),
    Progress(PlaybackItem, i64, bool),
    Stop(PlaybackItem, i64),
}

impl Report {
    async fn send(self) -> Result<()> {
        tokio::time::timeout(std::time::Duration::from_secs(5), async {
            match self {
                Self::Start(item) => {
                    aurelia_core::report_playback_start_event(
                        item.server_url,
                        item.token,
                        item.id,
                        Some(0),
                    )
                    .await
                }
                Self::Progress(item, ticks, paused) => {
                    aurelia_core::report_playback_progress_event(
                        item.server_url,
                        item.token,
                        item.id,
                        ticks,
                        paused,
                    )
                    .await
                }
                Self::Stop(item, ticks) => {
                    aurelia_core::report_playback_stop_event(
                        item.server_url,
                        item.token,
                        item.id,
                        ticks,
                    )
                    .await
                }
            }
        })
        .await??;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::seconds_to_ticks;
    use super::{PlaybackController, PlaybackItem};

    #[test]
    fn playback_seconds_convert_to_jellyfin_ticks() {
        assert_eq!(seconds_to_ticks(0.0), 0);
        assert_eq!(seconds_to_ticks(1.5), 15_000_000);
        assert_eq!(seconds_to_ticks(-4.0), 0);
    }

    #[tokio::test]
    async fn stale_play_command_never_initializes_audio_or_changes_current_track() {
        let controller = PlaybackController::new();
        let old = controller.begin_command();
        let current = controller.begin_command();
        let item = PlaybackItem {
            server_url: "http://127.0.0.1:1".into(),
            token: String::new(),
            id: "old".into(),
            title: "Old selection".into(),
            artist: String::new(),
            album: String::new(),
            album_id: None,
            container: None,
            duration_seconds: 1,
        };
        controller.play(item, 1.0, old).await.unwrap();
        assert!(controller.current.lock().unwrap().is_none());
        assert!(
            aurelia_core::audio::audio_is_playing(&controller.audio)
                .await
                .is_err()
        );
        controller.stop(current).await.unwrap();
    }
}
