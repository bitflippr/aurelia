use serde::Deserialize;

/// A validated, authenticated media URL supplied by the optional artwork plugin.
#[derive(Debug, Clone, Deserialize, uniffi::Record)]
#[serde(rename_all = "PascalCase")]
pub struct AnimatedArtworkVariant {
    pub url: String,
    pub content_type: String,
    pub sha256: String,
    pub bytes: u64,
    pub width: u32,
    pub height: u32,
    pub duration: f64,
}

#[derive(Debug, Clone, Deserialize, uniffi::Record)]
#[serde(rename_all = "PascalCase")]
pub struct AnimatedArtwork {
    pub album_id: String,
    pub square: Option<AnimatedArtworkVariant>,
    pub tall: Option<AnimatedArtworkVariant>,
}
