pub mod schema;

pub use schema::*;

use crate::models::{Album, Artist, Song};
use anyhow::{Result, anyhow};
use once_cell::sync::Lazy;
use redb::{Database, ReadOnlyTable, ReadableTable, TableDefinition};
use serde::de::DeserializeOwned;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tracing::{debug, info};

// Reuse one handle per database file. Selection belongs to each caller, never the registry.
static DATABASES: Lazy<Mutex<HashMap<PathBuf, Arc<Database>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

// Table definitions
const SONGS_TABLE: TableDefinition<&str, &[u8]> = TableDefinition::new("songs");
const ARTISTS_TABLE: TableDefinition<&str, &[u8]> = TableDefinition::new("artists");
const ALBUMS_TABLE: TableDefinition<&str, &[u8]> = TableDefinition::new("albums");

fn create_database(app_data_dir: &PathBuf) -> Result<Arc<Database>> {
    info!("Database path: {:?}", app_data_dir);

    std::fs::create_dir_all(app_data_dir)
        .map_err(|e| anyhow!("Failed to create app data directory: {}", e))?;

    let db_path = app_data_dir.join("aurelia.redb");
    debug!("Full database path: {:?}", db_path);

    let db = Arc::new(
        Database::create(&db_path).map_err(|e| anyhow!("Failed to create database: {}", e))?,
    );

    let write_txn = db
        .begin_write()
        .map_err(|e| anyhow!("Failed to begin write transaction: {}", e))?;
    {
        let _ = write_txn.open_table(schema::SONGS)?;
        let _ = write_txn.open_table(schema::ARTISTS)?;
        let _ = write_txn.open_table(schema::ALBUMS)?;
        let _ = write_txn.open_table(schema::PLAYLISTS)?;
        let _ = write_txn.open_table(schema::SONGS_BY_ALBUM)?;
        let _ = write_txn.open_table(schema::SONGS_BY_ARTIST)?;
        let _ = write_txn.open_table(schema::ALBUMS_BY_ARTIST)?;
        let _ = write_txn.open_table(schema::FAVORITES)?;
        let _ = write_txn.open_table(schema::SYNC_STATE)?;
        let _ = write_txn.open_table(schema::CREDENTIALS)?;
        let _ = write_txn.open_table(schema::SETTINGS)?;
        let _ = write_txn.open_table(schema::DB_VERSION)?;
    }
    write_txn.commit()?;

    info!("Database initialized successfully");
    Ok(db)
}

/// Open (or reuse) the database for `app_data_dir`.
///
/// Credentials live in the backend's base data dir while library/settings live
/// in a per-profile subdirectory. Those must stay open at the same time —
/// redb only allows one handle per file, so we cache handles instead of
/// closing/reopening when the active path changes.
pub fn open(app_data_dir: &PathBuf) -> Result<Arc<Database>> {
    let mut registry = DATABASES
        .lock()
        .map_err(|_| anyhow!("Failed to lock database handles"))?;
    if let Some(existing) = registry.get(app_data_dir) {
        debug!("Reusing open database for {:?}", app_data_dir);
        return Ok(existing.clone());
    }

    let db = create_database(app_data_dir)?;
    registry.insert(app_data_dir.clone(), db.clone());
    Ok(db)
}

// ============================================================================
// Sync functions
// ============================================================================

fn get_all_items<T: DeserializeOwned>(table: &ReadOnlyTable<&str, &[u8]>) -> Result<Vec<T>> {
    debug!("Getting all items from table");
    let items: Vec<T> = table
        .iter()
        .map_err(|e| anyhow!("Failed to iterate over table: {}", e))?
        .map(|res| {
            let (_, bytes) = res.map_err(|e| anyhow!("Failed to get table item: {}", e))?;
            let item = postcard::from_bytes(bytes.value())
                .map_err(|e| anyhow!("Failed to decode table item: {}", e))?;
            Ok(item)
        })
        .collect::<Result<Vec<T>>>()?;
    debug!("Retrieved {} items from table", items.len());
    Ok(items)
}

// ============================================================================
// Songs submodule
// ============================================================================

pub mod songs {
    use super::*;
    use redb::ReadableDatabase;

    pub fn get_all(db: &Database) -> Result<Vec<Song>> {
        let read_txn = db
            .begin_read()
            .map_err(|e| anyhow!("Failed to begin read transaction: {}", e))?;
        let table = read_txn
            .open_table(SONGS_TABLE)
            .map_err(|e| anyhow!("Failed to open songs table: {}", e))?;
        get_all_items(&table)
    }

    pub fn get_by_id(db: &Database, song_id: &str) -> Result<Option<Song>> {
        let read_txn = db
            .begin_read()
            .map_err(|e| anyhow!("Failed to begin read transaction: {}", e))?;
        let table = read_txn
            .open_table(SONGS_TABLE)
            .map_err(|e| anyhow!("Failed to open songs table: {}", e))?;

        if let Some(bytes) = table.get(song_id)? {
            let song: Song = postcard::from_bytes(bytes.value())
                .map_err(|e| anyhow!("Failed to decode song: {}", e))?;
            Ok(Some(song))
        } else {
            Ok(None)
        }
    }
}

// ============================================================================
// Artists submodule
// ============================================================================

pub mod artists {
    use super::*;
    use redb::ReadableDatabase;

    pub fn get_all(db: &Database) -> Result<Vec<Artist>> {
        let read_txn = db
            .begin_read()
            .map_err(|e| anyhow!("Failed to begin read transaction: {}", e))?;
        let table = read_txn
            .open_table(ARTISTS_TABLE)
            .map_err(|e| anyhow!("Failed to open artists table: {}", e))?;
        get_all_items(&table)
    }

    pub fn get_by_id(db: &Database, artist_id: &str) -> Result<Option<Artist>> {
        let read_txn = db
            .begin_read()
            .map_err(|e| anyhow!("Failed to begin read transaction: {}", e))?;
        let table = read_txn
            .open_table(ARTISTS_TABLE)
            .map_err(|e| anyhow!("Failed to open artists table: {}", e))?;

        if let Some(bytes) = table.get(artist_id)? {
            let artist: Artist = postcard::from_bytes(bytes.value())
                .map_err(|e| anyhow!("Failed to decode artist: {}", e))?;
            Ok(Some(artist))
        } else {
            Ok(None)
        }
    }
}

// ============================================================================
// Albums submodule
// ============================================================================

pub mod albums {
    use super::*;
    use redb::ReadableDatabase;

    pub fn get_all(db: &Database) -> Result<Vec<Album>> {
        let read_txn = db
            .begin_read()
            .map_err(|e| anyhow!("Failed to begin read transaction: {}", e))?;
        let table = read_txn
            .open_table(ALBUMS_TABLE)
            .map_err(|e| anyhow!("Failed to open albums table: {}", e))?;
        let albums = get_all_items(&table)?;
        info!("Retrieved {} albums from database", albums.len());
        Ok(albums)
    }

    pub fn get_by_id(db: &Database, album_id: &str) -> Result<Option<Album>> {
        let read_txn = db
            .begin_read()
            .map_err(|e| anyhow!("Failed to begin read transaction: {}", e))?;
        let table = read_txn
            .open_table(ALBUMS_TABLE)
            .map_err(|e| anyhow!("Failed to open albums table: {}", e))?;

        if let Some(bytes) = table.get(album_id)? {
            let album: Album = postcard::from_bytes(bytes.value())
                .map_err(|e| anyhow!("Failed to decode album: {}", e))?;
            Ok(Some(album))
        } else {
            Ok(None)
        }
    }
}
