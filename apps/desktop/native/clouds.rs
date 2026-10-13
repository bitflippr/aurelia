//! `<aurelia-clouds>`: the ground the glass lies on. In the full player,
//! after Android's, broad clouds of the art's colors drift over the base and
//! fade to a new song's colors over a few seconds. Behind the window they
//! hold `still`, as soft pools in the corners, and redraw only while they
//! fade. `theme` picks a pale shore or a dark tide pool. GPUI has no radial
//! gradient, so each cloud is the blurred shadow of a disc.
use crate::custom_elements::{
    custom_surface, CustomElement, CustomElementFactory, CustomRenderContext,
};
use gpui::{prelude::*, *};
use std::time::{Duration, Instant};

/// New colors fade in over this long.
const FADE: Duration = Duration::from_secs(5);
const NIGHT_BASE: u32 = 0x09090fff;
const SHORE_BASE: u32 = 0xebf0eeff;
/// Night tide's faint violet haze from above, as under a blacklight.
const HAZE: u32 = 0x604ae0ff;

pub struct CloudsFactory;

impl CustomElementFactory for CloudsFactory {
    fn element_type(&self) -> &str {
        "aurelia-clouds"
    }

    fn create(&self, _: u64) -> Box<dyn CustomElement> {
        Box::new(Clouds::default())
    }
}

/// Props: `colors`, three CSS colors, most prominent first. Without them the
/// clouds keep their current colors.
#[derive(Default)]
struct Clouds {
    colors: Option<[Rgba; 3]>,
    /// What the colors were fading from when they last changed.
    from: [Rgba; 3],
    changed: Option<Instant>,
    /// Time the clouds have drifted for; it stands still while undrawn.
    seconds: f64,
    last_frame: Option<Instant>,
    drawn: bool,
    shore: bool,
    still: bool,
}

impl Clouds {
    fn shown(&self) -> Option<[Rgba; 3]> {
        let colors = self.colors?;
        let Some(changed) = self.changed else {
            return Some(colors);
        };
        let t = (changed.elapsed().as_secs_f32() / FADE.as_secs_f32()).min(1.0);
        let t = ease_in_out(t);
        Some(std::array::from_fn(|i| mix(self.from[i], colors[i], t)))
    }

    fn fading(&self) -> bool {
        self.changed.is_some_and(|changed| changed.elapsed() < FADE)
    }
}

fn mix(from: Rgba, to: Rgba, t: f32) -> Rgba {
    Rgba {
        r: from.r + (to.r - from.r) * t,
        g: from.g + (to.g - from.g) * t,
        b: from.b + (to.b - from.b) * t,
        a: from.a + (to.a - from.a) * t,
    }
}

impl CustomElement for Clouds {
    fn set_prop(&mut self, key: &str, value: serde_json::Value) {
        match key {
            "theme" => {
                self.shore = value.as_str() == Some("shore");
                return;
            }
            "still" => {
                self.still = value.as_bool().unwrap_or(false);
                return;
            }
            "colors" => {}
            _ => return,
        }
        let Some(colors) = value.as_array().and_then(|list| {
            let parsed: Vec<Rgba> = list
                .iter()
                .filter_map(|color| color.as_str().and_then(crate::color::parse_color_rgba))
                .collect();
            <[Rgba; 3]>::try_from(parsed).ok()
        }) else {
            return;
        };
        if self.colors == Some(colors) {
            return;
        }
        // The first colors appear at once, unless the base already shows; then
        // they fade in like any change.
        self.from = match self.shown() {
            Some(shown) => shown,
            None if self.drawn => colors.map(|color| Rgba { a: 0.0, ..color }),
            None => {
                self.colors = Some(colors);
                return;
            }
        };
        self.colors = Some(colors);
        self.changed = Some(Instant::now());
    }

    fn supported_props(&self) -> &'static [&'static str] {
        &["colors", "theme", "still"]
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
        self.drawn = true;
        let now = Instant::now();
        if cx.reduce_motion() {
            self.changed = None;
            self.last_frame = None;
        } else if self.still {
            // Still clouds hold their places and redraw only to fade.
            self.last_frame = None;
            if self.fading() {
                window.request_animation_frame();
            }
        } else {
            if let Some(last) = self.last_frame {
                // A minimized window gets no frames; the clouds carry on from
                // where they were instead of jumping.
                self.seconds += (now - last).as_secs_f64().min(0.1);
            }
            self.last_frame = Some(now);
            window.request_animation_frame();
        }
        if !self.fading() {
            self.changed = None;
        }
        let colors = self.shown();
        let seconds = self.seconds;
        let (shore, still) = (self.shore, self.still);

        custom_surface(
            div().id(SharedString::from(format!("__aurelia_clouds_{}", ctx.id))),
            &ctx,
        )
        .child(
            canvas(
                |_, _, _| {},
                move |bounds, _, window, _| {
                    window.with_content_mask(Some(ContentMask { bounds }), |window| {
                        if still {
                            paint_ground(bounds, colors, shore, window)
                        } else {
                            paint_clouds(bounds, colors, seconds, shore, window)
                        }
                    });
                },
            )
            .size_full(),
        )
        .into_any_element()
    }
}

/// A cloud: its palette color, the point it circles, how far it wanders from
/// there on each axis and how fast, in fractions of the backdrop and radians
/// per second.
struct Cloud {
    color: usize,
    home: (f64, f64),
    wander: (f64, f64),
    speed: (f64, f64),
    phase: f64,
}

/// Loops with unrelated speeds, so the pattern takes minutes to come around.
/// Android's `ArtworkCloudBackdrop` uses the same clouds.
const CLOUDS: [Cloud; 5] = [
    Cloud {
        color: 0,
        home: (0.22, 0.25),
        wander: (0.28, 0.22),
        speed: (0.31, 0.23),
        phase: 0.0,
    },
    Cloud {
        color: 1,
        home: (0.78, 0.3),
        wander: (0.25, 0.25),
        speed: (0.19, 0.29),
        phase: 1.7,
    },
    Cloud {
        color: 2,
        home: (0.35, 0.8),
        wander: (0.3, 0.2),
        speed: (0.26, 0.17),
        phase: 3.1,
    },
    Cloud {
        color: 1,
        home: (0.75, 0.85),
        wander: (0.24, 0.2),
        speed: (0.22, 0.33),
        phase: 4.4,
    },
    Cloud {
        color: 0,
        home: (0.5, 0.5),
        wander: (0.35, 0.3),
        speed: (0.15, 0.21),
        phase: 2.3,
    },
];

/// A cloud of `color`, the blurred shadow of a disc `reach` from its middle
/// to where it has faded out.
fn paint_cloud(center: Point<Pixels>, reach: f32, color: Hsla, window: &mut Window) {
    // A disc this size, blurred this much, peaks at 70% and fades out by
    // `reach`.
    let radius = px(reach * 0.48);
    let blur = px(reach * 0.31);
    window.paint_drop_shadows(
        Bounds::centered_at(center, size(radius * 2.0, radius * 2.0)),
        Corners::all(radius),
        &[BoxShadow {
            color,
            offset: point(px(0.0), px(0.0)),
            blur_radius: blur,
            spread_radius: px(0.0),
            inset: false,
        }],
    );
}

/// Art colors are toned for flat tints. As light on a pale shore they are
/// lifted and softened; in the dark they stay deep, so they read as color in
/// the water rather than as glow.
fn as_light(color: Rgba, shore: bool, alpha: f32) -> Hsla {
    let mut color = Hsla::from(color);
    if shore {
        color.s = (color.s * 0.8).min(0.75);
        color.l = color.l.clamp(0.74, 0.84);
    } else {
        color.s = (color.s * 1.1).min(1.0);
        color.l = color.l.clamp(0.3, 0.42);
    }
    color.a *= alpha;
    color
}

/// Frost over the ground, about a point to a grain. Barely there: the grain
/// belongs to the glass, and the ground only keeps the pools from banding.
fn paint_frost(bounds: Bounds<Pixels>, shore: bool, window: &mut Window) {
    let cell = window.scale_factor().max(1.0);
    let (light, dark) = if shore {
        (rgba(0xffffff08), rgba(0x0a282405))
    } else {
        (rgba(0xffffff03), rgba(0x00000008))
    };
    window.paint_quad(fill(bounds, grain(light, dark, cell, 0.0)));
}

/// The window's ground: still pools of the art's colors in the corners.
fn paint_ground(
    bounds: Bounds<Pixels>,
    colors: Option<[Rgba; 3]>,
    shore: bool,
    window: &mut Window,
) {
    window.paint_quad(fill(
        bounds,
        rgba(if shore { SHORE_BASE } else { NIGHT_BASE }),
    ));
    let width = f32::from(bounds.size.width);
    let height = f32::from(bounds.size.height);
    let at = |x: f32, y: f32| bounds.origin + point(px(width * x), px(height * y));
    let reach = width.max(height) * 0.42;
    if !shore {
        paint_cloud(
            at(0.5, -0.16),
            reach * 1.2,
            Hsla::from(rgba(HAZE)).opacity(0.17),
            window,
        );
    }
    if let Some(colors) = colors {
        let (strong, faint) = if shore { (0.62, 0.3) } else { (0.36, 0.16) };
        paint_cloud(
            at(0.06, -0.06),
            reach,
            as_light(colors[0], shore, strong),
            window,
        );
        paint_cloud(
            at(0.98, 0.06),
            reach * 0.9,
            as_light(colors[1], shore, faint),
            window,
        );
        paint_cloud(
            at(0.52, 1.1),
            reach * 1.1,
            as_light(colors[0], shore, strong * 0.85),
            window,
        );
    }
    paint_frost(bounds, shore, window);
}

/// Clouds on looping paths, then a shade toward the bottom.
fn paint_clouds(
    bounds: Bounds<Pixels>,
    colors: Option<[Rgba; 3]>,
    seconds: f64,
    shore: bool,
    window: &mut Window,
) {
    window.paint_quad(fill(
        bounds,
        rgba(if shore { SHORE_BASE } else { NIGHT_BASE }),
    ));
    let width = f32::from(bounds.size.width);
    let height = f32::from(bounds.size.height);
    if !shore {
        let haze = bounds.origin + point(px(width * 0.5), px(height * -0.16));
        paint_cloud(
            haze,
            width.max(height) * 0.5,
            Hsla::from(rgba(HAZE)).opacity(0.17),
            window,
        );
    }
    if let Some(colors) = colors {
        for cloud in &CLOUDS {
            let x = cloud.home.0 + cloud.wander.0 * (seconds * cloud.speed.0 + cloud.phase).sin();
            let y = cloud.home.1 + cloud.wander.1 * (seconds * cloud.speed.1 + cloud.phase).cos();
            let center = bounds.origin + point(px(width * x as f32), px(height * y as f32));
            // Each cloud swells and shrinks a little as it goes. Clouds this
            // size leave gaps between them, so overlapping hues don't all
            // average to brown.
            let swell = 1.0 + 0.18 * (seconds * 0.37 + cloud.phase * 1.9).sin();
            let reach = width.max(height) * 0.5 * swell as f32;
            let alpha = if shore { 0.8 } else { 0.5 };
            paint_cloud(
                center,
                reach,
                as_light(colors[cloud.color], shore, alpha),
                window,
            );
        }
    }
    let (top, bottom) = if shore {
        (rgba(0xffffff00), rgba(0x0a282412))
    } else {
        (rgba(0x0000000f), rgba(0x00000066))
    };
    window.paint_quad(fill(
        bounds,
        linear_gradient(
            180.0,
            linear_color_stop(top, 0.0),
            linear_color_stop(bottom, 1.0),
        ),
    ));
    paint_frost(bounds, shore, window);
}
