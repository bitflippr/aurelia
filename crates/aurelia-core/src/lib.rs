pub mod cache;
pub mod db;
pub mod domain;
pub mod error;
pub mod models;
pub mod services;
pub mod utils;

#[cfg(feature = "desktop")]
pub mod audio;
#[cfg(feature = "desktop")]
pub mod media_controls;

#[uniffi::export]
pub fn ping() -> String {
    "pong".to_string()
}

/// Discover optional animated covers without changing the library's static image URLs.
#[uniffi::export(async_runtime = "tokio")]
pub async fn get_animated_artwork(
    server_url: String,
    token: String,
    item_id: String,
) -> Result<Option<models::AnimatedArtwork>, error::AppError> {
    services::JellyfinClient::with_auth(server_url, token)
        .get_animated_artwork(&item_id)
        .await
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn authenticate(
    request: models::AuthRequest,
) -> Result<models::LoginResponse, error::AppError> {
    match request.provider {
        models::BackendProvider::Jellyfin => {
            let client = services::JellyfinClient::new(request.server_url);
            client
                .authenticate(&request.username, &request.password, &request.device_id)
                .await
        }
    }
}

/// Fetch the complete song list and replace its cache without advancing library sync state.
#[uniffi::export(async_runtime = "tokio")]
pub async fn fetch_songs(
    server_url: String,
    token: String,
    user_id: String,
    app_data_dir: String,
) -> Result<Vec<models::Song>, error::AppError> {
    let songs = {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_music_library(&user_id).await?
    };
    if !app_data_dir.is_empty() {
        let app_dir = std::path::PathBuf::from(app_data_dir);
        if let Err(err) = cache::replace_songs(app_dir, &songs) {
            tracing::warn!("Failed to cache songs: {err}");
        }
    }
    Ok(songs)
}

#[uniffi::export]
pub fn load_cached_songs(app_data_dir: String) -> Result<Vec<models::Song>, error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(vec![]);
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::get_songs(app_dir).map_err(|err| error::AppError::Database(err.to_string()))
}

/// Derive mobile home sections from an in-memory song list.
#[uniffi::export]
pub fn derive_mobile_home_data(
    songs: Vec<models::Song>,
    most_played_limit: i64,
    recently_played_limit: i64,
    album_section_limit: i64,
    featured_albums_limit: i64,
) -> models::MobileHomeData {
    let limits = domain::services::MobileHomeViewLimits {
        most_played: most_played_limit.max(0) as u32,
        recently_played: recently_played_limit.max(0) as u32,
        album_section: album_section_limit.max(0) as u32,
        featured_albums: featured_albums_limit.max(0) as u32,
    };
    let mut rng = rand::rng();
    domain::services::derive_mobile_home_data(&songs, limits, &mut rng)
}

/// Replace the complete cached song list, preserving artist/album metadata and sync state.
#[uniffi::export]
pub fn cache_songs(app_data_dir: String, songs: Vec<models::Song>) -> Result<(), error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::replace_songs(app_dir, &songs).map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export]
pub fn get_library_sync_state(app_data_dir: String) -> Result<String, error::AppError> {
    if app_data_dir.is_empty() {
        return Ok("".to_string());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::get_sync_state(app_dir).map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export]
pub fn set_library_sync_state(
    app_data_dir: String,
    state_json: String,
) -> Result<(), error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::set_sync_state(app_dir, &state_json)
        .map_err(|err| error::AppError::Database(err.to_string()))
}

/// Get sync state as a typed struct (better for UI binding)
#[uniffi::export]
pub fn get_sync_state(app_data_dir: String) -> Result<domain::SyncState, error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(domain::SyncState::default());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    let json =
        cache::get_sync_state(app_dir).map_err(|err| error::AppError::Database(err.to_string()))?;

    if json.is_empty() {
        return Ok(domain::SyncState::default());
    }

    serde_json::from_str(&json).map_err(|err| error::AppError::Serialization(err.to_string()))
}

/// Build a stream URL optimized for mobile playback.
/// Uses progressive transcoding for non-seekable containers.
#[uniffi::export]
pub fn build_mobile_stream_url(
    server_url: String,
    token: String,
    item_id: String,
    container: Option<String>,
) -> String {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_mobile_audio_stream_url(&item_id, container.as_deref())
    }
}

/// Build an Android stream URL with a lossless stereo downmix for EAC3 only.
/// All other codecs retain the existing mobile stream selection.
#[uniffi::export]
pub fn build_android_stream_url(
    server_url: String,
    token: String,
    item_id: String,
    container: Option<String>,
    codec: Option<String>,
) -> String {
    if !is_eac3(codec.as_deref().or(container.as_deref())) {
        return build_mobile_stream_url(server_url, token, item_id, container);
    }
    stereo_transcode_url(server_url, token, item_id)
}

/// Whether a codec or container name is E-AC-3 (Dolby Digital Plus, often
/// Atmos), which arrives as 5.1 or more channels.
pub fn is_eac3(value: Option<&str>) -> bool {
    value.is_some_and(|value| {
        matches!(
            value.to_ascii_lowercase().as_str(),
            "eac3" | "e-ac-3" | "ec-3" | "ec3"
        )
    })
}

/// Mix surround channels down before transport; FLAC avoids another lossy
/// encode.
fn stereo_transcode_url(server_url: String, token: String, item_id: String) -> String {
    let url = build_mobile_stream_url(server_url, token, item_id, None);
    url.replace("transcodingContainer=aac", "transcodingContainer=flac")
        .replace("audioCodec=aac", "audioCodec=flac")
        + "&maxAudioChannels=2&transcodingAudioChannels=2"
}

/// Build a progressive stream URL for the desktop Rodio engine.
#[cfg(feature = "desktop")]
pub fn build_desktop_stream_url(
    server_url: String,
    token: String,
    item_id: String,
    container: Option<String>,
) -> String {
    // Rodio has no E-AC-3 decoder, and it would play only the front pair
    // of a surround stream.
    if is_eac3(container.as_deref()) {
        return stereo_transcode_url(server_url, token, item_id);
    }
    let client = services::JellyfinClient::with_auth(server_url, token);
    client.get_desktop_audio_stream_url(&item_id, container.as_deref())
}

#[uniffi::export]
pub fn build_image_url(
    server_url: String,
    token: String,
    item_id: String,
    image_type: String,
    width: Option<u32>,
    quality: Option<u32>,
) -> Result<Option<String>, error::AppError> {
    let result = {
        let mut url = format!(
            "{}/Items/{}/Images/{}",
            server_url.trim_end_matches('/'),
            item_id,
            image_type
        );
        let mut query = Vec::new();
        if let Some(w) = width {
            query.push(format!("width={w}"));
        }
        if let Some(q) = quality {
            query.push(format!("quality={q}"));
        }
        query.push(format!("api_key={token}"));
        if !query.is_empty() {
            url.push('?');
            url.push_str(&query.join("&"));
        }
        Some(url)
    };
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::build_mobile_stream_url;

    #[test]
    fn build_mobile_stream_url_uses_universal_for_non_seekable() {
        let url = build_mobile_stream_url(
            "http://localhost:8096".to_string(),
            "token".to_string(),
            "song123".to_string(),
            Some("alac".to_string()),
        );
        assert!(url.contains("/Audio/song123/universal"));
        assert!(url.contains("transcodingProtocol=http"));
    }
}

#[uniffi::export]
pub fn save_credentials(
    app_data_dir: String,
    credentials: models::Credentials,
) -> Result<(), error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::save_credentials(app_dir, &credentials)
        .map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export]
pub fn load_credentials(
    app_data_dir: String,
) -> Result<Option<models::Credentials>, error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(None);
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::load_credentials(app_dir).map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export]
pub fn clear_credentials(app_data_dir: String) -> Result<(), error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::clear_credentials(app_dir).map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export]
pub fn save_setting(
    app_data_dir: String,
    key: String,
    value: String,
) -> Result<(), error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::save_setting(app_dir, &key, &value)
        .map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export]
pub fn load_setting(app_data_dir: String, key: String) -> Result<Option<String>, error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(None);
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::load_setting(app_dir, &key).map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export]
pub fn delete_setting(app_data_dir: String, key: String) -> Result<(), error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::delete_setting(app_dir, &key).map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export]
pub fn clear_cache(app_data_dir: String) -> Result<(), error::AppError> {
    if app_data_dir.is_empty() {
        return Ok(());
    }
    let app_dir = std::path::PathBuf::from(app_data_dir);
    cache::clear_cache(app_dir).map_err(|err| error::AppError::Database(err.to_string()))
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_lyrics(
    server_url: String,
    token: String,
    item_id: String,
    artist: String,
    title: String,
) -> String {
    // 1. Try server lyrics for providers that support it
    if !server_url.is_empty() && !token.is_empty() && !item_id.is_empty() {
        let client = services::JellyfinClient::with_auth(server_url, token);
        if let Ok(Some(jf_lyrics)) = client.get_lyrics(&item_id).await
            && let Ok(lrc) = utils::lyrics::jellyfin_to_lrc(&jf_lyrics)
            && !lrc.trim().is_empty()
        {
            return lrc;
        }
    }

    // 2. Fall back to LrcLib
    let client = services::LrcLibClient::new();
    match client.search_lyrics(&artist, &title).await {
        Ok(results) => services::LrcLibClient::get_best_lyrics(&results).unwrap_or_default(),
        Err(_) => String::new(),
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_parsed_lyrics(
    server_url: String,
    token: String,
    item_id: String,
    artist: String,
    title: String,
) -> models::ParsedLyrics {
    // 1. Prefer Jellyfin's native lyrics API. Our TTML Jellyfin fork exposes
    // word timing, sections, agents, translations, language, and songwriters here.
    if !server_url.is_empty() && !token.is_empty() && !item_id.is_empty() {
        tracing::info!(
            "[Lyrics] Trying Jellyfin: itemId={}, serverUrl={}...",
            item_id,
            server_url.chars().take(30).collect::<String>()
        );
        let client = services::JellyfinClient::with_auth(server_url.clone(), token.clone());
        match client.get_lyrics(&item_id).await {
            Ok(Some(jf_lyrics)) => {
                let line_count = jf_lyrics.lyrics.len();
                let lines_with_cues = jf_lyrics
                    .lyrics
                    .iter()
                    .filter(|l| l.cues.as_ref().is_some_and(|c| !c.is_empty()))
                    .count();
                let section_count = jf_lyrics.sections.as_ref().map_or(0, Vec::len);
                let agent_count = jf_lyrics.agents.as_ref().map_or(0, Vec::len);
                tracing::info!(
                    "[Lyrics] Jellyfin returned {} lines, {} with cues, {} sections, {} agents",
                    line_count,
                    lines_with_cues,
                    section_count,
                    agent_count,
                );

                let parsed = utils::lyrics::jellyfin_to_parsed_lyrics(&jf_lyrics);
                tracing::info!(
                    "[Lyrics] Converted: syncedLines={}, hasWords={}, plainLines={}, hasSections={}",
                    parsed.synced.len(),
                    parsed.synced.first().is_some_and(|l| l.words.is_some()),
                    parsed.plain.len(),
                    parsed.sections.is_some(),
                );
                if parsed.is_valid() {
                    return parsed;
                }
                tracing::warn!("[Lyrics] Jellyfin lyrics parsed but not valid");
            }
            Ok(None) => {
                tracing::info!(
                    "[Lyrics] Jellyfin returned no lyrics for itemId={}",
                    item_id
                );
            }
            Err(e) => {
                tracing::warn!("[Lyrics] Jellyfin lyrics fetch error: {}", e);
            }
        }
    } else {
        tracing::info!(
            "[Lyrics] Skipping Jellyfin (serverUrl empty={}, token empty={}, itemId empty={})",
            server_url.is_empty(),
            token.is_empty(),
            item_id.is_empty(),
        );
    }

    // 2. Fall back to LrcLib
    tracing::info!(
        "[Lyrics] Falling back to LrcLib for '{}' by '{}'",
        title,
        artist
    );
    let lrclib_client = services::LrcLibClient::new();
    let raw = match lrclib_client.search_lyrics(&artist, &title).await {
        Ok(results) => {
            let best = services::LrcLibClient::get_best_lyrics(&results).unwrap_or_default();
            tracing::info!("[Lyrics] LrcLib returned {} bytes", best.len());
            best
        }
        Err(e) => {
            tracing::warn!("[Lyrics] LrcLib search error: {}", e);
            String::new()
        }
    };
    utils::lyrics::parse_lyrics(&raw)
}

pub async fn register_client_capabilities(
    server_url: String,
    token: String,
    device_id: String,
) -> Result<(), error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        let capabilities = models::ClientCapabilities {
            playable_media_types: vec!["Audio".to_string()],
            supported_commands: vec![
                "PlayNow".to_string(),
                "PlayNext".to_string(),
                "SetVolume".to_string(),
                "ToggleMute".to_string(),
            ],
            supports_media_control: true,
            supports_persistent_identifier: true,
            device_profile: models::DeviceProfile {
                name: Some("Aurelia Audio Profile".to_string()),
                id: Some(device_id),
                max_streaming_bitrate: Some(140000000),
                max_static_bitrate: Some(140000000),
                music_streaming_transcoding_bitrate: Some(384000),
                max_static_music_bitrate: Some(4000000),
                direct_play_profiles: vec![
                    models::DirectPlayProfile {
                        container: "mp3".to_string(),
                        audio_codec: Some("mp3".to_string()),
                        video_codec: None,
                        profile_type: "Audio".to_string(),
                    },
                    models::DirectPlayProfile {
                        container: "flac".to_string(),
                        audio_codec: Some("flac".to_string()),
                        video_codec: None,
                        profile_type: "Audio".to_string(),
                    },
                    models::DirectPlayProfile {
                        container: "ogg".to_string(),
                        audio_codec: Some("vorbis".to_string()),
                        video_codec: None,
                        profile_type: "Audio".to_string(),
                    },
                ],
                transcoding_profiles: vec![models::TranscodingProfile {
                    container: "mp3".to_string(),
                    profile_type: "Audio".to_string(),
                    video_codec: None,
                    audio_codec: Some("mp3".to_string()),
                    protocol: "http".to_string(),
                    estimate_content_length: None,
                    enable_mpegts_m2_ts_mode: None,
                    transcode_seek_info: None,
                    copy_timestamps: None,
                    context: Some("Streaming".to_string()),
                    enable_subtitles_in_manifest: None,
                    max_audio_channels: None,
                    min_segments: None,
                    segment_length: None,
                    break_on_non_key_frames: None,
                    conditions: vec![],
                    enable_audio_vbr_encoding: None,
                }],
                container_profiles: vec![],
                codec_profiles: vec![],
                subtitle_profiles: vec![models::SubtitleProfile {
                    format: "srt".to_string(),
                    method: "External".to_string(),
                    didl_mode: None,
                    language: None,
                    container: None,
                }],
            },
            app_store_url: None,
            icon_url: None,
        };
        client.register_capabilities(&capabilities).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn report_playback_start_event(
    server_url: String,
    token: String,
    item_id: String,
    position_ticks: Option<i64>,
) -> Result<(), error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.report_playback_start(&item_id, position_ticks).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn report_playback_progress_event(
    server_url: String,
    token: String,
    item_id: String,
    position_ticks: i64,
    is_paused: bool,
) -> Result<(), error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client
            .report_playback_progress(&item_id, Some(position_ticks), None, Some(is_paused))
            .await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn report_playback_stop_event(
    server_url: String,
    token: String,
    item_id: String,
    position_ticks: i64,
) -> Result<(), error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client
            .report_playback_stop(&item_id, Some(position_ticks))
            .await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn toggle_favorite(
    server_url: String,
    token: String,
    user_id: String,
    item_id: String,
    is_favorite: bool,
) -> Result<bool, error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client
            .toggle_favorite(&user_id, &item_id, is_favorite)
            .await?;
    }
    Ok(is_favorite)
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_favorite_ids(
    server_url: String,
    token: String,
    user_id: String,
) -> Result<Vec<String>, error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_favorite_ids(&user_id).await
    }
}

// Playlist operations

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_playlists(
    server_url: String,
    token: String,
    user_id: String,
) -> Result<Vec<models::Playlist>, error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_playlists(&user_id).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn create_playlist(
    server_url: String,
    token: String,
    data: models::PlaylistCreateData,
) -> Result<models::Playlist, error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.create_playlist(&data).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn update_playlist(
    server_url: String,
    token: String,
    playlist_id: String,
    updates: models::PlaylistUpdateData,
) -> Result<models::Playlist, error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.update_playlist(&playlist_id, &updates).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn delete_playlist(
    server_url: String,
    token: String,
    playlist_id: String,
) -> Result<(), error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.delete_playlist(&playlist_id).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn add_playlist_items(
    server_url: String,
    token: String,
    playlist_id: String,
    item_ids: Vec<String>,
) -> Result<(), error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.add_playlist_items(&playlist_id, &item_ids).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn remove_playlist_items(
    server_url: String,
    token: String,
    playlist_id: String,
    item_ids: Vec<String>,
) -> Result<(), error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.remove_playlist_items(&playlist_id, &item_ids).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_playlist_items(
    server_url: String,
    token: String,
    playlist_id: String,
) -> Result<Vec<models::Song>, error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_playlist_items(&playlist_id).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn mark_item_played(
    server_url: String,
    token: String,
    user_id: String,
    item_id: String,
) -> Result<(), error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.mark_item_played(&user_id, &item_id).await
    }
}

// Lazy-load functions for hybrid sync

/// Sync only songs (fast startup). Artists/albums are fetched on-demand.
/// Preserves the library sync checkpoint; returns whether the song cache was empty.
#[uniffi::export(async_runtime = "tokio")]
pub async fn sync_songs_only(
    server_url: String,
    token: String,
    user_id: String,
    app_data_dir: String,
) -> Result<bool, error::AppError> {
    // Retain this profile's database for the whole operation
    let app_data_path = std::path::PathBuf::from(&app_data_dir);
    let database =
        db::open(&app_data_path).map_err(|e| error::AppError::Database(e.to_string()))?;

    // Fetch songs only
    let songs = {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_music_library(&user_id).await?
    };

    domain::services::LibraryService::new(database)
        .replace_songs(&songs)
        .map_err(|e| error::AppError::Database(e.to_string()))
}

/// Returns progress for the specified profile and operation.
#[uniffi::export]
pub fn get_sync_progress(app_data_dir: String) -> domain::SyncProgress {
    services::library_sync::progress(&app_data_dir)
}

/// Fetch a validated full or incremental update and commit it atomically.
/// Concurrent callers for one profile share the same operation and result.
#[uniffi::export(async_runtime = "tokio")]
pub async fn sync_library_smart(
    server_url: String,
    token: String,
    user_id: String,
    app_data_dir: String,
) -> Result<domain::SyncReport, error::AppError> {
    services::library_sync::synchronize(app_data_dir, server_url, token, user_id).await
}

/// Sync favorite status for all songs after initial library sync.
/// Fetches the list of favorite IDs from Jellyfin and updates cached songs.
#[uniffi::export(async_runtime = "tokio")]
pub async fn sync_favorites(
    server_url: String,
    token: String,
    user_id: String,
    app_data_dir: String,
) -> Result<u32, error::AppError> {
    let app_data_path = std::path::PathBuf::from(&app_data_dir);
    let database =
        db::open(&app_data_path).map_err(|e| error::AppError::Database(e.to_string()))?;

    let favorite_ids = {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_favorite_ids(&user_id).await?
    };

    let favorite_count = domain::services::LibraryService::new(database)
        .sync_favorites(&favorite_ids)
        .map_err(|e| error::AppError::Database(e.to_string()))?;

    Ok(favorite_count)
}

/// Fetch a single artist from server and cache it
#[uniffi::export(async_runtime = "tokio")]
pub async fn fetch_artist(
    server_url: String,
    token: String,
    user_id: String,
    artist_id: String,
    app_data_dir: String,
) -> Result<models::Artist, error::AppError> {
    // Retain this profile's database for the whole operation
    let app_data_path = std::path::PathBuf::from(&app_data_dir);
    let database =
        db::open(&app_data_path).map_err(|e| error::AppError::Database(e.to_string()))?;

    // Fetch from server
    let artist = {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_artist_details(&user_id, &artist_id).await?
    };

    // Cache in database
    domain::services::LibraryService::new(database)
        .upsert_artists(std::slice::from_ref(&artist))
        .map_err(|e| error::AppError::Database(e.to_string()))?;

    Ok(artist)
}

/// Fetch a single album from server and cache it
#[uniffi::export(async_runtime = "tokio")]
pub async fn fetch_album(
    server_url: String,
    token: String,
    user_id: String,
    album_id: String,
    app_data_dir: String,
) -> Result<models::Album, error::AppError> {
    // Retain this profile's database for the whole operation
    let app_data_path = std::path::PathBuf::from(&app_data_dir);
    let database =
        db::open(&app_data_path).map_err(|e| error::AppError::Database(e.to_string()))?;

    // Fetch from server
    let album = {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_album_details(&user_id, &album_id).await?
    };

    // Cache in database
    domain::services::LibraryService::new(database)
        .upsert_albums(std::slice::from_ref(&album))
        .map_err(|e| error::AppError::Database(e.to_string()))?;

    Ok(album)
}

/// Get a cached artist from local database
#[uniffi::export]
pub fn get_cached_artist(
    app_data_dir: String,
    artist_id: String,
) -> Result<Option<models::Artist>, error::AppError> {
    let app_data_path = std::path::PathBuf::from(&app_data_dir);
    let database =
        db::open(&app_data_path).map_err(|e| error::AppError::Database(e.to_string()))?;

    db::artists::get_by_id(&database, &artist_id)
        .map_err(|e| error::AppError::Database(e.to_string()))
}

/// Get a cached album from local database
#[uniffi::export]
pub fn get_cached_album(
    app_data_dir: String,
    album_id: String,
) -> Result<Option<models::Album>, error::AppError> {
    let app_data_path = std::path::PathBuf::from(&app_data_dir);
    let database =
        db::open(&app_data_path).map_err(|e| error::AppError::Database(e.to_string()))?;

    db::albums::get_by_id(&database, &album_id)
        .map_err(|e| error::AppError::Database(e.to_string()))
}

/// Get a cached song from local database
#[uniffi::export]
pub fn get_cached_song(
    app_data_dir: String,
    song_id: String,
) -> Result<Option<models::Song>, error::AppError> {
    let app_data_path = std::path::PathBuf::from(&app_data_dir);
    let database =
        db::open(&app_data_path).map_err(|e| error::AppError::Database(e.to_string()))?;

    db::songs::get_by_id(&database, &song_id).map_err(|e| error::AppError::Database(e.to_string()))
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_recently_played(
    server_url: String,
    token: String,
    user_id: String,
) -> Result<Vec<models::Song>, error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_recently_played(&user_id).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_instant_mix(
    server_url: String,
    token: String,
    item_id: String,
) -> Result<Vec<models::Song>, error::AppError> {
    {
        let client = services::JellyfinClient::with_auth(server_url, token);
        client.get_instant_mix(&item_id).await
    }
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn get_related_artists(
    app_data_dir: String,
    artist_id: String,
) -> Result<Vec<models::Artist>, error::AppError> {
    let app_data_path = std::path::PathBuf::from(&app_data_dir);
    let database =
        db::open(&app_data_path).map_err(|e| error::AppError::Database(e.to_string()))?;

    let all_artists =
        db::artists::get_all(&database).map_err(|e| error::AppError::Database(e.to_string()))?;
    let all_songs =
        db::songs::get_all(&database).map_err(|e| error::AppError::Database(e.to_string()))?;

    let current_artist = all_artists
        .iter()
        .find(|a| a.id == artist_id)
        .ok_or_else(|| error::AppError::UniFfi("Artist not found".to_string()))?;

    const COLLABORATION_SCORE: i32 = 10;
    const SHARED_GENRE_SCORE: i32 = 5;
    const SHARED_ALBUM_SCORE: i32 = 2;

    let current_artist_songs: Vec<&models::Song> = all_songs
        .iter()
        .filter(|s| {
            s.artists
                .as_ref()
                .is_some_and(|a| a.contains(&current_artist.name))
        })
        .collect();

    let current_artist_genres: std::collections::HashSet<&String> = current_artist_songs
        .iter()
        .flat_map(|s| {
            s.genres
                .as_ref()
                .map_or_else(Vec::new, |g| g.iter().collect())
        })
        .collect();

    let current_artist_albums: std::collections::HashSet<&String> = current_artist_songs
        .iter()
        .filter_map(|s| s.album.as_ref())
        .collect();

    let mut artist_scores: std::collections::HashMap<String, i32> =
        std::collections::HashMap::new();

    for other_artist in &all_artists {
        if other_artist.id == current_artist.id {
            continue;
        }

        let mut score = 0;
        let other_artist_songs: Vec<&models::Song> = all_songs
            .iter()
            .filter(|s| {
                s.artists
                    .as_ref()
                    .is_some_and(|a| a.contains(&other_artist.name))
            })
            .collect();

        if other_artist_songs.is_empty() {
            continue;
        }

        let collaborations = current_artist_songs
            .iter()
            .filter(|s| {
                s.artists
                    .as_ref()
                    .is_some_and(|a| a.contains(&other_artist.name))
            })
            .count();
        score += collaborations as i32 * COLLABORATION_SCORE;

        let other_artist_genres: std::collections::HashSet<&String> = other_artist_songs
            .iter()
            .flat_map(|s| {
                s.genres
                    .as_ref()
                    .map_or_else(Vec::new, |g| g.iter().collect())
            })
            .collect();

        for genre in &other_artist_genres {
            if current_artist_genres.contains(genre) {
                score += SHARED_GENRE_SCORE;
            }
        }

        let other_artist_albums: std::collections::HashSet<&String> = other_artist_songs
            .iter()
            .filter_map(|s| s.album.as_ref())
            .collect();

        for album in &other_artist_albums {
            if current_artist_albums.contains(album) && collaborations == 0 {
                score += SHARED_ALBUM_SCORE;
            }
        }

        if score > 0 {
            artist_scores.insert(other_artist.id.clone(), score);
        }
    }

    let mut sorted_artists: Vec<_> = artist_scores.into_iter().collect();
    sorted_artists.sort_by_key(|b| std::cmp::Reverse(b.1));

    let result: Vec<models::Artist> = sorted_artists
        .iter()
        .take(6)
        .filter_map(|(artist_id, _)| all_artists.iter().find(|a| a.id == *artist_id).cloned())
        .collect();

    Ok(result)
}

uniffi::setup_scaffolding!();
