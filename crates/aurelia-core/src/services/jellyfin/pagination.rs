use super::*;
use std::collections::HashSet;

impl JellyfinClient {
    /// Fetch a page whose count and IDs can safely participate in deletion detection.
    pub async fn fetch_items_page(
        &self,
        base_query: &str,
        start_index: usize,
        limit: usize,
    ) -> AppResult<PaginatedResponse> {
        if limit == 0 {
            return Err(AppError::Config("Page size must be positive".into()));
        }
        let query = format!(
            "{base_query}&StartIndex={start_index}&Limit={limit}&enableTotalRecordCount=true&SortBy=SortName&SortOrder=Ascending"
        );
        let response = self
            .client
            .get(utils::build_jellyfin_url(&self.server_url, &query))
            .header("Authorization", self.get_auth_header())
            .send()
            .await?;
        let response = error_handling::handle_http_response(response).await?;
        let server_date = response
            .headers()
            .get("date")
            .and_then(|v| v.to_str().ok())
            .map(String::from);
        let json: serde_json::Value = response.json().await?;
        let total_record_count = json["TotalRecordCount"]
            .as_u64()
            .and_then(|n| usize::try_from(n).ok())
            .ok_or_else(|| AppError::ApiParse("Missing or invalid TotalRecordCount".into()))?;
        let items = json["Items"]
            .as_array()
            .cloned()
            .ok_or_else(|| AppError::ApiParse("Missing or invalid Items".into()))?;
        let end = start_index
            .checked_add(items.len())
            .ok_or_else(|| AppError::ApiParse("Page count overflow".into()))?;
        if items.len() > limit
            || end > total_record_count
            || (items.is_empty() && end != total_record_count)
        {
            return Err(AppError::ApiParse(
                "Incomplete or inconsistent items page".into(),
            ));
        }
        for item in &items {
            if item["Id"].as_str().is_none_or(str::is_empty) {
                return Err(AppError::ApiParse("Item has no nonempty ID".into()));
            }
        }
        Ok(PaginatedResponse {
            items,
            total_record_count,
            server_date,
        })
    }

    pub(super) async fn fetch_all_items(
        &self,
        query: &str,
        page_size: usize,
    ) -> AppResult<(Vec<serde_json::Value>, Option<String>)> {
        let mut items = Vec::new();
        let mut ids = HashSet::new();
        let mut expected_count = None;
        let mut server_date = None;
        loop {
            let page = self.fetch_items_page(query, items.len(), page_size).await?;
            if expected_count.is_some_and(|count| count != page.total_record_count) {
                return Err(AppError::ApiParse(
                    "Library changed during pagination; retry refresh".into(),
                ));
            }
            expected_count = Some(page.total_record_count);
            if server_date.is_none() {
                server_date = page.server_date;
            }
            for item in page.items {
                let id = item["Id"].as_str().expect("validated page ID").to_owned();
                if !ids.insert(id) {
                    return Err(AppError::ApiParse("Duplicate item ID across pages".into()));
                }
                items.push(item);
            }
            if items.len() == page.total_record_count {
                return Ok((items, server_date));
            }
        }
    }

    pub async fn get_songs_paginated(
        &self,
        user_id: &str,
        since_date: Option<&str>,
        page_size: usize,
    ) -> AppResult<(Vec<Song>, Option<String>)> {
        let mut query = format!(
            "/Items?userId={user_id}&IncludeItemTypes=Audio&Recursive=true&Fields=Genres,DateCreated,DateLastModified,MediaSources,ParentId,People,Tags,Path,RunTimeTicks,ImageTags,AlbumId,Artists,Album,ProductionYear,UserData,IndexNumber,PremiereDate,AlbumArtists,MediaStreams"
        );
        append_incremental_date_filter(&mut query, since_date);
        let (items, date) = self.fetch_all_items(&query, page_size).await?;
        Ok((
            items
                .iter()
                .map(|item| self.parse_single_music_item(item))
                .collect::<AppResult<_>>()?,
            date,
        ))
    }

    pub async fn get_albums_paginated(
        &self,
        user_id: &str,
        since_date: Option<&str>,
        page_size: usize,
    ) -> AppResult<(Vec<crate::models::Album>, Option<String>)> {
        let mut query = format!(
            "/Items?userId={user_id}&IncludeItemTypes=MusicAlbum&Recursive=true&Fields=ImageTags,Overview,ProductionYear,CommunityRating,Artists,ProviderIds,DateCreated,DateLastModified"
        );
        append_incremental_date_filter(&mut query, since_date);
        let (items, date) = self.fetch_all_items(&query, page_size).await?;
        Ok((
            items
                .iter()
                .map(|item| self.parse_single_album(item))
                .collect(),
            date,
        ))
    }

    pub async fn get_artists_paginated(
        &self,
        user_id: &str,
        since_date: Option<&str>,
        page_size: usize,
    ) -> AppResult<(Vec<Artist>, Option<String>)> {
        let mut query = format!(
            "/Items?userId={user_id}&IncludeItemTypes=MusicArtist&Recursive=true&Fields=ImageTags,Overview,ProviderIds,CommunityRating,DateLastModified"
        );
        append_incremental_date_filter(&mut query, since_date);
        let (items, date) = self.fetch_all_items(&query, page_size).await?;
        Ok((
            items
                .iter()
                .map(|item| self.parse_single_artist(item))
                .collect::<AppResult<_>>()?,
            date,
        ))
    }

    pub async fn get_all_item_ids(&self, user_id: &str, item_type: &str) -> AppResult<Vec<String>> {
        let query =
            format!("/Items?userId={user_id}&IncludeItemTypes={item_type}&Recursive=true&Fields=");
        let (items, _) = self.fetch_all_items(&query, 2000).await?;
        Ok(items
            .iter()
            .map(|item| item["Id"].as_str().expect("validated page ID").to_owned())
            .collect())
    }

    /// Use the first response timestamp; a missing server clock triggers an overlapping refresh.
    pub fn parse_server_date(date_header: Option<&str>) -> String {
        date_header
            .and_then(|header| chrono::DateTime::parse_from_rfc2822(header).ok())
            .map(|date| date.to_rfc3339())
            .unwrap_or_else(|| "1970-01-01T00:00:00Z".into())
    }
}
