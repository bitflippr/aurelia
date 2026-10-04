//! A playable song, as the queue and the player need it.

#[derive(Clone, Debug, PartialEq)]
pub struct Track {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub album_id: Option<String>,
    pub artwork_id: Option<String>,
    pub container: Option<String>,
    pub duration_seconds: u32,
    /// Unused by the runtime; kept so existing queue fixtures construct it.
    pub art_color: u32,
}
