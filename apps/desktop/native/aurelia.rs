//! Aurelia's GPUIX extension, compiled into the renderer by the fork's
//! `aurelia` branch. The interface stays in TypeScript; this file connects it
//! to the desktop runtime and paints the seek bar, which follows playback on
//! its own instead of through React. `lyrics.rs` does the same for lyrics, and
//! `clouds.rs` paints the full player's moving backdrop.
use crate::{
    custom_elements::{
        custom_surface, CustomElement, CustomElementFactory, CustomElementRegistry,
        CustomRenderContext,
    },
    GpuixRenderer,
};
use gpui::{prelude::*, *};
use std::{
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};

#[path = "clouds.rs"]
mod clouds;
#[path = "lyrics.rs"]
mod lyrics;

/// Add Aurelia's elements to the renderer's.
pub fn register(registry: &mut CustomElementRegistry) {
    registry.register(Box::new(WaveFactory));
    registry.register(Box::new(lyrics::LyricsFactory));
    registry.register(Box::new(clouds::CloudsFactory));
}

/// While something plays, the seek bar redraws this often. Progress moves
/// about a pixel a second, so this looks continuous without redrawing the
/// window every frame.
const PROGRESS_INTERVAL: Duration = Duration::from_millis(250);
/// The wave settles flat on pause and rises on play over this long.
const WAVE_EASE: Duration = Duration::from_millis(320);

#[napi_derive::napi]
impl GpuixRenderer {
    /// Start a runtime request (JSON with a `type`); its result arrives
    /// through `aureliaPoll`.
    #[napi]
    pub fn aurelia_request(&self, json: String) -> napi::Result<f64> {
        aurelia_desktop::runtime()
            .request(&json)
            .map(|id| id as f64)
            .map_err(napi::Error::from_reason)
    }

    /// Finished requests, playback state and sync progress, as JSON. `seen`
    /// is the player revision the interface already has.
    #[napi]
    pub fn aurelia_poll(&self, seen: f64) -> String {
        aurelia_desktop::runtime().poll(seen as u64)
    }
}

static REFRESHING: AtomicBool = AtomicBool::new(false);

/// Redraw while playing so the seek bar advances. Started by the first seek
/// bar, on the view it paints in.
fn start_refreshing(cx: &mut Context<crate::renderer::GpuixView>) {
    if REFRESHING.swap(true, Ordering::Relaxed) {
        return;
    }
    cx.spawn(async move |view, cx| {
        loop {
            cx.background_executor().timer(PROGRESS_INTERVAL).await;
            if !aurelia_desktop::runtime().progress().playing {
                continue;
            }
            if view.update(cx, |_, cx| cx.notify()).is_err() {
                break;
            }
        }
        REFRESHING.store(false, Ordering::Relaxed);
    })
    .detach();
}

pub struct WaveFactory;

impl CustomElementFactory for WaveFactory {
    fn element_type(&self) -> &str {
        "aurelia-wave"
    }

    fn create(&self, _: u64) -> Box<dyn CustomElement> {
        Box::new(Wave::default())
    }
}

/// `<aurelia-wave>`: the played part of the song as a gentle wave that flattens
/// while paused, the rest as a straight track. Props: `color`, `trackColor`,
/// `thumb` (show the knob), `preview` (a 0–1 position while the user drags),
/// `amplitude`, `wavelength` and `thickness` in pixels.
struct Wave {
    color: Rgba,
    track_color: Rgba,
    thumb: bool,
    preview: Option<f32>,
    amplitude: f32,
    wavelength: f32,
    thickness: f32,
    /// Wave height from 0 (flat) to 1, eased toward the playing state.
    rise: f32,
    rise_target: f32,
    rise_from: f32,
    rise_changed: Instant,
}

impl Default for Wave {
    fn default() -> Self {
        Self {
            color: rgba(0xf1f0f8ff),
            track_color: rgba(0xffffff29),
            thumb: false,
            preview: None,
            amplitude: 2.6,
            wavelength: 28.0,
            thickness: 4.0,
            rise: 0.0,
            rise_target: 0.0,
            rise_from: 0.0,
            rise_changed: Instant::now(),
        }
    }
}

fn color_prop(value: &serde_json::Value, fallback: Rgba) -> Rgba {
    value
        .as_str()
        .and_then(crate::color::parse_color_rgba)
        .unwrap_or(fallback)
}

impl Wave {
    fn ease_rise(&mut self, playing: bool, window: &mut Window) -> f32 {
        let target = if playing { 1.0 } else { 0.0 };
        if target != self.rise_target {
            self.rise_from = self.rise;
            self.rise_target = target;
            self.rise_changed = Instant::now();
        }
        let t = (self.rise_changed.elapsed().as_secs_f32() / WAVE_EASE.as_secs_f32()).min(1.0);
        let eased = 1.0 - (1.0 - t).powi(3);
        self.rise = self.rise_from + (self.rise_target - self.rise_from) * eased;
        if t < 1.0 {
            window.request_animation_frame();
        }
        self.rise
    }
}

impl CustomElement for Wave {
    fn set_prop(&mut self, key: &str, value: serde_json::Value) {
        let defaults = Wave::default();
        match key {
            "color" => self.color = color_prop(&value, defaults.color),
            "trackColor" => self.track_color = color_prop(&value, defaults.track_color),
            "thumb" => self.thumb = value.as_bool().unwrap_or(false),
            "preview" => self.preview = value.as_f64().map(|v| v.clamp(0.0, 1.0) as f32),
            "amplitude" => self.amplitude = value.as_f64().map_or(defaults.amplitude, |v| v as f32),
            "wavelength" => {
                self.wavelength = value
                    .as_f64()
                    .map_or(defaults.wavelength, |v| (v as f32).max(4.0))
            }
            "thickness" => self.thickness = value.as_f64().map_or(defaults.thickness, |v| v as f32),
            _ => {}
        }
    }

    fn supported_props(&self) -> &'static [&'static str] {
        &[
            "color",
            "trackColor",
            "thumb",
            "preview",
            "amplitude",
            "wavelength",
            "thickness",
        ]
    }

    fn supported_events(&self) -> &'static [&'static str] {
        &[]
    }

    fn destroy(&mut self) {}

    fn render(
        &mut self,
        ctx: CustomRenderContext,
        window: &mut Window,
        cx: &mut Context<crate::renderer::GpuixView>,
    ) -> AnyElement {
        start_refreshing(cx);
        let progress = aurelia_desktop::runtime().progress();
        let fraction = self.preview.unwrap_or(if progress.duration > 0.0 {
            (progress.position / progress.duration).clamp(0.0, 1.0) as f32
        } else {
            0.0
        });
        let rise = self.ease_rise(progress.playing, window);
        let amplitude = self.amplitude * rise;
        let (color, track_color, thumb) = (self.color, self.track_color, self.thumb);
        let (wavelength, thickness) = (self.wavelength, self.thickness);

        custom_surface(
            div().id(SharedString::from(format!("__aurelia_wave_{}", ctx.id))),
            &ctx,
        )
        .child(
            canvas(
                |_, _, _| {},
                move |bounds, _, window, _| {
                    paint_wave(
                        bounds,
                        fraction,
                        amplitude,
                        wavelength,
                        thickness,
                        color,
                        track_color,
                        thumb,
                        window,
                    );
                },
            )
            .size_full(),
        )
        .into_any_element()
    }
}

#[allow(clippy::too_many_arguments)]
fn paint_wave(
    bounds: Bounds<Pixels>,
    fraction: f32,
    amplitude: f32,
    wavelength: f32,
    thickness: f32,
    color: Rgba,
    track_color: Rgba,
    thumb: bool,
    window: &mut Window,
) {
    let width = f32::from(bounds.size.width);
    let middle = f32::from(bounds.origin.y) + f32::from(bounds.size.height) / 2.0;
    let left = f32::from(bounds.origin.x);
    let played = width * fraction;
    let half = thickness / 2.0;

    // The rest of the song: a straight rounded track after a small gap.
    let rest_start = (played + if played > 0.0 { thickness * 1.5 } else { 0.0 }).min(width);
    if rest_start < width {
        window.paint_quad(
            fill(
                Bounds::from_corners(
                    point(px(left + rest_start), px(middle - half)),
                    point(px(left + width), px(middle + half)),
                ),
                track_color,
            )
            .corner_radii(px(half)),
        );
    }

    // What has played: a sine wave that eases flat at both ends.
    if played > 1.0 {
        let mut path = PathBuilder::stroke(px(thickness));
        let mut x = 0.0f32;
        path.move_to(point(px(left), px(middle)));
        while x < played {
            x = (x + 2.0).min(played);
            let ease = (x / 18.0).min((played - x) / 18.0 + 0.15).clamp(0.0, 1.0);
            let y = (x / wavelength * std::f32::consts::TAU).sin() * amplitude * ease;
            path.line_to(point(px(left + x), px(middle + y)));
        }
        if let Ok(path) = path.build() {
            window.paint_path(path, color);
        }
        // Round the start of the stroke.
        window.paint_quad(
            fill(
                Bounds::centered_at(
                    point(px(left), px(middle)),
                    size(px(thickness), px(thickness)),
                ),
                color,
            )
            .corner_radii(px(half)),
        );
    }

    if thumb {
        let radius = thickness * 1.6;
        window.paint_quad(
            fill(
                Bounds::centered_at(
                    point(px(left + played), px(middle)),
                    size(px(radius * 2.0), px(radius * 2.0)),
                ),
                color,
            )
            .corner_radii(px(radius)),
        );
    }
}
