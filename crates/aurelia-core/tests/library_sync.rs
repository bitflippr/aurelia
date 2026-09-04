use aurelia_core::{
    cache_songs, get_sync_progress, get_sync_state, load_cached_songs, sync_library_smart,
};
use aurelia_core::{error::AppError, models::Song, services::JellyfinClient};
use httpmock::{Method::GET, MockServer};
use serde_json::json;
use tempfile::TempDir;

fn old_song() -> Song {
    serde_json::from_value(json!({"id":"old", "name":"Old", "itemType":"Audio"})).unwrap()
}

#[tokio::test]
async fn invalid_inventory_is_rejected_instead_of_becoming_a_deletion_list() {
    for body in [
        json!({}),
        json!({"Items":[],"TotalRecordCount":1}),
        json!({"Items":[{}],"TotalRecordCount":1}),
        json!({"Items":[{"Id":"same"},{"Id":"same"}],"TotalRecordCount":2}),
    ] {
        let server = MockServer::start();
        let mock = server.mock(|when, then| {
            when.method(GET).path("/Items");
            then.status(200).json_body(body);
        });
        let client = JellyfinClient::with_auth(server.base_url(), "token".into());
        assert!(matches!(
            client.get_all_item_ids("user", "Audio").await,
            Err(AppError::ApiParse(_))
        ));
        mock.assert();
    }
}

#[tokio::test]
async fn failed_full_sync_keeps_the_previous_snapshot_and_retries_from_the_start() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().to_string_lossy().into_owned();
    cache_songs(path.clone(), vec![old_song()]).unwrap();
    let before = serde_json::to_value(get_sync_state(path.clone()).unwrap()).unwrap();
    let server = MockServer::start();
    let songs = server.mock(|when, then| {
        when.method(GET)
            .path("/Items")
            .query_param("IncludeItemTypes", "Audio");
        then.status(200)
            .header("Date", "Fri, 04 Sep 2026 12:00:00 GMT")
            .json_body(
                json!({"TotalRecordCount":1,"Items":[{"Id":"new","Name":"New","Type":"Audio"}]}),
            );
    });
    let mut failure = server.mock(|when, then| {
        when.method(GET)
            .path("/Items")
            .query_param("IncludeItemTypes", "MusicAlbum");
        then.status(401);
    });
    let result = sync_library_smart(
        server.base_url(),
        "token".into(),
        "user".into(),
        path.clone(),
    )
    .await;
    assert!(matches!(result, Err(AppError::Http { status: 401, .. })));
    assert_eq!(load_cached_songs(path.clone()).unwrap(), [old_song()]);
    assert_eq!(
        serde_json::to_value(get_sync_state(path.clone()).unwrap()).unwrap(),
        before
    );
    assert_eq!(get_sync_progress(path.clone()).stage, "Failed");
    failure.delete();
    for entity in ["MusicAlbum", "MusicArtist"] {
        server.mock(|when, then| {
            when.method(GET)
                .path("/Items")
                .query_param("IncludeItemTypes", entity);
            then.status(200)
                .json_body(json!({"TotalRecordCount":0,"Items":[]}));
        });
    }
    sync_library_smart(
        server.base_url(),
        "token".into(),
        "user".into(),
        path.clone(),
    )
    .await
    .unwrap();
    assert_eq!(load_cached_songs(path.clone()).unwrap()[0].id, "new");
    assert_eq!(
        get_sync_state(path.clone()).unwrap().last_sync_time,
        "2026-09-04T12:00:00+00:00"
    );
    assert_eq!(get_sync_progress(path).operation_id, 2);
    songs.assert_calls(2);
}

#[tokio::test]
async fn simultaneous_sync_callers_share_one_operation() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().to_string_lossy().into_owned();
    let server = MockServer::start();
    let mock = server.mock(|when, then| {
        when.method(GET).path("/Items");
        then.status(200)
            .json_body(json!({"TotalRecordCount":0,"Items":[]}));
    });
    let (first, second) = tokio::join!(
        sync_library_smart(
            server.base_url(),
            "token".into(),
            "user".into(),
            path.clone()
        ),
        sync_library_smart(
            server.base_url(),
            "token".into(),
            "user".into(),
            path.clone()
        )
    );
    assert!(first.unwrap().full_sync);
    assert!(second.unwrap().full_sync);
    mock.assert_calls(3);
    let progress = get_sync_progress(path);
    assert!(progress.is_complete);
    assert_eq!(progress.operation_id, 1);
    let other = TempDir::new().unwrap();
    assert_eq!(
        get_sync_progress(other.path().to_string_lossy().into_owned()).operation_id,
        0
    );
}

#[tokio::test]
async fn invalid_incremental_inventory_preserves_rows_and_checkpoint() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().to_string_lossy().into_owned();
    cache_songs(path.clone(), vec![old_song()]).unwrap();
    let db = aurelia_core::db::open(&dir.path().to_path_buf()).unwrap();
    let service = aurelia_core::domain::services::LibraryService::new(db);
    let state = aurelia_core::domain::SyncState {
        last_sync_time: "2026-01-01T00:00:00Z".into(),
        last_full_sync_time: Some("2026-01-01T00:00:00Z".into()),
        song_count: 1,
        ..Default::default()
    };
    service.update_sync_state(&state).unwrap();
    let server = MockServer::start();
    server.mock(|when, then| {
        when.method(GET)
            .path("/Items")
            .query_param_exists("minDateLastSaved");
        then.status(200)
            .json_body(json!({"Items":[],"TotalRecordCount":0}));
    });
    let malformed = server.mock(|when, then| {
        when.method(GET).path("/Items").query_param("Fields", "");
        then.status(200).json_body(json!({}));
    });
    assert!(
        sync_library_smart(
            server.base_url(),
            "token".into(),
            "user".into(),
            path.clone()
        )
        .await
        .is_err()
    );
    malformed.assert_calls(1);
    assert_eq!(load_cached_songs(path.clone()).unwrap(), [old_song()]);
    assert_eq!(
        serde_json::to_value(get_sync_state(path).unwrap()).unwrap(),
        serde_json::to_value(state).unwrap()
    );
}
