use super::*;

impl JellyfinClient {
    /// Get lyrics for a song
    pub async fn get_lyrics(&self, item_id: &str) -> AppResult<Option<JellyfinLyrics>> {
        let lyrics_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!("/Audio/{item_id}/Lyrics?preferredFormat=ttml"),
        );

        tracing::info!("[Lyrics] GET {}", lyrics_url);

        let response = self
            .client
            .get(&lyrics_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        let status = response.status();
        tracing::info!("[Lyrics] Response status: {}", status);

        if status == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        response.error_for_status_ref()?;

        // Read body as text first so we can log it on parse failure
        let body = response.text().await?;
        tracing::info!(
            "[Lyrics] Response body length: {} bytes, preview: {}",
            body.len(),
            body.chars().take(300).collect::<String>()
        );

        match serde_json::from_str::<JellyfinLyrics>(&body) {
            Ok(lyrics) => Ok(Some(lyrics)),
            Err(e) => {
                tracing::error!("[Lyrics] Failed to parse Jellyfin lyrics JSON: {}", e);
                Ok(None)
            }
        }
    }

    /// Toggle favorite status for an item
    pub async fn toggle_favorite(
        &self,
        user_id: &str,
        item_id: &str,
        is_favorite: bool,
    ) -> AppResult<()> {
        let fav_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!("/UserFavoriteItems/{item_id}?userId={user_id}"),
        );

        let response = if is_favorite {
            self.client
                .post(&fav_url)
                .header("Authorization", self.get_auth_header())
                .send()
                .await?
        } else {
            self.client
                .delete(&fav_url)
                .header("Authorization", self.get_auth_header())
                .send()
                .await?
        };

        response.error_for_status_ref()?;

        Ok(())
    }

    /// Get all favorite item IDs for the user
    pub async fn get_favorite_ids(&self, user_id: &str) -> AppResult<Vec<String>> {
        let query = format!(
            "/Items?userId={user_id}&IncludeItemTypes=Audio&IsFavorite=true&Recursive=true"
        );
        let (items, _) = self.fetch_all_items(&query, 1000).await?;
        Ok(items
            .iter()
            .map(|item| item["Id"].as_str().expect("validated page ID").to_owned())
            .collect())
    }

    /// Get an audio stream URL for Aurelia's desktop streaming engine.
    ///
    /// Seekable formats are served directly. Other formats use Jellyfin's AAC
    /// transcoder so Rodio receives a progressive stream that can be restarted
    /// with `startTimeTicks` when native seeking is unavailable.
    pub fn get_desktop_audio_stream_url(&self, item_id: &str, container: Option<&str>) -> String {
        let token = self.token.as_deref().unwrap_or("");
        if utils::supports_seeking(container) {
            format!(
                "{}?ApiKey={}&static=true",
                utils::build_jellyfin_url(&self.server_url, &format!("/Audio/{item_id}/stream")),
                token
            )
        } else {
            format!(
                "{}?ApiKey={}",
                utils::build_jellyfin_url(
                    &self.server_url,
                    &format!("/Audio/{item_id}/stream.aac")
                ),
                token
            )
        }
    }

    /// Get an audio stream URL for native mobile players.
    ///
    /// For seekable containers, returns a direct static stream.
    /// For non-seekable containers (ALAC, etc.), uses the `/universal` endpoint which
    /// transcodes to AAC.
    /// Uses `transcodingProtocol=http` so ExoPlayer receives a
    /// progressive stream it can parse directly (not HLS which requires a special MediaSource).
    pub fn get_mobile_audio_stream_url(&self, item_id: &str, container: Option<&str>) -> String {
        let token = self.token.as_deref().unwrap_or("");
        if utils::supports_seeking(container) {
            format!(
                "{}?ApiKey={}&static=true",
                utils::build_jellyfin_url(&self.server_url, &format!("/Audio/{}/stream", item_id)),
                token
            )
        } else {
            format!(
                "{}?ApiKey={}\
                 &container=mp3,aac,m4a|aac,flac,ogg\
                 &transcodingContainer=aac\
                 &transcodingProtocol=http\
                 &audioCodec=aac\
                 &maxStreamingBitrate=999999999",
                utils::build_jellyfin_url(
                    &self.server_url,
                    &format!("/Audio/{}/universal", item_id)
                ),
                token
            )
        }
    }

    /// Register client capabilities with the Jellyfin server
    pub async fn register_capabilities(&self, capabilities: &ClientCapabilities) -> AppResult<()> {
        let capabilities_url =
            utils::build_jellyfin_url(&self.server_url, "/Sessions/Capabilities/Full");

        let request_body = serde_json::json!({
            "capabilities": capabilities
        });

        let response = self
            .client
            .post(&capabilities_url)
            .header("Authorization", self.get_auth_header())
            .header("Content-Type", "application/json")
            .json(&request_body)
            .send()
            .await?;

        response.error_for_status_ref()?;

        debug!("Successfully registered client capabilities with Jellyfin server");
        Ok(())
    }

    /// Report playback start to the Jellyfin server
    pub async fn report_playback_start(
        &self,
        item_id: &str,
        position_ticks: Option<i64>,
    ) -> AppResult<()> {
        let playing_url = utils::build_jellyfin_url(&self.server_url, "/Sessions/Playing");

        let mut request_body = serde_json::json!({
            "ItemId": item_id,
            "CanSeek": true,
            "IsPaused": false,
            "IsMuted": false
        });

        if let Some(position) = position_ticks {
            request_body["PositionTicks"] = serde_json::json!(position);
        }

        let response = self
            .client
            .post(&playing_url)
            .header("Authorization", self.get_auth_header())
            .header("Content-Type", "application/json")
            .json(&request_body)
            .send()
            .await?;

        response.error_for_status_ref()?;

        debug!("Successfully reported playback start for item: {}", item_id);
        Ok(())
    }

    /// Report playback progress to the Jellyfin server
    pub async fn report_playback_progress(
        &self,
        item_id: &str,
        position_ticks: Option<i64>,
        event_name: Option<&str>,
        is_paused: Option<bool>,
    ) -> AppResult<()> {
        let progress_url =
            utils::build_jellyfin_url(&self.server_url, "/Sessions/Playing/Progress");

        let mut request_body = serde_json::json!({
            "ItemId": item_id,
            "IsMuted": false
        });

        if let Some(position) = position_ticks {
            request_body["PositionTicks"] = serde_json::json!(position);
        }

        if let Some(event) = event_name {
            request_body["EventName"] = serde_json::json!(event);
        }

        if let Some(paused) = is_paused {
            request_body["IsPaused"] = serde_json::json!(paused);
        } else {
            request_body["IsPaused"] = serde_json::json!(false);
        }

        let response = self
            .client
            .post(&progress_url)
            .header("Authorization", self.get_auth_header())
            .header("Content-Type", "application/json")
            .json(&request_body)
            .send()
            .await?;

        response.error_for_status_ref()?;

        debug!(
            "Successfully reported playback progress for item: {}",
            item_id
        );
        Ok(())
    }

    /// Report playback stop to the Jellyfin server
    pub async fn report_playback_stop(
        &self,
        item_id: &str,
        position_ticks: Option<i64>,
    ) -> AppResult<()> {
        let stopped_url = utils::build_jellyfin_url(&self.server_url, "/Sessions/Playing/Stopped");

        let mut request_body = serde_json::json!({
            "ItemId": item_id,
            "IsPaused": false,
            "IsMuted": false
        });

        if let Some(position) = position_ticks {
            request_body["PositionTicks"] = serde_json::json!(position);
        }

        let response = self
            .client
            .post(&stopped_url)
            .header("Authorization", self.get_auth_header())
            .header("Content-Type", "application/json")
            .json(&request_body)
            .send()
            .await?;

        response.error_for_status_ref()?;

        debug!("Successfully reported playback stop for item: {}", item_id);
        Ok(())
    }

    /// Mark item as played (update play count and last played date)
    pub async fn mark_item_played(&self, user_id: &str, item_id: &str) -> AppResult<()> {
        let played_url = utils::build_jellyfin_url(
            &self.server_url,
            &format!("/UserPlayedItems/{item_id}?userId={user_id}"),
        );

        let response = self
            .client
            .post(&played_url)
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;

        response.error_for_status_ref()?;

        debug!(
            "Successfully marked item {} as played for user {}",
            item_id, user_id
        );
        Ok(())
    }
}
