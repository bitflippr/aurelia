mod artwork;
mod playback;
mod queue;
mod state;
mod text_input;
mod theme;

use std::{
    borrow::Cow,
    fs,
    ops::Range,
    path::{Path, PathBuf},
    sync::{Arc, OnceLock},
    time::Duration,
};

use artwork::{ArtworkCache, DEFAULT_ARTWORK_CACHE_CAPACITY};
use aurelia_core::models::{AuthRequest, BackendProvider, Credentials, Song};
use gpui::{
    Anchor, Animation, AnimationExt as _, AnyElement, App, Bounds, Context, Div, ElementId, Entity,
    FocusHandle, FontWeight, Image, ImageFormat, IntoElement, KeyBinding, KeyDownEvent,
    MouseButton, MouseDownEvent, MouseMoveEvent, MouseUpEvent, ObjectFit, Pixels, Point, Rgba,
    Role, ScrollStrategy, SharedString, SpringAnimation, SpringConfig, Stateful, StyledImage as _,
    Transformation, UniformListScrollHandle, Window, WindowBounds, WindowOptions, actions,
    anchored, bounce, deferred, div, ease_in_out, ease_out_quint, img, percentage, point,
    prelude::*, px, rgb, size, svg, uniform_list,
};
use gpui_platform::application;
use lucide_icons::{Icon, LUCIDE_FONT_BYTES};
use playback::{PlaybackController, PlaybackItem};
use queue::{AdvanceReason, QueueEntryId, RemoveOutcome, RepeatMode};
use reqwest_client::ReqwestClient;
use state::{DesktopState, Destination, Track};
use text_input::TextInput;
use theme::*;
use url::Url;
use uuid::Uuid;

actions!(aurelia_desktop, [Tab, TabPrev, FocusSearch, ToggleSidebar]);

const SIDEBAR_SPRING: SpringConfig = SpringConfig::new(400.0, 40.0, 1.0);
const FOCUS_SPRING: SpringConfig = SpringConfig::new(320.0, 34.0, 1.0);
const PROGRESS_SPRING: SpringConfig = SpringConfig::new(220.0, 28.0, 1.0);
const TRACK_ROW_HEIGHT: f32 = 60.0;

const LOADER_SVG: &[u8] = br#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>"#;

fn sidebar_animation(target: f32) -> SpringAnimation<f32> {
    SpringAnimation::new(SIDEBAR_SPRING)
        .to(target)
        .with_epsilon(0.001)
}

fn mix_color(from: Rgba, to: Rgba, phase: f32) -> Rgba {
    let phase = phase.clamp(0.0, 1.0);
    Rgba {
        r: from.r + (to.r - from.r) * phase,
        g: from.g + (to.g - from.g) * phase,
        b: from.b + (to.b - from.b) * phase,
        a: from.a + (to.a - from.a) * phase,
    }
}

fn loading_spinner(id: &'static str, size_px: f32, color: Rgba) -> impl IntoElement {
    svg()
        .data(LOADER_SVG)
        .size(px(size_px))
        .text_color(color)
        .with_animation(
            id,
            Animation::new(Duration::from_millis(850)).repeat(),
            |spinner, phase| spinner.with_transformation(Transformation::rotate(percentage(phase))),
        )
}

fn animated_loading_label() -> impl IntoElement {
    div().with_animation(
        "connecting-label-dots",
        Animation::new(Duration::from_millis(900))
            .repeat()
            .with_max_fps(4.0),
        |label, phase| {
            let dots = ((phase * 4.0).floor() as usize).min(3);
            label.child(format!("Connecting{}", ".".repeat(dots)))
        },
    )
}

fn icon(icon: Icon, size_px: f32) -> Div {
    div()
        .size(px(24.0))
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .font_family("lucide")
        .text_size(px(size_px))
        .line_height(px(24.0))
        .child(char::from(icon).to_string())
}

fn destination_icon(destination: Destination) -> Icon {
    match destination {
        Destination::Home => Icon::House,
        Destination::Songs => Icon::Music2,
        Destination::Albums => Icon::Disc3,
        Destination::Artists => Icon::UsersRound,
        Destination::Playlists => Icon::ListMusic,
        Destination::Favorites => Icon::Heart,
        Destination::RecentlyAdded => Icon::Clock3,
    }
}

fn aurelia_logo(size_px: f32) -> impl IntoElement {
    static LOGO: OnceLock<Arc<Image>> = OnceLock::new();
    let image = LOGO.get_or_init(|| {
        Arc::new(Image::from_bytes(
            ImageFormat::Png,
            include_bytes!(
                "../../mobile/ios/Aurelia/Assets.xcassets/AppIcon.appiconset/ios-dark-1024@1x.png"
            )
            .to_vec(),
        ))
    });

    img(image.clone()).size(px(size_px)).flex_none()
}

fn should_dismiss_account_menu(
    account_button_bounds: Option<Bounds<Pixels>>,
    pointer_position: Point<Pixels>,
) -> bool {
    !account_button_bounds.is_some_and(|bounds| bounds.contains(&pointer_position))
}

#[derive(Clone)]
struct DraggedQueueEntry {
    id: QueueEntryId,
    source_index: usize,
    title: SharedString,
    artist: SharedString,
}

impl Render for DraggedQueueEntry {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        div()
            .w(px(260.0))
            .h(px(52.0))
            .px_3()
            .flex()
            .items_center()
            .gap_3()
            .rounded_xl()
            .border_1()
            .border_color(PRIMARY)
            .bg(SURFACE)
            .shadow_lg()
            .text_color(TEXT_MUTED)
            .child(icon(Icon::GripVertical, 17.0))
            .child(
                div()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_sm()
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(TEXT)
                            .child(self.title.clone()),
                    )
                    .child(div().truncate().text_xs().child(self.artist.clone())),
            )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum AppStage {
    Login,
    Syncing,
    Home,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum LoginField {
    Server,
    Username,
    Password,
}

#[derive(Clone, Debug, Default)]
struct LoginForm {
    server_url: String,
    username: String,
    password: String,
    is_connecting: bool,
    error: Option<String>,
}

#[derive(Clone, Debug)]
struct Session {
    server_url: String,
    username: String,
    token: String,
    user_id: String,
    library_dir: String,
}

#[derive(Clone, Debug)]
struct SyncStatus {
    stage: String,
    current: u32,
    total: u32,
    error: Option<String>,
}

impl Default for SyncStatus {
    fn default() -> Self {
        Self {
            stage: "Preparing your library".into(),
            current: 0,
            total: 0,
            error: None,
        }
    }
}

fn normalize_server_url(raw: &str) -> Result<String, String> {
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

fn profile_library_dir(base_dir: &Path, server_url: &str, username: &str) -> PathBuf {
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

fn session_from_credentials(base_dir: &Path, credentials: Credentials) -> Session {
    let library_dir = profile_library_dir(base_dir, &credentials.server_url, &credentials.username);
    Session {
        server_url: credentials.server_url,
        username: credentials.username,
        token: credentials.token,
        user_id: credentials.user_id,
        library_dir: library_dir.to_string_lossy().into_owned(),
    }
}

async fn authenticate_login(form: LoginForm, base_dir: PathBuf) -> Result<Session, String> {
    let server_url = normalize_server_url(&form.server_url)?;
    let username = form.username.trim().to_string();
    if username.is_empty() {
        return Err("Enter your username".into());
    }
    if form.password.is_empty() {
        return Err("Enter your password".into());
    }

    let response = aurelia_core::authenticate(AuthRequest {
        provider: BackendProvider::Jellyfin,
        server_url: server_url.clone(),
        username: username.clone(),
        password: form.password,
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

fn track_color(id: &str) -> u32 {
    const COLORS: [u32; 8] = [
        0x8068d8, 0x3f6688, 0xc16888, 0xa36145, 0x4b806d, 0x6f74a8, 0x9b6a82, 0x527d87,
    ];
    COLORS[stable_checksum(id) as usize % COLORS.len()]
}

fn map_song(song: Song) -> Track {
    let artwork_id = song
        .album_art_url
        .as_ref()
        .map(|_| song.album_id.clone().unwrap_or_else(|| song.id.clone()));
    let artist = song
        .artists
        .filter(|artists| !artists.is_empty())
        .map(|artists| artists.join(", "))
        .unwrap_or_else(|| "Unknown artist".into());
    Track {
        art_color: track_color(&song.id),
        id: song.id,
        title: song.name,
        artist,
        album: song.album.unwrap_or_else(|| "Unknown album".into()),
        album_id: song.album_id,
        artwork_id,
        container: song.container,
        duration_seconds: song.duration.unwrap_or_default().max(0.0).round() as u32,
    }
}

async fn sync_session(session: Session) -> Result<Vec<Track>, String> {
    fs::create_dir_all(&session.library_dir)
        .map_err(|error| format!("Could not create the library cache: {error}"))?;
    aurelia_core::sync_library_smart(
        session.server_url.clone(),
        session.token.clone(),
        session.user_id.clone(),
        session.library_dir.clone(),
    )
    .await
    .map_err(|error| error.to_string())?;
    aurelia_core::sync_favorites(
        session.server_url,
        session.token,
        session.user_id,
        session.library_dir.clone(),
    )
    .await
    .map_err(|error| error.to_string())?;
    aurelia_core::load_cached_songs(session.library_dir)
        .map(|songs| songs.into_iter().map(map_song).collect())
        .map_err(|error| error.to_string())
}

struct AureliaDesktop {
    stage: AppStage,
    login: LoginForm,
    sync_status: SyncStatus,
    session: Option<Session>,
    base_data_dir: PathBuf,
    state: DesktopState,
    root_focus: FocusHandle,
    search_input: Entity<TextInput>,
    server_input: Entity<TextInput>,
    username_input: Entity<TextInput>,
    password_input: Entity<TextInput>,
    visible_track_indices: Vec<usize>,
    track_scroll_handle: UniformListScrollHandle,
    queue_scroll_handle: UniformListScrollHandle,
    artwork_cache: Entity<ArtworkCache>,
    playback: PlaybackController,
    playback_loading: bool,
    playback_error: Option<String>,
    playback_request_id: u64,
    account_menu_open: bool,
    account_button_bounds: Option<Bounds<Pixels>>,
    queue_open: bool,
    seek_bounds: Option<Bounds<Pixels>>,
    seek_dragging: bool,
    seek_in_flight: bool,
    seek_request_id: u64,
}

impl AureliaDesktop {
    fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let root_focus = cx.focus_handle();
        let search_input =
            cx.new(|cx| TextInput::new("Search artists, albums, and songs", false, cx));
        let server_input = cx.new(|cx| TextInput::new("https://music.example.com", false, cx));
        let username_input = cx.new(|cx| TextInput::new("Your Jellyfin username", false, cx));
        let password_input = cx.new(|cx| TextInput::new("Your Jellyfin password", true, cx));
        let base_data_dir = aurelia_core::utils::ensure_app_data_dir()
            .unwrap_or_else(|_| std::env::temp_dir().join("aurelia-desktop"));
        let credentials =
            aurelia_core::load_credentials(base_data_dir.to_string_lossy().into_owned())
                .ok()
                .flatten();
        let session =
            credentials.map(|credentials| session_from_credentials(&base_data_dir, credentials));
        let stage = if session.is_some() {
            AppStage::Syncing
        } else {
            AppStage::Login
        };

        let mut state = DesktopState::default();
        if let Ok(Some(saved_volume)) = aurelia_core::load_setting(
            base_data_dir.to_string_lossy().into_owned(),
            "desktop-volume".into(),
        ) && let Ok(saved_volume) = saved_volume.parse::<f32>()
        {
            state.volume_percent = (saved_volume.clamp(0.0, 1.0) * 100.0).round() as u8;
        }
        let visible_track_indices = state.filtered_track_indices();
        let artwork_cache = cx.new(|_| ArtworkCache::new(DEFAULT_ARTWORK_CACHE_CAPACITY));
        let mut desktop = Self {
            stage,
            login: LoginForm::default(),
            sync_status: SyncStatus::default(),
            session,
            base_data_dir,
            state,
            root_focus,
            search_input,
            server_input,
            username_input,
            password_input,
            visible_track_indices,
            track_scroll_handle: UniformListScrollHandle::new(),
            queue_scroll_handle: UniformListScrollHandle::new(),
            artwork_cache,
            playback: PlaybackController::new(),
            playback_loading: false,
            playback_error: None,
            playback_request_id: 0,
            account_menu_open: false,
            account_button_bounds: None,
            queue_open: false,
            seek_bounds: None,
            seek_dragging: false,
            seek_in_flight: false,
            seek_request_id: 0,
        };

        cx.observe(&desktop.search_input, |this, input, cx| {
            let query = input.read(cx).text();
            if this.state.query != query {
                this.state.query = query;
                this.refresh_main_list();
                cx.notify();
            }
        })
        .detach();
        cx.observe(&desktop.artwork_cache, |_, _, cx| cx.notify())
            .detach();

        if desktop.stage == AppStage::Syncing {
            window.focus(&desktop.root_focus, cx);
            desktop.start_sync(cx);
        } else {
            window.focus(&desktop.server_input.read(cx).focus_handle(), cx);
        }
        desktop.start_playback_poller(cx);
        desktop
    }

    fn start_login(&mut self, cx: &mut Context<Self>) {
        if self.login.is_connecting {
            return;
        }
        self.login.server_url = self.server_input.read(cx).text();
        self.login.username = self.username_input.read(cx).text();
        self.login.password = self.password_input.read(cx).text();
        if let Err(error) = normalize_server_url(&self.login.server_url) {
            self.login.error = Some(error);
            cx.notify();
            return;
        }
        if self.login.username.trim().is_empty() {
            self.login.error = Some("Enter your username".into());
            cx.notify();
            return;
        }
        if self.login.password.is_empty() {
            self.login.error = Some("Enter your password".into());
            cx.notify();
            return;
        }

        self.login.is_connecting = true;
        self.login.error = None;
        let form = self.login.clone();
        let base_dir = self.base_data_dir.clone();
        let task = gpui_tokio::Tokio::spawn(cx, authenticate_login(form, base_dir));
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(session)) => {
                    this.login.is_connecting = false;
                    this.login.password.clear();
                    this.password_input.update(cx, |input, cx| input.clear(cx));
                    this.session = Some(session);
                    this.sync_status = SyncStatus::default();
                    this.stage = AppStage::Syncing;
                    this.start_sync(cx);
                    cx.notify();
                }
                Ok(Err(error)) => {
                    this.login.is_connecting = false;
                    this.login.error = Some(error);
                    cx.notify();
                }
                Err(error) => {
                    this.login.is_connecting = false;
                    this.login.error = Some(format!("Login task failed: {error}"));
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn refresh_main_list(&mut self) {
        self.visible_track_indices = self.state.filtered_track_indices();
        if !self.visible_track_indices.is_empty() {
            self.track_scroll_handle
                .scroll_to_item_strict(0, ScrollStrategy::Top);
        }
    }

    fn playback_item(&self, track: &Track) -> Option<PlaybackItem> {
        let session = self.session.as_ref()?;
        Some(PlaybackItem {
            server_url: session.server_url.clone(),
            token: session.token.clone(),
            id: track.id.clone(),
            title: track.title.clone(),
            artist: track.artist.clone(),
            album: track.album.clone(),
            album_id: track.album_id.clone(),
            container: track.container.clone(),
            duration_seconds: track.duration_seconds,
        })
    }

    fn start_track(&mut self, index: usize, cx: &mut Context<Self>) {
        if index >= self.state.tracks.len() {
            return;
        }

        let queue_indices = if self.visible_track_indices.contains(&index) {
            self.visible_track_indices.clone()
        } else {
            (0..self.state.tracks.len()).collect()
        };
        let start_index = queue_indices
            .iter()
            .position(|candidate| *candidate == index)
            .unwrap_or(0);
        let tracks = queue_indices
            .into_iter()
            .filter_map(|index| self.state.tracks.get(index).cloned())
            .collect();
        self.state.queue.replace(tracks, start_index);
        self.start_current_queue_entry(cx);
    }

    fn play_track_next(&mut self, index: usize, cx: &mut Context<Self>) {
        let Some(track) = self.state.tracks.get(index).cloned() else {
            return;
        };
        let should_start = self.state.queue.is_empty();
        self.state.queue.play_next(track);
        if should_start {
            self.start_current_queue_entry(cx);
        } else {
            self.refresh_prepared_next(cx);
            cx.notify();
        }
    }

    fn add_track_to_queue(&mut self, index: usize, cx: &mut Context<Self>) {
        let Some(track) = self.state.tracks.get(index).cloned() else {
            return;
        };
        let should_start = self.state.queue.is_empty();
        self.state.queue.add_to_end(track);
        if should_start {
            self.start_current_queue_entry(cx);
        } else {
            self.refresh_prepared_next(cx);
            cx.notify();
        }
    }

    fn start_queue_entry(&mut self, id: QueueEntryId, cx: &mut Context<Self>) {
        if self.state.queue.select(id) {
            self.start_current_queue_entry(cx);
        }
    }

    fn toggle_queue(&mut self, cx: &mut Context<Self>) {
        self.queue_open = !self.queue_open;
        if self.queue_open
            && let Some(index) = self.state.queue.current_index()
        {
            self.queue_scroll_handle
                .scroll_to_item_strict(index, ScrollStrategy::Center);
        }
        cx.notify();
    }

    fn move_queue_entry(&mut self, id: QueueEntryId, target_index: usize, cx: &mut Context<Self>) {
        if self.state.queue.move_entry(id, target_index) {
            self.refresh_prepared_next(cx);
            cx.notify();
        }
    }

    fn move_queue_entry_by(&mut self, id: QueueEntryId, delta: isize, cx: &mut Context<Self>) {
        let Some(index) = self
            .state
            .queue
            .entries()
            .iter()
            .position(|entry| entry.id == id)
        else {
            return;
        };
        let target = index
            .saturating_add_signed(delta)
            .min(self.state.queue.len().saturating_sub(1));
        self.move_queue_entry(id, target, cx);
    }

    fn remove_queue_entry(&mut self, id: QueueEntryId, cx: &mut Context<Self>) {
        match self.state.queue.remove(id) {
            RemoveOutcome::NotFound => {}
            RemoveOutcome::Removed => {
                self.refresh_prepared_next(cx);
                cx.notify();
            }
            RemoveOutcome::CurrentChanged(Some(_)) => self.start_current_queue_entry(cx),
            RemoveOutcome::CurrentChanged(None) => {
                self.queue_open = false;
                self.stop_playback(cx);
            }
        }
    }

    fn clear_upcoming(&mut self, cx: &mut Context<Self>) {
        if self.state.queue.clear_upcoming() > 0 {
            self.refresh_prepared_next(cx);
            cx.notify();
        }
    }

    fn start_current_queue_entry(&mut self, cx: &mut Context<Self>) {
        let Some(track) = self.state.queue.current().map(|entry| entry.track.clone()) else {
            return;
        };
        if self.queue_open
            && let Some(index) = self.state.queue.current_index()
        {
            self.queue_scroll_handle
                .scroll_to_item_strict(index, ScrollStrategy::Center);
        }
        let Some(item) = self.playback_item(&track) else {
            self.playback_error = Some("Playback requires an active Jellyfin session".into());
            cx.notify();
            return;
        };

        self.playback_request_id = self.playback_request_id.wrapping_add(1);
        self.seek_request_id = self.seek_request_id.wrapping_add(1);
        self.seek_in_flight = false;
        let request_id = self.playback_request_id;
        self.state.is_playing = false;
        self.state.elapsed_seconds = 0;
        self.playback_loading = true;
        self.playback_error = None;
        let volume = f32::from(self.state.volume_percent) / 100.0;
        let playback = self.playback.clone();
        playback.begin_prepare();
        playback.begin_seek();
        let generation = playback.begin_command();
        let task =
            gpui_tokio::Tokio::spawn(
                cx,
                async move { playback.play(item, volume, generation).await },
            );
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.playback_request_id != request_id {
                    return;
                }
                this.playback_loading = false;
                match result {
                    Ok(Ok(())) => {
                        this.state.is_playing = true;
                        this.refresh_prepared_next(cx);
                    }
                    Ok(Err(error)) => {
                        this.state.is_playing = false;
                        this.playback_error = Some(error.to_string());
                    }
                    Err(error) => {
                        this.state.is_playing = false;
                        this.playback_error = Some(format!("Playback task failed: {error}"));
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn refresh_prepared_next(&mut self, cx: &mut Context<Self>) {
        if self.playback_loading {
            return;
        }
        let prepared = self
            .state
            .queue
            .next_for(AdvanceReason::TrackFinished)
            .and_then(|entry| {
                self.playback_item(&entry.track)
                    .map(|item| (entry.id.raw(), item))
            });
        let playback = self.playback.clone();
        let generation = playback.begin_prepare();
        gpui_tokio::Tokio::spawn(cx, async move {
            if let Err(error) = playback.prepare_next(prepared, generation).await {
                tracing::warn!("Could not prepare the next queue item: {error}");
            }
        })
        .detach();
    }

    fn set_playing(&mut self, should_play: bool, cx: &mut Context<Self>) {
        if self.playback_loading || self.state.queue.is_empty() {
            return;
        }
        if !self.state.is_playing && should_play && self.state.elapsed_seconds == 0 {
            self.start_current_queue_entry(cx);
            return;
        }

        self.state.is_playing = should_play;
        self.playback_error = None;
        let playback = self.playback.clone();
        let task = gpui_tokio::Tokio::spawn(cx, async move {
            if should_play {
                playback.resume().await
            } else {
                playback.pause().await
            }
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if let Ok(Err(error)) = result {
                    this.state.is_playing = !should_play;
                    this.playback_error = Some(error.to_string());
                } else if let Err(error) = result {
                    this.state.is_playing = !should_play;
                    this.playback_error = Some(format!("Playback task failed: {error}"));
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn toggle_playback(&mut self, cx: &mut Context<Self>) {
        self.set_playing(!self.state.is_playing, cx);
    }

    fn next_track(&mut self, cx: &mut Context<Self>) {
        if self.state.queue.advance(AdvanceReason::Manual).is_some() {
            self.start_current_queue_entry(cx);
        }
    }

    fn previous_track(&mut self, cx: &mut Context<Self>) {
        if self.state.queue.is_empty() {
            return;
        }
        if self.state.elapsed_seconds > 3 {
            self.seek_by(
                -i32::try_from(self.state.elapsed_seconds).unwrap_or(i32::MAX),
                cx,
            );
        } else {
            if self.state.queue.previous().is_some() {
                self.start_current_queue_entry(cx);
            } else {
                self.seek_by(
                    -i32::try_from(self.state.elapsed_seconds).unwrap_or(i32::MAX),
                    cx,
                );
            }
        }
    }

    fn seek_by(&mut self, seconds: i32, cx: &mut Context<Self>) {
        self.state.seek_by(seconds);
        self.request_seek(self.state.elapsed_seconds, cx);
    }

    fn preview_seek_to_fraction(
        &mut self,
        pointer_position: Point<Pixels>,
        cx: &mut Context<Self>,
    ) {
        let Some(bounds) = self.seek_bounds else {
            return;
        };
        let Some(duration) = self
            .state
            .queue
            .current()
            .map(|entry| entry.track.duration_seconds)
        else {
            return;
        };
        let fraction = ((pointer_position.x - bounds.origin.x) / bounds.size.width).clamp(0.0, 1.0);
        let position = (duration as f32 * fraction).round() as u32;
        self.state.elapsed_seconds = position;
        cx.notify();
    }

    fn request_seek(&mut self, position: u32, cx: &mut Context<Self>) {
        self.seek_request_id = self.seek_request_id.wrapping_add(1);
        let request_id = self.seek_request_id;
        self.seek_in_flight = true;
        let position = f64::from(position);
        let playback = self.playback.clone();
        let generation = playback.begin_seek();
        let task =
            gpui_tokio::Tokio::spawn(cx, async move { playback.seek(position, generation).await });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.seek_request_id != request_id {
                    return;
                }
                this.seek_in_flight = false;
                match result {
                    Ok(Ok(())) => this.refresh_prepared_next(cx),
                    Ok(Err(error)) => this.playback_error = Some(error.to_string()),
                    Err(error) => {
                        this.playback_error = Some(format!("Seek task failed: {error}"));
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn change_volume(&mut self, delta: i8, cx: &mut Context<Self>) {
        self.state.change_volume(delta);
        let volume_percent = self.state.volume_percent;
        let volume = f32::from(volume_percent) / 100.0;
        let _ = aurelia_core::save_setting(
            self.base_data_dir.to_string_lossy().into_owned(),
            "desktop-volume".into(),
            volume.to_string(),
        );
        let playback = self.playback.clone();
        let task = gpui_tokio::Tokio::spawn(cx, async move { playback.set_volume(volume).await });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if let Ok(Err(error)) = result {
                    this.playback_error = Some(error.to_string());
                } else if let Err(error) = result {
                    this.playback_error = Some(format!("Volume task failed: {error}"));
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn stop_playback(&mut self, cx: &mut Context<Self>) {
        self.playback_request_id = self.playback_request_id.wrapping_add(1);
        self.seek_request_id = self.seek_request_id.wrapping_add(1);
        self.seek_in_flight = false;
        self.seek_dragging = false;
        self.playback_loading = false;
        self.state.is_playing = false;
        self.state.elapsed_seconds = 0;
        let playback = self.playback.clone();
        playback.begin_seek();
        let generation = playback.begin_command();
        gpui_tokio::Tokio::spawn(cx, async move { playback.stop(generation).await }).detach();
        cx.notify();
    }

    fn start_playback_poller(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(250))
                    .await;
                let Some(playback) = this.read_with(cx, |this, _| this.playback.clone()).ok()
                else {
                    break;
                };
                let task = gpui_tokio::Tokio::spawn(cx, async move { playback.poll().await });
                let result = task.await;
                let keep_polling = this
                    .update(cx, |this, cx| {
                        if let Ok(Ok(snapshot)) = result
                            && !this.seek_dragging
                            && !this.seek_in_flight
                        {
                            if snapshot.did_auto_advance {
                                let prepared_id = snapshot.auto_advanced_token.and_then(|token| {
                                    this.state
                                        .queue
                                        .entries()
                                        .iter()
                                        .find(|entry| entry.id.raw() == token)
                                        .map(|entry| entry.id)
                                });
                                if prepared_id.is_some_and(|id| this.state.queue.select(id)) {
                                    this.state.elapsed_seconds = 0;
                                    this.state.is_playing = true;
                                    this.playback_error = None;
                                    if this.queue_open
                                        && let Some(index) = this.state.queue.current_index()
                                    {
                                        this.queue_scroll_handle
                                            .scroll_to_item_strict(index, ScrollStrategy::Center);
                                    }
                                    this.refresh_prepared_next(cx);
                                    cx.notify();
                                    return true;
                                }

                                if this
                                    .state
                                    .queue
                                    .advance(AdvanceReason::TrackFinished)
                                    .is_some()
                                {
                                    this.start_current_queue_entry(cx);
                                } else {
                                    this.stop_playback(cx);
                                }
                                return true;
                            }
                            let next_second = snapshot.position_seconds.max(0.0).round() as u32;
                            if this.state.elapsed_seconds != next_second {
                                this.state.elapsed_seconds = next_second;
                                cx.notify();
                            }
                            if !this.playback_loading
                                && this.state.is_playing != snapshot.is_playing
                            {
                                this.state.is_playing = snapshot.is_playing;
                                cx.notify();
                            }
                            if snapshot.is_finished && !this.playback_loading {
                                if this
                                    .state
                                    .queue
                                    .advance(AdvanceReason::TrackFinished)
                                    .is_some()
                                {
                                    this.start_current_queue_entry(cx);
                                } else {
                                    this.state.is_playing = false;
                                    cx.notify();
                                }
                            }
                        }

                        while let Some(event) = this.playback.pop_media_event() {
                            use aurelia_core::media_controls::MediaEvent;
                            match event {
                                MediaEvent::Play => this.set_playing(true, cx),
                                MediaEvent::Pause => this.set_playing(false, cx),
                                MediaEvent::Toggle => this.toggle_playback(cx),
                                MediaEvent::Next => this.next_track(cx),
                                MediaEvent::Previous => this.previous_track(cx),
                                MediaEvent::Stop => this.stop_playback(cx),
                                MediaEvent::SeekDelta(delta) => {
                                    this.seek_by(delta.round() as i32, cx)
                                }
                                MediaEvent::SetPosition(position) => {
                                    let delta = position.round() as i64
                                        - i64::from(this.state.elapsed_seconds);
                                    this.seek_by(
                                        delta.clamp(i64::from(i32::MIN), i64::from(i32::MAX))
                                            as i32,
                                        cx,
                                    );
                                }
                            }
                        }
                        true
                    })
                    .unwrap_or(false);
                if !keep_polling {
                    break;
                }
            }
        })
        .detach();
    }

    fn start_sync(&mut self, cx: &mut Context<Self>) {
        let Some(session) = self.session.clone() else {
            self.stage = AppStage::Login;
            return;
        };

        self.stage = AppStage::Syncing;
        self.sync_status = SyncStatus::default();
        let task = gpui_tokio::Tokio::spawn(cx, sync_session(session));
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(tracks)) => {
                    this.state.replace_library(tracks);
                    this.refresh_main_list();
                    this.stage = AppStage::Home;
                    this.sync_status = SyncStatus {
                        stage: "Library ready".into(),
                        current: 0,
                        total: 0,
                        error: None,
                    };
                    cx.notify();
                }
                Ok(Err(error)) => {
                    this.sync_status.error = Some(error);
                    cx.notify();
                }
                Err(error) => {
                    this.sync_status.error = Some(format!("Sync task failed: {error}"));
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();

        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(250))
                    .await;
                let keep_polling = this
                    .update(cx, |this, cx| {
                        if this.stage != AppStage::Syncing || this.sync_status.error.is_some() {
                            return false;
                        }
                        let Some(session) = this.session.as_ref() else {
                            return false;
                        };
                        let progress = aurelia_core::get_sync_progress(session.library_dir.clone());
                        this.sync_status.stage = if progress.is_complete {
                            "Finishing your library".into()
                        } else {
                            progress.stage
                        };
                        this.sync_status.current = progress.current;
                        this.sync_status.total = progress.total;
                        cx.notify();
                        true
                    })
                    .unwrap_or(false);
                if !keep_polling {
                    break;
                }
            }
        })
        .detach();
    }

    fn logout(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.stop_playback(cx);
        let _ = aurelia_core::clear_credentials(self.base_data_dir.to_string_lossy().into_owned());
        self.stage = AppStage::Login;
        self.session = None;
        self.login = LoginForm::default();
        self.server_input.update(cx, |input, cx| input.clear(cx));
        self.username_input.update(cx, |input, cx| input.clear(cx));
        self.password_input.update(cx, |input, cx| input.clear(cx));
        self.search_input.update(cx, |input, cx| input.clear(cx));
        self.sync_status = SyncStatus::default();
        self.state.replace_library(Vec::new());
        self.artwork_cache.update(cx, |cache, cx| cache.clear(cx));
        self.refresh_main_list();
        self.account_menu_open = false;
        window.focus(&self.server_input.read(cx).focus_handle(), cx);
        cx.notify();
    }

    fn input_for_login_field(&self, field: LoginField) -> Entity<TextInput> {
        match field {
            LoginField::Server => self.server_input.clone(),
            LoginField::Username => self.username_input.clone(),
            LoginField::Password => self.password_input.clone(),
        }
    }

    fn on_login_key_down(
        &mut self,
        field: LoginField,
        event: &KeyDownEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if event.keystroke.key != "enter" {
            return;
        }
        match field {
            LoginField::Server => window.focus(&self.username_input.read(cx).focus_handle(), cx),
            LoginField::Username => window.focus(&self.password_input.read(cx).focus_handle(), cx),
            LoginField::Password => self.start_login(cx),
        }
        self.login.error = None;
        cx.notify();
        cx.stop_propagation();
    }

    fn login_field(
        &self,
        field: LoginField,
        label: &'static str,
        field_icon: Icon,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let input = self.input_for_login_field(field);
        let focus = input.read(cx).focus_handle();
        let focused = focus.is_focused(window);
        let click_focus = focus.clone();

        div()
            .flex()
            .flex_col()
            .gap_2()
            .child(div().text_sm().font_weight(FontWeight::MEDIUM).child(label))
            .child(
                div()
                    .id(("login-field", field as usize))
                    .role(Role::TextInput)
                    .aria_label(label)
                    .track_focus(&focus)
                    .on_key_down(cx.listener(move |this, event, window, cx| {
                        this.on_login_key_down(field, event, window, cx)
                    }))
                    .on_click(move |_, window, cx| window.focus(&click_focus, cx))
                    .h(px(46.0))
                    .w_full()
                    .px_3()
                    .flex()
                    .items_center()
                    .gap_3()
                    .rounded_xl()
                    .border_1()
                    .bg(SURFACE)
                    .cursor_text()
                    .child(icon(field_icon, 18.0).text_color(if focused {
                        PRIMARY
                    } else {
                        TEXT_MUTED
                    }))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .h_full()
                            .overflow_hidden()
                            .child(input),
                    )
                    .with_spring(
                        ("login-field-focus", field as usize),
                        SpringAnimation::new(FOCUS_SPRING)
                            .to(if focused { 1.0 } else { 0.0 })
                            .with_epsilon(0.001),
                        |field, phase| field.border_color(mix_color(OUTLINE, PRIMARY, phase)),
                    ),
            )
            .into_any_element()
    }

    fn login_screen(&self, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        div()
            .size_full()
            .flex()
            .items_center()
            .justify_center()
            .bg(BACKGROUND)
            .child(
                div()
                    .w(px(420.0))
                    .p_8()
                    .flex()
                    .flex_col()
                    .gap_5()
                    .rounded_xl()
                    .border_1()
                    .border_color(OUTLINE)
                    .bg(SIDEBAR)
                    .shadow_lg()
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .items_center()
                            .gap_3()
                            .pb_2()
                            .child(aurelia_logo(54.0))
                            .child(
                                div()
                                    .text_2xl()
                                    .font_weight(FontWeight::BOLD)
                                    .child("Connect to Jellyfin"),
                            )
                            .child(
                                div()
                                    .text_sm()
                                    .text_color(TEXT_MUTED)
                                    .text_center()
                                    .child("Sign in to sync your music library with Aurelia."),
                            ),
                    )
                    .child(self.login_field(
                        LoginField::Server,
                        "Server URL",
                        Icon::Server,
                        window,
                        cx,
                    ))
                    .child(self.login_field(
                        LoginField::Username,
                        "Username",
                        Icon::UserRound,
                        window,
                        cx,
                    ))
                    .child(self.login_field(
                        LoginField::Password,
                        "Password",
                        Icon::LockKeyhole,
                        window,
                        cx,
                    ))
                    .when_some(self.login.error.clone(), |card, error| {
                        card.child(
                            div()
                                .p_3()
                                .flex()
                                .items_start()
                                .gap_2()
                                .rounded_lg()
                                .bg(translucent(PINK, 0.12))
                                .text_sm()
                                .text_color(PINK)
                                .child(icon(Icon::CircleAlert, 17.0))
                                .child(div().flex_1().child(error))
                                .with_animation(
                                    "login-error-enter",
                                    Animation::new(Duration::from_millis(180))
                                        .with_easing(ease_out_quint()),
                                    |error, phase| error.opacity(phase),
                                ),
                        )
                    })
                    .child(
                        div()
                            .id("login-submit")
                            .role(Role::Button)
                            .aria_label("Connect to Jellyfin")
                            .focusable()
                            .tab_stop(true)
                            .h(px(46.0))
                            .w_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .gap_2()
                            .rounded_xl()
                            .bg(PRIMARY)
                            .text_color(BACKGROUND)
                            .font_weight(FontWeight::SEMIBOLD)
                            .cursor_pointer()
                            .when(!self.login.is_connecting, |button| {
                                button.hover(|style| style.opacity(0.86))
                            })
                            .when(self.login.is_connecting, |button| button.opacity(0.7))
                            .when(self.login.is_connecting, |button| {
                                button
                                    .child(loading_spinner(
                                        "login-connect-spinner",
                                        18.0,
                                        BACKGROUND,
                                    ))
                                    .child(animated_loading_label())
                            })
                            .when(!self.login.is_connecting, |button| button.child("Connect"))
                            .on_click(cx.listener(|this, _, _, cx| this.start_login(cx))),
                    )
                    .with_animation(
                        "login-card-enter",
                        Animation::new(Duration::from_millis(360)).with_easing(ease_out_quint()),
                        |card, phase| {
                            card.opacity(phase)
                                .mt(px(14.0 * (1.0 - phase.clamp(0.0, 1.0))))
                        },
                    ),
            )
            .into_any_element()
    }

    fn sync_progress_bar(&self, progress: f32) -> AnyElement {
        let track = div()
            .relative()
            .w_full()
            .h(px(7.0))
            .rounded_full()
            .overflow_hidden()
            .bg(SURFACE_HIGH);

        if self.sync_status.total == 0 {
            track
                .child(
                    div()
                        .h_full()
                        .w(gpui::relative(0.24))
                        .rounded_full()
                        .bg(PRIMARY)
                        .with_animation(
                            "sync-indeterminate-progress",
                            Animation::new(Duration::from_millis(1_100))
                                .repeat()
                                .with_easing(ease_in_out),
                            |bar, phase| bar.ml(gpui::relative(phase * 0.76)),
                        ),
                )
                .into_any_element()
        } else {
            track
                .child(
                    div().h_full().rounded_full().bg(PRIMARY).with_spring(
                        "sync-determinate-progress",
                        SpringAnimation::new(PROGRESS_SPRING)
                            .to(progress)
                            .with_epsilon(0.001),
                        |bar, phase| bar.w(gpui::relative(phase.clamp(0.0, 1.0))),
                    ),
                )
                .into_any_element()
        }
    }

    fn sync_screen(&self, cx: &mut Context<Self>) -> AnyElement {
        let progress = if self.sync_status.total == 0 {
            0.12
        } else {
            (self.sync_status.current as f32 / self.sync_status.total as f32).clamp(0.02, 1.0)
        };
        let has_error = self.sync_status.error.is_some();

        div()
            .size_full()
            .flex()
            .items_center()
            .justify_center()
            .bg(BACKGROUND)
            .child(
                div()
                    .w(px(460.0))
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap_5()
                    .child(
                        div().child(aurelia_logo(64.0)).with_animation(
                            "sync-logo-pulse",
                            Animation::new(Duration::from_millis(1_400))
                                .repeat()
                                .with_easing(bounce(ease_in_out)),
                            |logo, phase| logo.opacity(0.72 + phase * 0.28),
                        ),
                    )
                    .child(
                        div()
                            .text_2xl()
                            .font_weight(FontWeight::BOLD)
                            .child(if has_error {
                                "We couldn’t finish syncing"
                            } else {
                                "Syncing your library"
                            }),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap_2()
                            .text_sm()
                            .text_center()
                            .text_color(TEXT_MUTED)
                            .when(!has_error, |stage| {
                                stage.child(loading_spinner("sync-stage-spinner", 15.0, TEXT_MUTED))
                            })
                            .child(if let Some(error) = self.sync_status.error.clone() {
                                error
                            } else {
                                self.sync_status.stage.clone()
                            }),
                    )
                    .when(!has_error, |content| {
                        content.child(self.sync_progress_bar(progress)).when(
                            self.sync_status.total > 0,
                            |content| {
                                content.child(div().text_xs().text_color(TEXT_MUTED).child(
                                    format!(
                                        "{} of {}",
                                        self.sync_status.current, self.sync_status.total
                                    ),
                                ))
                            },
                        )
                    })
                    .when(has_error, |content| {
                        content.child(
                            div()
                                .flex()
                                .items_center()
                                .gap_3()
                                .child(
                                    div()
                                        .id("sync-logout")
                                        .role(Role::Button)
                                        .aria_label("Log out")
                                        .focusable()
                                        .tab_stop(true)
                                        .h(px(42.0))
                                        .px_5()
                                        .flex()
                                        .items_center()
                                        .rounded_xl()
                                        .border_1()
                                        .border_color(OUTLINE)
                                        .cursor_pointer()
                                        .hover(|style| style.bg(SURFACE_HIGH))
                                        .child("Log out")
                                        .on_click(cx.listener(|this, _, window, cx| {
                                            this.logout(window, cx)
                                        })),
                                )
                                .child(
                                    div()
                                        .id("sync-retry")
                                        .role(Role::Button)
                                        .aria_label("Retry sync")
                                        .focusable()
                                        .tab_stop(true)
                                        .h(px(42.0))
                                        .px_5()
                                        .flex()
                                        .items_center()
                                        .gap_2()
                                        .rounded_xl()
                                        .bg(PRIMARY)
                                        .text_color(BACKGROUND)
                                        .font_weight(FontWeight::SEMIBOLD)
                                        .cursor_pointer()
                                        .hover(|style| style.opacity(0.86))
                                        .child(icon(Icon::RefreshCw, 17.0))
                                        .child("Try again")
                                        .on_click(
                                            cx.listener(|this, _, _, cx| this.start_sync(cx)),
                                        ),
                                ),
                        )
                    })
                    .with_animation(
                        "sync-screen-enter",
                        Animation::new(Duration::from_millis(300)).with_easing(ease_out_quint()),
                        |content, phase| {
                            content
                                .opacity(phase)
                                .mt(px(10.0 * (1.0 - phase.clamp(0.0, 1.0))))
                        },
                    ),
            )
            .into_any_element()
    }

    fn navigation_item(&self, destination: Destination, cx: &mut Context<Self>) -> Stateful<Div> {
        let available = destination.is_available();
        let selected = self.state.destination == destination;
        let collapsed = self.state.sidebar_collapsed;
        let expanded_phase = if collapsed { 0.0 } else { 1.0 };
        div()
            .id(("nav", destination as usize))
            .role(Role::Button)
            .aria_label(destination.label())
            .focusable()
            .tab_stop(available)
            .flex()
            .items_center()
            .h(px(40.0))
            .w_full()
            .rounded_lg()
            .cursor_pointer()
            .text_sm()
            .text_color(if selected { TEXT } else { TEXT_MUTED })
            .when(selected, |element| element.bg(PRIMARY_CONTAINER))
            .when(!selected, |element| {
                element.hover(|style| style.bg(translucent(SURFACE_HIGH, 0.6)))
            })
            .gap_3()
            .px_3()
            .child(
                icon(destination_icon(destination), 18.0).text_color(if selected {
                    PRIMARY
                } else {
                    TEXT_MUTED
                }),
            )
            .child(
                div()
                    .whitespace_nowrap()
                    .child(destination.label())
                    .when(!available, |label| label.child(" (coming soon)"))
                    .with_spring(
                        ("nav-label", destination as usize),
                        sidebar_animation(expanded_phase),
                        |label, phase| label.opacity(phase.clamp(0.0, 1.0)),
                    ),
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                if !available {
                    return;
                }
                this.state.destination = destination;
                this.refresh_main_list();
                cx.notify();
            }))
    }

    fn sidebar(&self, cx: &mut Context<Self>) -> AnyElement {
        let collapsed = self.state.sidebar_collapsed;
        let expanded_phase = if collapsed { 0.0 } else { 1.0 };
        let sidebar = div()
            .relative()
            .h_full()
            .flex_none()
            .flex()
            .flex_col()
            .overflow_hidden()
            .px_2()
            .py_3()
            .bg(SIDEBAR)
            .child(
                div()
                    .flex()
                    .flex_col()
                    .h_full()
                    .gap_5()
                    .child(
                        div()
                            .h(px(44.0))
                            .flex()
                            .items_center()
                            .gap_3()
                            .px_2()
                            .child(aurelia_logo(32.0))
                            .child(
                                div()
                                    .whitespace_nowrap()
                                    .text_xl()
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .child("Aurelia")
                                    .with_spring(
                                        "aurelia-wordmark",
                                        sidebar_animation(expanded_phase),
                                        |wordmark, phase| wordmark.opacity(phase.clamp(0.0, 1.0)),
                                    ),
                            ),
                    )
                    .child(
                        div().flex().flex_col().gap_1().children(
                            Destination::LIBRARY
                                .into_iter()
                                .map(|destination| self.navigation_item(destination, cx)),
                        ),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap_1()
                            .pt_3()
                            .border_t_1()
                            .border_color(OUTLINE)
                            .children(
                                Destination::COLLECTION
                                    .into_iter()
                                    .map(|destination| self.navigation_item(destination, cx)),
                            ),
                    )
                    .child(div().flex_1())
                    .child(
                        div()
                            .id("toggle-sidebar")
                            .role(Role::Button)
                            .aria_label(if collapsed {
                                "Expand sidebar"
                            } else {
                                "Collapse sidebar"
                            })
                            .focusable()
                            .tab_stop(true)
                            .h(px(40.0))
                            .w_full()
                            .flex()
                            .items_center()
                            .rounded_lg()
                            .cursor_pointer()
                            .text_sm()
                            .text_color(TEXT_MUTED)
                            .hover(|style| style.bg(translucent(SURFACE_HIGH, 0.6)))
                            .gap_3()
                            .px_3()
                            .child(icon(
                                if collapsed {
                                    Icon::PanelLeftOpen
                                } else {
                                    Icon::PanelLeftClose
                                },
                                18.0,
                            ))
                            .child(
                                div()
                                    .whitespace_nowrap()
                                    .child("Collapse sidebar")
                                    .with_spring(
                                        "collapse-sidebar-label",
                                        sidebar_animation(expanded_phase),
                                        |label, phase| label.opacity(phase.clamp(0.0, 1.0)),
                                    ),
                            )
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.state.toggle_sidebar();
                                cx.notify();
                            })),
                    ),
            )
            .child(
                div()
                    .absolute()
                    .top(px(0.0))
                    .right(px(0.0))
                    .bottom(px(0.0))
                    .w(px(1.0))
                    .bg(OUTLINE),
            );

        sidebar
            .with_spring(
                "sidebar-width",
                sidebar_animation(expanded_phase),
                |sidebar, phase| {
                    let phase = phase.clamp(0.0, 1.0);
                    sidebar.w(px(64.0 + 160.0 * phase))
                },
            )
            .into_any_element()
    }

    fn account_button(&self, cx: &mut Context<Self>) -> Stateful<Div> {
        div()
            .id("account-button")
            .role(Role::Button)
            .aria_label("Open account menu")
            .focusable()
            .tab_stop(true)
            .size(px(40.0))
            .rounded_full()
            .flex()
            .items_center()
            .justify_center()
            .bg(PINK)
            .text_color(BACKGROUND)
            .cursor_pointer()
            .hover(|style| style.opacity(0.86))
            .child(icon(Icon::UserRound, 19.0))
            .on_click(cx.listener(|this, _, _, cx| {
                this.account_menu_open = !this.account_menu_open;
                cx.notify();
            }))
    }

    fn account_menu_item(
        &self,
        id: &'static str,
        menu_icon: Icon,
        label: &'static str,
        destructive: bool,
        cx: &mut Context<Self>,
    ) -> Stateful<Div> {
        div()
            .id(id)
            .role(Role::MenuItem)
            .aria_label(label)
            .focusable()
            .tab_stop(true)
            .h(px(40.0))
            .px_3()
            .flex()
            .items_center()
            .gap_3()
            .rounded_lg()
            .text_sm()
            .text_color(if destructive { PINK } else { TEXT })
            .cursor_pointer()
            .hover(|style| style.bg(SURFACE_HIGH))
            .child(
                div()
                    .w(px(24.0))
                    .text_color(if destructive { PINK } else { TEXT_MUTED })
                    .child(icon(menu_icon, 17.0)),
            )
            .child(label)
            .on_click(cx.listener(move |this, _, window, cx| {
                if id == "account-logout" {
                    this.logout(window, cx);
                } else {
                    this.account_menu_open = false;
                    cx.notify();
                }
            }))
    }

    fn account_menu(&self, cx: &mut Context<Self>) -> Stateful<Div> {
        let username = self
            .session
            .as_ref()
            .map(|session| session.username.clone())
            .unwrap_or_else(|| "Aurelia user".into());
        let initial = username
            .chars()
            .next()
            .unwrap_or('A')
            .to_uppercase()
            .to_string();
        let server = self
            .session
            .as_ref()
            .and_then(|session| Url::parse(&session.server_url).ok())
            .and_then(|url| url.host_str().map(ToString::to_string))
            .unwrap_or_else(|| "Jellyfin server".into());
        div()
            .id("account-menu")
            .role(Role::Menu)
            .aria_label("Account menu")
            .w(px(224.0))
            .p_2()
            .flex()
            .flex_col()
            .rounded_xl()
            .border_1()
            .border_color(OUTLINE)
            .bg(SURFACE)
            .shadow_lg()
            .child(
                div()
                    .p_2()
                    .flex()
                    .items_center()
                    .gap_3()
                    .child(
                        div()
                            .size(px(40.0))
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .bg(PINK)
                            .text_color(BACKGROUND)
                            .font_weight(FontWeight::BOLD)
                            .child(initial),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap_1()
                            .child(
                                div()
                                    .text_sm()
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .child(username),
                            )
                            .child(div().text_xs().text_color(TEXT_MUTED).child(server)),
                    ),
            )
            .child(div().h(px(1.0)).mx_2().my_2().bg(OUTLINE))
            .child(self.account_menu_item(
                "account-settings",
                Icon::Settings,
                "Settings",
                false,
                cx,
            ))
            .child(self.account_menu_item(
                "account-servers",
                Icon::Server,
                "Manage servers",
                false,
                cx,
            ))
            .child(div().h(px(1.0)).mx_2().my_2().bg(OUTLINE))
            .child(self.account_menu_item("account-logout", Icon::LogOut, "Log out", true, cx))
            .on_mouse_down_out(cx.listener(|this, event: &gpui::MouseDownEvent, _, cx| {
                if !should_dismiss_account_menu(this.account_button_bounds, event.position) {
                    return;
                }
                this.account_menu_open = false;
                cx.notify();
            }))
    }

    fn search(&self, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let search_input = self.search_input.clone();
        let search_focus = search_input.read(cx).focus_handle();
        let focused = search_focus.is_focused(window);
        let click_focus = search_focus.clone();
        div()
            .id("library-search")
            .role(Role::TextInput)
            .aria_label("Search your library")
            .track_focus(&search_focus)
            .on_click(move |_, window, cx| window.focus(&click_focus, cx))
            .w(px(340.0))
            .h(px(40.0))
            .px_3()
            .flex()
            .items_center()
            .gap_2()
            .rounded_xl()
            .border_1()
            .border_color(if focused { PRIMARY } else { OUTLINE })
            .bg(SURFACE)
            .cursor_text()
            .child(icon(Icon::Search, 17.0).text_color(TEXT_MUTED))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .h_full()
                    .overflow_hidden()
                    .child(search_input),
            )
            .into_any_element()
    }

    fn artwork_placeholder(&self, track: &Track, size_px: f32) -> Div {
        div()
            .size(px(size_px))
            .flex_none()
            .rounded_lg()
            .overflow_hidden()
            .bg(rgb(track.art_color))
            .border_1()
            .border_color(translucent(TEXT, 0.12))
            .flex()
            .items_end()
            .p_2()
            .text_color(rgb(0xffffff))
            .text_sm()
            .font_weight(FontWeight::BOLD)
            .child(track.initials())
    }

    fn artwork(
        &self,
        track: &Track,
        size_px: f32,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let requested_width = (size_px * window.scale_factor()).ceil().max(1.0) as u32;
        let Some(session) = self.session.as_ref() else {
            return self.artwork_placeholder(track, size_px).into_any_element();
        };
        let Some(artwork_id) = track.artwork_id.as_ref() else {
            return self.artwork_placeholder(track, size_px).into_any_element();
        };
        let Some(url) = aurelia_core::build_image_url(
            session.server_url.clone(),
            session.token.clone(),
            artwork_id.clone(),
            "Primary".into(),
            Some(requested_width),
            Some(90),
        )
        .ok()
        .flatten() else {
            return self.artwork_placeholder(track, size_px).into_any_element();
        };

        let image = self
            .artwork_cache
            .update(cx, |cache, cx| cache.get_or_load(url.clone(), cx));
        let Some(image) = image else {
            return self.artwork_placeholder(track, size_px).into_any_element();
        };

        img(image)
            .size(px(size_px))
            .flex_none()
            .rounded_lg()
            .border_1()
            .border_color(translucent(TEXT, 0.12))
            .object_fit(ObjectFit::Cover)
            .with_animation(
                ("artwork-fade", gpui::hash(&url)),
                Animation::new(Duration::from_millis(180)).with_easing(ease_out_quint()),
                |image, phase| image.opacity(phase),
            )
            .into_any_element()
    }

    fn album_card(&self, index: usize, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let track = self.state.tracks[index].clone();
        div()
            .id(("album-card", index))
            .role(Role::Button)
            .aria_label(SharedString::from(format!(
                "Play {} by {}",
                track.album, track.artist
            )))
            .focusable()
            .tab_stop(true)
            .w(px(144.0))
            .rounded_xl()
            .cursor_pointer()
            .hover(|style| style.bg(SURFACE_HIGH))
            .child(self.artwork(&track, 144.0, window, cx))
            .child(
                div()
                    .pt_3()
                    .px_1()
                    .text_sm()
                    .font_weight(FontWeight::SEMIBOLD)
                    .overflow_hidden()
                    .child(track.album.clone()),
            )
            .child(
                div()
                    .px_1()
                    .pt_1()
                    .text_xs()
                    .text_color(TEXT_MUTED)
                    .child(track.artist.clone()),
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.start_track(index, cx);
            }))
            .into_any_element()
    }

    fn track_row(&self, index: usize, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let track = self.state.tracks[index].clone();
        let selected = self
            .state
            .queue
            .current()
            .is_some_and(|entry| entry.track.id == track.id);
        div()
            .id(("track-row", index))
            .group("library-track")
            .role(Role::Button)
            .aria_label(SharedString::from(format!(
                "Play {} by {}",
                track.title, track.artist
            )))
            .focusable()
            .tab_stop(true)
            .h(px(56.0))
            .w_full()
            .px_3()
            .flex()
            .items_center()
            .rounded_lg()
            .cursor_pointer()
            .when(selected, |element| element.bg(PRIMARY_CONTAINER))
            .when(!selected, |element| {
                element.hover(|style| style.bg(translucent(SURFACE_HIGH, 0.72)))
            })
            .child(
                div()
                    .w(px(34.0))
                    .flex_none()
                    .text_center()
                    .text_sm()
                    .text_color(if selected { PRIMARY } else { TEXT_MUTED })
                    .when(selected && self.state.is_playing, |element| {
                        element.child(icon(Icon::Music2, 16.0))
                    })
                    .when(!(selected && self.state.is_playing), |element| {
                        element.child(format!("{:02}", index + 1))
                    }),
            )
            .child(self.artwork(&track, 40.0, window, cx))
            .child(
                div()
                    .w(px(230.0))
                    .min_w_0()
                    .pl_3()
                    .flex()
                    .flex_col()
                    .gap_1()
                    .overflow_hidden()
                    .child(
                        div()
                            .w_full()
                            .truncate()
                            .text_sm()
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(if selected { PRIMARY } else { TEXT })
                            .child(track.title.clone()),
                    )
                    .child(
                        div()
                            .w_full()
                            .truncate()
                            .text_xs()
                            .text_color(TEXT_MUTED)
                            .child(track.artist.clone()),
                    ),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .truncate()
                    .text_sm()
                    .text_color(TEXT_MUTED)
                    .child(track.album.clone()),
            )
            .child(
                div()
                    .w(px(62.0))
                    .text_right()
                    .text_xs()
                    .text_color(TEXT_MUTED)
                    .child(track.duration_label()),
            )
            .child(
                div()
                    .w(px(76.0))
                    .flex()
                    .justify_end()
                    .gap_1()
                    .text_color(TEXT_MUTED)
                    .invisible()
                    .group_hover("library-track", |style| style.visible())
                    .child(
                        div()
                            .id(("play-next", index))
                            .role(Role::Button)
                            .aria_label(SharedString::from(format!("Play {} next", track.title)))
                            .size(px(32.0))
                            .rounded_lg()
                            .flex()
                            .items_center()
                            .justify_center()
                            .hover(|style| style.bg(SURFACE_HIGH).text_color(TEXT))
                            .child(icon(Icon::ListStart, 16.0))
                            .on_click(cx.listener(move |this, _, _, cx| {
                                cx.stop_propagation();
                                this.play_track_next(index, cx);
                            })),
                    )
                    .child(
                        div()
                            .id(("add-to-queue", index))
                            .role(Role::Button)
                            .aria_label(SharedString::from(format!("Add {} to queue", track.title)))
                            .size(px(32.0))
                            .rounded_lg()
                            .flex()
                            .items_center()
                            .justify_center()
                            .hover(|style| style.bg(SURFACE_HIGH).text_color(TEXT))
                            .child(icon(Icon::ListPlus, 16.0))
                            .on_click(cx.listener(move |this, _, _, cx| {
                                cx.stop_propagation();
                                this.add_track_to_queue(index, cx);
                            })),
                    ),
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.start_track(index, cx);
            }))
            .into_any_element()
    }

    fn track_list_items(
        &mut self,
        range: Range<usize>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        range
            .filter_map(|visible_index| {
                let &track_index = self.visible_track_indices.get(visible_index)?;
                Some(
                    div()
                        .w_full()
                        .h(px(TRACK_ROW_HEIGHT))
                        .child(self.track_row(track_index, window, cx))
                        .into_any_element(),
                )
            })
            .collect()
    }

    fn main_content(&self, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let destination = self.state.destination;
        let heading = match destination {
            Destination::Home => "Good evening",
            _ => destination.label(),
        };
        let subtitle = match destination {
            Destination::Home => "Pick up where you left off",
            Destination::Songs => "Every song in your Jellyfin library",
            Destination::Albums => "Albums from your library",
            Destination::Artists => "Artists you come back to",
            Destination::Playlists => "Your mixes and saved playlists",
            Destination::Favorites => "Songs you have marked as favorites",
            Destination::RecentlyAdded => "The newest music on your server",
        };
        let track_count = self.visible_track_indices.len();
        let section_title = if self.state.query.is_empty() {
            if destination == Destination::Home {
                "Songs".to_string()
            } else {
                format!("{} in your library", destination.label())
            }
        } else {
            format!("{track_count} results")
        };
        let weak_self = cx.weak_entity();
        let account_menu_position = point(window.viewport_size().width - px(24.0), px(64.0));

        div()
            .flex_1()
            .h_full()
            .min_w_0()
            .flex()
            .flex_col()
            .bg(BACKGROUND)
            .child(
                div()
                    .h(px(72.0))
                    .flex_none()
                    .px_6()
                    .flex()
                    .items_center()
                    .justify_between()
                    .border_b_1()
                    .border_color(OUTLINE)
                    .on_children_prepainted(move |child_bounds, _, cx| {
                        weak_self
                            .update(cx, |this, _| {
                                this.account_button_bounds = child_bounds.get(1).copied();
                            })
                            .ok();
                    })
                    .child(self.search(window, cx))
                    .child(self.account_button(cx))
                    .when(self.account_menu_open, |header| {
                        header.child(
                            deferred(
                                anchored()
                                    .anchor(Anchor::TopRight)
                                    .position(account_menu_position)
                                    .snap_to_window_with_margin(px(16.0))
                                    .child(self.account_menu(cx)),
                            )
                            .priority(1),
                        )
                    }),
            )
            .child(
                div()
                    .flex_none()
                    .px_6()
                    .pt_6()
                    .child(
                        div()
                            .text_3xl()
                            .font_weight(FontWeight::BOLD)
                            .child(heading),
                    )
                    .child(
                        div()
                            .pt_1()
                            .text_sm()
                            .text_color(TEXT_MUTED)
                            .child(subtitle),
                    ),
            )
            .when(destination == Destination::Home, |content| {
                content.child(
                    div().flex_none().px_6().pt_6().flex().gap_4().children(
                        (0..self.state.tracks.len().min(4))
                            .map(|index| self.album_card(index, window, cx)),
                    ),
                )
            })
            .child(
                div()
                    .flex_none()
                    .px_6()
                    .pt_6()
                    .pb_3()
                    .flex()
                    .items_end()
                    .justify_between()
                    .child(
                        div()
                            .text_lg()
                            .font_weight(FontWeight::SEMIBOLD)
                            .child(section_title),
                    )
                    .child(
                        div()
                            .text_xs()
                            .text_color(TEXT_MUTED)
                            .child(format!("{track_count} tracks")),
                    ),
            )
            .child(
                div().flex_1().min_h_0().overflow_y_hidden().px_6().child(
                    uniform_list(
                        "track-list",
                        track_count,
                        cx.processor(Self::track_list_items),
                    )
                    .size_full()
                    .pb_4()
                    .track_scroll(&self.track_scroll_handle),
                ),
            )
            .into_any_element()
    }

    fn queue_row(&self, index: usize, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let Some(entry) = self.state.queue.entries().get(index).cloned() else {
            return div().into_any_element();
        };
        let id = entry.id;
        let track = entry.track;
        let is_current = self.state.queue.current_id() == Some(id);
        let can_move_up = index > 0;
        let can_move_down = index + 1 < self.state.queue.len();
        let drag = DraggedQueueEntry {
            id,
            source_index: index,
            title: track.title.clone().into(),
            artist: track.artist.clone().into(),
        };

        div()
            .id(("queue-row", id.raw()))
            .role(Role::ListItem)
            .aria_label(SharedString::from(format!(
                "{} by {}, queue position {}",
                track.title,
                track.artist,
                index + 1
            )))
            .group("queue-row")
            .h(px(64.0))
            .w_full()
            .px_2()
            .flex()
            .items_center()
            .gap_2()
            .rounded_lg()
            .cursor_pointer()
            .when(is_current, |row| row.bg(PRIMARY_CONTAINER))
            .when(!is_current, |row| {
                row.hover(|style| style.bg(translucent(SURFACE_HIGH, 0.72)))
            })
            .drag_over::<DraggedQueueEntry>(move |row, dragged, _, _| {
                if dragged.id == id {
                    row
                } else if dragged.source_index < index {
                    row.border_b_2().border_color(PRIMARY)
                } else {
                    row.border_t_2().border_color(PRIMARY)
                }
            })
            .on_drop(
                cx.listener(move |this, dragged: &DraggedQueueEntry, _, cx| {
                    this.move_queue_entry(dragged.id, index, cx);
                }),
            )
            .child(
                div()
                    .id(("queue-drag", id.raw()))
                    .role(Role::Button)
                    .aria_label(SharedString::from(format!("Reorder {}", track.title)))
                    .size(px(28.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_center()
                    .cursor_move()
                    .text_color(TEXT_MUTED)
                    .child(icon(Icon::GripVertical, 16.0))
                    .on_drag(drag, |dragged, _, _, cx| cx.new(|_| dragged.clone()))
                    .on_click(|_, _, cx| cx.stop_propagation()),
            )
            .child(self.artwork(&track, 44.0, window, cx))
            .child(
                div()
                    .min_w_0()
                    .flex_1()
                    .flex()
                    .flex_col()
                    .gap_1()
                    .child(
                        div()
                            .w_full()
                            .truncate()
                            .text_sm()
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(if is_current { PRIMARY } else { TEXT })
                            .child(track.title.clone()),
                    )
                    .child(
                        div()
                            .w_full()
                            .truncate()
                            .text_xs()
                            .text_color(TEXT_MUTED)
                            .child(track.artist.clone()),
                    ),
            )
            .child(
                div()
                    .flex_none()
                    .text_xs()
                    .text_color(TEXT_MUTED)
                    .child(if is_current {
                        "Playing".to_string()
                    } else {
                        track.duration_label()
                    }),
            )
            .child(
                div()
                    .w(px(88.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_end()
                    .gap_0p5()
                    .invisible()
                    .group_hover("queue-row", |style| style.visible())
                    .child(
                        div()
                            .id(("queue-up", id.raw()))
                            .role(Role::Button)
                            .aria_label(SharedString::from(format!("Move {} up", track.title)))
                            .size(px(28.0))
                            .rounded_md()
                            .flex()
                            .items_center()
                            .justify_center()
                            .text_color(TEXT_MUTED)
                            .when(!can_move_up, |button| button.opacity(0.28))
                            .when(can_move_up, |button| {
                                button
                                    .cursor_pointer()
                                    .hover(|style| style.bg(SURFACE_HIGH).text_color(TEXT))
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        cx.stop_propagation();
                                        this.move_queue_entry_by(id, -1, cx);
                                    }))
                            })
                            .child(icon(Icon::ChevronUp, 15.0)),
                    )
                    .child(
                        div()
                            .id(("queue-down", id.raw()))
                            .role(Role::Button)
                            .aria_label(SharedString::from(format!("Move {} down", track.title)))
                            .size(px(28.0))
                            .rounded_md()
                            .flex()
                            .items_center()
                            .justify_center()
                            .text_color(TEXT_MUTED)
                            .when(!can_move_down, |button| button.opacity(0.28))
                            .when(can_move_down, |button| {
                                button
                                    .cursor_pointer()
                                    .hover(|style| style.bg(SURFACE_HIGH).text_color(TEXT))
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        cx.stop_propagation();
                                        this.move_queue_entry_by(id, 1, cx);
                                    }))
                            })
                            .child(icon(Icon::ChevronDown, 15.0)),
                    )
                    .child(
                        div()
                            .id(("queue-remove", id.raw()))
                            .role(Role::Button)
                            .aria_label(SharedString::from(format!(
                                "Remove {} from queue",
                                track.title
                            )))
                            .size(px(28.0))
                            .rounded_md()
                            .flex()
                            .items_center()
                            .justify_center()
                            .cursor_pointer()
                            .text_color(TEXT_MUTED)
                            .hover(|style| style.bg(SURFACE_HIGH).text_color(PINK))
                            .child(icon(Icon::X, 15.0))
                            .on_click(cx.listener(move |this, _, _, cx| {
                                cx.stop_propagation();
                                this.remove_queue_entry(id, cx);
                            })),
                    ),
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.start_queue_entry(id, cx);
            }))
            .into_any_element()
    }

    fn queue_list_items(
        &mut self,
        range: Range<usize>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        range
            .map(|index| {
                div()
                    .h(px(68.0))
                    .w_full()
                    .child(self.queue_row(index, window, cx))
                    .into_any_element()
            })
            .collect()
    }

    fn queue_drawer(&self, cx: &mut Context<Self>) -> AnyElement {
        let count = self.state.queue.len();
        let upcoming = self
            .state
            .queue
            .current_index()
            .map_or(0, |index| count.saturating_sub(index + 1));

        div()
            .id("playback-queue")
            .role(Role::List)
            .aria_label("Playback queue")
            .absolute()
            .top(px(88.0))
            .right(px(16.0))
            .bottom(px(112.0))
            .w(px(470.0))
            .min_h(px(240.0))
            .flex()
            .flex_col()
            .overflow_hidden()
            .rounded_xl()
            .border_1()
            .border_color(OUTLINE)
            .bg(SURFACE)
            .shadow_lg()
            .occlude()
            .child(
                div()
                    .h(px(68.0))
                    .px_4()
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_between()
                    .border_b_1()
                    .border_color(OUTLINE)
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap_1()
                            .child(
                                div()
                                    .text_lg()
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .child("Queue"),
                            )
                            .child(
                                div()
                                    .text_xs()
                                    .text_color(TEXT_MUTED)
                                    .child(format!("{count} tracks · {upcoming} upcoming")),
                            ),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap_2()
                            .when(upcoming > 0, |actions| {
                                actions.child(
                                    div()
                                        .id("clear-upcoming")
                                        .role(Role::Button)
                                        .aria_label("Clear upcoming tracks")
                                        .h(px(32.0))
                                        .px_3()
                                        .rounded_lg()
                                        .flex()
                                        .items_center()
                                        .gap_2()
                                        .cursor_pointer()
                                        .text_xs()
                                        .text_color(TEXT_MUTED)
                                        .hover(|style| style.bg(SURFACE_HIGH).text_color(TEXT))
                                        .child(icon(Icon::ListX, 15.0))
                                        .child("Clear")
                                        .on_click(cx.listener(|this, _, _, cx| {
                                            this.clear_upcoming(cx);
                                        })),
                                )
                            })
                            .child(
                                div()
                                    .id("close-queue")
                                    .role(Role::Button)
                                    .aria_label("Close queue")
                                    .size(px(32.0))
                                    .rounded_lg()
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .cursor_pointer()
                                    .text_color(TEXT_MUTED)
                                    .hover(|style| style.bg(SURFACE_HIGH).text_color(TEXT))
                                    .child(icon(Icon::X, 17.0))
                                    .on_click(cx.listener(|this, _, _, cx| {
                                        this.queue_open = false;
                                        cx.notify();
                                    })),
                            ),
                    ),
            )
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .overflow_y_hidden()
                    .px_2()
                    .py_2()
                    .child(
                        uniform_list("queue-list", count, cx.processor(Self::queue_list_items))
                            .size_full()
                            .track_scroll(&self.queue_scroll_handle),
                    ),
            )
            .with_animation(
                "queue-drawer-in",
                Animation::new(Duration::from_millis(150)).with_easing(ease_out_quint()),
                |drawer, phase| drawer.opacity(phase).right(px(16.0 - (1.0 - phase) * 16.0)),
            )
            .into_any_element()
    }

    fn control_button(
        &self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
        control_icon: Icon,
        prominent: bool,
    ) -> Stateful<Div> {
        let label = label.into();
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .focusable()
            .tab_stop(true)
            .size(px(if prominent { 40.0 } else { 32.0 }))
            .rounded_full()
            .flex()
            .items_center()
            .justify_center()
            .cursor_pointer()
            .bg(if prominent { PRIMARY } else { SURFACE_HIGH })
            .text_color(if prominent { BACKGROUND } else { TEXT })
            .hover(|style| style.opacity(0.82))
            .child(icon(control_icon, if prominent { 18.0 } else { 16.0 }))
    }

    fn player_bar(&self, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let track = self
            .state
            .queue
            .current()
            .expect("player bar requires a current queue item")
            .track
            .clone();
        let progress = if track.duration_seconds == 0 {
            0.0
        } else {
            (self.state.elapsed_seconds as f32 / track.duration_seconds as f32).clamp(0.0, 1.0)
        };
        let (playback_detail, playback_detail_color) = if let Some(error) = &self.playback_error {
            (error.clone(), PINK)
        } else if self.playback_loading {
            ("Loading audio…".into(), PRIMARY)
        } else {
            (track.artist.clone(), TEXT_MUTED)
        };
        let elapsed = format!(
            "{}:{:02}",
            self.state.elapsed_seconds / 60,
            self.state.elapsed_seconds % 60
        );
        let repeat_mode = self.state.queue.repeat_mode();
        let shuffle_enabled = self.state.queue.shuffle_enabled();
        let repeat_label = match repeat_mode {
            RepeatMode::Off => "Repeat is off",
            RepeatMode::All => "Repeat queue",
            RepeatMode::One => "Repeat current track",
        };
        let weak_self = cx.weak_entity();

        div()
            .h(px(96.0))
            .w_full()
            .flex_none()
            .px_5()
            .flex()
            .items_center()
            .bg(SURFACE)
            .border_t_1()
            .border_color(OUTLINE)
            .child(
                div()
                    .id("now-playing-summary")
                    .role(Role::Button)
                    .aria_label("Show playback queue")
                    .w(px(310.0))
                    .flex()
                    .items_center()
                    .gap_3()
                    .cursor_pointer()
                    .child(self.artwork(&track, 56.0, window, cx))
                    .child(
                        div()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .gap_1()
                            .child(
                                div()
                                    .text_sm()
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .child(track.title.clone()),
                            )
                            .child(
                                div()
                                    .text_xs()
                                    .truncate()
                                    .text_color(playback_detail_color)
                                    .child(playback_detail),
                            ),
                    )
                    .child(div().pl_2().text_color(PINK).child(icon(Icon::Heart, 18.0)))
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.toggle_queue(cx);
                    })),
            )
            .child(
                div()
                    .flex_1()
                    .max_w(px(590.0))
                    .mx_auto()
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap_2()
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap_2()
                            .child(
                                self.control_button(
                                    "shuffle",
                                    if shuffle_enabled {
                                        "Disable shuffle"
                                    } else {
                                        "Enable shuffle"
                                    },
                                    Icon::Shuffle,
                                    false,
                                )
                                .when(shuffle_enabled, |button| {
                                    button.bg(PRIMARY_CONTAINER).text_color(PRIMARY)
                                })
                                .on_click(cx.listener(
                                    |this, _, _, cx| {
                                        this.state.queue.toggle_shuffle();
                                        this.refresh_prepared_next(cx);
                                        cx.notify();
                                    },
                                )),
                            )
                            .child(
                                self.control_button(
                                    "previous",
                                    "Previous track",
                                    Icon::SkipBack,
                                    false,
                                )
                                .on_click(cx.listener(
                                    |this, _, _, cx| {
                                        this.previous_track(cx);
                                    },
                                )),
                            )
                            .child(
                                self.control_button(
                                    "play-pause",
                                    if self.state.is_playing {
                                        "Pause"
                                    } else {
                                        "Play"
                                    },
                                    if self.state.is_playing {
                                        Icon::Pause
                                    } else {
                                        Icon::Play
                                    },
                                    true,
                                )
                                .on_click(cx.listener(
                                    |this, _, _, cx| {
                                        this.toggle_playback(cx);
                                    },
                                )),
                            )
                            .child(
                                self.control_button("next", "Next track", Icon::SkipForward, false)
                                    .on_click(cx.listener(|this, _, _, cx| {
                                        this.next_track(cx);
                                    })),
                            )
                            .child(
                                self.control_button(
                                    "repeat",
                                    repeat_label,
                                    if repeat_mode == RepeatMode::One {
                                        Icon::Repeat1
                                    } else {
                                        Icon::Repeat
                                    },
                                    false,
                                )
                                .when(repeat_mode != RepeatMode::Off, |button| {
                                    button.bg(PRIMARY_CONTAINER).text_color(PRIMARY)
                                })
                                .on_click(cx.listener(
                                    |this, _, _, cx| {
                                        this.state.queue.cycle_repeat_mode();
                                        this.refresh_prepared_next(cx);
                                        cx.notify();
                                    },
                                )),
                            ),
                    )
                    .child(
                        div()
                            .w_full()
                            .flex()
                            .items_center()
                            .gap_2()
                            .text_xs()
                            .text_color(TEXT_MUTED)
                            .child(elapsed)
                            .child(
                                div()
                                    .flex_1()
                                    .h(px(18.0))
                                    .flex()
                                    .items_center()
                                    .on_children_prepainted(move |child_bounds, _, cx| {
                                        if let Some(bounds) = child_bounds.first().copied() {
                                            weak_self
                                                .update(cx, |this, _| {
                                                    this.seek_bounds = Some(bounds);
                                                })
                                                .ok();
                                        }
                                    })
                                    .child(
                                        div()
                                            .id("seek-track")
                                            .role(Role::Slider)
                                            .aria_label("Playback position")
                                            .w_full()
                                            .h(px(5.0))
                                            .rounded_full()
                                            .bg(SURFACE_HIGH)
                                            .cursor_pointer()
                                            .child(
                                                div()
                                                    .h_full()
                                                    .w(gpui::relative(progress))
                                                    .rounded_full()
                                                    .bg(PRIMARY),
                                            )
                                            .on_mouse_down(
                                                MouseButton::Left,
                                                cx.listener(
                                                    |this, event: &MouseDownEvent, _, cx| {
                                                        this.seek_dragging = true;
                                                        this.preview_seek_to_fraction(
                                                            event.position,
                                                            cx,
                                                        );
                                                        cx.stop_propagation();
                                                    },
                                                ),
                                            )
                                            .on_mouse_move(cx.listener(
                                                |this, event: &MouseMoveEvent, _, cx| {
                                                    if this.seek_dragging {
                                                        this.preview_seek_to_fraction(
                                                            event.position,
                                                            cx,
                                                        );
                                                        cx.stop_propagation();
                                                    }
                                                },
                                            ))
                                            .on_mouse_up(
                                                MouseButton::Left,
                                                cx.listener(|this, event: &MouseUpEvent, _, cx| {
                                                    if this.seek_dragging {
                                                        this.preview_seek_to_fraction(
                                                            event.position,
                                                            cx,
                                                        );
                                                        this.seek_dragging = false;
                                                        this.request_seek(
                                                            this.state.elapsed_seconds,
                                                            cx,
                                                        );
                                                        cx.stop_propagation();
                                                    }
                                                }),
                                            ),
                                    ),
                            )
                            .child(track.duration_label()),
                    ),
            )
            .child(
                div()
                    .w(px(310.0))
                    .flex()
                    .items_center()
                    .justify_end()
                    .gap_2()
                    .text_sm()
                    .text_color(TEXT_MUTED)
                    .child(
                        div()
                            .id("toggle-queue")
                            .role(Role::Button)
                            .aria_label(if self.queue_open {
                                "Close playback queue"
                            } else {
                                "Show playback queue"
                            })
                            .size(px(34.0))
                            .rounded_lg()
                            .flex()
                            .items_center()
                            .justify_center()
                            .cursor_pointer()
                            .when(self.queue_open, |button| {
                                button.bg(PRIMARY_CONTAINER).text_color(PRIMARY)
                            })
                            .hover(|style| style.bg(SURFACE_HIGH).text_color(TEXT))
                            .child(icon(Icon::ListMusic, 17.0))
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.toggle_queue(cx);
                            })),
                    )
                    .child(icon(Icon::Volume2, 17.0))
                    .child(
                        div()
                            .id("volume-down")
                            .role(Role::Button)
                            .aria_label("Turn volume down")
                            .focusable()
                            .tab_stop(true)
                            .cursor_pointer()
                            .px_2()
                            .child(icon(Icon::Minus, 14.0))
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.change_volume(-10, cx);
                            })),
                    )
                    .child(
                        div()
                            .w(px(84.0))
                            .h(px(5.0))
                            .rounded_full()
                            .bg(SURFACE_HIGH)
                            .child(
                                div()
                                    .h_full()
                                    .w(gpui::relative(self.state.volume_percent as f32 / 100.0))
                                    .rounded_full()
                                    .bg(TEXT_MUTED),
                            ),
                    )
                    .child(
                        div()
                            .id("volume-up")
                            .role(Role::Button)
                            .aria_label("Turn volume up")
                            .focusable()
                            .tab_stop(true)
                            .cursor_pointer()
                            .px_2()
                            .child(icon(Icon::Plus, 14.0))
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.change_volume(10, cx);
                            })),
                    )
                    .child(format!("{}%", self.state.volume_percent)),
            )
            .into_any_element()
    }
}

impl Render for AureliaDesktop {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let stage_content = match self.stage {
            AppStage::Login => self.login_screen(window, cx),
            AppStage::Syncing => self.sync_screen(cx),
            AppStage::Home => div()
                .relative()
                .size_full()
                .flex()
                .flex_col()
                .child(
                    div()
                        .flex_1()
                        .min_h_0()
                        .flex()
                        .child(self.sidebar(cx))
                        .child(self.main_content(window, cx)),
                )
                .when(!self.state.queue.is_empty(), |app| {
                    app.child(self.player_bar(window, cx))
                })
                .child(div().absolute().inset_0().bg(BACKGROUND).with_animation(
                    "home-stage-reveal",
                    Animation::new(Duration::from_millis(260)).with_easing(ease_out_quint()),
                    |overlay, phase| overlay.opacity(1.0 - phase),
                ))
                .when(self.queue_open && !self.state.queue.is_empty(), |app| {
                    app.child(self.queue_drawer(cx))
                })
                .into_any_element(),
        };

        div()
            .id("aurelia-desktop")
            .role(Role::Application)
            .aria_label("Aurelia desktop music player")
            .track_focus(&self.root_focus)
            .on_action(cx.listener(|_, _: &Tab, window, cx| window.focus_next(cx)))
            .on_action(cx.listener(|_, _: &TabPrev, window, cx| window.focus_prev(cx)))
            .on_action(cx.listener(|this, _: &FocusSearch, window, cx| {
                window.focus(&this.search_input.read(cx).focus_handle(), cx)
            }))
            .on_action(cx.listener(|this, _: &ToggleSidebar, _, cx| {
                this.state.toggle_sidebar();
                cx.notify();
            }))
            .size_full()
            .flex()
            .flex_col()
            .overflow_hidden()
            .bg(BACKGROUND)
            .text_color(TEXT)
            .font_family("Inter")
            .child(stage_content)
    }
}

fn main() {
    application().run(|cx: &mut App| {
        gpui_tokio::init(cx);
        let http_client =
            ReqwestClient::user_agent("Aurelia/0.1").expect("failed to initialize HTTP client");
        cx.set_http_client(Arc::new(http_client));
        cx.text_system()
            .add_fonts(vec![Cow::Borrowed(LUCIDE_FONT_BYTES)])
            .expect("failed to load the Lucide icon font");

        cx.bind_keys([
            KeyBinding::new("tab", Tab, None),
            KeyBinding::new("shift-tab", TabPrev, None),
            KeyBinding::new("secondary-f", FocusSearch, None),
            KeyBinding::new("secondary-b", ToggleSidebar, None),
        ]);
        cx.bind_keys(text_input::key_bindings());

        let bounds = Bounds::centered(None, size(px(1180.0), px(760.0)), cx);
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                titlebar: Some(gpui::TitlebarOptions {
                    title: Some("Aurelia".into()),
                    ..Default::default()
                }),
                ..Default::default()
            },
            |window, cx| cx.new(|cx| AureliaDesktop::new(window, cx)),
        )
        .expect("failed to open Aurelia desktop window");

        cx.activate(true);
    });
}

#[cfg(test)]
mod view_tests {
    use super::*;

    #[test]
    fn account_button_click_does_not_count_as_outside_menu_click() {
        let button_bounds = Bounds::new(point(px(100.0), px(16.0)), size(px(40.0), px(40.0)));

        assert!(!should_dismiss_account_menu(
            Some(button_bounds),
            point(px(120.0), px(36.0)),
        ));
        assert!(should_dismiss_account_menu(
            Some(button_bounds),
            point(px(80.0), px(36.0)),
        ));
        assert!(should_dismiss_account_menu(
            None,
            point(px(120.0), px(36.0)),
        ));
    }

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
