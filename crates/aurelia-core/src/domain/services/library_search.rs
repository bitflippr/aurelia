//! Immutable, reusable library search index. Build once per library snapshot, not per keystroke.

use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use unicode_normalization::{UnicodeNormalization, char::is_combining_mark};

use crate::models::Song;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, uniffi::Enum)]
pub enum LibrarySearchKind {
    Artist,
    Album,
    Song,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LibrarySearchHit {
    pub kind: LibrarySearchKind,
    pub id: String,
    pub name: String,
    pub artist_names: Vec<String>,
    pub artwork_url: Option<String>,
    pub song_count: u32,
}

#[derive(Debug)]
struct SearchText {
    text: String,
    compact: String,
    words: Vec<String>,
}

impl SearchText {
    fn new(text: &str) -> Self {
        let normalized: String = text
            .nfkd()
            .filter(|c| !is_combining_mark(*c))
            .flat_map(char::to_lowercase)
            .map(|c| if c.is_alphanumeric() { c } else { ' ' })
            .collect();
        let words: Vec<String> = normalized.split_whitespace().map(str::to_owned).collect();
        Self {
            text: words.join(" "),
            compact: words.join(""),
            words,
        }
    }
}

#[derive(Debug)]
struct Entry {
    hit: LibrarySearchHit,
    name: SearchText,
    context: SearchText,
}

impl Entry {
    fn new(hit: LibrarySearchHit, context: String) -> Self {
        Self {
            name: SearchText::new(&hit.name),
            context: SearchText::new(&context),
            hit,
        }
    }
}

#[derive(Debug, uniffi::Object)]
pub struct LibrarySearchIndex {
    entries: Vec<Entry>,
}

#[uniffi::export]
impl LibrarySearchIndex {
    #[uniffi::constructor]
    pub fn new(songs: Vec<Song>) -> Arc<Self> {
        let mut entries = Vec::new();
        let mut albums: BTreeMap<String, LibrarySearchHit> = BTreeMap::new();
        let mut artists: BTreeMap<String, LibrarySearchHit> = BTreeMap::new();
        let mut seen_songs = HashSet::new();
        for song in songs {
            if song.id.trim().is_empty() || !seen_songs.insert(song.id.clone()) {
                continue;
            }
            let song_artists = song.artists.clone().unwrap_or_default();
            entries.push(Entry::new(
                LibrarySearchHit {
                    kind: LibrarySearchKind::Song,
                    id: song.id,
                    name: song.name,
                    artist_names: song_artists.clone(),
                    artwork_url: song.album_art_url.clone(),
                    song_count: 1,
                },
                format!(
                    "{} {}",
                    song_artists.join(" "),
                    song.album.as_deref().unwrap_or_default()
                ),
            ));
            if let Some(id) = song.album_id.filter(|id| !id.trim().is_empty()) {
                let album = albums
                    .entry(id.clone())
                    .or_insert_with(|| LibrarySearchHit {
                        kind: LibrarySearchKind::Album,
                        id,
                        name: song
                            .album
                            .clone()
                            .filter(|name| !name.trim().is_empty())
                            .unwrap_or_else(|| "Unknown Album".into()),
                        artist_names: Vec::new(),
                        artwork_url: song.album_art_url.clone(),
                        song_count: 0,
                    });
                album.song_count += 1;
                if album.artwork_url.is_none() {
                    album.artwork_url = song.album_art_url.clone();
                }
                let credits: Vec<String> = song
                    .album_artists
                    .as_ref()
                    .filter(|credits| !credits.is_empty())
                    .map(|credits| credits.iter().map(|credit| credit.name.clone()).collect())
                    .unwrap_or_else(|| song_artists.clone());
                for name in credits {
                    if !name.trim().is_empty() && !album.artist_names.contains(&name) {
                        album.artist_names.push(name);
                    }
                }
            }
            let mut song_artist_ids = HashSet::new();
            for (id, name) in song
                .artist_ids
                .unwrap_or_default()
                .into_iter()
                .zip(song_artists)
            {
                if id.trim().is_empty()
                    || name.trim().is_empty()
                    || !song_artist_ids.insert(id.clone())
                {
                    continue;
                }
                let artist = artists
                    .entry(id.clone())
                    .or_insert_with(|| LibrarySearchHit {
                        kind: LibrarySearchKind::Artist,
                        id,
                        name,
                        artist_names: Vec::new(),
                        artwork_url: None,
                        song_count: 0,
                    });
                artist.song_count += 1;
            }
        }
        entries.extend(albums.into_values().map(|hit| {
            let context = hit.artist_names.join(" ");
            Entry::new(hit, context)
        }));
        entries.extend(
            artists
                .into_values()
                .map(|hit| Entry::new(hit, String::new())),
        );
        Arc::new(Self { entries })
    }

    /// Rank all kinds together; apply independent per-kind limits so tracks cannot hide artists/albums.
    pub fn search(&self, query: String, limit_per_kind: u32) -> Vec<LibrarySearchHit> {
        let query = SearchText::new(&query);
        if query.compact.chars().count() < 2 || query.words.len() > 12 || limit_per_kind == 0 {
            return Vec::new();
        }
        let mut matches: Vec<_> = self
            .entries
            .iter()
            .filter_map(|entry| score(&query, entry).map(|score| (score, entry)))
            .collect();
        matches.sort_by(|(a_score, a), (b_score, b)| {
            b_score
                .cmp(a_score)
                .then_with(|| a.hit.kind.cmp(&b.hit.kind))
                .then_with(|| a.name.text.cmp(&b.name.text))
                .then_with(|| a.hit.id.cmp(&b.hit.id))
        });
        let mut counts = [0; 3];
        matches
            .into_iter()
            .filter_map(|(_, entry)| {
                let count = &mut counts[entry.hit.kind as usize];
                if *count >= limit_per_kind.min(100) {
                    return None;
                }
                *count += 1;
                Some(entry.hit.clone())
            })
            .collect()
    }
}

fn score(query: &SearchText, entry: &Entry) -> Option<u32> {
    if entry.name.compact == query.compact {
        return Some(1000);
    }
    if entry.name.text.starts_with(&query.text) {
        return Some(900);
    }
    if entry.name.text.contains(&query.text) {
        return Some(850);
    }
    if entry.name.compact.contains(&query.compact) {
        return Some(820);
    }
    let mut total = 0;
    let mut all_in_name = true;
    for word in &query.words {
        let name_score = word_score(word, &entry.name);
        let context_score = word_score(word, &entry.context).map(|score| score / 2);
        let best = name_score.into_iter().chain(context_score).max()?;
        all_in_name &= name_score.is_some();
        total += best;
    }
    Some(if all_in_name { 650 } else { 400 } + total / query.words.len() as u32)
}

fn word_score(query: &str, text: &SearchText) -> Option<u32> {
    let query_len = query.chars().count();
    let mut best: Option<u32> = None;
    for word in &text.words {
        let score = if word == query {
            100
        } else if word.starts_with(query) {
            90
        } else if query_len >= 3 && word.contains(query) {
            80
        } else {
            // One typo for ordinary words, two for longer words; short queries remain precise.
            let tolerance = match query_len {
                4..=7 => 1,
                8..=64 => 2,
                _ => continue,
            };
            if query_len.abs_diff(word.chars().count()) > tolerance {
                continue;
            }
            let distance = strsim::osa_distance(query, word);
            if distance > tolerance {
                continue;
            }
            70 - distance as u32 * 10
        };
        best = Some(best.unwrap_or_default().max(score));
        if score == 100 {
            break;
        }
    }
    // Also tolerate omitted punctuation/spacing within a name (e.g. blink182).
    if query_len >= 4 && text.compact.contains(query) {
        best = Some(best.unwrap_or_default().max(75));
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;

    fn song(
        id: &str,
        name: &str,
        artist_id: &str,
        artist: &str,
        album_id: &str,
        album: &str,
    ) -> Song {
        serde_json::from_value(serde_json::json!({
            "id": id, "name": name, "itemType": "Audio", "artists": [artist], "artistIds": [artist_id],
            "albumId": album_id, "album": album, "albumArtUrl": format!("https://art/{album_id}")
        })).unwrap()
    }

    fn library() -> Arc<LibrarySearchIndex> {
        LibrarySearchIndex::new(vec![
            song(
                "song-1",
                "thingsudo2me",
                "ericdoa-id",
                "ericdoa",
                "album-id",
                "Then I'll Be Happy",
            ),
            song(
                "song-2",
                "Cloak n Dagger",
                "glaive-id",
                "glaive",
                "album-id",
                "Then I'll Be Happy",
            ),
            song(
                "song-3",
                "Jóga",
                "bjork-id",
                "Björk",
                "homogenic-id",
                "Homogenic",
            ),
            song(
                "song-4",
                "What's My Age Again?",
                "blink-id",
                "blink-182",
                "enema-id",
                "Enema of the State",
            ),
        ])
    }

    #[test]
    fn typos_and_transpositions_find_the_artist_with_the_correct_id() {
        for query in ["glaiv", "glavie", "glaibe"] {
            let hits = library().search(query.into(), 20);
            assert!(
                hits.iter()
                    .any(|hit| hit.kind == LibrarySearchKind::Artist && hit.id == "glaive-id"),
                "{query}: {hits:?}"
            );
        }
    }

    #[test]
    fn accents_punctuation_and_whitespace_do_not_block_a_match() {
        for (query, id) in [
            (" BJORK ", "bjork-id"),
            ("joga", "song-3"),
            ("blink182", "blink-id"),
            ("whats my age", "song-4"),
        ] {
            assert!(
                library()
                    .search(query.into(), 20)
                    .iter()
                    .any(|hit| hit.id == id),
                "{query}"
            );
        }
    }

    #[test]
    fn words_can_be_in_any_order_and_span_title_and_artist() {
        assert!(
            library()
                .search("dagger glaive cloak".into(), 20)
                .iter()
                .any(|hit| hit.id == "song-2")
        );
        assert!(
            library()
                .search("happy then".into(), 20)
                .iter()
                .any(|hit| hit.id == "album-id")
        );
        assert!(
            library()
                .search("glaive nonexistentword".into(), 20)
                .is_empty()
        );
    }

    #[test]
    fn exact_titles_rank_above_artist_context_and_fuzzy_matches() {
        let index = LibrarySearchIndex::new(vec![
            song(
                "context",
                "A track",
                "glaive-id",
                "glaive",
                "album-a",
                "An album",
            ),
            song(
                "fuzzy",
                "glaives",
                "other",
                "Other",
                "album-b",
                "Another album",
            ),
            song(
                "exact",
                "glaive",
                "other",
                "Other",
                "album-b",
                "Another album",
            ),
        ]);
        let hits = index.search("glaive".into(), 20);
        let position = |id| hits.iter().position(|hit| hit.id == id).unwrap();
        assert!(position("exact") < position("context"));
        assert!(position("exact") < position("fuzzy"));
    }

    #[test]
    fn albums_and_collaborators_keep_their_ids_and_credits() {
        let mut track = song(
            "song",
            "Collaboration",
            "glaive-id",
            "glaive",
            "album",
            "Happy",
        );
        track.artist_ids = Some(vec!["glaive-id".into(), "ericdoa-id".into()]);
        track.artists = Some(vec!["glaive".into(), "ericdoa".into()]);
        let index = LibrarySearchIndex::new(vec![track.clone(), track]);
        let hits = index.search("ericdoa".into(), 20);
        let artist = hits
            .iter()
            .find(|hit| hit.kind == LibrarySearchKind::Artist)
            .unwrap();
        assert_eq!(
            (&artist.id, &artist.name, artist.song_count),
            (&"ericdoa-id".into(), &"ericdoa".into(), 1)
        );
        let album = hits
            .iter()
            .find(|hit| hit.kind == LibrarySearchKind::Album)
            .unwrap();
        assert_eq!(album.artist_names, vec!["glaive", "ericdoa"]);
        assert_eq!(album.song_count, 1);
    }

    #[test]
    fn missing_ids_do_not_create_dead_album_or_artist_links() {
        let index =
            LibrarySearchIndex::new(vec![song("song", "Example", "", "Example", "", "Example")]);
        let hits = index.search("example".into(), 20);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].kind, LibrarySearchKind::Song);
    }

    #[test]
    fn each_category_has_its_own_limit_and_results_are_deterministic() {
        let index = LibrarySearchIndex::new(
            (0..80)
                .map(|i| {
                    song(
                        &format!("song-{i}"),
                        "Example",
                        "artist",
                        "Example",
                        "album",
                        "Example",
                    )
                })
                .collect(),
        );
        let hits = index.search("example".into(), 2);
        assert_eq!(
            hits.iter()
                .filter(|hit| hit.kind == LibrarySearchKind::Song)
                .count(),
            2
        );
        assert_eq!(hits.len(), 4);
        assert_eq!(hits, index.search("EXAMPLE".into(), 2));
    }

    #[test]
    fn blank_short_and_unrelated_queries_do_not_return_noise() {
        for query in ["", " ", "g", "---", "zzzzzzzz"] {
            assert!(library().search(query.into(), 20).is_empty());
        }
    }
}
