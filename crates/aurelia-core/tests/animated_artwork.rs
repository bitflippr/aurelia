use aurelia_core::get_animated_artwork;
use httpmock::prelude::*;
use serde_json::{Value, json};

fn metadata(url: &str) -> Value {
    json!({
        "ApiVersion": 1, "ItemId": "track", "AlbumId": "album",
        "Square": {
            "Url": url, "ContentType": "video/mp4", "Sha256": "ab".repeat(32),
            "Bytes": 1024, "Width": 1080, "Height": 1080, "Duration": 12.5
        },
        "Tall": null
    })
}

#[tokio::test]
async fn resolves_base_path_and_authenticates_without_query_tokens() {
    let server = MockServer::start();
    let mock = server.mock(|when, then| {
        when.method(GET)
            .path("/jellyfin/AnimatedArtwork/Items/track")
            .header("Authorization", "MediaBrowser Token=\"secret\"");
        then.json_body(metadata("/jellyfin/AnimatedArtwork/Items/album/square"));
    });
    let artwork = get_animated_artwork(
        format!("{}/jellyfin", server.base_url()),
        "secret".into(),
        "track".into(),
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(artwork.album_id, "album");
    assert_eq!(
        artwork.square.unwrap().url,
        format!(
            "{}/jellyfin/AnimatedArtwork/Items/album/square",
            server.base_url()
        )
    );
    mock.assert();
}

#[tokio::test]
async fn missing_plugin_or_artwork_is_not_an_error() {
    let server = MockServer::start();
    server.mock(|when, then| {
        when.method(GET);
        then.status(404);
    });
    assert!(
        get_animated_artwork(server.base_url(), "secret".into(), "album".into())
            .await
            .unwrap()
            .is_none()
    );
}

#[tokio::test]
async fn authentication_errors_are_not_reported_as_missing_artwork() {
    let server = MockServer::start();
    server.mock(|when, then| {
        when.method(GET);
        then.status(401);
    });
    assert!(
        get_animated_artwork(server.base_url(), "secret".into(), "album".into())
            .await
            .is_err()
    );
}

#[tokio::test]
async fn rejects_foreign_media_urls_and_invalid_metadata() {
    for (field, value) in [
        ("Url", json!("https://other.example/cover.mp4")),
        ("Url", json!("//other.example/cover.mp4")),
        ("Url", json!("file:///cover.mp4")),
        ("ContentType", json!("text/html")),
        ("Bytes", json!(64 * 1024 * 1024 + 1)),
        ("Duration", json!(91)),
        ("Width", json!(0)),
        ("Sha256", json!("not-a-checksum")),
    ] {
        let server = MockServer::start();
        let mut body = metadata("/AnimatedArtwork/Items/album/square");
        body["Square"][field] = value;
        server.mock(|when, then| {
            when.method(GET);
            then.json_body(body);
        });
        assert!(
            get_animated_artwork(server.base_url(), "secret".into(), "album".into())
                .await
                .unwrap()
                .is_none(),
            "{field}"
        );
    }
}

#[tokio::test]
async fn unsupported_api_version_falls_back() {
    let server = MockServer::start();
    let mut body = metadata("/AnimatedArtwork/Items/album/square");
    body["ApiVersion"] = json!(2);
    server.mock(|when, then| {
        when.method(GET);
        then.json_body(body);
    });
    assert!(
        get_animated_artwork(server.base_url(), "secret".into(), "album".into())
            .await
            .unwrap()
            .is_none()
    );
}
