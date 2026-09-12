use std::time::Instant;

use aurelia_core::domain::services::library_search::{LibrarySearchIndex, LibrarySearchKind};
use aurelia_core::models::Song;

#[test]
fn a_large_library_keeps_late_matches_and_reuses_the_index_for_typing() {
    let songs: Vec<Song> = (0..20_000)
        .map(|i| {
            serde_json::from_value(serde_json::json!({
                "id": format!("song-{i}"),
                "name": if i == 19_999 { "Needle".into() } else { format!("Library Track {i}") },
                "itemType": "Audio",
                "artists": [format!("Artist {}", i % 100)],
                "artistIds": [format!("artist-{}", i % 100)],
                "album": format!("Album {}", i % 500),
                "albumId": format!("album-{}", i % 500)
            }))
            .unwrap()
        })
        .collect();
    let build_start = Instant::now();
    let index = LibrarySearchIndex::new(songs);
    let build_time = build_start.elapsed();
    let query_start = Instant::now();
    for query in ["nee", "need", "needel", "needle"] {
        let hits = index.search(query.into(), 20);
        assert_eq!(hits.first().unwrap().id, "song-19999");
    }
    let hits = index.search("artist 42".into(), 20);
    assert!(
        hits.iter()
            .any(|hit| hit.id == "artist-42" && hit.kind == LibrarySearchKind::Artist)
    );
    assert!(hits.iter().any(|hit| hit.kind == LibrarySearchKind::Album));
    eprintln!(
        "20,000 tracks: index {build_time:?}; five searches {:?}",
        query_start.elapsed()
    );
}
