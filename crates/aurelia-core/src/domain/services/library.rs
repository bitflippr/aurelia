use crate::db::schema::*;
use crate::domain::errors::DomainError;
use crate::domain::models::{SyncReport, SyncState};
use crate::models::{Album, Artist, Song};
use redb::{Database, ReadableDatabase, ReadableTable, ReadableTableMetadata};
use std::sync::Arc;
use tracing::info;

pub struct LibraryInventory {
    pub songs: std::collections::HashSet<String>,
    pub artists: std::collections::HashSet<String>,
    pub albums: std::collections::HashSet<String>,
}

pub struct LibraryService {
    db: Arc<Database>,
}

impl LibraryService {
    pub fn new(db: Arc<Database>) -> Self {
        Self { db }
    }

    pub fn get_sync_state(&self) -> Result<SyncState, DomainError> {
        let read_txn = self.db.begin_read()?;
        let table = read_txn.open_table(SYNC_STATE)?;

        if let Some(bytes) = table.get("library")? {
            // Try to deserialize; if the stored format is from an older schema
            // (fewer fields), fall back to default so the DB upgrades on next write.
            match postcard::from_bytes::<SyncState>(bytes.value()) {
                Ok(state) => Ok(state),
                Err(e) => {
                    info!(
                        "SyncState deserialization failed (schema upgrade?), resetting: {}",
                        e
                    );
                    Ok(SyncState::default())
                }
            }
        } else {
            Ok(SyncState::default())
        }
    }

    pub fn update_sync_state(&self, state: &SyncState) -> Result<(), DomainError> {
        let write_txn = self.db.begin_write()?;
        {
            let mut table = write_txn.open_table(SYNC_STATE)?;
            let encoded = postcard::to_stdvec(state)
                .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            table.insert("library", encoded.as_slice())?;
        }
        write_txn.commit()?;
        Ok(())
    }

    /// Replace a complete song snapshot and its indexes in one transaction.
    ///
    /// Artist/album metadata and the library sync checkpoint are untouched: a
    /// song refresh does not establish when the rest of the library was synced.
    /// Returns whether the song cache was empty before replacement.
    pub fn replace_songs(&self, songs: &[Song]) -> Result<bool, DomainError> {
        let write_txn = self.db.begin_write()?;
        let was_empty = write_txn.open_table(SONGS)?.is_empty()?;
        self.clear_table(&write_txn, SONGS)?;
        self.clear_composite_table(&write_txn, SONGS_BY_ALBUM)?;
        self.clear_composite_table(&write_txn, SONGS_BY_ARTIST)?;
        self.clear_table(&write_txn, FAVORITES)?;
        self.insert_songs(&write_txn, songs)?;
        write_txn.commit()?;
        Ok(was_empty)
    }

    fn insert_songs(
        &self,
        write_txn: &redb::WriteTransaction,
        songs: &[Song],
    ) -> Result<(), DomainError> {
        // Sync songs with indexes
        let mut songs_table = write_txn.open_table(SONGS)?;
        let mut songs_by_album = write_txn.open_table(SONGS_BY_ALBUM)?;
        let mut songs_by_artist = write_txn.open_table(SONGS_BY_ARTIST)?;
        let mut favorites = write_txn.open_table(FAVORITES)?;

        for song in songs {
            let encoded =
                postcard::to_stdvec(song).map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            let previous = songs_table
                .get(song.id.as_str())?
                .map(|value| postcard::from_bytes::<Song>(value.value()))
                .transpose()
                .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            if let Some(previous) = previous {
                if let Some(album) = previous.album_id {
                    songs_by_album.remove((album.as_str(), song.id.as_str()))?;
                }
                for artist in previous.artist_ids.unwrap_or_default() {
                    songs_by_artist.remove((artist.as_str(), song.id.as_str()))?;
                }
            }
            songs_table.insert(song.id.as_str(), encoded.as_slice())?;

            // Update album index
            if let Some(album_id) = &song.album_id {
                songs_by_album.insert((album_id.as_str(), song.id.as_str()), ())?;
            }

            // Update artist indexes
            if let Some(artist_ids) = &song.artist_ids {
                for artist_id in artist_ids {
                    songs_by_artist.insert((artist_id.as_str(), song.id.as_str()), ())?;
                }
            }

            // Update favorites
            if let Some(true) = song.is_favorite {
                let timestamp = chrono::Utc::now().to_rfc3339();
                let encoded_ts = postcard::to_stdvec(&timestamp)
                    .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
                favorites.insert(song.id.as_str(), encoded_ts.as_slice())?;
            } else {
                favorites.remove(song.id.as_str())?;
            }
        }
        Ok(())
    }

    fn insert_artists(
        &self,
        write_txn: &redb::WriteTransaction,
        artists: &[Artist],
    ) -> Result<(), DomainError> {
        // Sync artists
        let mut artists_table = write_txn.open_table(ARTISTS)?;
        for artist in artists {
            let encoded = postcard::to_stdvec(artist)
                .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            artists_table.insert(artist.id.as_str(), encoded.as_slice())?;
        }
        Ok(())
    }

    fn insert_albums(
        &self,
        write_txn: &redb::WriteTransaction,
        albums: &[Album],
    ) -> Result<(), DomainError> {
        // Sync albums with indexes
        let mut albums_table = write_txn.open_table(ALBUMS)?;
        let mut albums_by_artist = write_txn.open_table(ALBUMS_BY_ARTIST)?;

        for album in albums {
            let album_id = album
                .id
                .as_ref()
                .filter(|id| !id.is_empty())
                .ok_or_else(|| DomainError::ValidationError("Album has no ID".into()))?;

            let encoded = postcard::to_stdvec(album)
                .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            let previous = albums_table
                .get(album_id.as_str())?
                .map(|value| postcard::from_bytes::<Album>(value.value()))
                .transpose()
                .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            if let Some(previous) = previous.and_then(|album| album.artist_id) {
                albums_by_artist.remove((previous.as_str(), album_id.as_str()))?;
            }
            albums_table.insert(album_id.as_str(), encoded.as_slice())?;

            // Update artist-album index
            if let Some(artist_id) = &album.artist_id {
                albums_by_artist.insert((artist_id.as_str(), album_id.as_str()), ())?;
            }
        }
        Ok(())
    }

    /// Clear library records and indexes using this service's database.
    pub fn clear_library(&self) -> Result<(), DomainError> {
        let write_txn = self.db.begin_write()?;
        self.clear_all_tables(&write_txn)?;
        self.clear_table(&write_txn, SYNC_STATE)?;
        write_txn.commit()?;
        Ok(())
    }

    /// Commit a validated remote update and its checkpoint together. Without an
    /// inventory this is a complete replacement; with one it is an incremental update.
    pub fn commit_sync(
        &self,
        songs: &[Song],
        artists: &[Artist],
        albums: &[Album],
        inventory: Option<&LibraryInventory>,
        mut state: SyncState,
    ) -> Result<SyncReport, DomainError> {
        let write_txn = self.db.begin_write()?;
        let full_sync = inventory.is_none();
        if full_sync {
            self.clear_all_tables(&write_txn)?;
        }
        self.insert_songs(&write_txn, songs)?;
        self.insert_artists(&write_txn, artists)?;
        self.insert_albums(&write_txn, albums)?;
        let mut removed = (0, 0, 0);
        if let Some(inventory) = inventory {
            removed.0 = self.retain_records(&write_txn, SONGS, &inventory.songs)?;
            removed.1 = self.retain_records(&write_txn, ARTISTS, &inventory.artists)?;
            removed.2 = self.retain_records(&write_txn, ALBUMS, &inventory.albums)?;
            self.rebuild_indexes(&write_txn)?;
        }
        state.song_count = write_txn.open_table(SONGS)?.len()? as u32;
        state.artist_count = write_txn.open_table(ARTISTS)?.len()? as u32;
        state.album_count = write_txn.open_table(ALBUMS)?.len()? as u32;
        let encoded =
            postcard::to_stdvec(&state).map_err(|e| DomainError::DatabaseError(e.to_string()))?;
        write_txn
            .open_table(SYNC_STATE)?
            .insert("library", encoded.as_slice())?;
        write_txn.commit()?;
        Ok(SyncReport {
            full_sync,
            songs_updated: songs.len() as u32 + removed.0,
            artists_updated: artists.len() as u32 + removed.1,
            albums_updated: albums.len() as u32 + removed.2,
            duration_ms: 0,
        })
    }

    fn retain_records(
        &self,
        txn: &redb::WriteTransaction,
        definition: redb::TableDefinition<&str, &[u8]>,
        ids: &std::collections::HashSet<String>,
    ) -> Result<u32, DomainError> {
        let mut table = txn.open_table(definition)?;
        let mut deleted = Vec::new();
        for entry in table.iter()? {
            let (id, _) = entry?;
            if !ids.contains(id.value()) {
                deleted.push(id.value().to_owned());
            }
        }
        for id in &deleted {
            table.remove(id.as_str())?;
        }
        Ok(deleted.len() as u32)
    }

    fn rebuild_indexes(&self, txn: &redb::WriteTransaction) -> Result<(), DomainError> {
        self.clear_composite_table(txn, SONGS_BY_ALBUM)?;
        self.clear_composite_table(txn, SONGS_BY_ARTIST)?;
        self.clear_composite_table(txn, ALBUMS_BY_ARTIST)?;
        self.clear_table(txn, FAVORITES)?;
        let mut by_album = txn.open_table(SONGS_BY_ALBUM)?;
        let mut by_artist = txn.open_table(SONGS_BY_ARTIST)?;
        let mut favorites = txn.open_table(FAVORITES)?;
        let timestamp = postcard::to_stdvec(&chrono::Utc::now().to_rfc3339())
            .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
        for entry in txn.open_table(SONGS)?.iter()? {
            let (_, value) = entry?;
            let song: Song = postcard::from_bytes(value.value())
                .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            if let Some(album) = song.album_id {
                by_album.insert((album.as_str(), song.id.as_str()), ())?;
            }
            for artist in song.artist_ids.unwrap_or_default() {
                by_artist.insert((artist.as_str(), song.id.as_str()), ())?;
            }
            if song.is_favorite == Some(true) {
                favorites.insert(song.id.as_str(), timestamp.as_slice())?;
            }
        }
        let mut album_index = txn.open_table(ALBUMS_BY_ARTIST)?;
        for entry in txn.open_table(ALBUMS)?.iter()? {
            let (id, value) = entry?;
            let album: Album = postcard::from_bytes(value.value())
                .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            if let Some(artist) = album.artist_id {
                album_index.insert((artist.as_str(), id.value()), ())?;
            }
        }
        Ok(())
    }

    pub fn update_favorite(&self, song_id: &str, favorite: bool) -> Result<(), DomainError> {
        let txn = self.db.begin_write()?;
        let mut song = txn
            .open_table(SONGS)?
            .get(song_id)?
            .map(|bytes| postcard::from_bytes::<Song>(bytes.value()))
            .transpose()
            .map_err(|e| DomainError::DatabaseError(e.to_string()))?
            .ok_or_else(|| DomainError::NotFound(song_id.into()))?;
        song.is_favorite = Some(favorite);
        self.insert_songs(&txn, &[song])?;
        txn.commit()?;
        Ok(())
    }

    pub fn sync_favorites(&self, favorite_ids: &[String]) -> Result<u32, DomainError> {
        let ids: std::collections::HashSet<_> = favorite_ids.iter().collect();
        let txn = self.db.begin_write()?;
        let mut changes = Vec::new();
        for entry in txn.open_table(SONGS)?.iter()? {
            let (_, bytes) = entry?;
            let mut song: Song = postcard::from_bytes(bytes.value())
                .map_err(|e| DomainError::DatabaseError(e.to_string()))?;
            let favorite = ids.contains(&song.id);
            if song.is_favorite != Some(favorite) {
                song.is_favorite = Some(favorite);
                changes.push(song);
            }
        }
        self.insert_songs(&txn, &changes)?;
        txn.commit()?;
        Ok(changes.len() as u32)
    }

    fn clear_all_tables(&self, write_txn: &redb::WriteTransaction) -> Result<(), DomainError> {
        // Clear main tables
        self.clear_table(write_txn, SONGS)?;
        self.clear_table(write_txn, ARTISTS)?;
        self.clear_table(write_txn, ALBUMS)?;

        // Clear index tables
        self.clear_composite_table(write_txn, SONGS_BY_ALBUM)?;
        self.clear_composite_table(write_txn, SONGS_BY_ARTIST)?;
        self.clear_composite_table(write_txn, ALBUMS_BY_ARTIST)?;

        // Clear metadata tables
        self.clear_table(write_txn, FAVORITES)?;

        Ok(())
    }

    fn clear_table(
        &self,
        write_txn: &redb::WriteTransaction,
        table_def: redb::TableDefinition<&str, &[u8]>,
    ) -> Result<(), DomainError> {
        let mut table = write_txn.open_table(table_def)?;
        let mut keys = Vec::new();
        for item in table.iter()? {
            let (key, _) = item?;
            keys.push(key.value().to_string());
        }
        for key in keys {
            table.remove(key.as_str())?;
        }
        Ok(())
    }

    fn clear_composite_table(
        &self,
        write_txn: &redb::WriteTransaction,
        table_def: redb::TableDefinition<(&str, &str), ()>,
    ) -> Result<(), DomainError> {
        let mut table = write_txn.open_table(table_def)?;
        let mut keys = Vec::new();
        for item in table.iter()? {
            let (key, _) = item?;
            let (k1, k2) = key.value();
            keys.push((k1.to_string(), k2.to_string()));
        }
        for (k1, k2) in keys {
            table.remove((k1.as_str(), k2.as_str()))?;
        }
        Ok(())
    }

    pub fn get_library_stats(&self) -> Result<(u32, u32, u32), DomainError> {
        let read_txn = self.db.begin_read()?;

        let songs_table = read_txn.open_table(SONGS)?;
        let artists_table = read_txn.open_table(ARTISTS)?;
        let albums_table = read_txn.open_table(ALBUMS)?;

        let song_count = songs_table.len()? as u32;
        let artist_count = artists_table.len()? as u32;
        let album_count = albums_table.len()? as u32;

        Ok((song_count, artist_count, album_count))
    }

    /// Upsert songs into the database with their indexes.
    /// Returns the number of songs upserted.
    pub fn upsert_songs(&self, songs: &[Song]) -> Result<u32, DomainError> {
        if songs.is_empty() {
            return Ok(0);
        }
        let write_txn = self.db.begin_write()?;
        self.insert_songs(&write_txn, songs)?;
        write_txn.commit()?;
        Ok(songs.len() as u32)
    }

    /// Upsert albums into the database with their indexes.
    /// Returns the number of albums upserted.
    pub fn upsert_albums(&self, albums: &[Album]) -> Result<u32, DomainError> {
        if albums.is_empty() {
            return Ok(0);
        }
        let write_txn = self.db.begin_write()?;
        self.insert_albums(&write_txn, albums)?;
        write_txn.commit()?;
        Ok(albums.len() as u32)
    }

    /// Upsert artists into the database.
    /// Returns the number of artists upserted.
    pub fn upsert_artists(&self, artists: &[Artist]) -> Result<u32, DomainError> {
        if artists.is_empty() {
            return Ok(0);
        }
        let write_txn = self.db.begin_write()?;
        self.insert_artists(&write_txn, artists)?;
        write_txn.commit()?;
        Ok(artists.len() as u32)
    }

    /// Remove songs whose IDs are NOT in the provided set of valid remote IDs.
    /// Returns the number of songs removed.
    pub fn remove_deleted_songs(
        &self,
        valid_remote_ids: &std::collections::HashSet<String>,
    ) -> Result<u32, DomainError> {
        let txn = self.db.begin_write()?;
        let count = self.retain_records(&txn, SONGS, valid_remote_ids)?;
        self.rebuild_indexes(&txn)?;
        txn.commit()?;
        Ok(count)
    }

    /// Remove albums whose IDs are NOT in the provided set of valid remote IDs.
    /// Returns the number of albums removed.
    pub fn remove_deleted_albums(
        &self,
        valid_remote_ids: &std::collections::HashSet<String>,
    ) -> Result<u32, DomainError> {
        let txn = self.db.begin_write()?;
        let count = self.retain_records(&txn, ALBUMS, valid_remote_ids)?;
        self.rebuild_indexes(&txn)?;
        txn.commit()?;
        Ok(count)
    }

    /// Remove artists whose IDs are NOT in the provided set of valid remote IDs.
    /// Returns the number of artists removed.
    pub fn remove_deleted_artists(
        &self,
        valid_remote_ids: &std::collections::HashSet<String>,
    ) -> Result<u32, DomainError> {
        let txn = self.db.begin_write()?;
        let count = self.retain_records(&txn, ARTISTS, valid_remote_ids)?;
        self.rebuild_indexes(&txn)?;
        txn.commit()?;
        Ok(count)
    }
}

#[cfg(test)]
mod tests {
    use super::LibraryService;
    use crate::db;
    use crate::db::schema::{FAVORITES, SONGS, SONGS_BY_ALBUM, SONGS_BY_ARTIST};
    use crate::models::{Album, Artist, Song};
    use redb::ReadableDatabase;
    use tempfile::TempDir;

    fn init_db() -> (TempDir, std::sync::Arc<redb::Database>) {
        let dir = TempDir::new().expect("temp dir");
        let db = db::open(&dir.path().to_path_buf()).expect("database");
        (dir, db)
    }

    fn song(id: &str, album_id: &str, artist_id: &str, date_modified: &str) -> Song {
        Song {
            id: id.to_string(),
            name: format!("Song {id}"),
            item_type: "Audio".to_string(),
            album: Some(format!("Album {album_id}")),
            album_id: Some(album_id.to_string()),
            artists: Some(vec![format!("Artist {artist_id}")]),
            artist_ids: Some(vec![artist_id.to_string()]),
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
            date_created: Some("2024-01-01T00:00:00Z".to_string()),
            date_modified: Some(date_modified.to_string()),
            album_artists: None,
            lyrics: None,
            image_tags: None,
        }
    }

    fn artist(id: &str) -> Artist {
        Artist {
            name: format!("Artist {id}"),
            id: id.to_string(),
            image_tags: None,
            image_url: None,
            overview: None,
            provider_ids: None,
            community_rating: None,
            song_count: None,
            date_modified: None,
            songs: None,
        }
    }

    fn album(id: &str, artist_id: &str) -> Album {
        Album {
            id: Some(id.to_string()),
            name: format!("Album {id}"),
            artist: format!("Artist {artist_id}"),
            artist_id: Some(artist_id.to_string()),
            album_art_url: None,
            song_count: 1,
            songs: None,
            image_tags: None,
            provider_ids: None,
            date_created: Some("2024-01-01T00:00:00Z".to_string()),
            date_modified: None,
        }
    }

    #[test]
    fn upsert_songs_inserts_new_and_updates_existing() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db);

        // Insert initial songs
        let songs_v1 = vec![
            song("s1", "a1", "ar1", "2024-01-01T00:00:00Z"),
            song("s2", "a1", "ar1", "2024-01-01T00:00:00Z"),
        ];
        let count = service.upsert_songs(&songs_v1).expect("upsert");
        assert_eq!(count, 2);

        let (song_count, _, _) = service.get_library_stats().expect("stats");
        assert_eq!(song_count, 2);

        // Upsert: update s1, add s3
        let songs_v2 = vec![
            song("s1", "a1", "ar1", "2024-06-01T00:00:00Z"), // updated
            song("s3", "a2", "ar2", "2024-06-01T00:00:00Z"), // new
        ];
        let count = service.upsert_songs(&songs_v2).expect("upsert v2");
        assert_eq!(count, 2);

        let (song_count, _, _) = service.get_library_stats().expect("stats");
        assert_eq!(song_count, 3); // s1, s2, s3
    }

    #[test]
    fn upsert_albums_inserts_and_updates() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db);

        let albums_v1 = vec![album("alb1", "ar1"), album("alb2", "ar1")];
        let count = service.upsert_albums(&albums_v1).expect("upsert");
        assert_eq!(count, 2);

        let (_, _, album_count) = service.get_library_stats().expect("stats");
        assert_eq!(album_count, 2);

        // Add a third album
        let albums_v2 = vec![album("alb3", "ar2")];
        service.upsert_albums(&albums_v2).expect("upsert v2");

        let (_, _, album_count) = service.get_library_stats().expect("stats");
        assert_eq!(album_count, 3);
    }

    #[test]
    fn upsert_artists_inserts_and_updates() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db);

        let artists_v1 = vec![artist("ar1"), artist("ar2")];
        let count = service.upsert_artists(&artists_v1).expect("upsert");
        assert_eq!(count, 2);

        let (_, artist_count, _) = service.get_library_stats().expect("stats");
        assert_eq!(artist_count, 2);

        // Add a third artist
        let artists_v2 = vec![artist("ar3")];
        service.upsert_artists(&artists_v2).expect("upsert v2");

        let (_, artist_count, _) = service.get_library_stats().expect("stats");
        assert_eq!(artist_count, 3);
    }

    #[test]
    fn remove_deleted_songs_removes_absent_ids() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db);

        // Populate with 3 songs
        let songs = vec![
            song("s1", "a1", "ar1", "2024-01-01"),
            song("s2", "a1", "ar1", "2024-01-01"),
            song("s3", "a2", "ar2", "2024-01-01"),
        ];
        service.upsert_songs(&songs).expect("upsert");

        // Remote only has s1 and s3 - s2 was deleted on server
        let valid_ids: std::collections::HashSet<String> =
            ["s1".to_string(), "s3".to_string()].into_iter().collect();
        let removed = service.remove_deleted_songs(&valid_ids).expect("remove");
        assert_eq!(removed, 1);

        let (song_count, _, _) = service.get_library_stats().expect("stats");
        assert_eq!(song_count, 2);
    }

    #[test]
    fn remove_deleted_albums_removes_absent_ids() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db);

        let albums = vec![
            album("alb1", "ar1"),
            album("alb2", "ar1"),
            album("alb3", "ar2"),
        ];
        service.upsert_albums(&albums).expect("upsert");

        // alb2 deleted on server
        let valid_ids: std::collections::HashSet<String> = ["alb1".to_string(), "alb3".to_string()]
            .into_iter()
            .collect();
        let removed = service.remove_deleted_albums(&valid_ids).expect("remove");
        assert_eq!(removed, 1);

        let (_, _, album_count) = service.get_library_stats().expect("stats");
        assert_eq!(album_count, 2);
    }

    #[test]
    fn remove_deleted_artists_removes_absent_ids() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db);

        let artists = vec![artist("ar1"), artist("ar2"), artist("ar3")];
        service.upsert_artists(&artists).expect("upsert");

        // ar2 deleted on server
        let valid_ids: std::collections::HashSet<String> =
            ["ar1".to_string(), "ar3".to_string()].into_iter().collect();
        let removed = service.remove_deleted_artists(&valid_ids).expect("remove");
        assert_eq!(removed, 1);

        let (_, artist_count, _) = service.get_library_stats().expect("stats");
        assert_eq!(artist_count, 2);
    }

    #[test]
    fn remove_deleted_returns_zero_when_nothing_to_delete() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db);

        let songs = vec![song("s1", "a1", "ar1", "2024-01-01")];
        service.upsert_songs(&songs).expect("upsert");

        // All IDs still valid
        let valid_ids: std::collections::HashSet<String> = ["s1".to_string()].into_iter().collect();
        let removed = service.remove_deleted_songs(&valid_ids).expect("remove");
        assert_eq!(removed, 0);
    }

    #[test]
    fn upsert_empty_is_noop() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db);

        assert_eq!(service.upsert_songs(&[]).expect("upsert"), 0);
        assert_eq!(service.upsert_albums(&[]).expect("upsert"), 0);
        assert_eq!(service.upsert_artists(&[]).expect("upsert"), 0);
    }

    #[test]
    fn upsert_songs_updates_favorite_status() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db.clone());

        // Insert a favorite song
        let mut fav_song = song("fav1", "a1", "ar1", "2024-01-01");
        fav_song.is_favorite = Some(true);
        service.upsert_songs(&[fav_song]).expect("upsert");

        // Now un-favorite it
        let mut unfav_song = song("fav1", "a1", "ar1", "2024-02-01");
        unfav_song.is_favorite = Some(false);
        service.upsert_songs(&[unfav_song]).expect("upsert");

        // Verify it's no longer in favorites (song still exists)
        let (song_count, _, _) = service.get_library_stats().expect("stats");
        assert_eq!(song_count, 1);
        let read = db.begin_read().unwrap();
        assert!(
            read.open_table(FAVORITES)
                .unwrap()
                .get("fav1")
                .unwrap()
                .is_none()
        );
        let table = read.open_table(SONGS).unwrap();
        let record = table.get("fav1").unwrap().unwrap();
        let stored: Song = postcard::from_bytes(record.value()).unwrap();
        assert_eq!(stored.is_favorite, Some(false));
    }

    #[test]
    fn changing_album_and_artist_removes_old_index_memberships() {
        let (_dir, db) = init_db();
        let service = LibraryService::new(db.clone());
        service
            .upsert_songs(&[song("s", "old-album", "old-artist", "2024-01-01")])
            .unwrap();
        service
            .upsert_songs(&[song("s", "new-album", "new-artist", "2024-02-01")])
            .unwrap();
        let read = db.begin_read().unwrap();
        let albums = read.open_table(SONGS_BY_ALBUM).unwrap();
        let artists = read.open_table(SONGS_BY_ARTIST).unwrap();
        assert!(albums.get(("old-album", "s")).unwrap().is_none());
        assert!(artists.get(("old-artist", "s")).unwrap().is_none());
        assert!(albums.get(("new-album", "s")).unwrap().is_some());
        assert!(artists.get(("new-artist", "s")).unwrap().is_some());
    }
}
