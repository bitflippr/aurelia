use aurelia_core::db::{self, schema::*};
use aurelia_core::domain::{SyncState, services::LibraryService};
use aurelia_core::models::{Album, Artist, Song};
use aurelia_core::{cache_songs, fetch_songs, load_cached_songs, sync_songs_only};
use httpmock::{Method::GET, MockServer};
use redb::{ReadableDatabase, ReadableTableMetadata};
use serde_json::json;
use std::sync::Arc;
use tempfile::TempDir;

fn song(id: &str, album: &str, artist: &str, favorite: bool) -> Song {
    serde_json::from_value(json!({
        "id": id, "name": id, "itemType": "Audio", "albumId": album,
        "artistIds": [artist], "isFavorite": favorite
    }))
    .unwrap()
}

struct Library {
    _dir: TempDir,
    path: String,
    db: Arc<redb::Database>,
}

impl Library {
    fn new(songs: &[Song]) -> Self {
        let dir = TempDir::new().unwrap();
        let path = dir.path().to_string_lossy().into_owned();
        let db = db::open(&dir.path().to_path_buf()).unwrap();
        let artist: Artist = serde_json::from_value(json!({
            "id": "artist", "name": "Artist", "overview": "Cached biography"
        }))
        .unwrap();
        let album: Album = serde_json::from_value(json!({
            "id": "album", "name": "Album", "artist": "Artist",
            "artistId": "artist", "songCount": 2
        }))
        .unwrap();
        let service = LibraryService::new(db.clone());
        service
            .commit_sync(songs, &[artist], &[album], None, SyncState::default())
            .unwrap();
        service
            .update_sync_state(&SyncState {
                last_sync_time: "2025-01-02T00:00:00Z".into(),
                last_full_sync_time: Some("2025-01-01T00:00:00Z".into()),
                last_sync_version: Some("checkpoint".into()),
                song_count: songs.len() as u32,
                artist_count: 1,
                album_count: 1,
                full_sync_in_progress: true,
                full_sync_last_page_index: 200,
                full_sync_entity_type: Some("albums".into()),
            })
            .unwrap();
        Self {
            _dir: dir,
            path,
            db,
        }
    }

    // Compare the stored checkpoint and metadata, including fields not exposed by reads.
    fn metadata(&self) -> Vec<Vec<u8>> {
        let txn = self.db.begin_read().unwrap();
        let mut records = Vec::new();
        for (definition, key) in [
            (ARTISTS, "artist"),
            (ALBUMS, "album"),
            (SYNC_STATE, "library"),
        ] {
            let table = txn.open_table(definition).unwrap();
            records.push(
                table
                    .get(key)
                    .unwrap()
                    .map(|v| v.value().to_vec())
                    .unwrap_or_default(),
            );
        }
        let index = txn.open_table(ALBUMS_BY_ARTIST).unwrap();
        assert_eq!(index.len().unwrap(), 1);
        assert!(index.get(("artist", "album")).unwrap().is_some());
        records
    }
}

#[test]
fn caching_songs_replaces_song_data_without_changing_metadata_or_checkpoint() {
    let library = Library::new(&[
        song("keep", "old-album", "old-artist", true),
        song("remove", "old-album", "old-artist", true),
    ]);
    let metadata = library.metadata();
    let songs = vec![
        song("add", "new-album", "new-artist", true),
        song("keep", "new-album", "new-artist", false),
    ];
    cache_songs(library.path.clone(), songs.clone()).unwrap();
    assert_eq!(load_cached_songs(library.path.clone()).unwrap(), songs);
    assert_eq!(library.metadata(), metadata);

    let txn = library.db.begin_read().unwrap();
    for (definition, parent) in [
        (SONGS_BY_ALBUM, "new-album"),
        (SONGS_BY_ARTIST, "new-artist"),
    ] {
        let index = txn.open_table(definition).unwrap();
        assert_eq!(index.len().unwrap(), 2);
        assert!(index.get((parent, "add")).unwrap().is_some());
        assert!(index.get((parent, "keep")).unwrap().is_some());
    }
    let favorites = txn.open_table(FAVORITES).unwrap();
    assert_eq!(favorites.len().unwrap(), 1);
    assert!(favorites.get("add").unwrap().is_some());
}

#[test]
fn empty_song_snapshot_clears_only_songs_and_their_indexes() {
    let library = Library::new(&[song("remove", "album", "artist", true)]);
    let metadata = library.metadata();
    cache_songs(library.path.clone(), vec![]).unwrap();
    assert!(load_cached_songs(library.path.clone()).unwrap().is_empty());
    assert_eq!(library.metadata(), metadata);
    let txn = library.db.begin_read().unwrap();
    assert_eq!(txn.open_table(FAVORITES).unwrap().len().unwrap(), 0);
    for definition in [SONGS_BY_ALBUM, SONGS_BY_ARTIST] {
        assert_eq!(txn.open_table(definition).unwrap().len().unwrap(), 0);
    }
}

#[test]
fn caching_first_songs_does_not_claim_a_completed_library_sync() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().to_string_lossy().into_owned();
    cache_songs(path, vec![song("first", "album", "artist", false)]).unwrap();
    let db = db::open(&dir.path().to_path_buf()).unwrap();
    let txn = db.begin_read().unwrap();
    assert!(
        txn.open_table(SYNC_STATE)
            .unwrap()
            .get("library")
            .unwrap()
            .is_none()
    );
}

#[tokio::test]
async fn network_song_refreshes_preserve_metadata_for_empty_and_existing_song_caches() {
    let server = MockServer::start();
    let mock = server.mock(|when, then| {
        when.method(GET)
            .path("/Items")
            .query_param("IncludeItemTypes", "Audio");
        then.status(200)
            .json_body(json!({"TotalRecordCount": 1, "Items": [{"Id": "fresh", "Name": "Fresh", "Type": "Audio"}]}));
    });
    for existing in [vec![], vec![song("old", "album", "artist", false)]] {
        for use_sync in [false, true] {
            let library = Library::new(&existing);
            let metadata = library.metadata();
            if use_sync {
                let initial = sync_songs_only(
                    server.base_url(),
                    "token".into(),
                    "user".into(),
                    library.path.clone(),
                )
                .await
                .unwrap();
                assert_eq!(initial, existing.is_empty());
            } else {
                let fetched = fetch_songs(
                    server.base_url(),
                    "token".into(),
                    "user".into(),
                    library.path.clone(),
                )
                .await
                .unwrap();
                assert_eq!(fetched.len(), 1);
                assert_eq!(fetched[0].id, "fresh");
            }
            let cached = load_cached_songs(library.path.clone()).unwrap();
            assert_eq!(cached.len(), 1);
            assert_eq!(cached[0].id, "fresh");
            assert_eq!(library.metadata(), metadata);
        }
    }
    mock.assert_calls(4);
}

#[tokio::test]
async fn failed_song_fetch_keeps_the_cached_library() {
    let server = MockServer::start();
    let mock = server.mock(|when, then| {
        when.method(GET).path("/Items");
        then.status(500);
    });
    let songs = vec![song("keep", "album", "artist", true)];
    let library = Library::new(&songs);
    let metadata = library.metadata();
    assert!(
        fetch_songs(
            server.base_url(),
            "token".into(),
            "user".into(),
            library.path.clone()
        )
        .await
        .is_err()
    );
    assert_eq!(load_cached_songs(library.path.clone()).unwrap(), songs);
    assert_eq!(library.metadata(), metadata);
    mock.assert();
}
