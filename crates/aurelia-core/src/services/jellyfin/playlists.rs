use super::*;

impl JellyfinClient {
    pub async fn get_playlists(
        &self,
        user_id: &str,
    ) -> AppResult<Vec<crate::models::music::Playlist>> {
        let url = utils::build_jellyfin_url(&self.server_url, &format!("Users/{}/Items", user_id));

        let response = self
            .client
            .get(&url)
            .header("Authorization", self.get_auth_header())
            .query(&[("IncludeItemTypes", "Playlist"), ("Recursive", "true")])
            .send()
            .await?;

        response.error_for_status_ref()?;

        let response_json: serde_json::Value = response.json().await.map_err(|e| {
            error_handling::network_error_with_context(
                e,
                "Failed to parse playlists response from server",
            )
        })?;

        let items = response_json["Items"]
            .as_array()
            .ok_or_else(|| AppError::ApiParse("Invalid response format".to_string()))?;

        let mut playlists = Vec::new();

        for item in items {
            let playlist = self.parse_single_playlist_item(item)?;
            playlists.push(playlist);
        }

        debug!(
            "Retrieved {} playlists for user {}",
            playlists.len(),
            user_id
        );
        for playlist in &playlists {
            debug!(
                "Playlist: {} (ID: {}, Type: {}, ChildCount: {:?})",
                playlist.name, playlist.id, playlist.item_type, playlist.child_count
            );
        }
        Ok(playlists)
    }

    /// Create a new playlist
    pub async fn create_playlist(
        &self,
        data: &crate::models::music::PlaylistCreateData,
    ) -> AppResult<crate::models::music::Playlist> {
        let url = utils::build_jellyfin_url(&self.server_url, "Playlists");

        // Construct the request JSON with PascalCase field names as expected by Jellyfin
        let request_body = serde_json::json!({
            "Name": data.name,
            "Ids": data.ids,
            "UserId": data.user_id,
            "IsPublic": data.is_public.unwrap_or(false)
        });

        debug!("Creating playlist with JSON: {}", request_body);

        let response = self
            .client
            .post(&url)
            .header("Authorization", self.get_auth_header())
            .header("Content-Type", "application/json")
            .json(&request_body)
            .send()
            .await?;

        response.error_for_status_ref()?;

        // Debug: log the raw response
        let response_text = response.text().await.map_err(|e| {
            error_handling::network_error_with_context(
                e,
                "Failed to read playlist creation response",
            )
        })?;

        debug!("Playlist creation response: {}", response_text);

        // Try to parse as full Playlist first
        let playlist = match serde_json::from_str::<crate::models::music::Playlist>(&response_text)
        {
            Ok(p) => p,
            Err(_) => {
                // If that fails, try to parse as a simple object with just Id and Name
                let simple_response: serde_json::Value = serde_json::from_str(&response_text)
                    .map_err(|e| {
                        error!("Failed to parse any playlist JSON: {}", e);
                        error_handling::api_parse_error_with_context(
                            format!("JSON parse error: {}", e),
                            "Failed to parse created playlist response from server",
                        )
                    })?;

                // Create a minimal playlist from the response
                crate::models::music::Playlist {
                    name: simple_response["Name"]
                        .as_str()
                        .unwrap_or("Untitled Playlist")
                        .to_string(),
                    server_id: self.server_url.clone(),
                    id: simple_response["Id"]
                        .as_str()
                        .ok_or_else(|| {
                            error_handling::api_parse_error_with_context(
                                "Missing playlist ID in response".to_string(),
                                "Failed to parse created playlist response from server",
                            )
                        })?
                        .to_string(),
                    can_delete: Some(true),
                    sort_name: None,
                    is_folder: true,
                    item_type: "Playlist".to_string(),
                    user_data: None,
                    run_time_ticks: None,
                    child_count: Some(data.ids.as_ref().map_or(0, |ids| ids.len() as i32)),
                    image_tags: None,
                    backdrop_image_tags: None,
                    image_blur_hashes: None,
                    location_type: "Virtual".to_string(),
                    media_type: None,
                    date_created: None,
                    date_last_saved: None,
                    is_favorite: None,
                    description: None,
                    songs: None,
                }
            }
        };

        info!("Successfully created playlist: {}", playlist.name);
        Ok(playlist)
    }

    /// Update an existing playlist
    pub async fn update_playlist(
        &self,
        playlist_id: &str,
        updates: &crate::models::music::PlaylistUpdateData,
    ) -> AppResult<crate::models::music::Playlist> {
        let url =
            utils::build_jellyfin_url(&self.server_url, &format!("Playlists/{}", playlist_id));

        // Construct the request JSON with PascalCase field names as expected by Jellyfin
        let request_body = serde_json::json!({
            "Name": updates.name,
            "Ids": updates.ids,
            "UserId": updates.user_id,
            "IsPublic": updates.is_public
        });

        debug!("Updating playlist with JSON: {}", request_body);

        let response = self
            .client
            .post(&url)
            .header("Authorization", self.get_auth_header())
            .header("Content-Type", "application/json")
            .json(&request_body)
            .send()
            .await?;

        response.error_for_status_ref()?;

        // Debug: log the raw response
        let response_text = response.text().await.map_err(|e| {
            error_handling::network_error_with_context(e, "Failed to read playlist update response")
        })?;

        debug!(
            "Playlist update response: '{}' (length: {})",
            response_text,
            response_text.len()
        );

        // For updates, Jellyfin may return empty response or just success status
        let playlist = if response_text.trim().is_empty() {
            debug!("Empty response from playlist update, constructing playlist from input");
            // If response is empty, construct a playlist from the input data
            crate::models::music::Playlist {
                name: updates
                    .name
                    .clone()
                    .unwrap_or_else(|| "Untitled Playlist".to_string()),
                server_id: self.server_url.clone(),
                id: playlist_id.to_string(),
                can_delete: Some(true),
                sort_name: None,
                is_folder: true,
                item_type: "Playlist".to_string(),
                user_data: None,
                run_time_ticks: None,
                child_count: Some(updates.ids.as_ref().map_or(0, |ids| ids.len() as i32)),
                image_tags: None,
                backdrop_image_tags: None,
                image_blur_hashes: None,
                location_type: "Virtual".to_string(),
                media_type: None,
                date_created: None,
                date_last_saved: None,
                is_favorite: None,
                description: None,
                songs: None,
            }
        } else {
            // Try to parse as full Playlist first
            match serde_json::from_str::<crate::models::music::Playlist>(&response_text) {
                Ok(p) => p,
                Err(_) => {
                    // If that fails, try to parse as a simple object with just Id and Name
                    let simple_response: serde_json::Value = serde_json::from_str(&response_text)
                        .map_err(|e| {
                        error!("Failed to parse any updated playlist JSON: {}", e);
                        error_handling::api_parse_error_with_context(
                            format!("JSON parse error: {}", e),
                            "Failed to parse updated playlist response from server",
                        )
                    })?;

                    // Create a minimal playlist from the response
                    crate::models::music::Playlist {
                        name: simple_response["Name"]
                            .as_str()
                            .unwrap_or("Untitled Playlist")
                            .to_string(),
                        server_id: self.server_url.clone(),
                        id: simple_response["Id"]
                            .as_str()
                            .unwrap_or(playlist_id)
                            .to_string(),
                        can_delete: Some(true),
                        sort_name: None,
                        is_folder: true,
                        item_type: "Playlist".to_string(),
                        user_data: None,
                        run_time_ticks: None,
                        child_count: Some(updates.ids.as_ref().map_or(0, |ids| ids.len() as i32)),
                        image_tags: None,
                        backdrop_image_tags: None,
                        image_blur_hashes: None,
                        location_type: "Virtual".to_string(),
                        media_type: None,
                        date_created: None,
                        date_last_saved: None,
                        is_favorite: None,
                        description: None,
                        songs: None,
                    }
                }
            }
        };

        info!("Successfully updated playlist: {}", playlist.name);
        Ok(playlist)
    }

    /// Delete a playlist
    pub async fn delete_playlist(&self, playlist_id: &str) -> AppResult<()> {
        let url = utils::build_jellyfin_url(&self.server_url, &format!("Items/{}", playlist_id));

        let response = self
            .client
            .delete(&url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        info!("Successfully deleted playlist {}", playlist_id);
        Ok(())
    }

    /// Add items to a playlist
    pub async fn add_playlist_items(
        &self,
        playlist_id: &str,
        item_ids: &[String],
    ) -> AppResult<()> {
        let url = utils::build_jellyfin_url(
            &self.server_url,
            &format!("Playlists/{}/Items", playlist_id),
        );

        let ids_param = item_ids.join(",");

        let response = self
            .client
            .post(&url)
            .header("Authorization", self.get_auth_header())
            .query(&[("Ids", &ids_param)])
            .send()
            .await?;

        response.error_for_status_ref()?;

        info!(
            "Successfully added {} items to playlist {}",
            item_ids.len(),
            playlist_id
        );
        Ok(())
    }

    /// Remove items from a playlist
    pub async fn remove_playlist_items(
        &self,
        playlist_id: &str,
        item_ids: &[String],
    ) -> AppResult<()> {
        let url = utils::build_jellyfin_url(
            &self.server_url,
            &format!("Playlists/{}/Items", playlist_id),
        );

        let ids_param = item_ids.join(",");

        let response = self
            .client
            .delete(&url)
            .header("Authorization", self.get_auth_header())
            .query(&[("EntryIds", &ids_param)])
            .send()
            .await?;

        response.error_for_status_ref()?;

        info!(
            "Successfully removed {} items from playlist {}",
            item_ids.len(),
            playlist_id
        );
        Ok(())
    }

    /// Get items in a playlist
    pub async fn get_playlist_items(
        &self,
        playlist_id: &str,
    ) -> AppResult<Vec<crate::models::music::Song>> {
        // Use Items endpoint with ParentId to get playlist contents
        let url = utils::build_jellyfin_url(&self.server_url, "Items");

        let response = self
            .client
            .get(&url)
            .header("Authorization", self.get_auth_header())
            .query(&[
                ("ParentId", playlist_id),
                ("IncludeItemTypes", "Audio"),
                ("Recursive", "false"),
                ("Fields", "Path,ParentId,RunTimeTicks,ImageTags,AlbumId,Artists,Album,ProductionYear,UserData,IndexNumber,Genres,PremiereDate,AlbumArtists,MediaStreams,DateCreated,DateLastSaved,DateLastMediaAdded,MediaSources,Width,Height,Container"),
            ])
            .send()
            .await?;

        response.error_for_status_ref()?;

        // Debug: log the raw response first
        let response_text = response.text().await.map_err(|e| {
            error_handling::network_error_with_context(e, "Failed to read playlist items response")
        })?;

        debug!("Playlist items raw response: {}", response_text);

        // Parse using the same method as library items
        let json_value: serde_json::Value = serde_json::from_str(&response_text).map_err(|e| {
            error_handling::network_error_with_context(
                format!("Failed to parse JSON: {}", e),
                "Failed to parse playlist items response from server",
            )
        })?;

        let songs = self.parse_music_items(&json_value)?;

        debug!(
            "Retrieved {} items from playlist {}",
            songs.len(),
            playlist_id
        );
        Ok(songs)
    }
}
