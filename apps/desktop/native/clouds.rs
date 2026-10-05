//! `<aurelia-clouds>`: the full player's backdrop, after Android's. Broad
//! clouds of the art's colors drift over a dark base and fade to a new song's
//! colors over a few seconds. GPUI has no radial gradient, so each cloud is
//! the blurred shadow of a disc.
use crate::custom_elements::{
    custom_surface, CustomElement, CustomElementFactory, CustomRenderContext,
};
use gpui::{prelude::*, *};
use std::time::{Duration, Instant};

/// New colors fade in over this long.
const FADE: Duration = Duration::from_secs(5);
const BASE: u32 = 0x100f18ff;

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
        if key != "colors" {
            return;
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
        &["colors"]
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

        custom_surface(
            div().id(SharedString::from(format!("__aurelia_clouds_{}", ctx.id))),
            &ctx,
        )
        .child(
            canvas(
                |_, _, _| {},
                move |bounds, _, window, _| {
                    window.with_content_mask(Some(ContentMask { bounds }), |window| {
                        paint_clouds(bounds, colors, seconds, window)
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

/// Clouds on looping paths, then a shade toward the bottom.
fn paint_clouds(
    bounds: Bounds<Pixels>,
    colors: Option<[Rgba; 3]>,
    seconds: f64,
    window: &mut Window,
) {
    window.paint_quad(fill(bounds, rgba(BASE)));
    if let Some(colors) = colors {
        let width = f32::from(bounds.size.width);
        let height = f32::from(bounds.size.height);
        for cloud in &CLOUDS {
            let x = cloud.home.0 + cloud.wander.0 * (seconds * cloud.speed.0 + cloud.phase).sin();
            let y = cloud.home.1 + cloud.wander.1 * (seconds * cloud.speed.1 + cloud.phase).cos();
            let center = bounds.origin + point(px(width * x as f32), px(height * y as f32));
            // Each cloud swells and shrinks a little as it goes. Clouds this
            // size leave dark gaps between them, so overlapping hues don't
            // all average to brown. A disc this size, blurred this much,
            // peaks at 70% and fades out by `reach`.
            let swell = 1.0 + 0.18 * (seconds * 0.37 + cloud.phase * 1.9).sin();
            let reach = width.max(height) * 0.5 * swell as f32;
            let radius = px(reach * 0.48);
            let blur = px(reach * 0.31);
            // Art colors are toned for flat tints; a little richer reads
            // better as light.
            let mut color = Hsla::from(colors[cloud.color]);
            color.s = (color.s * 1.15).min(1.0);
            color.l = color.l.clamp(0.38, 0.5);
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
    }
    window.paint_quad(fill(
        bounds,
        linear_gradient(
            180.0,
            linear_color_stop(rgba(0x0000000f), 0.0),
            linear_color_stop(rgba(0x00000059), 1.0),
        ),
    ));
}
