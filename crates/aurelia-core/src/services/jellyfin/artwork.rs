use super::*;
use crate::models::{AnimatedArtwork, AnimatedArtworkVariant};
use reqwest::Url;
use serde::Deserialize;

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ArtworkResponse {
    api_version: u32,
    #[serde(flatten)]
    artwork: AnimatedArtwork,
}

impl JellyfinClient {
    /// A missing plugin or missing album animation is an ordinary static-cover fallback.
    pub async fn get_animated_artwork(&self, item_id: &str) -> AppResult<Option<AnimatedArtwork>> {
        let url = utils::build_jellyfin_url(
            &self.server_url,
            &format!("/AnimatedArtwork/Items/{}", urlencoding::encode(item_id)),
        );
        let response = self
            .client
            .get(url)
            .header("Authorization", self.get_auth_header())
            .timeout(std::time::Duration::from_secs(10))
            .send()
            .await?;
        if response.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        response.error_for_status_ref()?;
        let response: ArtworkResponse = response.json().await?;
        if response.api_version != 1 {
            return Ok(None);
        }
        let base = Url::parse(&format!("{}/", self.server_url.trim_end_matches('/')))
            .map_err(|_| AppError::Config("Invalid artwork server URL".into()))?;
        let mut artwork = response.artwork;
        artwork.square = artwork.square.and_then(|v| validate_variant(&base, v));
        artwork.tall = artwork.tall.and_then(|v| validate_variant(&base, v));
        Ok((artwork.square.is_some() || artwork.tall.is_some()).then_some(artwork))
    }
}

fn validate_variant(
    base: &Url,
    mut variant: AnimatedArtworkVariant,
) -> Option<AnimatedArtworkVariant> {
    let url = base.join(&variant.url).ok()?;
    // Native clients attach the session header to this URL. Never send it to another origin.
    if !matches!(url.scheme(), "http" | "https")
        || url.origin() != base.origin()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || variant.content_type != "video/mp4"
        || !(1..=64 * 1024 * 1024).contains(&variant.bytes)
        || variant.width == 0
        || variant.height == 0
        || !variant.duration.is_finite()
        || !(0.0..=90.0).contains(&variant.duration)
        || variant.duration == 0.0
        || variant.sha256.len() != 64
        || !variant.sha256.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return None;
    }
    variant.url = url.into();
    variant.sha256.make_ascii_lowercase();
    Some(variant)
}
