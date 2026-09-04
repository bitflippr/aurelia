use crate::queue::PlaybackQueue;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum Destination {
    #[default]
    Home,
    Songs,
    Albums,
    Artists,
    Playlists,
    Favorites,
    RecentlyAdded,
}

impl Destination {
    pub const fn is_available(self) -> bool {
        matches!(self, Self::Home | Self::Songs)
    }

    pub const LIBRARY: [Self; 5] = [
        Self::Home,
        Self::Songs,
        Self::Albums,
        Self::Artists,
        Self::Playlists,
    ];

    pub const COLLECTION: [Self; 2] = [Self::Favorites, Self::RecentlyAdded];

    pub const fn label(self) -> &'static str {
        match self {
            Self::Home => "Home",
            Self::Songs => "Songs",
            Self::Albums => "Albums",
            Self::Artists => "Artists",
            Self::Playlists => "Playlists",
            Self::Favorites => "Favorites",
            Self::RecentlyAdded => "Recently added",
        }
    }
}

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
    pub art_color: u32,
}

impl Track {
    pub fn duration_label(&self) -> String {
        format!(
            "{}:{:02}",
            self.duration_seconds / 60,
            self.duration_seconds % 60
        )
    }

    pub fn initials(&self) -> String {
        self.album
            .split_whitespace()
            .filter_map(|part| part.chars().next())
            .take(2)
            .collect::<String>()
            .to_uppercase()
    }
}

#[derive(Debug)]
pub struct DesktopState {
    pub destination: Destination,
    pub sidebar_collapsed: bool,
    pub query: String,
    pub queue: PlaybackQueue,
    pub is_playing: bool,
    pub elapsed_seconds: u32,
    pub volume_percent: u8,
    pub tracks: Vec<Track>,
}

impl Default for DesktopState {
    fn default() -> Self {
        Self {
            destination: Destination::Home,
            sidebar_collapsed: false,
            query: String::new(),
            queue: PlaybackQueue::default(),
            is_playing: false,
            elapsed_seconds: 0,
            volume_percent: 72,
            tracks: mock_tracks(),
        }
    }
}

impl DesktopState {
    pub fn replace_library(&mut self, tracks: Vec<Track>) {
        self.tracks = tracks;
        self.queue.clear();
        self.elapsed_seconds = 0;
        self.is_playing = false;
        self.query.clear();
        self.destination = Destination::Home;
    }
}

impl DesktopState {
    pub fn toggle_sidebar(&mut self) {
        self.sidebar_collapsed = !self.sidebar_collapsed;
    }

    pub fn filtered_track_indices(&self) -> Vec<usize> {
        let query = self.query.trim().to_lowercase();
        self.tracks
            .iter()
            .enumerate()
            .filter(|(_, track)| {
                query.is_empty()
                    || track.title.to_lowercase().contains(&query)
                    || track.artist.to_lowercase().contains(&query)
                    || track.album.to_lowercase().contains(&query)
            })
            .map(|(index, _)| index)
            .collect()
    }

    pub fn seek_by(&mut self, seconds: i32) {
        let Some(track) = self.queue.current().map(|entry| &entry.track) else {
            return;
        };
        let duration = track.duration_seconds as i32;
        self.elapsed_seconds = (self.elapsed_seconds as i32 + seconds).clamp(0, duration) as u32;
    }

    pub fn change_volume(&mut self, delta: i8) {
        self.volume_percent = (self.volume_percent as i16 + delta as i16).clamp(0, 100) as u8;
    }
}

fn mock_tracks() -> Vec<Track> {
    [
        ("1", "Afterglow", "Maya Sol", "Violet Hours", 244, 0x8068d8),
        (
            "2",
            "Northbound",
            "Harbor Lines",
            "Night Transit",
            218,
            0x3f6688,
        ),
        (
            "3",
            "Static Bloom",
            "Juniper Vale",
            "Soft Signals",
            196,
            0xc16888,
        ),
        (
            "4",
            "Glasshouse",
            "Low Weather",
            "Borrowed Light",
            281,
            0xa36145,
        ),
        ("5", "Second Sun", "Maya Sol", "Violet Hours", 232, 0x8068d8),
        (
            "6",
            "Side Streets",
            "Paper Atlas",
            "Anywhere Else",
            207,
            0x4b806d,
        ),
        (
            "7",
            "Understory",
            "Juniper Vale",
            "Soft Signals",
            265,
            0xc16888,
        ),
        (
            "8",
            "Blue Hour",
            "Harbor Lines",
            "Night Transit",
            226,
            0x3f6688,
        ),
    ]
    .into_iter()
    .map(
        |(id, title, artist, album, duration_seconds, art_color)| Track {
            id: id.into(),
            title: title.into(),
            artist: artist.into(),
            album: album.into(),
            album_id: None,
            artwork_id: None,
            container: Some("flac".into()),
            duration_seconds,
            art_color,
        },
    )
    .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_matches_title_artist_and_album_case_insensitively() {
        let mut state = DesktopState {
            query: "GLaSS".into(),
            ..DesktopState::default()
        };
        assert_eq!(state.filtered_track_indices(), vec![3]);

        state.query = "maya".into();
        assert_eq!(state.filtered_track_indices(), vec![0, 4]);

        state.query = "soft signals".into();
        assert_eq!(state.filtered_track_indices(), vec![2, 6]);
    }

    #[test]
    fn queue_selection_drives_seek_bounds() {
        let mut state = DesktopState::default();
        state.queue.replace(state.tracks.clone(), 3);
        state.seek_by(10_000);
        assert_eq!(state.elapsed_seconds, state.tracks[3].duration_seconds);
    }

    #[test]
    fn seek_and_volume_are_clamped() {
        let mut state = DesktopState::default();
        state.queue.replace(state.tracks.clone(), 0);
        state.seek_by(10_000);
        assert_eq!(
            state.elapsed_seconds,
            state.queue.current().unwrap().track.duration_seconds
        );
        state.seek_by(-10_000);
        assert_eq!(state.elapsed_seconds, 0);

        state.change_volume(100);
        assert_eq!(state.volume_percent, 100);
        state.change_volume(-120);
        assert_eq!(state.volume_percent, 0);
    }

    #[test]
    fn sidebar_toggle_round_trips() {
        let mut state = DesktopState::default();
        assert!(!state.sidebar_collapsed);

        state.toggle_sidebar();
        assert!(state.sidebar_collapsed);

        state.toggle_sidebar();
        assert!(!state.sidebar_collapsed);
    }
}
