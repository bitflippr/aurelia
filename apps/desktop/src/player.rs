//! Playback: the queue, the audio controller, system media controls and the
//! state the interface reads. Commands return at once; audio work runs on the
//! runtime and settles into the shared state.

use std::{
    sync::{Arc, Mutex, MutexGuard},
    time::{Duration, Instant},
};

use aurelia_core::media_controls::MediaEvent;
use serde::Serialize;

use crate::{
    playback::{PlaybackController, PlaybackItem, PlaybackSnapshot},
    queue::{AdvanceReason, PlaybackQueue, QueueEntryId, RemoveOutcome, RepeatMode},
    track::Track,
};

const POLL_INTERVAL: Duration = Duration::from_millis(200);
/// Previous restarts the song instead once it has played this long.
const RESTART_THRESHOLD_SECONDS: f64 = 3.0;

#[derive(Clone)]
pub struct Player {
    inner: Arc<Inner>,
}

struct Inner {
    controller: PlaybackController,
    state: Mutex<State>,
    handle: tokio::runtime::Handle,
}

struct State {
    queue: PlaybackQueue,
    revision: u64,
    server: Option<(String, String)>,
    /// The entry the audio controller has loaded, if any.
    loaded: Option<QueueEntryId>,
    playing: bool,
    position: f64,
    position_at: Instant,
    loading: bool,
    error: Option<String>,
    volume: f32,
    request_id: u64,
    seek_request_id: u64,
    seek_in_flight: bool,
}

impl State {
    fn item(&self, track: &Track) -> Option<PlaybackItem> {
        let (server_url, token) = self.server.clone()?;
        Some(PlaybackItem {
            server_url,
            token,
            id: track.id.clone(),
            title: track.title.clone(),
            artist: track.artist.clone(),
            album: track.album.clone(),
            album_id: track.album_id.clone(),
            container: track.container.clone(),
            duration_seconds: track.duration_seconds,
        })
    }

    fn set_position(&mut self, position: f64) {
        self.position = position.max(0.0);
        self.position_at = Instant::now();
    }

    fn duration(&self) -> f64 {
        self.queue
            .current()
            .map_or(0.0, |entry| f64::from(entry.track.duration_seconds))
    }

    /// The position now, advanced from the last report while playing.
    fn position(&self) -> f64 {
        let position = if self.playing && !self.loading && !self.seek_in_flight {
            self.position + self.position_at.elapsed().as_secs_f64()
        } else {
            self.position
        };
        let duration = self.duration();
        if duration > 0.0 {
            position.min(duration)
        } else {
            position
        }
    }

    fn changed(&mut self) {
        self.revision = self.revision.wrapping_add(1);
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueItemView {
    pub entry: u64,
    pub id: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerView {
    pub revision: u64,
    pub current: Option<u64>,
    pub playing: bool,
    pub position: f64,
    pub duration: f64,
    pub loading: bool,
    pub error: Option<String>,
    pub shuffle: bool,
    pub repeat: &'static str,
    pub volume: f32,
    /// Present when the queue changed since the revision the caller last saw.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub queue: Option<Vec<QueueItemView>>,
}

/// What the seek bar needs every frame, without serializing anything.
#[derive(Clone, Copy, Default)]
pub struct Progress {
    pub position: f64,
    pub duration: f64,
    pub playing: bool,
}

impl Player {
    pub fn new(handle: tokio::runtime::Handle, volume: f32) -> Self {
        let controller = {
            let _guard = handle.enter();
            PlaybackController::new()
        };
        let player = Self {
            inner: Arc::new(Inner {
                controller,
                state: Mutex::new(State {
                    queue: PlaybackQueue::default(),
                    revision: 1,
                    server: None,
                    loaded: None,
                    playing: false,
                    position: 0.0,
                    position_at: Instant::now(),
                    loading: false,
                    error: None,
                    volume,
                    request_id: 0,
                    seek_request_id: 0,
                    seek_in_flight: false,
                }),
                handle,
            }),
        };
        player.start_polling();
        player
    }

    fn state(&self) -> MutexGuard<'_, State> {
        self.inner.state.lock().expect("player state poisoned")
    }

    fn controller(&self) -> &PlaybackController {
        &self.inner.controller
    }

    fn spawn(&self, future: impl Future<Output = ()> + Send + 'static) {
        self.inner.handle.spawn(future);
    }

    pub fn set_server(&self, server: Option<(String, String)>) {
        let signed_out = server.is_none();
        self.state().server = server;
        if signed_out {
            self.clear();
        }
    }

    pub fn view(&self, seen_revision: u64) -> PlayerView {
        let state = self.state();
        PlayerView {
            revision: state.revision,
            current: state.queue.current_id().map(QueueEntryId::raw),
            playing: state.playing,
            position: state.position(),
            duration: state.duration(),
            loading: state.loading,
            error: state.error.clone(),
            shuffle: state.queue.shuffle_enabled(),
            repeat: match state.queue.repeat_mode() {
                RepeatMode::Off => "off",
                RepeatMode::All => "all",
                RepeatMode::One => "one",
            },
            volume: state.volume,
            queue: (seen_revision != state.revision).then(|| {
                state
                    .queue
                    .entries()
                    .iter()
                    .map(|entry| QueueItemView {
                        entry: entry.id.raw(),
                        id: entry.track.id.clone(),
                    })
                    .collect()
            }),
        }
    }

    pub fn progress(&self) -> Progress {
        let state = self.state();
        Progress {
            position: state.position(),
            duration: state.duration(),
            playing: state.playing && !state.loading,
        }
    }

    /// The queue's song IDs and current index, for restoring it next launch.
    pub fn saved_queue(&self) -> (Vec<String>, usize) {
        let state = self.state();
        (
            state
                .queue
                .entries()
                .iter()
                .map(|entry| entry.track.id.clone())
                .collect(),
            state.queue.current_index().unwrap_or(0),
        )
    }

    // ---- Queue -----------------------------------------------------------

    pub fn play(&self, tracks: Vec<Track>, start: usize, shuffle: bool) {
        {
            let mut state = self.state();
            if shuffle != state.queue.shuffle_enabled() {
                state.queue.toggle_shuffle();
            }
            let start = if shuffle && !tracks.is_empty() {
                random_index(tracks.len())
            } else {
                start
            };
            state.queue.replace(tracks, start);
            state.changed();
        }
        self.start_current();
    }

    /// Put songs back in the queue without playing them.
    pub fn restore(&self, tracks: Vec<Track>, index: usize) {
        let mut state = self.state();
        if !state.queue.is_empty() || tracks.is_empty() {
            return;
        }
        state.queue.replace(tracks, index);
        state.loaded = None;
        state.set_position(0.0);
        state.changed();
    }

    pub fn play_next(&self, tracks: Vec<Track>) {
        let was_empty = {
            let mut state = self.state();
            let was_empty = state.queue.is_empty();
            for track in tracks {
                state.queue.play_next(track);
            }
            state.changed();
            was_empty
        };
        if was_empty {
            self.start_current();
        } else {
            self.refresh_prepared_next();
        }
    }

    pub fn enqueue(&self, tracks: Vec<Track>) {
        let was_empty = {
            let mut state = self.state();
            let was_empty = state.queue.is_empty();
            for track in tracks {
                state.queue.add_to_end(track);
            }
            state.changed();
            was_empty
        };
        if was_empty {
            self.start_current();
        } else {
            self.refresh_prepared_next();
        }
    }

    fn entry_id(state: &State, raw: u64) -> Option<QueueEntryId> {
        state
            .queue
            .entries()
            .iter()
            .find(|entry| entry.id.raw() == raw)
            .map(|entry| entry.id)
    }

    pub fn jump(&self, entry: u64) {
        let selected = {
            let mut state = self.state();
            let selected = Self::entry_id(&state, entry).is_some_and(|id| state.queue.select(id));
            if selected {
                state.changed();
            }
            selected
        };
        if selected {
            self.start_current();
        }
    }

    pub fn move_entry(&self, entry: u64, target: usize) {
        let moved = {
            let mut state = self.state();
            let moved =
                Self::entry_id(&state, entry).is_some_and(|id| state.queue.move_entry(id, target));
            if moved {
                state.changed();
            }
            moved
        };
        if moved {
            self.refresh_prepared_next();
        }
    }

    pub fn remove(&self, entry: u64) {
        let outcome = {
            let mut state = self.state();
            let Some(id) = Self::entry_id(&state, entry) else {
                return;
            };
            let outcome = state.queue.remove(id);
            state.changed();
            outcome
        };
        match outcome {
            RemoveOutcome::NotFound => {}
            RemoveOutcome::Removed => self.refresh_prepared_next(),
            RemoveOutcome::CurrentChanged(Some(_)) => self.start_current(),
            RemoveOutcome::CurrentChanged(None) => self.stop(),
        }
    }

    pub fn clear_upcoming(&self) {
        let cleared = {
            let mut state = self.state();
            let cleared = state.queue.clear_upcoming() > 0;
            if cleared {
                state.changed();
            }
            cleared
        };
        if cleared {
            self.refresh_prepared_next();
        }
    }

    pub fn clear(&self) {
        {
            let mut state = self.state();
            state.queue.clear();
            state.changed();
        }
        self.stop();
    }

    pub fn toggle_shuffle(&self) {
        {
            let mut state = self.state();
            state.queue.toggle_shuffle();
            state.changed();
        }
        self.refresh_prepared_next();
    }

    pub fn cycle_repeat(&self) {
        {
            let mut state = self.state();
            state.queue.cycle_repeat_mode();
            state.changed();
        }
        self.refresh_prepared_next();
    }

    // ---- Transport -------------------------------------------------------

    fn start_current(&self) {
        let (item, request_id, volume, generation) = {
            let mut state = self.state();
            let Some(entry) = state.queue.current().cloned() else {
                return;
            };
            let Some(item) = state.item(&entry.track) else {
                state.error = Some("Playback requires an active Jellyfin session".into());
                return;
            };
            state.request_id = state.request_id.wrapping_add(1);
            state.seek_request_id = state.seek_request_id.wrapping_add(1);
            state.seek_in_flight = false;
            state.playing = false;
            state.loading = true;
            state.error = None;
            state.loaded = Some(entry.id);
            state.set_position(0.0);
            state.changed();
            let controller = self.controller();
            controller.begin_prepare();
            controller.begin_seek();
            let generation = controller.begin_command();
            (item, state.request_id, state.volume, generation)
        };
        let player = self.clone();
        self.spawn(async move {
            let result = player.controller().play(item, volume, generation).await;
            let started = {
                let mut state = player.state();
                if state.request_id != request_id {
                    return;
                }
                state.loading = false;
                match result {
                    Ok(()) => {
                        state.playing = true;
                        state.set_position(0.0);
                        true
                    }
                    Err(error) => {
                        state.playing = false;
                        state.error = Some(failure(&error));
                        false
                    }
                }
            };
            if started {
                player.refresh_prepared_next();
            }
        });
    }

    fn refresh_prepared_next(&self) {
        let prepared = {
            let state = self.state();
            if state.loading {
                return;
            }
            state
                .queue
                .next_for(AdvanceReason::TrackFinished)
                .and_then(|entry| state.item(&entry.track).map(|item| (entry.id.raw(), item)))
        };
        let generation = self.controller().begin_prepare();
        let player = self.clone();
        self.spawn(async move {
            if let Err(error) = player.controller().prepare_next(prepared, generation).await {
                tracing::warn!("Could not prepare the next queue item: {error}");
            }
        });
    }

    pub fn set_playing(&self, should_play: bool) {
        let needs_start = {
            let mut state = self.state();
            if state.loading || state.queue.is_empty() {
                return;
            }
            if should_play && state.loaded != state.queue.current_id() {
                true
            } else {
                let position = state.position();
                state.set_position(position);
                state.playing = should_play;
                state.error = None;
                false
            }
        };
        if needs_start {
            self.start_current();
            return;
        }
        let player = self.clone();
        self.spawn(async move {
            let result = if should_play {
                player.controller().resume().await
            } else {
                player.controller().pause().await
            };
            if let Err(error) = result {
                let mut state = player.state();
                state.playing = !should_play;
                state.error = Some(failure(&error));
            }
        });
    }

    pub fn toggle(&self) {
        let playing = self.state().playing;
        self.set_playing(!playing);
    }

    pub fn next(&self) {
        let advanced = {
            let mut state = self.state();
            let advanced = state.queue.advance(AdvanceReason::Manual).is_some();
            if advanced {
                state.changed();
            }
            advanced
        };
        if advanced {
            self.start_current();
        }
    }

    pub fn previous(&self) {
        let went_back = {
            let mut state = self.state();
            if state.queue.is_empty() {
                return;
            }
            let went_back =
                state.position() <= RESTART_THRESHOLD_SECONDS && state.queue.previous().is_some();
            if went_back {
                state.changed();
            }
            went_back
        };
        if went_back {
            self.start_current();
        } else {
            self.seek(0.0);
        }
    }

    pub fn seek(&self, position: f64) {
        let (request_id, position) = {
            let mut state = self.state();
            if state.queue.is_empty() {
                return;
            }
            if state.loaded != state.queue.current_id() {
                // Nothing is loaded yet; start from the beginning instead.
                drop(state);
                self.start_current();
                return;
            }
            let duration = state.duration();
            let position = if duration > 0.0 {
                position.clamp(0.0, duration)
            } else {
                position.max(0.0)
            };
            state.seek_request_id = state.seek_request_id.wrapping_add(1);
            state.seek_in_flight = true;
            state.set_position(position);
            (state.seek_request_id, position)
        };
        let generation = self.controller().begin_seek();
        let player = self.clone();
        self.spawn(async move {
            let result = player.controller().seek(position, generation).await;
            let settled = {
                let mut state = player.state();
                if state.seek_request_id != request_id {
                    return;
                }
                state.seek_in_flight = false;
                state.set_position(position);
                match result {
                    Ok(()) => true,
                    Err(error) => {
                        state.error = Some(failure(&error));
                        false
                    }
                }
            };
            if settled {
                player.refresh_prepared_next();
            }
        });
    }

    pub fn set_volume(&self, volume: f32) {
        let volume = volume.clamp(0.0, 1.0);
        let loaded = {
            let mut state = self.state();
            state.volume = volume;
            state.loaded.is_some()
        };
        // Playback applies the stored volume when it starts. Before that there
        // is no audio output to change.
        if !loaded {
            return;
        }
        let player = self.clone();
        self.spawn(async move {
            // A drag sends many changes and these tasks can finish in any
            // order, so each applies the latest volume rather than its own.
            let volume = player.volume();
            if let Err(error) = player.controller().set_volume(volume).await {
                player.state().error = Some(failure(&error));
            }
        });
    }

    pub fn volume(&self) -> f32 {
        self.state().volume
    }

    pub fn stop(&self) {
        {
            let mut state = self.state();
            state.request_id = state.request_id.wrapping_add(1);
            state.seek_request_id = state.seek_request_id.wrapping_add(1);
            state.seek_in_flight = false;
            state.loading = false;
            state.playing = false;
            state.loaded = None;
            state.set_position(0.0);
        }
        let controller = self.controller();
        controller.begin_seek();
        let generation = controller.begin_command();
        let player = self.clone();
        self.spawn(async move {
            let _ = player.controller().stop(generation).await;
        });
    }

    // ---- Following the audio --------------------------------------------

    fn start_polling(&self) {
        let player = Arc::downgrade(&self.inner);
        self.inner.handle.spawn(async move {
            loop {
                tokio::time::sleep(POLL_INTERVAL).await;
                let Some(inner) = player.upgrade() else {
                    break;
                };
                let player = Player { inner };
                if let Ok(snapshot) = player.controller().poll().await {
                    player.follow(snapshot);
                }
                while let Some(event) = player.controller().pop_media_event() {
                    player.handle_media_event(event);
                }
            }
        });
    }

    fn follow(&self, snapshot: PlaybackSnapshot) {
        let mut state = self.state();
        if state.seek_in_flight {
            return;
        }
        if snapshot.did_auto_advance {
            // The prepared track already started without a gap.
            let prepared = snapshot
                .auto_advanced_token
                .and_then(|token| Self::entry_id(&state, token));
            if let Some(id) = prepared.filter(|id| state.queue.select(*id)) {
                state.loaded = Some(id);
                state.playing = true;
                state.error = None;
                state.set_position(snapshot.position_seconds);
                state.changed();
                drop(state);
                self.refresh_prepared_next();
                return;
            }
            let advanced = state.queue.advance(AdvanceReason::TrackFinished).is_some();
            state.changed();
            drop(state);
            if advanced {
                self.start_current();
            } else {
                self.stop();
            }
            return;
        }
        if state.loading {
            return;
        }
        state.set_position(snapshot.position_seconds);
        state.playing = snapshot.is_playing;
        if snapshot.is_finished && state.loaded.is_some() {
            let advanced = state.queue.advance(AdvanceReason::TrackFinished).is_some();
            if advanced {
                state.changed();
                drop(state);
                self.start_current();
            } else {
                state.playing = false;
                state.loaded = None;
                state.set_position(0.0);
            }
        }
    }

    fn handle_media_event(&self, event: MediaEvent) {
        match event {
            MediaEvent::Play => self.set_playing(true),
            MediaEvent::Pause => self.set_playing(false),
            MediaEvent::Toggle => self.toggle(),
            MediaEvent::Next => self.next(),
            MediaEvent::Previous => self.previous(),
            MediaEvent::Stop => self.stop(),
            MediaEvent::SeekDelta(delta) => {
                let position = self.state().position();
                self.seek(position + delta);
            }
            MediaEvent::SetPosition(position) => self.seek(position),
        }
    }
}

/// The message the player shows for an error. The full chain of causes goes
/// to the terminal, since the message alone rarely says what failed.
fn failure(error: &anyhow::Error) -> String {
    eprintln!("Aurelia playback: {error:#}");
    error.to_string()
}

fn random_index(len: usize) -> usize {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |time| time.subsec_nanos() as usize);
    nanos.wrapping_mul(0x9e37_79b9) % len
}
