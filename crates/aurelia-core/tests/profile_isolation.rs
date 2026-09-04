use aurelia_core::{cache_songs, fetch_artist, get_cached_artist, load_cached_songs};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::mpsc;
use std::time::Duration;
use tempfile::TempDir;

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn artist_response_stays_in_its_profile_when_another_profile_is_read() {
    let profile_a = TempDir::new().unwrap();
    let profile_b = TempDir::new().unwrap();
    let path_a = profile_a.path().to_string_lossy().into_owned();
    let path_b = profile_b.path().to_string_lossy().into_owned();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let server_url = format!("http://{}", listener.local_addr().unwrap());
    let (requested_tx, requested_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();

    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(10)))
            .unwrap();
        let mut request = Vec::new();
        let mut buffer = [0; 1024];
        while !request.ends_with(b"\r\n\r\n") {
            let read = socket.read(&mut buffer).unwrap();
            assert_ne!(read, 0, "request ended before its headers");
            request.extend_from_slice(&buffer[..read]);
        }
        requested_tx.send(()).unwrap();
        release_rx.recv_timeout(Duration::from_secs(10)).unwrap();
        let body = r#"{"Id":"artist-a","Name":"Profile A Artist"}"#;
        write!(
            socket,
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            body.len(),
            body
        )
        .unwrap();
    });

    let request = tokio::spawn(fetch_artist(
        server_url,
        "test-token".into(),
        "user-a".into(),
        "artist-a".into(),
        path_a.clone(),
    ));
    requested_rx.recv_timeout(Duration::from_secs(10)).unwrap();
    load_cached_songs(path_b.clone()).unwrap();
    release_tx.send(()).unwrap();
    let artist = request.await.unwrap().unwrap();
    server.join().unwrap();

    assert_eq!(artist.id, "artist-a");
    assert!(
        get_cached_artist(path_a, "artist-a".into())
            .unwrap()
            .is_some()
    );
    assert!(
        get_cached_artist(path_b, "artist-a".into())
            .unwrap()
            .is_none()
    );
}

#[test]
fn concurrent_library_reads_and_writes_stay_in_their_profiles() {
    let profiles: Vec<_> = (0..4).map(|_| TempDir::new().unwrap()).collect();
    let barrier = std::sync::Barrier::new(profiles.len());
    std::thread::scope(|scope| {
        for (index, profile) in profiles.iter().enumerate() {
            let path = profile.path().to_string_lossy().into_owned();
            let barrier = &barrier;
            scope.spawn(move || {
                let song: aurelia_core::models::Song = serde_json::from_value(serde_json::json!({
                    "id": format!("song-{index}"),
                    "name": "Profile-specific song",
                    "itemType": "Audio"
                }))
                .unwrap();
                barrier.wait();
                for _ in 0..10 {
                    cache_songs(path.clone(), vec![song.clone()]).unwrap();
                    assert_eq!(load_cached_songs(path.clone()).unwrap(), vec![song.clone()]);
                }
            });
        }
    });
}
