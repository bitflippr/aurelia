//! One in-flight synchronization and progress record per profile.
use crate::{
    db,
    domain::{SyncProgress, SyncReport},
    error::{AppError, AppResult},
};
use once_cell::sync::Lazy;
use redb::Database;
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use tokio::sync::watch;

type Completion = Option<AppResult<SyncReport>>;
#[derive(Default)]
struct ProfileSync {
    flight: Mutex<Option<watch::Receiver<Completion>>>,
    progress: Mutex<SyncProgress>,
}
static PROFILES: Lazy<Mutex<HashMap<PathBuf, Arc<ProfileSync>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

fn profile(path: &str) -> Arc<ProfileSync> {
    PROFILES
        .lock()
        .expect("sync registry poisoned")
        .entry(PathBuf::from(path))
        .or_default()
        .clone()
}

pub fn progress(path: &str) -> SyncProgress {
    profile(path)
        .progress
        .lock()
        .expect("sync progress poisoned")
        .clone()
}

pub async fn synchronize(
    path: String,
    server_url: String,
    token: String,
    user_id: String,
) -> AppResult<SyncReport> {
    let owner = profile(&path);
    let mut receiver = {
        let mut slot = owner.flight.lock().expect("sync flight poisoned");
        if let Some(receiver) = slot
            .as_ref()
            .filter(|receiver| receiver.borrow().is_none() && receiver.has_changed().is_ok())
        {
            receiver.clone()
        } else {
            let database =
                db::open(&PathBuf::from(&path)).map_err(|e| AppError::Database(e.to_string()))?;
            let (sender, receiver) = watch::channel(None);
            *slot = Some(receiver.clone());
            {
                let mut progress = owner.progress.lock().expect("sync progress poisoned");
                let operation_id = progress.operation_id.wrapping_add(1);
                *progress = SyncProgress {
                    operation_id,
                    stage: "Fetching library".into(),
                    ..SyncProgress::default()
                };
            }
            let owner = owner.clone();
            // The profile owns this work. Cancelling a screen's wait does not cancel other subscribers.
            tokio::spawn(async move {
                let client = crate::services::JellyfinClient::with_auth(server_url, token);
                let result = run_sync(database, &client, &user_id).await;
                {
                    let mut progress = owner.progress.lock().expect("sync progress poisoned");
                    progress.stage = if result.is_ok() { "Complete" } else { "Failed" }.into();
                    progress.is_complete = true;
                }
                sender.send_replace(Some(result));
            });
            receiver
        }
    };
    loop {
        if let Some(result) = receiver.borrow().clone() {
            return result;
        }
        receiver
            .changed()
            .await
            .map_err(|_| AppError::General("Library sync task ended without a result".into()))?;
    }
}

/// Fetch and validate the remote update before opening a write transaction.
async fn run_sync(
    db: Arc<Database>,
    client: &crate::services::JellyfinClient,
    user_id: &str,
) -> crate::error::AppResult<crate::domain::SyncReport> {
    use crate::domain::{
        SyncState,
        services::{LibraryService, library::LibraryInventory},
    };
    use crate::error::AppError;
    let service = LibraryService::new(db);
    let state = service
        .get_sync_state()
        .map_err(|e| AppError::Database(e.to_string()))?;
    let full = state.last_full_sync_time.is_none() || state.full_sync_in_progress;
    let since = if full {
        None
    } else {
        Some(state.last_sync_time.as_str())
    };
    let start = std::time::Instant::now();
    let (songs, date) = client.get_songs_paginated(user_id, since, 200).await?;
    let (albums, _) = client.get_albums_paginated(user_id, since, 200).await?;
    let (artists, _) = client.get_artists_paginated(user_id, since, 200).await?;
    let inventory = if full {
        None
    } else {
        Some(LibraryInventory {
            songs: client
                .get_all_item_ids(user_id, "Audio")
                .await?
                .into_iter()
                .collect(),
            albums: client
                .get_all_item_ids(user_id, "MusicAlbum")
                .await?
                .into_iter()
                .collect(),
            artists: client
                .get_all_item_ids(user_id, "MusicArtist")
                .await?
                .into_iter()
                .collect(),
        })
    };
    let sync_time = crate::services::JellyfinClient::parse_server_date(date.as_deref());
    let next_state = SyncState {
        last_sync_time: sync_time.clone(),
        last_full_sync_time: if full {
            Some(sync_time)
        } else {
            state.last_full_sync_time
        },
        ..SyncState::default()
    };
    let report = service
        .commit_sync(&songs, &artists, &albums, inventory.as_ref(), next_state)
        .map_err(|e| AppError::Database(e.to_string()))?;
    Ok(crate::domain::SyncReport {
        duration_ms: start.elapsed().as_millis() as u64,
        ..report
    })
}
