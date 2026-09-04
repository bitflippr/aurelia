use serde::{Deserialize, Serialize};

/// Sync state persisted to track library sync history
#[derive(Serialize, Deserialize, Debug, Clone, uniffi::Record)]
pub struct SyncState {
    pub last_sync_time: String,
    pub last_full_sync_time: Option<String>,
    pub last_sync_version: Option<String>,
    pub song_count: u32,
    pub artist_count: u32,
    pub album_count: u32,
    /// Whether a full sync is currently in progress (for resumability)
    pub full_sync_in_progress: bool,
    /// The last page index that was successfully committed during a full sync
    pub full_sync_last_page_index: u32,
    /// Which entity type the in-progress full sync is on: "songs", "albums", "artists", or "done"
    pub full_sync_entity_type: Option<String>,
}

impl Default for SyncState {
    fn default() -> Self {
        Self {
            last_sync_time: "1970-01-01T00:00:00Z".to_string(),
            last_full_sync_time: None,
            last_sync_version: None,
            song_count: 0,
            artist_count: 0,
            album_count: 0,
            full_sync_in_progress: false,
            full_sync_last_page_index: 0,
            full_sync_entity_type: None,
        }
    }
}

/// Progress update during sync (for UI feedback)
#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncProgress {
    /// Identifies the current operation within this profile.
    pub operation_id: u64,
    /// Current stage of sync (e.g., "Fetching songs", "Saving to database")
    pub stage: String,
    /// Current item being processed
    pub current: u32,
    /// Total items to process
    pub total: u32,
    /// Whether sync is complete
    pub is_complete: bool,
}

impl Default for SyncProgress {
    fn default() -> Self {
        Self {
            stage: "Starting".to_string(),
            operation_id: 0,
            current: 0,
            total: 0,
            is_complete: false,
        }
    }
}

impl SyncProgress {
    pub fn new(stage: &str, current: u32, total: u32) -> Self {
        Self {
            stage: stage.to_string(),
            operation_id: 0,
            current,
            total,
            is_complete: false,
        }
    }

    pub fn complete() -> Self {
        Self {
            stage: "Complete".to_string(),
            operation_id: 0,
            current: 0,
            total: 0,
            is_complete: true,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncReport {
    pub full_sync: bool,
    pub songs_updated: u32,
    pub artists_updated: u32,
    pub albums_updated: u32,
    pub duration_ms: u64,
}

#[cfg(test)]
mod tests {
    use super::SyncProgress;

    #[test]
    fn sync_progress_defaults_and_helpers() {
        let default = SyncProgress::default();
        assert_eq!(default.stage, "Starting");
        assert_eq!(default.current, 0);
        assert_eq!(default.total, 0);
        assert!(!default.is_complete);

        let in_progress = SyncProgress::new("Fetching", 2, 10);
        assert_eq!(in_progress.stage, "Fetching");
        assert_eq!(in_progress.current, 2);
        assert_eq!(in_progress.total, 10);
        assert!(!in_progress.is_complete);

        let done = SyncProgress::complete();
        assert!(done.is_complete);
        assert_eq!(done.stage, "Complete");
    }
}
