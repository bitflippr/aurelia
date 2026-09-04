//! Authentication-related data models

use serde::{Deserialize, Serialize};

/// Supported backend providers.
#[derive(Default, Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum BackendProvider {
    #[default]
    Jellyfin,
}

pub const fn default_backend_provider() -> BackendProvider {
    BackendProvider::Jellyfin
}

/// Authentication request payload.
#[derive(Serialize, Deserialize, Debug, Clone, uniffi::Record)]
pub struct AuthRequest {
    pub provider: BackendProvider,
    #[serde(rename = "serverUrl")]
    pub server_url: String,
    pub username: String,
    pub password: String,
    #[serde(rename = "deviceId")]
    pub device_id: String,
}

/// Response from successful login
#[derive(Serialize, Deserialize, Debug, uniffi::Record)]
pub struct LoginResponse {
    /// User authentication token
    pub token: String,
    /// User ID
    #[serde(rename = "userId")]
    pub user_id: String,
}

/// User credentials for backend authentication
#[derive(Serialize, Deserialize, Debug, Clone, uniffi::Record)]
pub struct Credentials {
    #[serde(default = "default_backend_provider")]
    pub provider: BackendProvider,
    /// Backend server URL
    #[serde(rename = "serverUrl")]
    pub server_url: String,
    /// Username
    pub username: String,
    /// Authentication token
    pub token: String,
    /// User ID
    #[serde(rename = "userId")]
    pub user_id: String,
}

/// Jellyfin user information
#[derive(Serialize, Deserialize, Debug)]
pub struct JellyfinUser {
    /// User ID
    #[serde(rename = "Id")]
    pub id: String,
}

/// Jellyfin authentication response
#[derive(Serialize, Deserialize, Debug)]
pub struct JellyfinAuthResponse {
    /// Access token for API calls
    #[serde(rename = "AccessToken")]
    pub access_token: String,
    /// User information
    #[serde(rename = "User")]
    pub user: JellyfinUser,
}
