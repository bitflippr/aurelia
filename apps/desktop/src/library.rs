//! The library as the interface reads it: compact songs, albums, artists and
//! playlists, plus the server lookups behind detail pages.

use std::collections::HashMap;

use aurelia_core::models::{Album, Artist, Playlist, PlaylistCreateData, Song};
use serde::Serialize;

use crate::{session::Session, track::Track};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SongView {
    pub id: String,
    pub title: String,
    pub album: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album_id: Option<String>,
    pub artists: Vec<String>,
    pub artist_ids: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album_artist: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album_artist_id: Option<String>,
    pub duration: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub track: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub disc: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub year: Option<i32>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub genres: Vec<String>,
    pub plays: i32,
    pub fav: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub added: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub played: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub codec: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bit_rate: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sample_rate: Option<i32>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlbumView {
    pub id: String,
    pub title: String,
    pub artist: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub artist_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_tag: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub added: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistView {
    pub id: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_tag: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backdrop_tag: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistView {
    pub id: String,
    pub name: String,
    pub count: i32,
    pub duration: f64,
    pub can_delete: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_tag: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryView {
    pub songs: Vec<SongView>,
    pub albums: Vec<AlbumView>,
    pub artists: Vec<ArtistView>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistDetail {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub overview: Option<String>,
    pub related: Vec<String>,
}

const TICKS_PER_SECOND: f64 = 10_000_000.0;

fn primary_tag(tags: &Option<HashMap<String, String>>) -> Option<String> {
    tags.as_ref()?.get("Primary").cloned()
}

pub fn song_view(song: &Song) -> SongView {
    let album_artist = song
        .album_artists
        .as_ref()
        .and_then(|artists| artists.first());
    SongView {
        id: song.id.clone(),
        title: song.name.clone(),
        album: song.album.clone().unwrap_or_default(),
        album_id: song.album_id.clone(),
        artists: song.artists.clone().unwrap_or_default(),
        artist_ids: song.artist_ids.clone().unwrap_or_default(),
        album_artist: album_artist.map(|artist| artist.name.clone()),
        album_artist_id: album_artist.map(|artist| artist.id.clone()),
        duration: song.duration.unwrap_or_default().max(0.0),
        track: song.track_number,
        disc: song.disc_number,
        year: song.year,
        genres: song.genres.clone().unwrap_or_default(),
        plays: song.play_count.unwrap_or_default(),
        fav: song.is_favorite.unwrap_or_default(),
        added: song.date_created.clone(),
        played: song.date_played.clone(),
        container: song.container.clone(),
        codec: song.codec.clone(),
        bit_rate: song.bit_rate,
        sample_rate: song.sample_rate,
    }
}

pub fn track_from(song: &SongView) -> Track {
    Track {
        id: song.id.clone(),
        title: song.title.clone(),
        artist: if song.artists.is_empty() {
            song.album_artist.clone().unwrap_or_default()
        } else {
            song.artists.join(", ")
        },
        album: song.album.clone(),
        album_id: song.album_id.clone(),
        artwork_id: song.album_id.clone(),
        // The container chooses how the song streams. A codec the desktop
        // can't decode needs a transcode whatever file holds it, so it is
        // named by its codec; E-AC-3 also gets mixed down to stereo.
        container: match song.codec.as_deref() {
            codec if aurelia_core::is_eac3(codec) => Some("eac3".into()),
            Some(codec) if !aurelia_core::desktop_decodes(codec) => {
                Some(codec.to_ascii_lowercase())
            }
            _ => song.container.clone(),
        },
        duration_seconds: song.duration.round() as u32,
        art_color: 0,
    }
}

fn album_view(album: &Album) -> Option<AlbumView> {
    Some(AlbumView {
        id: album.id.clone()?,
        title: album.name.clone(),
        artist: album.artist.clone(),
        artist_id: album.artist_id.clone(),
        image_tag: primary_tag(&album.image_tags),
        added: album.date_created.clone(),
    })
}

fn artist_view(artist: &Artist) -> ArtistView {
    ArtistView {
        id: artist.id.clone(),
        name: artist.name.clone(),
        image_tag: primary_tag(&artist.image_tags),
        backdrop_tag: artist
            .image_tags
            .as_ref()
            .and_then(|tags| tags.get("Backdrop").cloned()),
    }
}

fn playlist_view(playlist: &Playlist) -> PlaylistView {
    PlaylistView {
        id: playlist.id.clone(),
        name: playlist.name.clone(),
        count: playlist.child_count.unwrap_or_default(),
        duration: playlist.run_time_ticks.unwrap_or_default() as f64 / TICKS_PER_SECOND,
        can_delete: playlist.can_delete.unwrap_or(true),
        image_tag: primary_tag(&playlist.image_tags),
        description: playlist
            .description
            .clone()
            .filter(|text| !text.trim().is_empty()),
    }
}

/// Refresh the profile's cache from the server.
pub async fn sync(session: &Session) -> Result<(), String> {
    std::fs::create_dir_all(&session.library_dir)
        .map_err(|error| format!("Could not create the library cache: {error}"))?;
    aurelia_core::sync_library_smart(
        session.server_url.clone(),
        session.token.clone(),
        session.user_id.clone(),
        session.library_dir.clone(),
    )
    .await
    .map_err(|error| error.to_string())?;
    aurelia_core::sync_favorites(
        session.server_url.clone(),
        session.token.clone(),
        session.user_id.clone(),
        session.library_dir.clone(),
    )
    .await
    .map_err(|error| error.to_string())?;
    Ok(())
}

/// Whether the profile has synced before, so the cache can show at once.
pub fn has_synced(session: &Session) -> bool {
    aurelia_core::get_sync_state(session.library_dir.clone())
        .map(|state| state.last_sync_time != "1970-01-01T00:00:00Z")
        .unwrap_or(false)
}

pub fn load(session: &Session) -> Result<LibraryView, String> {
    let dir = std::path::PathBuf::from(&session.library_dir);
    let database = aurelia_core::db::open(&dir).map_err(|error| error.to_string())?;
    let songs = aurelia_core::db::songs::get_all(&database).map_err(|error| error.to_string())?;
    let albums = aurelia_core::db::albums::get_all(&database).map_err(|error| error.to_string())?;
    let artists =
        aurelia_core::db::artists::get_all(&database).map_err(|error| error.to_string())?;
    Ok(LibraryView {
        songs: songs.iter().map(song_view).collect(),
        albums: albums.iter().filter_map(album_view).collect(),
        artists: artists.iter().map(artist_view).collect(),
    })
}

pub async fn playlists(session: &Session) -> Result<Vec<PlaylistView>, String> {
    aurelia_core::get_playlists(
        session.server_url.clone(),
        session.token.clone(),
        session.user_id.clone(),
    )
    .await
    .map(|playlists| playlists.iter().map(playlist_view).collect())
    .map_err(|error| error.to_string())
}

pub async fn playlist_songs(session: &Session, id: &str) -> Result<Vec<SongView>, String> {
    aurelia_core::get_playlist_items(session.server_url.clone(), session.token.clone(), id.into())
        .await
        .map(|songs| songs.iter().map(song_view).collect())
        .map_err(|error| error.to_string())
}

pub async fn create_playlist(
    session: &Session,
    name: String,
    ids: Vec<String>,
) -> Result<PlaylistView, String> {
    aurelia_core::create_playlist(
        session.server_url.clone(),
        session.token.clone(),
        PlaylistCreateData {
            name,
            ids: (!ids.is_empty()).then_some(ids),
            user_id: session.user_id.clone(),
            is_public: Some(false),
        },
    )
    .await
    .map(|playlist| playlist_view(&playlist))
    .map_err(|error| error.to_string())
}

pub async fn add_to_playlist(
    session: &Session,
    id: String,
    ids: Vec<String>,
) -> Result<(), String> {
    aurelia_core::add_playlist_items(session.server_url.clone(), session.token.clone(), id, ids)
        .await
        .map_err(|error| error.to_string())
}

pub async fn set_favorite(session: &Session, id: String, favorite: bool) -> Result<bool, String> {
    let result = aurelia_core::toggle_favorite(
        session.server_url.clone(),
        session.token.clone(),
        session.user_id.clone(),
        id.clone(),
        favorite,
    )
    .await
    .map_err(|error| error.to_string())?;
    let _ = aurelia_core::cache::update_song_favorite_status(
        std::path::PathBuf::from(&session.library_dir),
        &id,
        favorite,
    );
    Ok(result)
}

pub async fn artist(session: &Session, id: String) -> Result<ArtistDetail, String> {
    let cached = aurelia_core::get_cached_artist(session.library_dir.clone(), id.clone())
        .ok()
        .flatten()
        .filter(|artist| artist.overview.is_some());
    let artist = match cached {
        Some(artist) => Some(artist),
        None => aurelia_core::fetch_artist(
            session.server_url.clone(),
            session.token.clone(),
            session.user_id.clone(),
            id.clone(),
            session.library_dir.clone(),
        )
        .await
        .ok(),
    };
    let related = aurelia_core::get_related_artists(session.library_dir.clone(), id)
        .await
        .map(|artists| artists.into_iter().map(|artist| artist.id).collect())
        .unwrap_or_default();
    Ok(ArtistDetail {
        overview: artist
            .and_then(|artist| artist.overview)
            .filter(|text| !text.trim().is_empty()),
        related,
    })
}

pub async fn instant_mix(session: &Session, id: String) -> Result<Vec<SongView>, String> {
    aurelia_core::get_instant_mix(session.server_url.clone(), session.token.clone(), id)
        .await
        .map(|songs| songs.iter().map(song_view).collect())
        .map_err(|error| error.to_string())
}

pub async fn lyrics(
    session: &Session,
    id: String,
    artist: String,
    title: String,
) -> aurelia_core::models::ParsedLyrics {
    aurelia_core::get_parsed_lyrics(
        session.server_url.clone(),
        session.token.clone(),
        id,
        artist,
        title,
    )
    .await
}
