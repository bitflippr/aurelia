use super::*;

impl JellyfinClient {
    /// Parse music items from Jellyfin API response
    pub(crate) fn parse_music_items(
        &self,
        response_json: &serde_json::Value,
    ) -> AppResult<Vec<Song>> {
        let items = response_json["Items"]
            .as_array()
            .ok_or_else(|| AppError::ApiParse("Invalid response format".to_string()))?;

        let mut music_items = Vec::new();

        for item in items {
            let music_item = self.parse_single_music_item(item)?;
            music_items.push(music_item);
        }

        Ok(music_items)
    }

    /// Parse a single music item from JSON with manual field mapping
    #[allow(clippy::too_many_lines)]
    pub fn parse_single_music_item(&self, item: &serde_json::Value) -> AppResult<Song> {
        let id = item["Id"]
            .as_str()
            .ok_or_else(|| AppError::ApiParse("Missing item ID".to_string()))?
            .to_string();

        let name = item["Name"].as_str().unwrap_or("").to_string();

        let item_type = item["Type"].as_str().unwrap_or("").to_string();

        let album = item["Album"].as_str().map(std::string::ToString::to_string);
        let album_id = item["AlbumId"]
            .as_str()
            .map(std::string::ToString::to_string)
            .or_else(|| {
                item["ParentId"]
                    .as_str()
                    .map(std::string::ToString::to_string)
            });

        #[allow(clippy::cast_precision_loss)]
        let duration = item["RunTimeTicks"]
            .as_i64()
            .map(|ticks| ticks as f64 / 10_000_000.0);

        let user_data = &item["UserData"];
        let play_count = user_data["PlayCount"]
            .as_i64()
            .and_then(|n| n.try_into().ok());
        let is_favorite = user_data["IsFavorite"].as_bool();
        let date_played = user_data["LastPlayedDate"].as_str().map(String::from);

        let year = item["ProductionYear"]
            .as_i64()
            .and_then(|n| n.try_into().ok());
        let disc_number = item["ParentIndexNumber"]
            .as_i64()
            .and_then(|n| n.try_into().ok());
        let track_number = item["IndexNumber"].as_i64().and_then(|n| n.try_into().ok());
        let premiere_date = item["PremiereDate"]
            .as_str()
            .map(std::string::ToString::to_string);
        let date_created = item["DateCreated"]
            .as_str()
            .map(std::string::ToString::to_string);

        let (bit_rate, sample_rate, codec): (Option<i32>, Option<i32>, Option<String>) =
            item["MediaStreams"]
                .as_array()
                .map_or((None, None, None), |streams| {
                    streams.iter().find(|s| s["Type"] == "Audio").map_or(
                        (None, None, None),
                        |audio_stream| {
                            (
                                audio_stream["BitRate"]
                                    .as_i64()
                                    .and_then(|n| n.try_into().ok()),
                                audio_stream["SampleRate"]
                                    .as_i64()
                                    .and_then(|n| n.try_into().ok()),
                                audio_stream["Codec"]
                                    .as_str()
                                    .map(std::string::ToString::to_string),
                            )
                        },
                    )
                });

        let (album_art_url, image_tags) = self.extract_image_info(item, Some(&id));
        let artists = Self::extract_artists(item);
        let artist_ids = Self::extract_artist_ids(item);
        let album_artists = Self::extract_album_artists(item);
        let genres = Self::extract_genres(item);

        let container = item["Container"]
            .as_str()
            .map(std::string::ToString::to_string)
            .or_else(|| {
                item["Path"].as_str().and_then(|p| {
                    std::path::Path::new(p)
                        .extension()
                        .and_then(|os_str| os_str.to_str())
                        .map(str::to_lowercase)
                })
            });

        let date_modified = item["DateLastModified"]
            .as_str()
            .map(std::string::ToString::to_string);

        let song = Song {
            id,
            name,
            item_type,
            album,
            album_id,
            artists,
            artist_ids,
            path: item["Path"].as_str().map(std::string::ToString::to_string),
            duration,
            album_art_url,
            year,
            play_count,
            is_favorite,
            disc_number,
            track_number,
            container,
            bit_rate,
            sample_rate,
            codec,
            genres,
            premiere_date,
            date_played,
            date_created,
            date_modified,
            album_artists,
            lyrics: None,
            image_tags,
        };

        Ok(song)
    }

    /// Extract album artwork URL from item
    pub(crate) fn extract_image_info(
        &self,
        item: &serde_json::Value,
        item_id: Option<&str>,
    ) -> (Option<String>, Option<HashMap<String, String>>) {
        let image_id = item["AlbumId"]
            .as_str()
            .or(item_id)
            .unwrap_or_else(|| item["Id"].as_str().unwrap_or(""));

        // Build image tags map if available
        let image_tags = item["ImageTags"].as_object().map(|tags| {
            tags.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect::<HashMap<String, String>>()
        });

        // For songs, always try to build the album art URL using AlbumId (if present)
        // For albums/artists, only build URL if they have a Primary image tag
        let has_album_id = item["AlbumId"].as_str().is_some();
        let has_primary_tag = image_tags
            .as_ref()
            .is_some_and(|tags| tags.contains_key("Primary"));

        let url = if has_album_id || has_primary_tag {
            let base_url = utils::build_jellyfin_url(
                &self.server_url,
                &format!("/Items/{}/Images/Primary", image_id),
            );
            if let Some(token) = &self.token {
                Some(format!("{base_url}?api_key={token}"))
            } else {
                Some(base_url)
            }
        } else {
            None
        };

        (url, image_tags)
    }

    /// Parse a single playlist item from JSON
    pub(crate) fn parse_single_playlist_item(
        &self,
        item: &serde_json::Value,
    ) -> AppResult<crate::models::music::Playlist> {
        let id = item["Id"]
            .as_str()
            .ok_or_else(|| AppError::ApiParse("Missing playlist ID".to_string()))?
            .to_string();

        let name = item["Name"]
            .as_str()
            .unwrap_or("Untitled Playlist")
            .to_string();

        let server_id = item["ServerId"].as_str().unwrap_or("").to_string();

        let can_delete = item["CanDelete"].as_bool();
        let sort_name = item["SortName"].as_str().map(String::from);
        let is_folder = item["IsFolder"].as_bool().unwrap_or(true);
        let item_type = item["Type"].as_str().unwrap_or("Playlist").to_string();

        let user_data_json = item["UserData"].as_object();
        let is_favorite = user_data_json
            .and_then(|ud| ud.get("IsFavorite").and_then(|v| v.as_bool()))
            .unwrap_or(false);

        let user_data = user_data_json.map(|ud| crate::models::music::UserData {
            playback_position_ticks: ud
                .get("PlaybackPositionTicks")
                .and_then(|v| v.as_i64())
                .unwrap_or(0),
            play_count: ud.get("PlayCount").and_then(|v| v.as_i64()).unwrap_or(0) as i32,
            is_favorite,
            played: ud.get("Played").and_then(|v| v.as_bool()).unwrap_or(false),
            last_played_date: ud
                .get("LastPlayedDate")
                .and_then(|v| v.as_str())
                .map(String::from),
        });

        let run_time_ticks = item["RunTimeTicks"].as_i64();
        let child_count = item["ChildCount"].as_i64().map(|n| n as i32);
        let date_created = item["DateCreated"].as_str().map(String::from);
        let date_last_saved = item["DateLastSaved"].as_str().map(String::from);

        // Extract image info
        let (image_tags, backdrop_image_tags, image_blur_hashes) =
            if let Some(image_tags_obj) = item["ImageTags"].as_object() {
                let image_tags = image_tags_obj
                    .iter()
                    .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                    .collect::<HashMap<String, String>>();

                let backdrop_tags = item["BackdropImageTags"].as_array().map(|arr| {
                    arr.iter()
                        .filter_map(|v| v.as_str().map(String::from))
                        .collect()
                });

                let blur_hashes = item["ImageBlurHashes"].as_object().map(|blur_obj| {
                    blur_obj
                        .iter()
                        .filter_map(|(k, v)| {
                            v.as_object().map(|inner| {
                                (
                                    k.clone(),
                                    inner
                                        .iter()
                                        .filter_map(|(ik, iv)| {
                                            iv.as_str().map(|s| (ik.clone(), s.to_string()))
                                        })
                                        .collect(),
                                )
                            })
                        })
                        .collect()
                });

                (Some(image_tags), backdrop_tags, blur_hashes)
            } else {
                (None, None, None)
            };

        let location_type = item["LocationType"]
            .as_str()
            .unwrap_or("Virtual")
            .to_string();
        let media_type = item["MediaType"].as_str().map(String::from);

        Ok(crate::models::music::Playlist {
            name,
            server_id,
            id,
            can_delete,
            sort_name,
            is_folder,
            item_type,
            user_data,
            run_time_ticks,
            child_count,
            image_tags,
            backdrop_image_tags,
            image_blur_hashes,
            location_type,
            media_type,
            date_created,
            date_last_saved,
            is_favorite: Some(is_favorite),
            description: None, // Playlists don't seem to have descriptions in the basic response
            songs: None,       // Songs would need a separate API call
        })
    }

    /// Extract artists from item
    fn extract_artists(item: &serde_json::Value) -> Option<Vec<String>> {
        // Use ArtistItems (array of objects with Name+Id) to ensure alignment with IDs
        let artist_items = &item["ArtistItems"];

        if let Some(arr) = artist_items.as_array() {
            let mut artists = Vec::new();
            for item_value in arr {
                if let Some(name_str) = item_value["Name"].as_str() {
                    if name_str.contains('\x1F') {
                        for split_name in name_str.split('\x1F').filter(|s| !s.is_empty()) {
                            artists.push(split_name.to_string());
                        }
                    } else {
                        artists.push(name_str.to_string());
                    }
                }
            }
            if !artists.is_empty() {
                return Some(artists);
            }
        }

        // Fallback to Artists array if ArtistItems not available
        let artists_value = &item["Artists"];
        if let Some(arr) = artists_value.as_array() {
            let mut artists = Vec::new();
            for artist_value in arr {
                if let Some(artist_str) = artist_value.as_str() {
                    if artist_str.contains('\x1F') {
                        for split_artist in artist_str.split('\x1F').filter(|s| !s.is_empty()) {
                            artists.push(split_artist.to_string());
                        }
                    } else {
                        artists.push(artist_str.to_string());
                    }
                }
            }
            if !artists.is_empty() {
                return Some(artists);
            }
        }

        if let Some(artists_str) = artists_value.as_str() {
            let artists = artists_str
                .split('\x1F')
                .filter(|s| !s.is_empty())
                .map(std::string::ToString::to_string)
                .collect::<Vec<String>>();
            if !artists.is_empty() {
                return Some(artists);
            }
        }
        None
    }

    /// Extract artist IDs from item
    fn extract_artist_ids(item: &serde_json::Value) -> Option<Vec<String>> {
        let artist_items = &item["ArtistItems"];

        if let Some(arr) = artist_items.as_array() {
            let mut artist_ids = Vec::new();
            for item_value in arr {
                if let Some(id_str) = item_value["Id"].as_str() {
                    if id_str.contains('\x1F') {
                        for split_id in id_str.split('\x1F').filter(|s| !s.is_empty()) {
                            artist_ids.push(split_id.to_string());
                        }
                    } else {
                        artist_ids.push(id_str.to_string());
                    }
                }
            }
            if !artist_ids.is_empty() {
                return Some(artist_ids);
            }
        }

        if let Some(arr) = artist_items.as_array() {
            let artist_ids = arr
                .iter()
                .filter_map(|v| v.as_str().map(std::string::ToString::to_string))
                .collect::<Vec<String>>();
            if !artist_ids.is_empty() {
                let mut final_ids = Vec::new();
                for id_str in artist_ids {
                    if id_str.contains('\x1F') {
                        for split_id in id_str.split('\x1F').filter(|s| !s.is_empty()) {
                            final_ids.push(split_id.to_string());
                        }
                    } else {
                        final_ids.push(id_str);
                    }
                }
                return Some(final_ids);
            }
        }
        None
    }

    /// Extract genres from item
    fn extract_genres(item: &serde_json::Value) -> Option<Vec<String>> {
        let genres_value = &item["Genres"];

        if let Some(arr) = genres_value.as_array() {
            let mut genres = Vec::new();
            for genre_value in arr {
                if let Some(genre_str) = genre_value.as_str() {
                    if genre_str.contains('\x1F') {
                        for split_genre in genre_str.split('\x1F').filter(|s| !s.is_empty()) {
                            genres.push(split_genre.to_string());
                        }
                    } else {
                        genres.push(genre_str.to_string());
                    }
                }
            }
            if !genres.is_empty() {
                return Some(genres);
            }
        }

        if let Some(genres_str) = genres_value.as_str() {
            let genres = genres_str
                .split('\x1F')
                .filter(|s| !s.is_empty())
                .map(std::string::ToString::to_string)
                .collect::<Vec<String>>();
            if !genres.is_empty() {
                return Some(genres);
            }
        }

        None
    }

    /// Extract album artists from item
    fn extract_album_artists(item: &serde_json::Value) -> Option<Vec<NameIdPair>> {
        let album_artists_value = &item["AlbumArtists"];

        if let Some(arr) = album_artists_value.as_array() {
            let mut album_artists = Vec::new();
            for artist_value in arr {
                if let (Some(id), Some(name)) =
                    (artist_value["Id"].as_str(), artist_value["Name"].as_str())
                {
                    album_artists.push(NameIdPair {
                        id: id.to_string(),
                        name: name.to_string(),
                    });
                }
            }
            if !album_artists.is_empty() {
                return Some(album_artists);
            }
        }

        None
    }

    /// Get artist details
    pub async fn get_artist_details(&self, user_id: &str, artist_id: &str) -> AppResult<Artist> {
        let artist_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!(
                "/Items/{artist_id}?userId={user_id}&Fields=ImageTags,Overview,ProviderIds,CommunityRating,DateLastModified"
            ),
        );

        let response = self
            .client
            .get(&artist_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_json: serde_json::Value = response.json().await?;
        self.parse_single_artist(&response_json)
    }

    /// Get album details
    pub async fn get_album_details(
        &self,
        user_id: &str,
        album_id: &str,
    ) -> AppResult<crate::models::Album> {
        let album_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!(
                "/Items/{album_id}?userId={user_id}&Fields=ImageTags,Overview,ProductionYear,CommunityRating,Artists,ProviderIds,DateCreated,DateLastModified"
            ),
        );

        let response = self
            .client
            .get(&album_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_json: serde_json::Value = response.json().await?;
        Ok(self.parse_single_album(&response_json))
    }

    /// Get all artists
    pub async fn get_all_artists(&self) -> AppResult<Vec<Artist>> {
        let artists_url = utils::build_jellyfin_url(
            &self.server_url,
            "/Artists?Recursive=true&Fields=ImageTags,Overview,ProviderIds,CommunityRating",
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
            .map_err(|e| AppError::ApiParse(format!("Failed to parse artists JSON: {e}")))?;

        let items = response_json["Items"]
            .as_array()
            .ok_or_else(|| AppError::ApiParse("Invalid artists response format".to_string()))?;

        let mut artists = Vec::new();

        for item in items {
            let artist = self.parse_single_artist(item)?;
            artists.push(artist);
        }

        Ok(artists)
    }

    /// Get all artists for a user via Items endpoint (more complete Overview support)
    pub async fn get_all_artists_for_user(&self, user_id: &str) -> AppResult<Vec<Artist>> {
        let mut all_artists = Vec::new();
        let mut start_index = 0;
        let limit = 100; // Jellyfin's default page size

        loop {
            let url = utils::build_jellyfin_url(
                &self.server_url,
                &format!(
                    "/Items?userId={user_id}&IncludeItemTypes=MusicArtist&Recursive=true&Fields=ImageTags,Overview,ProviderIds,CommunityRating&StartIndex={start_index}&Limit={limit}"
                ),
            );

            let response = self
                .client
                .get(&url)
                .header("Authorization", self.get_auth_header())
                .send()
                .await?;

            response.error_for_status_ref()?;

            let response_json: serde_json::Value = response.json().await?;

            let items = response_json["Items"]
                .as_array()
                .ok_or_else(|| AppError::ApiParse("Invalid artists response format".to_string()))?;

            if items.is_empty() {
                // No more artists to fetch
                break;
            }

            for item in items {
                let artist = self.parse_single_artist(item)?;
                all_artists.push(artist);
            }

            start_index += items.len();
        }

        Ok(all_artists)
    }

    /// Parse a single artist from JSON with manual field mapping
    pub fn parse_single_artist(&self, item: &serde_json::Value) -> AppResult<Artist> {
        let id = item["Id"]
            .as_str()
            .ok_or_else(|| AppError::ApiParse("Missing artist ID".to_string()))?
            .to_string();

        let name = item["Name"].as_str().unwrap_or("").to_string();

        let image_tags = item["ImageTags"].as_object().map(|obj| {
            obj.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect::<HashMap<String, String>>()
        });

        let image_url = item["ImageTags"].as_object().and_then(|tags| {
            if tags.contains_key("Primary") {
                let base_url = utils::build_jellyfin_url(
                    &self.server_url,
                    &format!("/Items/{}/Images/Primary", id),
                );

                if let Some(token) = &self.token {
                    Some(format!("{base_url}?api_key={token}"))
                } else {
                    Some(base_url)
                }
            } else {
                None
            }
        });

        let overview = item["Overview"].as_str().and_then(|s| {
            let trimmed = s.trim();
            if trimmed.is_empty() {
                None
            } else {
                Some(trimmed.to_string())
            }
        });

        let provider_ids = item["ProviderIds"].as_object().map(|obj| {
            obj.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect::<HashMap<String, String>>()
        });

        let community_rating = item["CommunityRating"].as_f64();
        let song_count = item["SongCount"].as_i64();

        let date_modified = item["DateLastModified"]
            .as_str()
            .map(std::string::ToString::to_string);

        let artist = Artist {
            name,
            id,
            image_tags,
            image_url,
            overview,
            provider_ids,
            community_rating,
            song_count,
            date_modified,
            songs: None,
        };

        Ok(artist)
    }
}
