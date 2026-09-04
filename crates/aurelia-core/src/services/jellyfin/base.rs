use super::*;

impl JellyfinClient {
    /// Create a new Jellyfin client
    #[must_use]
    pub fn new(server_url: String) -> Self {
        Self {
            client: Client::builder()
                .connect_timeout(std::time::Duration::from_secs(10))
                .timeout(std::time::Duration::from_secs(60))
                .build()
                .expect("HTTP client configuration"),
            server_url,
            token: None,
        }
    }

    /// Create a new authenticated Jellyfin client
    #[must_use]
    pub fn with_auth(server_url: String, token: String) -> Self {
        Self {
            client: Client::builder()
                .connect_timeout(std::time::Duration::from_secs(10))
                .timeout(std::time::Duration::from_secs(60))
                .build()
                .expect("HTTP client configuration"),
            server_url,
            token: Some(token),
        }
    }

    /// Get the HTTP client (for internal use)
    #[must_use]
    pub const fn get_client(&self) -> &Client {
        &self.client
    }

    /// Get the authorization header value
    #[must_use]
    pub fn get_auth_header(&self) -> String {
        self.token.as_ref().map_or_else(
            || utils::build_jellyfin_auth_header(None),
            |token| format!("MediaBrowser Token=\"{token}\""),
        )
    }

    /// Authenticate user with Jellyfin server
    pub async fn authenticate(
        &self,
        username: &str,
        password: &str,
        device_id: &str,
    ) -> AppResult<LoginResponse> {
        let login_url = utils::build_jellyfin_url(&self.server_url, "/Users/AuthenticateByName");

        let response = self
            .client
            .post(&login_url)
            .header(
                "Authorization",
                utils::build_jellyfin_auth_header(Some(device_id)),
            )
            .header("Content-Type", "application/json")
            .json(&serde_json::json!({
                "Username": username,
                "Pw": password
            }))
            .send()
            .await?;

        response.error_for_status_ref()?;

        let auth_response: JellyfinAuthResponse = response.json().await.map_err(|e| {
            error_handling::api_parse_error_with_context(
                e,
                "Failed to parse authentication response from server",
            )
        })?;
        Ok(LoginResponse {
            token: auth_response.access_token,
            user_id: auth_response.user.id,
        })
    }

    /// Get album artists only
    pub async fn get_album_artists(&self) -> AppResult<Vec<Artist>> {
        let artists_url = utils::build_jellyfin_url(
            &self.server_url,
            "/Artists/AlbumArtists?Recursive=true&Fields=ImageTags,Overview,ProviderIds,CommunityRating,DateLastModified",
        );

        let response = self
            .client
            .get(&artists_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_text = response.text().await?;
        let response_json: serde_json::Value = serde_json::from_str(&response_text)
            .map_err(|e| AppError::ApiParse(format!("Failed to parse album artists JSON: {e}")))?;

        let items = response_json["Items"].as_array().ok_or_else(|| {
            AppError::ApiParse("Invalid album artists response format".to_string())
        })?;

        let mut artists = Vec::new();

        for item in items {
            let artist = self.parse_single_artist(item)?;
            artists.push(artist);
        }

        debug!("Fetched {} album artists from Jellyfin API", artists.len());
        Ok(artists)
    }

    /// Get albums directly from Jellyfin
    pub async fn get_albums(&self, user_id: &str) -> AppResult<Vec<crate::models::Album>> {
        let albums_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!(
                "/Items?userId={user_id}&IncludeItemTypes=MusicAlbum&Recursive=true&Fields=ImageTags,Overview,ProductionYear,CommunityRating,Artists,ProviderIds,DateCreated,DateLastModified",
            ),
        );

        let response = self
            .client
            .get(&albums_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_text = response.text().await?;
        let response_json: serde_json::Value = serde_json::from_str(&response_text)
            .map_err(|e| AppError::ApiParse(format!("Failed to parse albums JSON: {e}")))?;

        let items = response_json["Items"]
            .as_array()
            .ok_or_else(|| AppError::ApiParse("Invalid albums response format".to_string()))?;

        let mut albums = Vec::new();

        for item in items {
            let album = self.parse_single_album(item);
            albums.push(album);
        }

        debug!("Fetched {} albums directly from Jellyfin API", albums.len());
        Ok(albums)
    }

    /// Parse a single album from JSON
    pub fn parse_single_album(&self, item: &serde_json::Value) -> crate::models::Album {
        let id = item["Id"].as_str().map(std::string::ToString::to_string);
        let name = item["Name"].as_str().unwrap_or("Unknown Album").to_string();

        let artist = item["Artists"]
            .as_array()
            .map_or("Unknown Artist", |artists| {
                artists
                    .first()
                    .and_then(|a| a.as_str())
                    .unwrap_or("Unknown Artist")
            });

        let artist_id = item["AlbumArtists"]
            .as_array()
            .and_then(|artists| artists.first())
            .and_then(|artist| artist["Id"].as_str())
            .map(std::string::ToString::to_string);

        let (album_art_url, image_tags) = self.extract_image_info(item, id.as_deref());

        let song_count = item["SongCount"].as_i64().unwrap_or(0);

        let provider_ids = item["ProviderIds"].as_object().map(|obj| {
            obj.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect::<HashMap<String, String>>()
        });

        let date_created = item["DateCreated"]
            .as_str()
            .map(std::string::ToString::to_string);

        let date_modified = item["DateLastModified"]
            .as_str()
            .map(std::string::ToString::to_string);

        crate::models::Album {
            id,
            name,
            artist: artist.to_string(),
            artist_id,
            album_art_url,
            song_count,
            songs: None,
            image_tags,
            provider_ids,
            date_created,
            date_modified,
        }
    }

    /// Get albums for a specific artist
    /// Handles compilation vs non-compilation albums like Feishin does
    /// - For compilations: uses ContributingArtistIds (artist contributed to the album)
    /// - For non-compilations: uses AlbumArtistIds (artist is the album artist)
    /// - If neither specified: uses ArtistIds (all credits)
    pub async fn get_albums_for_artist(
        &self,
        user_id: &str,
        artist_id: &str,
        compilation: Option<bool>,
    ) -> AppResult<Vec<crate::models::Album>> {
        let artist_filter = match compilation {
            Some(true) => format!("ContributingArtistIds={artist_id}"),
            Some(false) => format!("AlbumArtistIds={artist_id}"),
            None => format!("ArtistIds={artist_id}"),
        };

        let albums_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!(
                "/Items?userId={user_id}&IncludeItemTypes=MusicAlbum&Recursive=true&{artist_filter}&Fields=People,Tags,ImageTags,Overview,ProductionYear,CommunityRating,Artists,ProviderIds,DateCreated",
            ),
        );

        let response = self
            .client
            .get(&albums_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_json: serde_json::Value = response.json().await?;
        let items = response_json["Items"]
            .as_array()
            .ok_or_else(|| AppError::ApiParse("Invalid albums response format".to_string()))?;

        let albums = items
            .iter()
            .map(|item| self.parse_single_album(item))
            .collect();
        Ok(albums)
    }

    /// Get music library items
    pub async fn get_music_library(&self, user_id: &str) -> AppResult<Vec<Song>> {
        self.get_songs_paginated(user_id, None, 200)
            .await
            .map(|(songs, _)| songs)
    }

    /// Get recently played music items
    pub async fn get_recently_played(&self, user_id: &str) -> AppResult<Vec<Song>> {
        let library_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!(
                "/Items?userId={user_id}&IncludeItemTypes=Audio&Recursive=true&Filters=IsPlayed&SortBy=DatePlayed&SortOrder=Descending&Limit=20&Fields=Genres,DateCreated,DateLastModified,MediaSources,ParentId,People,Tags,Path,RunTimeTicks,ImageTags,AlbumId,Artists,Album,ProductionYear,UserData,IndexNumber,PremiereDate,AlbumArtists,MediaStreams"
            ),
        );

        let response = self
            .client
            .get(&library_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_json: serde_json::Value = response.json().await?;
        self.parse_music_items(&response_json)
    }

    /// Get songs for a specific album artist using server-side filtering
    /// This uses the AlbumArtistIds query parameter to let Jellyfin do the filtering
    pub async fn get_songs_for_album_artist(
        &self,
        user_id: &str,
        artist_id: &str,
    ) -> AppResult<Vec<Song>> {
        let songs_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!(
                "/Items?userId={user_id}&IncludeItemTypes=Audio&Recursive=true&AlbumArtistIds={artist_id}&Fields=Genres,DateCreated,MediaSources,ParentId,People,Tags,Path,RunTimeTicks,ImageTags,AlbumId,Artists,Album,ProductionYear,UserData,IndexNumber,PremiereDate,AlbumArtists,MediaStreams&SortBy=Album,SortName"
            ),
        );

        let response = self
            .client
            .get(&songs_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_json: serde_json::Value = response.json().await?;
        self.parse_music_items(&response_json)
    }

    /// Get songs for a specific album using server-side filtering
    /// Includes workaround for Jellyfin bug where AlbumIds filter also matches album names
    pub async fn get_songs_for_album(&self, user_id: &str, album_id: &str) -> AppResult<Vec<Song>> {
        let songs_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!(
                "/Items?userId={user_id}&IncludeItemTypes=Audio&Recursive=true&AlbumIds={album_id}&Fields=Genres,DateCreated,MediaSources,ParentId,People,Tags,Path,RunTimeTicks,ImageTags,AlbumId,Artists,Album,ProductionYear,UserData,IndexNumber,PremiereDate,AlbumArtists,MediaStreams&SortBy=ParentIndexNumber,IndexNumber,SortName"
            ),
        );

        let response = self
            .client
            .get(&songs_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_json: serde_json::Value = response.json().await?;
        let mut songs = self.parse_music_items(&response_json)?;

        // Workaround for Jellyfin bug: AlbumIds filter searches for both:
        // 1. Matching album ID
        // 2. An album with the NAME of the album
        // Filter client-side to ensure we only get songs from the requested album
        songs.retain(|song| song.album_id.as_ref() == Some(&album_id.to_string()));

        Ok(songs)
    }

    /// Get instant mix (similar songs) for a given item
    pub async fn get_instant_mix(&self, item_id: &str) -> AppResult<Vec<Song>> {
        let instant_mix_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!(
                "/Items/{}/InstantMix?Fields=Genres,DateCreated,MediaSources,ParentId,People,Tags,Path,RunTimeTicks,ImageTags,AlbumId,Artists,Album,ProductionYear,UserData,IndexNumber,PremiereDate,AlbumArtists,MediaStreams",
                item_id
            ),
        );

        let response = self
            .client
            .get(&instant_mix_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_json: serde_json::Value = response.json().await?;
        self.parse_music_items(&response_json)
    }
}
