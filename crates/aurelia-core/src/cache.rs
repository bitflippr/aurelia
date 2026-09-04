use crate::db;
use crate::models::{Credentials, Song};
use anyhow::{Result, anyhow};
use redb::ReadableDatabase;
use serde_json;
use std::path::PathBuf;
use std::sync::Arc;

fn open_db(app_data_dir: &PathBuf) -> Result<Arc<redb::Database>> {
    db::open(app_data_dir)
}

pub fn replace_songs(app_data_dir: PathBuf, songs: &[Song]) -> Result<()> {
    let database = open_db(&app_data_dir)?;
    crate::domain::services::LibraryService::new(database).replace_songs(songs)?;
    Ok(())
}

pub fn get_songs(app_data_dir: PathBuf) -> Result<Vec<Song>> {
    let database = open_db(&app_data_dir)?;
    db::songs::get_all(&database)
}

pub fn clear_cache(app_data_dir: PathBuf) -> Result<()> {
    let database = open_db(&app_data_dir)?;
    crate::domain::services::LibraryService::new(database).clear_library()?;
    Ok(())
}

pub fn update_song_favorite_status(
    app_data_dir: PathBuf,
    song_id: &str,
    is_favorite: bool,
) -> Result<()> {
    let database = open_db(&app_data_dir)?;
    crate::domain::services::LibraryService::new(database).update_favorite(song_id, is_favorite)?;
    Ok(())
}

pub fn get_sync_state(app_data_dir: PathBuf) -> Result<String> {
    let database = open_db(&app_data_dir)?;
    let service = crate::domain::services::LibraryService::new(database);
    let state = service
        .get_sync_state()
        .map_err(|err| anyhow!("Failed to read sync state: {err}"))?;
    let encoded = serde_json::to_string(&state)?;
    Ok(encoded)
}

pub fn set_sync_state(app_data_dir: PathBuf, state_json: &str) -> Result<()> {
    let database = open_db(&app_data_dir)?;
    let service = crate::domain::services::LibraryService::new(database);
    let state = serde_json::from_str(state_json)?;
    service
        .update_sync_state(&state)
        .map_err(|err| anyhow!("Failed to update sync state: {err}"))?;
    Ok(())
}

pub fn save_credentials(app_data_dir: PathBuf, credentials: &Credentials) -> Result<()> {
    let database = open_db(&app_data_dir)?;
    let write_txn = database.begin_write()?;
    {
        let mut table = write_txn.open_table(crate::db::schema::CREDENTIALS)?;
        let json = serde_json::to_vec(credentials)?;
        table.insert("main", json.as_slice())?;
    }
    write_txn.commit()?;
    Ok(())
}

pub fn load_credentials(app_data_dir: PathBuf) -> Result<Option<Credentials>> {
    let database = open_db(&app_data_dir)?;
    let read_txn = database.begin_read()?;
    let table = read_txn.open_table(crate::db::schema::CREDENTIALS)?;
    if let Some(guard) = table.get("main")? {
        let credentials: Credentials = serde_json::from_slice(guard.value())?;
        Ok(Some(credentials))
    } else {
        Ok(None)
    }
}

pub fn clear_credentials(app_data_dir: PathBuf) -> Result<()> {
    let database = open_db(&app_data_dir)?;
    let write_txn = database.begin_write()?;
    {
        let mut table = write_txn.open_table(crate::db::schema::CREDENTIALS)?;
        table.remove("main")?;
    }
    write_txn.commit()?;
    Ok(())
}

pub fn save_setting(app_data_dir: PathBuf, key: &str, value: &str) -> Result<()> {
    let database = open_db(&app_data_dir)?;
    let write_txn = database.begin_write()?;
    {
        let mut table = write_txn.open_table(crate::db::schema::SETTINGS)?;
        table.insert(key, value)?;
    }
    write_txn.commit()?;
    Ok(())
}

pub fn load_setting(app_data_dir: PathBuf, key: &str) -> Result<Option<String>> {
    let database = open_db(&app_data_dir)?;
    let read_txn = database.begin_read()?;
    let table = read_txn.open_table(crate::db::schema::SETTINGS)?;
    Ok(table.get(key)?.map(|guard| guard.value().to_string()))
}

pub fn delete_setting(app_data_dir: PathBuf, key: &str) -> Result<()> {
    let database = open_db(&app_data_dir)?;
    let write_txn = database.begin_write()?;
    {
        let mut table = write_txn.open_table(crate::db::schema::SETTINGS)?;
        let _ = table.remove(key);
    }
    write_txn.commit()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{Credentials, Song};
    use tempfile::TempDir;

    fn song(id: &str) -> Song {
        Song {
            id: id.to_string(),
            name: format!("Song {id}"),
            item_type: "Audio".to_string(),
            album: None,
            album_id: None,
            artists: None,
            artist_ids: None,
            path: None,
            duration: None,
            album_art_url: None,
            year: None,
            play_count: None,
            is_favorite: None,
            disc_number: None,
            track_number: None,
            container: None,
            bit_rate: None,
            sample_rate: None,
            codec: None,
            genres: None,
            premiere_date: None,
            date_played: None,
            date_created: None,
            date_modified: None,
            album_artists: None,
            lyrics: None,
            image_tags: None,
        }
    }

    #[test]
    fn replace_and_load_songs() {
        let dir = TempDir::new().expect("temp dir");
        let app_dir = dir.path().to_path_buf();
        let songs = vec![song("s1"), song("s2")];
        replace_songs(app_dir.clone(), &songs).expect("replace");
        let loaded = get_songs(app_dir).expect("load");
        assert_eq!(loaded.len(), 2);
    }

    #[test]
    fn save_and_clear_credentials() {
        let dir = TempDir::new().expect("temp dir");
        let app_dir = dir.path().to_path_buf();
        let creds = Credentials {
            provider: crate::models::BackendProvider::Jellyfin,
            server_url: "http://localhost:8096".to_string(),
            username: "user".to_string(),
            token: "token".to_string(),
            user_id: "user-id".to_string(),
        };

        save_credentials(app_dir.clone(), &creds).expect("save");
        let loaded = load_credentials(app_dir.clone()).expect("load");
        let loaded = loaded.expect("credentials present");
        assert_eq!(loaded.server_url, creds.server_url);
        assert_eq!(loaded.username, creds.username);
        assert_eq!(loaded.token, creds.token);
        assert_eq!(loaded.user_id, creds.user_id);

        clear_credentials(app_dir.clone()).expect("clear");
        let cleared = load_credentials(app_dir).expect("load after clear");
        assert!(cleared.is_none());
    }

    #[test]
    fn load_credentials_defaults_provider_for_previous_record() {
        let dir = TempDir::new().expect("temp dir");
        let app_dir = dir.path().to_path_buf();
        let database = db::open(&app_dir).expect("database");
        let write_txn = database.begin_write().expect("write txn");
        {
            let mut table = write_txn
                .open_table(crate::db::schema::CREDENTIALS)
                .expect("credentials table");
            let previous_json =
                br#"{"serverUrl":"http://localhost:8096","username":"user","token":"token","userId":"user-id"}"#;
            table
                .insert("main", previous_json.as_slice())
                .expect("insert");
        }
        write_txn.commit().expect("commit");

        let loaded = load_credentials(app_dir).expect("load");
        let loaded = loaded.expect("credentials present");
        assert_eq!(loaded.provider, crate::models::BackendProvider::Jellyfin);
        assert_eq!(loaded.server_url, "http://localhost:8096");
        assert_eq!(loaded.username, "user");
        assert_eq!(loaded.token, "token");
        assert_eq!(loaded.user_id, "user-id");
    }

    #[test]
    fn concurrent_base_and_profile_databases_do_not_deadlock() {
        let base = TempDir::new().expect("base dir");
        let profile = TempDir::new().expect("profile dir");
        let base_path = base.path().to_path_buf();
        let profile_path = profile.path().to_path_buf();

        let creds = Credentials {
            provider: crate::models::BackendProvider::Jellyfin,
            server_url: "http://localhost:8096".to_string(),
            username: "user".to_string(),
            token: "token".to_string(),
            user_id: "user-id".to_string(),
        };

        save_credentials(base_path.clone(), &creds).expect("save creds");
        save_setting(profile_path.clone(), "feature_enabled", "true").expect("save setting");

        std::thread::scope(|scope| {
            let base_for_thread = base_path.clone();
            let profile_for_thread = profile_path.clone();
            scope.spawn(move || {
                for _ in 0..20 {
                    load_credentials(base_for_thread.clone()).expect("load creds");
                }
            });
            scope.spawn(move || {
                for _ in 0..20 {
                    load_setting(profile_for_thread.clone(), "feature_enabled")
                        .expect("load setting");
                }
            });
        });

        let loaded = load_credentials(base_path)
            .expect("final creds")
            .expect("present");
        assert_eq!(loaded.username, "user");
        let setting = load_setting(profile_path, "feature_enabled").expect("final setting");
        assert_eq!(setting.as_deref(), Some("true"));
    }
}
