//! Jellyfin sign-in and the per-profile library location.

use std::{
    fs,
    path::{Path, PathBuf},
};

use aurelia_core::models::{AuthRequest, BackendProvider, Credentials};
use serde::Serialize;
use url::Url;
use uuid::Uuid;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub server_url: String,
    pub username: String,
    pub token: String,
    pub user_id: String,
    #[serde(skip)]
    pub library_dir: String,
}

pub fn normalize_server_url(raw: &str) -> Result<String, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err("Enter your Jellyfin server URL".into());
    }

    let lowercase = trimmed.to_ascii_lowercase();
    let candidate = if lowercase.starts_with("http://") || lowercase.starts_with("https://") {
        trimmed.to_string()
    } else if trimmed.contains("://") {
        return Err("Server URL must use http or https".into());
    } else {
        format!("https://{trimmed}")
    };
    let mut url = Url::parse(&candidate).map_err(|_| "Enter a valid server URL".to_string())?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err("Server URL must use http or https".into());
    }
    if url.query().is_some() || url.fragment().is_some() {
        return Err("Server URL cannot include a query or fragment".into());
    }

    let trimmed_path = url.path().trim_end_matches('/').to_string();
    url.set_path(&trimmed_path);
    let normalized = url.to_string();
    Ok(if trimmed_path.is_empty() {
        normalized.trim_end_matches('/').to_string()
    } else {
        normalized
    })
}

fn stable_checksum(value: &str) -> u64 {
    value
        .as_bytes()
        .iter()
        .fold(1_469_598_103_934_665_603, |hash, byte| {
            (hash ^ u64::from(*byte)).wrapping_mul(1_099_511_628_211)
        })
}

pub fn profile_library_dir(base_dir: &Path, server_url: &str, username: &str) -> PathBuf {
    let profile_id = format!(
        "jellyfin|{}|{}",
        username.trim().to_lowercase(),
        server_url.trim().to_lowercase()
    );
    base_dir
        .join("profiles")
        .join(format!("jellyfin-{:x}", stable_checksum(&profile_id)))
}

fn desktop_device_id(base_dir: &Path) -> Result<String, String> {
    let path = base_dir.join("desktop-device-id");
    if let Ok(existing) = fs::read_to_string(&path) {
        let existing = existing.trim();
        if !existing.is_empty() {
            return Ok(existing.to_string());
        }
    }

    let id = Uuid::new_v4().to_string();
    fs::write(path, &id).map_err(|error| format!("Could not save the device ID: {error}"))?;
    Ok(id)
}

pub fn session_from_credentials(base_dir: &Path, credentials: Credentials) -> Session {
    let library_dir = profile_library_dir(base_dir, &credentials.server_url, &credentials.username);
    Session {
        server_url: credentials.server_url,
        username: credentials.username,
        token: credentials.token,
        user_id: credentials.user_id,
        library_dir: library_dir.to_string_lossy().into_owned(),
    }
}

pub fn saved_session(base_dir: &Path) -> Option<Session> {
    aurelia_core::load_credentials(base_dir.to_string_lossy().into_owned())
        .ok()
        .flatten()
        .map(|credentials| session_from_credentials(base_dir, credentials))
}

pub async fn sign_in(
    server_url: &str,
    username: &str,
    password: String,
    base_dir: PathBuf,
) -> Result<Session, String> {
    let server_url = normalize_server_url(server_url)?;
    let username = username.trim().to_string();
    if username.is_empty() {
        return Err("Enter your username".into());
    }
    if password.is_empty() {
        return Err("Enter your password".into());
    }

    let response = aurelia_core::authenticate(AuthRequest {
        provider: BackendProvider::Jellyfin,
        server_url: server_url.clone(),
        username: username.clone(),
        password,
        device_id: desktop_device_id(&base_dir)?,
    })
    .await
    .map_err(|error| error.to_string())?;

    let credentials = Credentials {
        provider: BackendProvider::Jellyfin,
        server_url,
        username,
        token: response.token,
        user_id: response.user_id,
    };
    aurelia_core::save_credentials(base_dir.to_string_lossy().into_owned(), credentials.clone())
        .map_err(|error| error.to_string())?;
    Ok(session_from_credentials(&base_dir, credentials))
}

pub fn sign_out(base_dir: &Path) {
    let _ = aurelia_core::clear_credentials(base_dir.to_string_lossy().into_owned());
}

#[cfg(test)]
mod view_tests {
    use super::*;

    #[test]
    fn server_urls_are_normalized_like_the_mobile_clients() {
        assert_eq!(
            normalize_server_url(" music.example.com/ "),
            Ok("https://music.example.com".into())
        );
        assert_eq!(
            normalize_server_url("http://localhost:8096/jellyfin///"),
            Ok("http://localhost:8096/jellyfin".into())
        );
        assert_eq!(
            normalize_server_url("HTTPS://music.example.com/"),
            Ok("https://music.example.com".into())
        );
        assert!(normalize_server_url("ftp://music.example.com").is_err());
        assert!(normalize_server_url("https://music.example.com?token=nope").is_err());
    }

    #[test]
    fn profile_cache_path_is_stable_and_account_specific() {
        let base = Path::new("/tmp/aurelia-test");
        let first = profile_library_dir(base, "https://music.example.com", "Marshall");
        let repeated = profile_library_dir(base, "https://music.example.com", "Marshall");
        let other = profile_library_dir(base, "https://music.example.com", "Someone Else");
        let profiles_dir = base.join("profiles");

        assert_eq!(first, repeated);
        assert_ne!(first, other);
        assert_eq!(first.parent(), Some(profiles_dir.as_path()));
    }
}
