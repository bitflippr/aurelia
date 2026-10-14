//! `<aurelia-glass>`: a piece of sea glass, painted behind the content of the
//! element it sits in. Place it first, absolutely filling its parent; the
//! parent keeps the events, focus and layout, and the glass only draws.
//!
//! Two lights, one per theme:
//!
//! - **Shore** lights the glass from above. Its color is in the body, two soft
//!   lobes of lighter tint scatter inside it, a bright lip marks the thick
//!   lower edge, and the light that passes through tints the ground below.
//! - **Night tide** lights it from behind, as on a light table. Thick glass
//!   absorbs the most, so the body is deep and brightens toward its thin edge,
//!   a hairline rim catches the light, and nothing spills outside the glass.
//!   A filled piece that is playing fluoresces instead, as uranium glass does
//!   under UV: brightest in the middle, in its own color.
//!
//! Props: `tint` (`{ b, l, d, u }`: body, inner light, deep and fluorescent
//! colors), `variant` (`tonal`, `filled`, `pearl`, `panel`, `sheet` or `frost`),
//! `theme` (`shore` or `night`), `radius` (one for every corner, or `[top left,
//! top right, bottom right, bottom left]`), `live` (the light drifts while music
//! plays) and `interactive` (lifts under the pointer and sinks when pressed).
//!
//! A panel whose page scrolls is `plain`: the page lays `frost`, the panel's grain
//! alone, under each of its rows, so the grain moves with the content. `trail`
//! carries a frost on past its bottom, under the empty end of a page.
use crate::custom_elements::{
    custom_surface, CustomElement, CustomElementFactory, CustomRenderContext,
};
use gpui::{prelude::*, *};
use std::{
    cell::Cell,
    rc::Rc,
    sync::{
        atomic::{AtomicBool, Ordering},
        OnceLock,
    },
    time::{Duration, Instant},
};

/// While something plays, drifting light redraws this often. It moves a few
/// pixels a second, so steps this small read as continuous.
const DRIFT_INTERVAL: Duration = Duration::from_millis(100);
/// One full wander of the drifting light.
const DRIFT_PERIOD: f32 = 28.0;
/// A new radius eases in over this long, as the play button rounds on pause.
const RESHAPE: Duration = Duration::from_millis(260);

pub struct GlassFactory;

impl CustomElementFactory for GlassFactory {
    fn element_type(&self) -> &str {
        "aurelia-glass"
    }

    fn create(&self, _: u64) -> Box<dyn CustomElement> {
        Box::new(Glass::default())
    }
}

#[derive(Clone, Copy, PartialEq, Default)]
enum Variant {
    #[default]
    Tonal,
    Filled,
    Pearl,
    Panel,
    Sheet,
    Frost,
}

#[derive(Clone, Copy, PartialEq, Default)]
enum Theme {
    #[default]
    Night,
    Shore,
}

#[derive(Clone, Copy)]
struct Tint {
    /// The body, as a pale piece shows it.
    b: Rgba,
    /// Light scattering inside the body.
    l: Rgba,
    /// The deep tone: filled pieces, thick edges and colored shadows.
    d: Rgba,
    /// What the glass fluoresces under UV.
    u: Rgba,
}

impl Default for Tint {
    // Aurelia's own moon jelly.
    fn default() -> Self {
        Tint {
            b: rgb(0xcbc9f7),
            l: rgb(0xf4f3ff),
            d: rgb(0x4d44c5),
            u: rgb(0xbdb6ff),
        }
    }
}

#[derive(Default)]
struct Glass {
    tint: Tint,
    variant: Variant,
    theme: Theme,
    radius: [f32; 4],
    /// The radius a change eases from, and when it started.
    reshape: Option<([f32; 4], Instant)>,
    live: bool,
    interactive: bool,
    plain: bool,
    trail: f32,
    pressed: Rc<Cell<bool>>,
    drawn: bool,
}

impl Glass {
    /// The radius now, partway through easing to a new one.
    fn shown_radius(&mut self) -> [f32; 4] {
        let Some((from, at)) = self.reshape else {
            return self.radius;
        };
        let t = at.elapsed().as_secs_f32() / RESHAPE.as_secs_f32();
        if t >= 1.0 {
            self.reshape = None;
            return self.radius;
        }
        // Ease out, as the interface's other motion does.
        let eased = 1.0 - (1.0 - t).powi(3);
        std::array::from_fn(|i| from[i] + (self.radius[i] - from[i]) * eased)
    }
}

fn color(value: &serde_json::Value, key: &str, fallback: Rgba) -> Rgba {
    value
        .get(key)
        .and_then(|v| v.as_str())
        .and_then(crate::color::parse_color_rgba)
        .unwrap_or(fallback)
}

impl CustomElement for Glass {
    fn set_prop(&mut self, key: &str, value: serde_json::Value) {
        match key {
            "tint" => {
                let fallback = Tint::default();
                self.tint = Tint {
                    b: color(&value, "b", fallback.b),
                    l: color(&value, "l", fallback.l),
                    d: color(&value, "d", fallback.d),
                    u: color(&value, "u", fallback.u),
                };
            }
            "variant" => {
                self.variant = match value.as_str() {
                    Some("filled") => Variant::Filled,
                    Some("pearl") => Variant::Pearl,
                    Some("panel") => Variant::Panel,
                    Some("sheet") => Variant::Sheet,
                    Some("frost") => Variant::Frost,
                    _ => Variant::Tonal,
                }
            }
            "theme" => {
                self.theme = match value.as_str() {
                    Some("shore") => Theme::Shore,
                    _ => Theme::Night,
                }
            }
            "radius" => {
                let radius = match value.as_array() {
                    Some(list) if list.len() == 4 => {
                        std::array::from_fn(|i| list[i].as_f64().unwrap_or(0.0) as f32)
                    }
                    _ => [value.as_f64().unwrap_or(0.0) as f32; 4],
                };
                if radius != self.radius {
                    // The first radius is simply the shape; later ones ease in.
                    if self.drawn {
                        let from = self.shown_radius();
                        self.reshape = Some((from, Instant::now()));
                    }
                    self.radius = radius;
                }
            }
            "live" => self.live = value.as_bool().unwrap_or(false),
            "interactive" => self.interactive = value.as_bool().unwrap_or(false),
            "plain" => self.plain = value.as_bool().unwrap_or(false),
            "trail" => self.trail = value.as_f64().unwrap_or(0.0).max(0.0) as f32,
            _ => {}
        }
    }

    fn supported_props(&self) -> &'static [&'static str] {
        &[
            "tint",
            "variant",
            "theme",
            "radius",
            "live",
            "interactive",
            "plain",
            "trail",
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
        let playing = aurelia_desktop::runtime().progress().playing;
        let drifting = self.live && playing && !cx.reduce_motion();
        if drifting {
            start_drifting(cx);
        }
        self.drawn = true;
        let radius = if cx.reduce_motion() {
            self.reshape = None;
            self.radius
        } else {
            self.shown_radius()
        };
        if self.reshape.is_some() {
            window.request_animation_frame();
        }
        let look = Look {
            tint: self.tint,
            variant: self.variant,
            theme: self.theme,
            radius,
            live: self.live,
            plain: self.plain,
            trail: self.trail,
            phase: if drifting { drift_phase() } else { 0.0 },
        };
        let interactive = self.interactive;
        let pressed = self.pressed.clone();

        custom_surface(
            div().id(SharedString::from(format!("__aurelia_glass_{}", ctx.id))),
            &ctx,
        )
        .child(
            canvas(
                move |bounds, window, _| {
                    interactive.then(|| window.insert_hitbox(bounds, HitboxBehavior::Normal))
                },
                move |bounds, hitbox, window, _| {
                    let mut state = State::default();
                    if let Some(hitbox) = hitbox {
                        state.hovered = hitbox.is_hovered(window);
                        state.pressed = pressed.get() && state.hovered;
                        watch(hitbox, state.hovered, pressed, window);
                    }
                    paint(bounds, &look, state, window);
                },
            )
            .size_full(),
        )
        .into_any_element()
    }
}

/// Redraw when the pointer enters or leaves, or presses and releases.
fn watch(hitbox: Hitbox, hovered: bool, pressed: Rc<Cell<bool>>, window: &mut Window) {
    let view = window.current_view();
    let moved = hitbox.clone();
    window.on_mouse_event(move |_: &MouseMoveEvent, phase, window, cx| {
        if phase == DispatchPhase::Bubble && moved.is_hovered(window) != hovered {
            cx.notify(view);
        }
    });
    let down = pressed.clone();
    window.on_mouse_event(move |event: &MouseDownEvent, phase, window, cx| {
        if phase == DispatchPhase::Bubble
            && event.button == MouseButton::Left
            && hitbox.is_hovered(window)
        {
            down.set(true);
            cx.notify(view);
        }
    });
    window.on_mouse_event(move |_: &MouseUpEvent, phase, _, cx| {
        if phase == DispatchPhase::Bubble && pressed.replace(false) {
            cx.notify(view);
        }
    });
}

static DRIFTING: AtomicBool = AtomicBool::new(false);

/// Redraw while playing so live glass drifts. Started by the first live piece.
fn start_drifting(cx: &mut Context<crate::renderer::GpuixView>) {
    if DRIFTING.swap(true, Ordering::Relaxed) {
        return;
    }
    cx.spawn(async move |view, cx| {
        loop {
            cx.background_executor().timer(DRIFT_INTERVAL).await;
            if !aurelia_desktop::runtime().progress().playing {
                break;
            }
            if view.update(cx, |_, cx| cx.notify()).is_err() {
                break;
            }
        }
        DRIFTING.store(false, Ordering::Relaxed);
    })
    .detach();
}

/// Where the drifting light is in its wander, from one shared clock so every
/// live piece moves together.
fn drift_phase() -> f32 {
    static START: OnceLock<Instant> = OnceLock::new();
    let seconds = START.get_or_init(Instant::now).elapsed().as_secs_f32();
    seconds / DRIFT_PERIOD * std::f32::consts::TAU
}

#[derive(Clone, Copy)]
struct Look {
    tint: Tint,
    variant: Variant,
    theme: Theme,
    radius: [f32; 4],
    live: bool,
    plain: bool,
    trail: f32,
    phase: f32,
}

#[derive(Clone, Copy, Default)]
struct State {
    hovered: bool,
    pressed: bool,
}

/// The two lobes of light inside the glass, as fractions of its box: still at
/// the top left and bottom right, or wandering while live glass plays.
fn lobes(phase: f32) -> [(f32, f32); 2] {
    if phase == 0.0 {
        return [(0.24, 0.1), (0.84, 0.98)];
    }
    [
        (
            0.48 - 0.28 * phase.cos(),
            0.14 + 0.08 * (1.7 * phase + 1.0).sin(),
        ),
        (
            0.54 + 0.32 * (phase + 0.6).cos(),
            0.93 + 0.05 * (1.3 * phase).sin(),
        ),
    ]
}

fn paint(bounds: Bounds<Pixels>, look: &Look, state: State, window: &mut Window) {
    let short = f32::from(bounds.size.width.min(bounds.size.height));
    let corner = |r: f32| px(r.min(short / 2.0).max(0.0));
    let [top_left, top_right, bottom_right, bottom_left] = look.radius;
    let radii = Corners {
        top_left: corner(top_left),
        top_right: corner(top_right),
        bottom_right: corner(bottom_right),
        bottom_left: corner(bottom_left),
    };
    match (look.variant, look.theme) {
        (Variant::Frost, theme) => paint_frost(bounds, look.trail, theme, window),
        (Variant::Panel | Variant::Sheet, theme) => paint_surface(
            bounds,
            radii,
            look.variant == Variant::Sheet,
            !look.plain,
            theme,
            window,
        ),
        (_, Theme::Shore) => paint_shore(bounds, radii, look, window),
        (Variant::Filled, Theme::Night) if look.live => {
            paint_fluorescent(bounds, radii, look, window)
        }
        (_, Theme::Night) => paint_night(bounds, radii, look, window),
    }
    if state.hovered || state.pressed {
        let wash = match (state.pressed, look.theme) {
            (true, Theme::Shore) => hsla_of(rgba(0x0a1e1c0f)),
            (true, Theme::Night) => hsla_of(rgba(0x00000024)),
            (false, Theme::Shore) => hsla_of(rgba(0xffffff1f)),
            (false, Theme::Night) => hsla_of(rgba(0xffffff12)),
        };
        window.paint_quad(fill(bounds, wash).corner_radii(radii));
    }
}

/// Lit from above: the color is in the body, light scatters inside it, and
/// what passes through tints the ground.
fn paint_shore(bounds: Bounds<Pixels>, radii: Corners<Pixels>, look: &Look, window: &mut Window) {
    let t = look.tint;
    let (body, lobe, edge, strength) = match look.variant {
        Variant::Filled => (
            t.d,
            oklab_mix(t.d, t.b, 0.45),
            oklab_mix(t.d, BLACK, 0.35),
            (0.6, 0.4),
        ),
        _ => (t.b, t.l, t.d, (0.85, 0.5)),
    };
    let short = f32::from(bounds.size.width.min(bounds.size.height));

    window.paint_outer_shadows(
        bounds,
        radii,
        &[
            shadow(0.0, 1.0, 0.75, 0.0, rgba(0x0a1e1c24)),
            shadow(0.0, 10.0, 10.0, -10.0, with_alpha(edge, 0.55)),
        ],
    );
    window.paint_quad(
        fill(
            bounds,
            linear_gradient(
                172.0,
                linear_color_stop(hsla_of(body), 0.0),
                linear_color_stop(hsla_of(oklab_mix(body, edge, 0.24)), 1.0),
            )
            .color_space(ColorSpace::Oklab),
        )
        .corner_radii(radii),
    );
    let [first, second] = lobes(look.phase);
    window.paint_inner_glows(
        bounds,
        radii,
        &[
            lobe_glow(
                bounds,
                first,
                (0.42, 0.6),
                short * 0.16,
                with_alpha(lobe, strength.0),
            ),
            lobe_glow(
                bounds,
                second,
                (0.36, 0.5),
                short * 0.14,
                with_alpha(lobe, strength.1),
            ),
        ],
    );
    window.paint_inset_shadows(
        bounds,
        radii,
        &[
            inset(0.0, 1.0, 0.25, 0.0, rgba(0xffffffbf)),
            inset(0.0, -1.5, 0.25, 0.0, rgba(0xffffff80)),
            inset(0.0, 0.0, 0.0, 1.0, with_alpha(edge, 0.16)),
            inset(0.0, -12.0, 8.0, -12.0, with_alpha(edge, 0.36)),
        ],
    );
    // Bright pits stand out on deep glass; keep them few there.
    if look.variant == Variant::Filled {
        paint_grain(
            bounds,
            radii,
            rgba(0xffffff08),
            rgba(0x00000008),
            0.001,
            window,
        );
    } else {
        paint_grain(
            bounds,
            radii,
            rgba(0xffffff0c),
            rgba(0x0a282408),
            0.0015,
            window,
        );
    }
}

/// Lit from behind: deep where the glass is thick, brighter toward the thin
/// edge, a hairline rim, and no light outside the glass.
fn paint_night(bounds: Bounds<Pixels>, radii: Corners<Pixels>, look: &Look, window: &mut Window) {
    let t = look.tint;
    let (core, thin) = match look.variant {
        Variant::Filled => (oklab_mix(t.b, t.d, 0.12), t.l),
        Variant::Pearl => (oklab_mix(t.b, t.d, 0.28), oklab_mix(t.l, t.b, 0.15)),
        _ => (oklch_scale(t.d, 0.78, 0.88), oklch_lift(t.d, 0.08)),
    };
    let short = f32::from(bounds.size.width.min(bounds.size.height));
    // How far in from the edge the glass stays thin.
    let rim = (short * 0.16).clamp(5.0, 16.0);

    window.paint_outer_shadows(
        bounds,
        radii,
        &[
            shadow(0.0, 1.0, 1.0, 0.0, rgba(0x000000a6)),
            shadow(0.0, 8.0, 9.0, -8.0, rgba(0x000000bf)),
        ],
    );
    window.paint_quad(
        fill(
            bounds,
            linear_gradient(
                180.0,
                linear_color_stop(hsla_of(oklab_mix(core, thin, 0.15)), 0.0),
                linear_color_stop(hsla_of(core), 0.45),
            )
            .color_space(ColorSpace::Oklab),
        )
        .corner_radii(radii),
    );
    if look.live {
        let [first, _] = lobes(look.phase);
        window.paint_inner_glows(
            bounds,
            radii,
            &[lobe_glow(
                bounds,
                first,
                (0.42, 0.6),
                short * 0.16,
                with_alpha(thin, 0.26),
            )],
        );
    }
    window.paint_inset_shadows(
        bounds,
        radii,
        &[
            inset(0.0, 0.0, rim * 1.2, 0.0, with_alpha(thin, 0.5)),
            inset(0.0, 0.0, rim * 0.3, 0.0, with_alpha(thin, 0.45)),
            inset(0.0, 0.0, 0.0, 1.0, with_alpha(t.b, 0.16)),
            inset(0.0, 1.0, 0.0, 0.0, with_alpha(t.b, 0.4)),
        ],
    );
    // Lit from behind, the frost shows as dark pits with a few bright bubbles.
    // On a pale piece the pits read as dirt, so they stay shallow there.
    if look.variant == Variant::Tonal {
        paint_grain(
            bounds,
            radii,
            rgba(0xffffff06),
            rgba(0x00000012),
            0.001,
            window,
        );
    } else {
        paint_grain(
            bounds,
            radii,
            rgba(0xffffff08),
            rgba(0x00000008),
            0.0,
            window,
        );
    }
}

/// Fluorescing under UV: the glass makes its own light, brightest where it is
/// thickest, with only a faint spill right beneath it.
fn paint_fluorescent(
    bounds: Bounds<Pixels>,
    radii: Corners<Pixels>,
    look: &Look,
    window: &mut Window,
) {
    let t = look.tint;
    let full = bounds.size;
    let short = f32::from(full.width.min(full.height));
    window.paint_outer_shadows(
        bounds,
        radii,
        &[
            shadow(0.0, 1.0, 1.0, 0.0, rgba(0x00000099)),
            shadow(0.0, 6.0, 6.0, -6.0, with_alpha(t.u, 0.45)),
        ],
    );
    window.paint_quad(fill(bounds, hsla_of(oklab_mix(t.u, t.d, 0.3))).corner_radii(radii));
    let center = bounds.center();
    let body = Bounds::centered_at(center, size(full.width * 0.76, full.height * 0.76));
    let core = Bounds::centered_at(center, size(full.width * 0.38, full.height * 0.38));
    let [first, _] = lobes(look.phase);
    window.paint_inner_glows(
        bounds,
        radii,
        &[
            InnerGlow {
                bounds: body,
                corner_radii: Corners::all(body.size.width.min(body.size.height) / 2.0),
                blur_radius: px(short * 0.16),
                color: hsla_of(t.u),
            },
            InnerGlow {
                bounds: core,
                corner_radii: Corners::all(core.size.width.min(core.size.height) / 2.0),
                blur_radius: px(short * 0.14),
                color: hsla_of(oklab_mix(t.u, WHITE, 0.28)),
            },
            lobe_glow(bounds, first, (0.4, 0.55), short * 0.14, rgba(0xffffff61)),
        ],
    );
    window.paint_inset_shadows(
        bounds,
        radii,
        &[
            inset(0.0, 0.0, 0.0, 1.0, oklab_mix(t.u, WHITE, 0.3)),
            inset(0.0, -2.0, 3.0, -2.0, with_alpha(t.d, 0.45)),
        ],
    );
    paint_grain(
        bounds,
        radii,
        rgba(0xffffff0a),
        rgba(0x00000008),
        0.0015,
        window,
    );
}

/// Frosted panels the pages and the player sit on, and sheets for menus and
/// dialogs. A sheet covers content and nothing blurs what is behind it, so it
/// is opaque, frosted only by its grain and rim.
fn paint_surface(
    bounds: Bounds<Pixels>,
    radii: Corners<Pixels>,
    sheet: bool,
    frosted: bool,
    theme: Theme,
    window: &mut Window,
) {
    match theme {
        Theme::Shore => {
            window.paint_outer_shadows(
                bounds,
                radii,
                &if sheet {
                    [
                        shadow(0.0, 1.0, 1.0, 0.0, rgba(0x0f28260f)),
                        shadow(0.0, 18.0, 24.0, -12.0, rgba(0x0f282647)),
                    ]
                } else {
                    [
                        shadow(0.0, 1.0, 1.0, 0.0, rgba(0x0f28260f)),
                        shadow(0.0, 22.0, 23.0, -28.0, rgba(0x0f323066)),
                    ]
                },
            );
            let body = if sheet {
                rgba(0xf8fbfaff)
            } else {
                rgba(0xffffff80)
            };
            window.paint_quad(fill(bounds, body).corner_radii(radii));
            window.paint_inset_shadows(
                bounds,
                radii,
                &[
                    inset(0.0, 1.0, 0.0, 0.0, rgba(0xfffffff2)),
                    inset(0.0, -1.0, 0.0, 0.0, rgba(0xffffff73)),
                    inset(0.0, 0.0, 0.0, 1.0, rgba(0xffffff66)),
                ],
            );
            if frosted {
                let (light, dark) = surface_grain(theme);
                paint_grain(bounds, radii, light, dark, 0.0, window);
            }
        }
        Theme::Night => {
            window.paint_outer_shadows(
                bounds,
                radii,
                &[if sheet {
                    shadow(0.0, 18.0, 24.0, -12.0, rgba(0x00000099))
                } else {
                    shadow(0.0, 24.0, 25.0, -24.0, rgba(0x000000bf))
                }],
            );
            // Frosted slate, a step up from the dark ground, so deep glass
            // reads as color rather than as a hole in the panel.
            let body = if sheet {
                rgba(0x33343fff)
            } else {
                rgba(0x2d2e3aeb)
            };
            window.paint_quad(fill(bounds, body).corner_radii(radii));
            window.paint_inset_shadows(
                bounds,
                radii,
                &[
                    inset(0.0, 1.0, 0.0, 0.0, rgba(0xffffff17)),
                    inset(0.0, 0.0, 0.0, 1.0, rgba(0xffffff0a)),
                ],
            );
            if frosted {
                let (light, dark) = surface_grain(theme);
                paint_grain(bounds, radii, light, dark, 0.0, window);
            }
        }
    }
}

/// Surfaces are the quiet ground the glass sits on: only a trace of the frost
/// the pieces carry.
fn surface_grain(theme: Theme) -> (Rgba, Rgba) {
    match theme {
        Theme::Shore => (rgba(0xffffff0f), rgba(0x0a282408)),
        Theme::Night => (rgba(0xffffff06), rgba(0x0000000f)),
    }
}

/// A panel's grain alone, laid under a row of its page so it scrolls with it,
/// and on for `trail` below.
fn paint_frost(bounds: Bounds<Pixels>, trail: f32, theme: Theme, window: &mut Window) {
    let bounds = Bounds {
        origin: bounds.origin,
        size: size(bounds.size.width, bounds.size.height + px(trail)),
    };
    let (light, dark) = surface_grain(theme);
    paint_grain(bounds, Corners::default(), light, dark, 0.0, window);
}

/// Fine frost over the whole piece, about a point to a grain.
fn paint_grain(
    bounds: Bounds<Pixels>,
    radii: Corners<Pixels>,
    light: Rgba,
    dark: Rgba,
    sparkle: f32,
    window: &mut Window,
) {
    let cell = window.scale_factor().max(1.0);
    window.paint_quad(
        fill(bounds, grain(hsla_of(light), hsla_of(dark), cell, sparkle)).corner_radii(radii),
    );
}

/// A soft pool of light inside the glass, centered at `at` (fractions of the
/// box), `extent` of the box across before blurring.
fn lobe_glow(
    bounds: Bounds<Pixels>,
    at: (f32, f32),
    extent: (f32, f32),
    blur: f32,
    color: Rgba,
) -> InnerGlow {
    let full = bounds.size;
    let center = bounds.origin + point(full.width * at.0, full.height * at.1);
    let glow = Bounds::centered_at(center, size(full.width * extent.0, full.height * extent.1));
    InnerGlow {
        bounds: glow,
        corner_radii: Corners::all(glow.size.width.min(glow.size.height) / 2.0),
        blur_radius: px(blur),
        color: hsla_of(color),
    }
}

/// A drop shadow; `blur` is GPUI's, the standard deviation of the blur.
fn shadow(x: f32, y: f32, blur: f32, spread: f32, color: Rgba) -> BoxShadow {
    BoxShadow::new(px(x), px(y), hsla_of(color))
        .blur_radius(px(blur))
        .spread_radius(px(spread))
}

fn inset(x: f32, y: f32, blur: f32, spread: f32, color: Rgba) -> BoxShadow {
    BoxShadow {
        inset: true,
        ..shadow(x, y, blur, spread, color)
    }
}

// ---- Color --------------------------------------------------------------------

const BLACK: Rgba = Rgba {
    r: 0.0,
    g: 0.0,
    b: 0.0,
    a: 1.0,
};
const WHITE: Rgba = Rgba {
    r: 1.0,
    g: 1.0,
    b: 1.0,
    a: 1.0,
};

fn hsla_of(color: Rgba) -> Hsla {
    Hsla::from(color)
}

fn with_alpha(color: Rgba, alpha: f32) -> Rgba {
    Rgba {
        a: color.a * alpha,
        ..color
    }
}

// The color math runs in f64: OKLab's published constants carry more digits
// than f32 holds.
fn to_linear(c: f32) -> f64 {
    let c = f64::from(c);
    if c <= 0.04045 {
        c / 12.92
    } else {
        ((c + 0.055) / 1.055).powf(2.4)
    }
}

fn to_srgb(c: f64) -> f32 {
    let c = c.clamp(0.0, 1.0);
    let encoded = if c <= 0.0031308 {
        12.92 * c
    } else {
        1.055 * c.powf(1.0 / 2.4) - 0.055
    };
    encoded as f32
}

fn oklab(color: Rgba) -> [f64; 3] {
    let (r, g, b) = (to_linear(color.r), to_linear(color.g), to_linear(color.b));
    let l = (0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b).cbrt();
    let m = (0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b).cbrt();
    let s = (0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b).cbrt();
    [
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    ]
}

fn linear_from_oklab([l_, a, b]: [f64; 3]) -> [f64; 3] {
    let l = (l_ + 0.3963377774 * a + 0.2158037573 * b).powi(3);
    let m = (l_ - 0.1055613458 * a - 0.0638541728 * b).powi(3);
    let s = (l_ - 0.0894841775 * a - 1.2914855480 * b).powi(3);
    [
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076956086 * s,
    ]
}

/// A color outside sRGB keeps its lightness and hue and gives up chroma until
/// it fits. Clipping each channel instead would shift the hue and sink a deep,
/// vivid tone toward black.
fn from_oklab([l_, a, b]: [f64; 3], alpha: f32) -> Rgba {
    let l_ = l_.clamp(0.0, 1.0);
    let fits = |k: f64| {
        linear_from_oklab([l_, a * k, b * k])
            .iter()
            .all(|c| (-1e-4..=1.0 + 1e-4).contains(c))
    };
    let mut keep = 1.0;
    if !fits(keep) {
        let (mut inside, mut outside) = (0.0, 1.0);
        for _ in 0..20 {
            let middle = (inside + outside) / 2.0;
            if fits(middle) {
                inside = middle;
            } else {
                outside = middle;
            }
        }
        keep = inside;
    }
    let [r, g, b] = linear_from_oklab([l_, a * keep, b * keep]);
    Rgba {
        r: to_srgb(r),
        g: to_srgb(g),
        b: to_srgb(b),
        a: alpha,
    }
}

/// `from` moved `t` of the way to `to`, in OKLab.
fn oklab_mix(from: Rgba, to: Rgba, t: f32) -> Rgba {
    let (a, b) = (oklab(from), oklab(to));
    let t64 = f64::from(t);
    from_oklab(
        std::array::from_fn(|i| a[i] + (b[i] - a[i]) * t64),
        from.a + (to.a - from.a) * t,
    )
}

/// The same hue with its lightness and chroma scaled, as thick glass deepens.
fn oklch_scale(color: Rgba, lightness: f64, chroma: f64) -> Rgba {
    let [l, a, b] = oklab(color);
    from_oklab([l * lightness, a * chroma, b * chroma], color.a)
}

/// The same hue and chroma, lighter by `amount`, as thin glass is.
fn oklch_lift(color: Rgba, amount: f64) -> Rgba {
    let [l, a, b] = oklab(color);
    from_oklab([(l + amount).min(0.95), a, b], color.a)
}
