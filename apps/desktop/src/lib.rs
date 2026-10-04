//! Aurelia's desktop runtime.
//!
//! The interface sends JSON requests and polls for their results, playback
//! state and sync progress. Requests return an ID at once; work that touches
//! the network or audio runs on this runtime's Tokio pool. Nothing here
//! depends on the window, so playback keeps going across interface reloads.

mod artwork;
mod library;
mod playback;
mod player;
mod queue;
mod session;
mod track;

use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
    time::Duration,
};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub use player::Progress;
use {library::SongView, player::Player, session::Session};

const VOLUME_SETTING: &str = "desktop-volume";
const QUEUE_SETTING: &str = "desktop-queue";

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
enum Request {
    Init,
    SignIn {
        server: String,
        username: String,
        password: String,
    },
    SignOut,
    Sync,
    Library,
    Playlists,
    PlaylistSongs {
        id: String,
    },
    CreatePlaylist {
        name: String,
        #[serde(default)]
        ids: Vec<String>,
    },
    AddToPlaylist {
        id: String,
        ids: Vec<String>,
    },
    Favorite {
        id: String,
        value: bool,
    },
    Artist {
        id: String,
    },
    Mix {
        id: String,
    },
    Lyrics {
        id: String,
    },
    Artwork {
        id: String,
        tag: Option<String>,
    },
    GetSetting {
        key: String,
    },
    SetSetting {
        key: String,
        value: Option<String>,
    },
    RestoreQueue,
    Play {
        ids: Vec<String>,
        #[serde(default)]
        start: usize,
        #[serde(default)]
        shuffle: bool,
    },
    PlayNext {
        ids: Vec<String>,
    },
    Enqueue {
        ids: Vec<String>,
    },
    Jump {
        entry: u64,
    },
    Move {
        entry: u64,
        to: usize,
    },
    Remove {
        entry: u64,
    },
    ClearUpcoming,
    Toggle,
    SetPlaying {
        value: bool,
    },
    Next,
    Previous,
    Seek {
        position: f64,
    },
    Volume {
        value: f32,
    },
    Shuffle,
    Repeat,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SyncStatus {
    stage: String,
    current: u32,
    total: u32,
    running: bool,
    error: Option<String>,
}

#[derive(Default)]
struct Shared {
    session: Option<Session>,
    songs: HashMap<String, SongView>,
    responses: Vec<Value>,
    sync: Option<SyncStatus>,
    artwork: HashMap<String, artwork::Artwork>,
    saved_queue_revision: u64,
    /// The saved queue is only overwritten once it has been restored, so
    /// starting up never replaces it with the empty one.
    queue_restored: bool,
}

pub struct Runtime {
    tokio: tokio::runtime::Runtime,
    base_dir: PathBuf,
    http: reqwest::Client,
    player: Player,
    shared: Arc<Mutex<Shared>>,
    next_request: std::sync::atomic::AtomicU64,
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// The process-wide runtime, created on first use.
pub fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(Runtime::new)
}

impl Runtime {
    fn new() -> Self {
        let tokio = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("aurelia")
            .enable_all()
            .build()
            .expect("failed to start the Aurelia runtime");
        let base_dir = aurelia_core::utils::ensure_app_data_dir()
            .unwrap_or_else(|_| std::env::temp_dir().join("aurelia-desktop"));
        let volume = aurelia_core::load_setting(dir_string(&base_dir), VOLUME_SETTING.into())
            .ok()
            .flatten()
            .and_then(|value| value.parse::<f32>().ok())
            .unwrap_or(0.72)
            .clamp(0.0, 1.0);
        let player = Player::new(tokio.handle().clone(), volume);
        let session = session::saved_session(&base_dir);
        player.set_server(
            session
                .as_ref()
                .map(|session| (session.server_url.clone(), session.token.clone())),
        );
        Self {
            tokio,
            base_dir,
            http: reqwest::Client::builder()
                .user_agent("Aurelia/0.1")
                .timeout(Duration::from_secs(20))
                .build()
                .unwrap_or_default(),
            player,
            shared: Arc::new(Mutex::new(Shared {
                session,
                ..Default::default()
            })),
            next_request: 1.into(),
        }
    }

    fn shared(&self) -> std::sync::MutexGuard<'_, Shared> {
        self.shared.lock().expect("runtime state poisoned")
    }

    fn session(&self) -> Result<Session, String> {
        self.shared()
            .session
            .clone()
            .ok_or_else(|| "Sign in to your Jellyfin server first".to_string())
    }

    /// What the seek bar paints each frame.
    pub fn progress(&self) -> Progress {
        self.player.progress()
    }

    /// Start a request and return its ID; the result arrives through `poll`.
    pub fn request(&'static self, json: &str) -> Result<u64, String> {
        let request: Request =
            serde_json::from_str(json).map_err(|error| format!("Invalid request: {error}"))?;
        let id = self
            .next_request
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.dispatch(id, request);
        Ok(id)
    }

    fn respond(&self, id: u64, result: Result<Value, String>) {
        let response = match result {
            Ok(value) => json!({ "id": id, "ok": true, "value": value }),
            Err(error) => json!({ "id": id, "ok": false, "error": error }),
        };
        self.shared().responses.push(response);
    }

    fn spawn<F>(&'static self, id: u64, work: F)
    where
        F: Future<Output = Result<Value, String>> + Send + 'static,
    {
        self.tokio.spawn(async move {
            let result = work.await;
            self.respond(id, result);
        });
    }

    fn tracks(&self, ids: &[String]) -> Vec<track::Track> {
        let shared = self.shared();
        ids.iter()
            .filter_map(|id| shared.songs.get(id))
            .map(library::track_from)
            .collect()
    }

    fn remember(&self, songs: &[SongView]) {
        let mut shared = self.shared();
        for song in songs {
            shared.songs.insert(song.id.clone(), song.clone());
        }
    }

    fn dispatch(&'static self, id: u64, request: Request) {
        let done = |value: Value| self.respond(id, Ok(value));
        match request {
            Request::Init => {
                let shared = self.shared();
                let session = shared.session.clone();
                let synced = session.as_ref().is_some_and(library::has_synced);
                drop(shared);
                done(json!({ "session": session, "synced": synced }));
            }
            Request::SignIn {
                server,
                username,
                password,
            } => {
                let base_dir = self.base_dir.clone();
                self.spawn(id, async move {
                    let session = session::sign_in(&server, &username, password, base_dir).await?;
                    self.player
                        .set_server(Some((session.server_url.clone(), session.token.clone())));
                    let synced = library::has_synced(&session);
                    self.shared().session = Some(session.clone());
                    Ok(json!({ "session": session, "synced": synced }))
                });
            }
            Request::SignOut => {
                self.player.set_server(None);
                session::sign_out(&self.base_dir);
                *self.shared() = Shared::default();
                done(Value::Null);
            }
            Request::Sync => self.start_sync(id),
            Request::Library => self.spawn(id, async move {
                let session = self.session()?;
                let view = tokio::task::spawn_blocking(move || library::load(&session))
                    .await
                    .map_err(|error| error.to_string())??;
                self.remember(&view.songs);
                serde_json::to_value(view).map_err(|error| error.to_string())
            }),
            Request::Playlists => self.spawn(id, async move {
                let session = self.session()?;
                let playlists = library::playlists(&session).await?;
                serde_json::to_value(playlists).map_err(|error| error.to_string())
            }),
            Request::PlaylistSongs { id: playlist } => self.spawn(id, async move {
                let session = self.session()?;
                let songs = library::playlist_songs(&session, &playlist).await?;
                self.remember(&songs);
                serde_json::to_value(songs).map_err(|error| error.to_string())
            }),
            Request::CreatePlaylist { name, ids } => self.spawn(id, async move {
                let session = self.session()?;
                let playlist = library::create_playlist(&session, name, ids).await?;
                serde_json::to_value(playlist).map_err(|error| error.to_string())
            }),
            Request::AddToPlaylist { id: playlist, ids } => self.spawn(id, async move {
                let session = self.session()?;
                library::add_to_playlist(&session, playlist, ids).await?;
                Ok(Value::Null)
            }),
            Request::Favorite { id: song, value } => self.spawn(id, async move {
                let session = self.session()?;
                let favorite = library::set_favorite(&session, song.clone(), value).await?;
                if let Some(song) = self.shared().songs.get_mut(&song) {
                    song.fav = favorite;
                }
                Ok(json!(favorite))
            }),
            Request::Artist { id: artist } => self.spawn(id, async move {
                let session = self.session()?;
                let detail = library::artist(&session, artist).await?;
                serde_json::to_value(detail).map_err(|error| error.to_string())
            }),
            Request::Mix { id: seed } => self.spawn(id, async move {
                let session = self.session()?;
                let songs = library::instant_mix(&session, seed).await?;
                self.remember(&songs);
                serde_json::to_value(songs).map_err(|error| error.to_string())
            }),
            Request::Lyrics { id: song } => self.spawn(id, async move {
                let session = self.session()?;
                let (artist, title) = self
                    .shared()
                    .songs
                    .get(&song)
                    .map(|song| {
                        (
                            song.artists.first().cloned().unwrap_or_default(),
                            song.title.clone(),
                        )
                    })
                    .unwrap_or_default();
                let lyrics = library::lyrics(&session, song, artist, title).await;
                serde_json::to_value(lyrics).map_err(|error| error.to_string())
            }),
            Request::Artwork { id: item, tag } => {
                let key = format!("{item}:{}", tag.as_deref().unwrap_or(""));
                if let Some(artwork) = self.shared().artwork.get(&key).cloned() {
                    done(serde_json::to_value(artwork).unwrap_or(Value::Null));
                    return;
                }
                self.spawn(id, async move {
                    let session = self.session()?;
                    let cache_dir = self.base_dir.join("artwork");
                    let artwork =
                        artwork::load(&self.http, &session, &cache_dir, &item, tag.as_deref())
                            .await?;
                    self.shared().artwork.insert(key, artwork.clone());
                    serde_json::to_value(artwork).map_err(|error| error.to_string())
                });
            }
            Request::GetSetting { key } => done(json!(
                aurelia_core::load_setting(dir_string(&self.base_dir), key)
                    .ok()
                    .flatten()
            )),
            Request::SetSetting { key, value } => {
                let dir = dir_string(&self.base_dir);
                let _ = match value {
                    Some(value) => aurelia_core::save_setting(dir, key, value),
                    None => aurelia_core::delete_setting(dir, key),
                };
                done(Value::Null);
            }
            Request::RestoreQueue => {
                let saved =
                    aurelia_core::load_setting(dir_string(&self.base_dir), QUEUE_SETTING.into())
                        .ok()
                        .flatten()
                        .and_then(|json| serde_json::from_str::<SavedQueue>(&json).ok());
                if let Some(saved) = saved {
                    let tracks = self.tracks(&saved.ids);
                    let index = saved
                        .ids
                        .get(saved.index)
                        .and_then(|id| tracks.iter().position(|track| &track.id == id))
                        .unwrap_or(0);
                    self.player.restore(tracks, index);
                }
                self.shared().queue_restored = true;
                done(Value::Null);
            }
            Request::Play {
                ids,
                start,
                shuffle,
            } => {
                let start_id = ids.get(start).cloned();
                let tracks = self.tracks(&ids);
                let start = start_id
                    .and_then(|id| tracks.iter().position(|track| track.id == id))
                    .unwrap_or(0);
                self.player.play(tracks, start, shuffle);
                done(Value::Null);
            }
            Request::PlayNext { ids } => {
                self.player.play_next(self.tracks(&ids));
                done(Value::Null);
            }
            Request::Enqueue { ids } => {
                self.player.enqueue(self.tracks(&ids));
                done(Value::Null);
            }
            Request::Jump { entry } => {
                self.player.jump(entry);
                done(Value::Null);
            }
            Request::Move { entry, to } => {
                self.player.move_entry(entry, to);
                done(Value::Null);
            }
            Request::Remove { entry } => {
                self.player.remove(entry);
                done(Value::Null);
            }
            Request::ClearUpcoming => {
                self.player.clear_upcoming();
                done(Value::Null);
            }
            Request::Toggle => {
                self.player.toggle();
                done(Value::Null);
            }
            Request::SetPlaying { value } => {
                self.player.set_playing(value);
                done(Value::Null);
            }
            Request::Next => {
                self.player.next();
                done(Value::Null);
            }
            Request::Previous => {
                self.player.previous();
                done(Value::Null);
            }
            Request::Seek { position } => {
                self.player.seek(position);
                done(Value::Null);
            }
            Request::Volume { value } => {
                self.player.set_volume(value);
                // Saved off the interface thread, which a drag would otherwise
                // stall with a database write per move. Each save stores the
                // latest volume, so a late one cannot write back an older value.
                self.tokio.spawn_blocking(move || {
                    let _ = aurelia_core::save_setting(
                        dir_string(&self.base_dir),
                        VOLUME_SETTING.into(),
                        self.player.volume().to_string(),
                    );
                });
                done(Value::Null);
            }
            Request::Shuffle => {
                self.player.toggle_shuffle();
                done(Value::Null);
            }
            Request::Repeat => {
                self.player.cycle_repeat();
                done(Value::Null);
            }
        }
    }

    fn start_sync(&'static self, id: u64) {
        let session = match self.session() {
            Ok(session) => session,
            Err(error) => return self.respond(id, Err(error)),
        };
        {
            let mut shared = self.shared();
            if shared.sync.as_ref().is_some_and(|sync| sync.running) {
                drop(shared);
                return self.respond(id, Err("A sync is already running".into()));
            }
            shared.sync = Some(SyncStatus {
                stage: "Preparing your library".into(),
                current: 0,
                total: 0,
                running: true,
                error: None,
            });
        }
        let progress_dir = session.library_dir.clone();
        self.tokio.spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_millis(250)).await;
                let progress = aurelia_core::get_sync_progress(progress_dir.clone());
                let mut shared = self.shared();
                let Some(sync) = shared.sync.as_mut().filter(|sync| sync.running) else {
                    break;
                };
                sync.stage = if progress.is_complete {
                    "Finishing your library".into()
                } else {
                    progress.stage
                };
                sync.current = progress.current;
                sync.total = progress.total;
            }
        });
        self.spawn(id, async move {
            let result = library::sync(&session).await;
            let mut shared = self.shared();
            if let Some(sync) = shared.sync.as_mut() {
                sync.running = false;
                sync.error = result.as_ref().err().cloned();
                if result.is_ok() {
                    sync.stage = "Library ready".into();
                }
            }
            result.map(|_| Value::Null)
        });
    }

    /// Finished requests, playback state and sync progress. `seen` is the
    /// player revision the caller has; the queue is only sent when it moved.
    pub fn poll(&self, seen: u64) -> String {
        let player = self.player.view(seen);
        let (responses, sync, save_queue) = {
            let mut shared = self.shared();
            let save_queue = shared.saved_queue_revision != player.revision;
            shared.saved_queue_revision = player.revision;
            (
                std::mem::take(&mut shared.responses),
                shared.sync.clone(),
                save_queue && shared.session.is_some() && shared.queue_restored,
            )
        };
        if save_queue {
            let (ids, index) = self.player.saved_queue();
            if let Ok(json) = serde_json::to_string(&SavedQueue { ids, index }) {
                let _ = aurelia_core::save_setting(
                    dir_string(&self.base_dir),
                    QUEUE_SETTING.into(),
                    json,
                );
            }
        }
        json!({ "responses": responses, "player": player, "sync": sync }).to_string()
    }
}

#[derive(Serialize, Deserialize)]
struct SavedQueue {
    ids: Vec<String>,
    index: usize,
}

fn dir_string(path: &std::path::Path) -> String {
    path.to_string_lossy().into_owned()
}
