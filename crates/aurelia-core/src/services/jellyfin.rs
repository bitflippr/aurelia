//! Jellyfin API service client

mod artwork;
mod base;
mod incremental;
mod mapping;
mod pagination;
mod playback;
mod playlists;

use crate::error::{AppError, AppResult};
use crate::models::{
    Artist, NameIdPair, Song,
    auth::{JellyfinAuthResponse, LoginResponse},
    jellyfin::{ClientCapabilities, JellyfinBackgroundCues, JellyfinLyrics},
};
use crate::utils;
use crate::utils::error_handling;
use incremental::append_incremental_date_filter;
use reqwest::Client;
use serde_json;
use std::collections::HashMap;
use tracing::{debug, error, info};

/// Paginated response from Jellyfin Items endpoint
pub struct PaginatedResponse {
    pub items: Vec<serde_json::Value>,
    pub total_record_count: usize,
    /// The Date header from the server response (for clock-skew-safe sync)
    pub server_date: Option<String>,
}

/// Jellyfin API client
pub struct JellyfinClient {
    client: Client,
    server_url: String,
    token: Option<String>,
}
