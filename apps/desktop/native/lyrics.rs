//! `<aurelia-lyrics>`: synced lyrics drawn the way the Android app draws them
//! (`ExpressiveLyrics.kt` and `LyricWordRenderer.kt`).
//!
//! Each line glides on its own spring so the current one sits a little above
//! the middle, the lines below following a beat behind. The current line
//! grows to full size while the others sit smaller and dimmer. Words fill with
//! a soft-edged sweep as they are sung and rise while held; long notes pulse
//! letter by letter with a glow.
//!
//! GPUIX cannot transform, gradient-fill or glow text from React, so this
//! element lays out and paints the glyphs itself and reads the playback clock
//! every frame. Every number that shapes the look arrives from React in
//! `settings`, so tuning it hot-reloads; the layout and motion live here.
use crate::custom_elements::{
    custom_surface, CustomElement, CustomElementFactory, CustomRenderContext,
};
use gpui::{prelude::*, *};
use serde::Deserialize;
use std::{cell::RefCell, collections::HashMap, rc::Rc, time::Instant};

/// A line stays lit this long after its end, as on Android.
const GRACE_MS: i64 = 250;

pub struct LyricsFactory;

impl CustomElementFactory for LyricsFactory {
    fn element_type(&self) -> &str {
        "aurelia-lyrics"
    }

    fn create(&self, _: u64) -> Box<dyn CustomElement> {
        Box::new(LyricsElement::default())
    }
}

#[derive(Default)]
struct LyricsElement {
    state: Rc<RefCell<State>>,
}

// ---- Props --------------------------------------------------------------

/// The lyrics as the runtime returns them.
#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct Data {
    synced: Vec<LineData>,
    sections: Option<Vec<SectionData>>,
    agents: Option<Vec<AgentData>>,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct LineData {
    time_ms: i64,
    end_time_ms: Option<i64>,
    line: String,
    words: Option<Vec<WordData>>,
    agent_id: Option<String>,
    translation: Option<String>,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct WordData {
    time_ms: i64,
    end_time_ms: Option<i64>,
    word: String,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct SectionData {
    name: String,
    lines: Vec<LineData>,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct AgentData {
    id: String,
    agent_type: String,
}

/// The look, in pixels, seconds and fractions. React sends every field; these
/// defaults are the Android app's values at its 28sp text size.
#[derive(Deserialize, Clone, PartialEq)]
#[serde(default, rename_all = "camelCase")]
struct Settings {
    font_family: String,
    font_weight: f32,
    font_size: f32,
    line_height: f32,
    background_font_size: f32,
    background_line_height: f32,
    translation_size: f32,
    translation_line_height: f32,
    translation_weight: f32,
    translation_alpha: f32,
    translation_gap: f32,
    label_size: f32,
    label_line_height: f32,
    label_weight: f32,
    label_spacing: f32,
    label_alpha: f32,
    label_gap: f32,
    padding_x: f32,
    padding_y: f32,
    edge_fade: f32,
    color: String,
    anchor: f32,
    unsung_alpha: f32,
    past_alpha: f32,
    past_alpha_scrolled: f32,
    inactive_scale: f32,
    position_stiffness: f32,
    position_damping: f32,
    scale_stiffness: f32,
    scale_damping: f32,
    scale_release: f32,
    scroll_stiffness: f32,
    scroll_damping: f32,
    stagger: f32,
    stagger_decay: f32,
    fade_in: f32,
    fade_out: f32,
    manual_hold: f32,
    feather: f32,
    sweep_steps: f32,
    lift: f32,
    background_lift: f32,
    pulse_min_ms: f32,
    pulse_scale: f32,
    pulse_spread: f32,
    pulse_lift: f32,
    pulse_stagger_ms: f32,
    glow_alpha: f32,
    glow_blur: f32,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            font_family: "Aurelia Sans".into(),
            font_weight: 700.0,
            font_size: 28.0,
            line_height: 36.0,
            background_font_size: 22.0,
            background_line_height: 30.0,
            translation_size: 17.0,
            translation_line_height: 22.0,
            translation_weight: 500.0,
            translation_alpha: 0.65,
            translation_gap: 5.0,
            label_size: 11.0,
            label_line_height: 16.0,
            label_weight: 500.0,
            label_spacing: 1.5,
            label_alpha: 0.6,
            label_gap: 8.0,
            padding_x: 8.0,
            padding_y: 12.0,
            edge_fade: 36.0,
            color: "#ffffff".into(),
            anchor: 0.38,
            unsung_alpha: 0.45,
            past_alpha: 0.3,
            past_alpha_scrolled: 0.5,
            inactive_scale: 0.94,
            position_stiffness: 200.0,
            position_damping: 28.0,
            scale_stiffness: 120.0,
            scale_damping: 22.0,
            scale_release: 0.6,
            scroll_stiffness: 240.0,
            scroll_damping: 28.0,
            stagger: 0.05,
            stagger_decay: 1.05,
            fade_in: 0.12,
            fade_out: 0.4,
            manual_hold: 2.0,
            feather: 0.1,
            sweep_steps: 8.0,
            lift: 0.05,
            background_lift: 0.1,
            pulse_min_ms: 1000.0,
            pulse_scale: 0.12,
            pulse_spread: 0.018,
            pulse_lift: 0.06,
            pulse_stagger_ms: 250.0,
            glow_alpha: 0.6,
            glow_blur: 0.22,
        }
    }
}

// ---- Lines ----------------------------------------------------------------

/// When a stretch of the line's text is sung, in bytes and milliseconds.
struct Timing {
    start: usize,
    end: usize,
    from: i64,
    until: i64,
}

/// A whitespace-separated word with timing.
struct Group {
    start: usize,
    end: usize,
    from: i64,
    until: i64,
    last: bool,
    /// Whether its letters may pulse separately: not right-to-left text.
    independent: bool,
    /// The timings filling it, as indices into `Line::timings`.
    fills: Vec<usize>,
}

struct Line {
    time: i64,
    /// When the line stops being sung, before the grace period.
    end: i64,
    text: String,
    timings: Vec<Timing>,
    groups: Vec<Group>,
    /// A second singer in a duet: right-aligned.
    secondary: bool,
    /// Backing vocals: smaller and italic.
    background: bool,
    translation: Option<String>,
    /// The name of the section this line opens.
    section: Option<String>,
}

impl Line {
    fn active(&self, time: i64) -> bool {
        time >= self.time && time <= self.end + GRACE_MS
    }
}

fn prepare(data: Data) -> Vec<Line> {
    let agents = data.agents.unwrap_or_default();
    let first_person = agents
        .iter()
        .find(|agent| agent.agent_type == "person")
        .map(|agent| agent.id.as_str());
    let agent_type = |id: &str| {
        agents
            .iter()
            .find(|agent| agent.id == id)
            .map(|agent| agent.agent_type.as_str())
    };
    let lines = data.synced;
    let mut labels = HashMap::new();
    for section in data.sections.unwrap_or_default() {
        if section.name.trim().is_empty() {
            continue;
        }
        let Some(first) = section.lines.first() else {
            continue;
        };
        if let Some(index) = lines.iter().position(|line| line.time_ms == first.time_ms) {
            labels.insert(index, section.name.to_uppercase());
        }
    }
    lines
        .iter()
        .enumerate()
        .map(|(index, line)| {
            let words = line.words.as_deref().unwrap_or_default();
            let text = if line.line.is_empty() {
                words
                    .iter()
                    .map(|word| word.word.as_str())
                    .collect::<String>()
            } else {
                line.line.clone()
            }
            .replace('\n', " ");
            let timings = timings(&text, line, words);
            let groups = groups(&text, &timings);
            let end = line
                .end_time_ms
                .or_else(|| words.last().and_then(|word| word.end_time_ms))
                .or_else(|| {
                    lines
                        .iter()
                        .find(|next| next.time_ms > line.time_ms)
                        .map(|next| next.time_ms)
                })
                .unwrap_or(line.time_ms + 5000)
                .max(line.time_ms);
            let id = line.agent_id.as_deref();
            Line {
                time: line.time_ms,
                end,
                text,
                timings,
                groups,
                secondary: id.is_some_and(|id| {
                    agent_type(id) == Some("person")
                        && first_person.is_some_and(|first| first != id)
                }),
                background: id.is_some_and(|id| agent_type(id) == Some("other")),
                translation: line
                    .translation
                    .clone()
                    .filter(|translation| !translation.trim().is_empty()),
                section: labels.remove(&index),
            }
        })
        .collect()
}

/// Find each timed word in the display text. If one cannot be found the line
/// keeps its text and lights as a whole.
fn timings(text: &str, line: &LineData, words: &[WordData]) -> Vec<Timing> {
    let mut cursor = 0;
    let mut timings = Vec::new();
    for (index, word) in words.iter().enumerate() {
        let token = word.word.trim();
        if token.is_empty() {
            continue;
        }
        let Some(found) = text[cursor..].find(token) else {
            return Vec::new();
        };
        let start = cursor + found;
        let from = word.time_ms;
        let until = word
            .end_time_ms
            .filter(|&end| end > from)
            .or_else(|| {
                words
                    .get(index + 1)
                    .map(|next| next.time_ms)
                    .filter(|&next| next > from)
            })
            .or_else(|| line.end_time_ms.filter(|&end| end > from))
            .unwrap_or(from + 500);
        cursor = start + token.len();
        timings.push(Timing {
            start,
            end: cursor,
            from,
            until,
        });
    }
    timings
}

fn groups(text: &str, timings: &[Timing]) -> Vec<Group> {
    if timings.is_empty() {
        return Vec::new();
    }
    let mut spans = Vec::new();
    let mut start = None;
    for (index, ch) in text.char_indices() {
        if ch.is_whitespace() {
            if let Some(start) = start.take() {
                spans.push((start, index));
            }
        } else if start.is_none() {
            start = Some(index);
        }
    }
    if let Some(start) = start {
        spans.push((start, text.len()));
    }
    let count = spans.len();
    spans
        .into_iter()
        .enumerate()
        .filter_map(|(index, (start, end))| {
            let fills: Vec<usize> = timings
                .iter()
                .enumerate()
                .filter(|(_, timing)| timing.start < end && timing.end > start)
                .map(|(index, _)| index)
                .collect();
            Some(Group {
                start,
                end,
                from: fills.iter().map(|&fill| timings[fill].from).min()?,
                until: fills.iter().map(|&fill| timings[fill].until).max()?,
                last: index + 1 == count,
                independent: !text[start..end].chars().any(right_to_left),
                fills,
            })
        })
        .collect()
}

fn right_to_left(ch: char) -> bool {
    matches!(ch as u32, 0x0590..=0x08FF | 0xFB1D..=0xFDFF | 0xFE70..=0xFEFF)
}

// ---- Layout ---------------------------------------------------------------

/// One glyph of a shaped block, placed within the block.
struct Glyph {
    font_id: FontId,
    id: GlyphId,
    emoji: bool,
    /// Byte offset of the glyph's text.
    index: usize,
    x: f32,
    width: f32,
    row: usize,
}

/// Wrapped text: a label, a lyric or a translation.
struct Block {
    glyphs: Vec<Glyph>,
    rows: usize,
    font_size: f32,
    line_height: f32,
    /// From the top of a row to its baseline.
    baseline: f32,
    ascent: f32,
    descent: f32,
}

impl Block {
    fn height(&self) -> f32 {
        self.rows as f32 * self.line_height
    }
}

/// Where a lyric glyph sits in the word timing.
#[derive(Clone, Copy)]
struct Part {
    group: Option<usize>,
    /// Position among the group's glyphs.
    order: usize,
    /// The timing that fills the glyph, and the glyph's distance along it.
    fill: Option<(usize, f32)>,
}

/// One laid-out lyric line: section label, text and translation.
struct Row {
    height: f32,
    label: Option<(Block, f32)>,
    text: Block,
    text_top: f32,
    parts: Vec<Part>,
    group_sizes: Vec<usize>,
    fill_widths: Vec<f32>,
    translation: Option<(Block, f32)>,
}

struct Layout {
    width: f32,
    rows: Vec<Row>,
}

struct Shape<'a> {
    text: &'a str,
    font: Font,
    size: f32,
    line_height: f32,
    wrap: f32,
    align_end: bool,
    spacing: f32,
}

fn shape(window: &mut Window, shape: Shape) -> Block {
    let run = TextRun {
        len: shape.text.len(),
        font: shape.font,
        color: white(),
        background_color: None,
        underline: None,
        strikethrough: None,
    };
    let lines = window
        .text_system()
        .shape_text(
            SharedString::from(shape.text.to_string()),
            px(shape.size),
            &[run],
            Some(px(shape.wrap)),
            None,
        )
        .unwrap_or_default();
    let mut block = Block {
        glyphs: Vec::new(),
        rows: 0,
        font_size: shape.size,
        line_height: shape.line_height,
        baseline: shape.line_height * 0.75,
        ascent: shape.size * 0.75,
        descent: shape.size * 0.25,
    };
    for line in &lines {
        let layout = &line.unwrapped_layout;
        if !layout.runs.is_empty() {
            block.ascent = f32::from(layout.ascent);
            block.descent = f32::from(layout.descent);
            block.baseline =
                (shape.line_height - block.ascent - block.descent) / 2.0 + block.ascent;
        }
        let flat: Vec<(FontId, &ShapedGlyph)> = layout
            .runs
            .iter()
            .flat_map(|run| run.glyphs.iter().map(move |glyph| (run.font_id, glyph)))
            .collect();
        let mut run_starts = Vec::with_capacity(layout.runs.len());
        let mut count = 0;
        for run in &layout.runs {
            run_starts.push(count);
            count += run.glyphs.len();
        }
        let mut starts = vec![0];
        starts.extend(
            line.wrap_boundaries
                .iter()
                .map(|boundary| run_starts[boundary.run_ix] + boundary.glyph_ix),
        );
        let width = f32::from(layout.width);
        let x_of = |k: usize| {
            flat.get(k)
                .map_or(width, |(_, glyph)| f32::from(glyph.position.x))
        };
        let blank = |k: usize| {
            shape
                .text
                .get(flat[k].1.index..)
                .and_then(|rest| rest.chars().next())
                .is_some_and(char::is_whitespace)
        };
        for (row, &start) in starts.iter().enumerate() {
            let stop = starts.get(row + 1).copied().unwrap_or(flat.len());
            let left = x_of(start);
            // Trailing spaces do not count toward an aligned row's width.
            let mut right = x_of(stop);
            let mut visible = stop;
            while visible > start && blank(visible - 1) {
                visible -= 1;
                right = x_of(visible);
            }
            let spread = shape.spacing * visible.saturating_sub(start + 1) as f32;
            let shift = if shape.align_end {
                (shape.wrap - (right - left) - spread).max(0.0)
            } else {
                0.0
            };
            for (n, &(font_id, glyph)) in flat[start..stop].iter().enumerate() {
                let k = start + n;
                block.glyphs.push(Glyph {
                    font_id,
                    id: glyph.id,
                    emoji: glyph.is_emoji,
                    index: glyph.index,
                    x: x_of(k) - left + shift + shape.spacing * n as f32,
                    width: x_of(k + 1) - x_of(k),
                    row: block.rows + row,
                });
            }
        }
        block.rows += starts.len();
    }
    block.rows = block.rows.max(1);
    block
}

fn row(window: &mut Window, line: &Line, s: &Settings, width: f32) -> Row {
    let wrap = (width - 2.0 * s.padding_x).max(1.0);
    let font = |weight: f32, italic: bool| Font {
        family: s.font_family.clone().into(),
        features: FontFeatures::default(),
        weight: FontWeight(weight),
        style: if italic {
            FontStyle::Italic
        } else {
            FontStyle::Normal
        },
        fallbacks: None,
    };
    let mut top = s.padding_y;
    let label = line.section.as_deref().map(|name| {
        let block = shape(
            window,
            Shape {
                text: name,
                font: font(s.label_weight, false),
                size: s.label_size,
                line_height: s.label_line_height,
                wrap,
                align_end: line.secondary,
                spacing: s.label_spacing,
            },
        );
        let at = top;
        top += block.height() + s.label_gap;
        (block, at)
    });
    let (size, line_height) = if line.background {
        (s.background_font_size, s.background_line_height)
    } else {
        (s.font_size, s.line_height)
    };
    let text = shape(
        window,
        Shape {
            text: &line.text,
            font: font(s.font_weight, line.background),
            size,
            line_height,
            wrap,
            align_end: line.secondary,
            spacing: 0.0,
        },
    );
    let text_top = top;
    top += text.height();
    let translation = line.translation.as_deref().map(|translation| {
        top += s.translation_gap;
        let block = shape(
            window,
            Shape {
                text: translation,
                font: font(s.translation_weight, false),
                size: s.translation_size,
                line_height: s.translation_line_height,
                wrap,
                align_end: line.secondary,
                spacing: 0.0,
            },
        );
        let at = top;
        top += block.height();
        (block, at)
    });
    top += s.padding_y;

    let mut group_sizes = vec![0; line.groups.len()];
    let mut fill_widths = vec![0.0; line.timings.len()];
    let parts = text
        .glyphs
        .iter()
        .map(|glyph| {
            let group = line
                .groups
                .iter()
                .position(|group| glyph.index >= group.start && glyph.index < group.end);
            let order = group.map_or(0, |group| {
                group_sizes[group] += 1;
                group_sizes[group] - 1
            });
            let fill = group
                .and_then(|group| {
                    line.groups[group].fills.iter().copied().find(|&fill| {
                        let timing = &line.timings[fill];
                        glyph.index >= timing.start && glyph.index < timing.end
                    })
                })
                .map(|fill| {
                    let along = fill_widths[fill];
                    fill_widths[fill] += glyph.width;
                    (fill, along)
                });
            Part { group, order, fill }
        })
        .collect();
    Row {
        height: top,
        label,
        text,
        text_top,
        parts,
        group_sizes,
        fill_widths,
        translation,
    }
}

// ---- Motion ---------------------------------------------------------------

#[derive(Clone, Copy, Default)]
struct Spring {
    value: f32,
    target: f32,
    velocity: f32,
    delay: f32,
}

struct Tuning {
    stiffness: f32,
    damping: f32,
    speed: f32,
    position_tolerance: f32,
    velocity_tolerance: f32,
}

impl Spring {
    fn at(value: f32) -> Self {
        Self {
            value,
            target: value,
            ..Self::default()
        }
    }

    fn settle(&mut self, value: f32) {
        *self = Self::at(value);
    }

    /// Advance by `elapsed` seconds. True while it moves or waits to.
    fn step(&mut self, elapsed: f32, tuning: Tuning) -> bool {
        if self.delay > 0.0 {
            self.delay = (self.delay - elapsed).max(0.0);
            if self.delay > 0.0 {
                return true;
            }
        }
        if (self.target - self.value).abs() < tuning.position_tolerance
            && self.velocity.abs() < tuning.velocity_tolerance
        {
            let moved = self.value != self.target;
            self.settle(self.target);
            return moved;
        }
        let (stiffness, damping) = (
            tuning.stiffness * tuning.speed * tuning.speed,
            tuning.damping * tuning.speed,
        );
        let mut remaining = elapsed;
        while remaining > 0.0 {
            let dt = remaining.min(1.0 / 240.0);
            self.velocity +=
                ((self.target - self.value) * stiffness - self.velocity * damping) * dt;
            self.value += self.velocity * dt;
            remaining -= dt;
        }
        elapsed > 0.0
    }
}

/// Android's `LyricListMotion`: where each line is, how big and how bright.
#[derive(Default)]
struct Motion {
    positions: Vec<Spring>,
    scales: Vec<Spring>,
    opacities: Vec<f32>,
    scroll: Spring,
    heights: Vec<f32>,
    viewport: f32,
    pin: Option<usize>,
    /// Seconds left before a manual scroll lets go.
    manual: f32,
    refollow: bool,
    min_offset: f32,
    max_offset: f32,
}

impl Motion {
    fn new(count: usize, s: &Settings) -> Self {
        Self {
            positions: vec![Spring::default(); count],
            scales: vec![Spring::at(s.inactive_scale); count],
            opacities: vec![s.unsung_alpha; count],
            ..Self::default()
        }
    }

    fn measure(
        &mut self,
        heights: Vec<f32>,
        viewport: f32,
        lines: &[Line],
        time: i64,
        s: &Settings,
    ) {
        if heights == self.heights && viewport == self.viewport {
            return;
        }
        self.heights = heights;
        self.viewport = viewport;
        self.retarget(lines, time, true, s);
    }

    fn opacity(&self, line: &Line, time: i64, s: &Settings) -> f32 {
        if line.active(time) {
            1.0
        } else if time > line.end + GRACE_MS {
            if self.manual > 0.0 {
                s.past_alpha_scrolled
            } else {
                s.past_alpha
            }
        } else {
            s.unsung_alpha
        }
    }

    fn retarget(&mut self, lines: &[Line], time: i64, snap: bool, s: &Settings) {
        if self.heights.is_empty() {
            return;
        }
        let pin = lines
            .iter()
            .rposition(|line| line.time <= time)
            .unwrap_or(0);
        if !snap && self.pin == Some(pin) && !self.refollow {
            return;
        }
        self.refollow = false;
        self.pin = Some(pin);
        let center = self.heights[..pin].iter().sum::<f32>() + self.heights[pin] / 2.0;
        let mut top = self.viewport * s.anchor - center;
        self.max_offset = (-top).max(0.0);
        let (mut delay, mut increment) = (0.0, s.stagger);
        for (index, line) in lines.iter().enumerate() {
            if snap {
                self.positions[index].settle(top);
                self.scales[index].settle(if line.active(time) {
                    1.0
                } else {
                    s.inactive_scale
                });
                self.opacities[index] = self.opacity(line, time, s);
            } else {
                let spring = &mut self.positions[index];
                spring.value = spring
                    .value
                    .clamp(top - self.viewport * 1.5, top + self.viewport * 1.5);
                spring.target = top;
                spring.delay = if index > pin { delay } else { 0.0 };
            }
            if index >= pin {
                delay += increment;
                increment /= s.stagger_decay;
            }
            top += self.heights[index];
        }
        self.min_offset = (self.viewport - top).min(0.0);
        let offset = self.scroll.value.clamp(self.min_offset, self.max_offset);
        if snap || offset != self.scroll.value {
            self.scroll.settle(offset);
        }
    }

    /// Scroll by hand; the lines hold still until the hold runs out.
    fn scroll(&mut self, delta: f32, s: &Settings) -> bool {
        let (Some(first), Some(last), Some(height)) = (
            self.positions.first(),
            self.positions.last(),
            self.heights.last(),
        ) else {
            return false;
        };
        if self.manual == 0.0 {
            self.max_offset = (-first.value).max(0.0);
            self.min_offset = (self.viewport - last.value - height).min(0.0);
            for spring in &mut self.positions {
                spring.settle(spring.value);
            }
        }
        let old = self.scroll.value.clamp(self.min_offset, self.max_offset);
        let next = (old + delta).clamp(self.min_offset, self.max_offset);
        self.scroll.settle(next);
        self.manual = s.manual_hold;
        self.refollow = true;
        next != old
    }

    fn release(&mut self) {
        self.manual = 0.0;
        self.scroll.target = 0.0;
    }

    /// Move everything one frame on. True while anything is still moving.
    fn advance(&mut self, lines: &[Line], time: i64, elapsed: f32, s: &Settings) -> bool {
        if self.heights.is_empty() {
            return false;
        }
        self.manual = (self.manual - elapsed).max(0.0);
        if self.manual == 0.0 {
            self.retarget(lines, time, false, s);
            self.scroll.target = 0.0;
        }
        let mut moving = self.manual > 0.0;
        for (index, line) in lines.iter().enumerate() {
            let active = line.active(time);
            self.scales[index].target = if active { 1.0 } else { s.inactive_scale };
            moving |= self.positions[index].step(
                elapsed,
                Tuning {
                    stiffness: s.position_stiffness,
                    damping: s.position_damping,
                    speed: 1.0,
                    position_tolerance: 0.05,
                    velocity_tolerance: 0.5,
                },
            );
            moving |= self.scales[index].step(
                elapsed,
                Tuning {
                    stiffness: s.scale_stiffness,
                    damping: s.scale_damping,
                    speed: if active { 1.0 } else { s.scale_release },
                    position_tolerance: 0.0005,
                    velocity_tolerance: 0.005,
                },
            );
            let target = self.opacity(line, time, s);
            let old = self.opacities[index];
            let fade = if target > old { s.fade_in } else { s.fade_out };
            self.opacities[index] = if (target - old).abs() < 0.001 {
                target
            } else {
                old + (target - old) * (1.0 - (-elapsed / fade.max(0.001)).exp())
            };
            moving |= self.opacities[index] != old;
        }
        moving |= self.scroll.step(
            elapsed,
            Tuning {
                stiffness: s.scroll_stiffness,
                damping: s.scroll_damping,
                speed: 1.0,
                position_tolerance: 0.05,
                velocity_tolerance: 0.5,
            },
        );
        moving
    }
}

// ---- Element --------------------------------------------------------------

#[derive(Default)]
struct State {
    lines: Vec<Line>,
    settings: Settings,
    layout: Option<Layout>,
    motion: Motion,
    last_frame: Option<Instant>,
    bounds: Bounds<Pixels>,
    /// Each painted line's top and bottom within the element, and its time.
    hits: Vec<(f32, f32, i64)>,
}

impl State {
    fn set_lyrics(&mut self, value: serde_json::Value) {
        let data = serde_json::from_value(value).unwrap_or_default();
        self.lines = prepare(data);
        self.layout = None;
        self.motion = Motion::new(self.lines.len(), &self.settings);
        self.last_frame = None;
    }

    fn set_settings(&mut self, value: serde_json::Value) {
        let settings = serde_json::from_value(value).unwrap_or_default();
        if settings != self.settings {
            self.settings = settings;
            self.layout = None;
        }
    }

    fn line_at(&self, position: Point<Pixels>) -> Option<i64> {
        let y = f32::from(position.y - self.bounds.origin.y);
        self.hits
            .iter()
            .find(|(top, bottom, _)| y >= *top && y < *bottom)
            .map(|(_, _, time)| *time)
    }

    fn paint(&mut self, bounds: Bounds<Pixels>, window: &mut Window) {
        self.bounds = bounds;
        self.hits.clear();
        let width = f32::from(bounds.size.width);
        let height = f32::from(bounds.size.height);
        if self.lines.is_empty() || width <= 0.0 || height <= 0.0 {
            return;
        }
        if self
            .layout
            .as_ref()
            .is_none_or(|layout| layout.width != width)
        {
            let rows = self
                .lines
                .iter()
                .map(|line| row(window, line, &self.settings, width))
                .collect();
            self.layout = Some(Layout { width, rows });
        }
        let State {
            lines,
            settings,
            layout,
            motion,
            last_frame,
            hits,
            ..
        } = self;
        let Some(layout) = layout.as_ref() else {
            return;
        };

        let progress = aurelia_desktop::runtime().progress();
        let time = (progress.position * 1000.0).round() as i64;
        let now = Instant::now();
        let elapsed = last_frame
            .map_or(0.0, |at| now.duration_since(at).as_secs_f32())
            .min(0.1);
        *last_frame = Some(now);
        motion.measure(
            layout.rows.iter().map(|row| row.height).collect(),
            height,
            lines,
            time,
            settings,
        );
        let moving = motion.advance(lines, time, elapsed, settings);

        let color = crate::color::parse_color_rgba(&settings.color)
            .map(Hsla::from)
            .unwrap_or_else(white);
        let painter = Painter {
            origin: bounds.origin,
            height,
            color,
            settings,
            time,
        };
        window.with_content_mask(Some(ContentMask { bounds }), |window| {
            for (index, (line, row)) in lines.iter().zip(&layout.rows).enumerate() {
                let top = motion.positions[index].value + motion.scroll.value;
                if top + row.height < -height * 0.25 || top > height * 1.25 {
                    continue;
                }
                hits.push((top, top + row.height, line.time));
                let opacity = motion.opacities[index];
                if opacity <= 0.001 {
                    continue;
                }
                let place = Place {
                    pivot: (
                        if line.secondary { width } else { 0.0 },
                        top + row.height / 2.0,
                    ),
                    scale: motion.scales[index].value,
                };
                if let Some((label, at)) = &row.label {
                    painter.plain(
                        window,
                        label,
                        top + at,
                        place,
                        opacity * settings.label_alpha,
                    );
                }
                painter.lyric(window, line, row, top, place, opacity);
                if let Some((translation, at)) = &row.translation {
                    painter.plain(
                        window,
                        translation,
                        top + at,
                        place,
                        opacity * settings.translation_alpha,
                    );
                }
            }
        });

        // Words fill continuously while a song plays; otherwise frames run
        // only until the motion settles.
        if moving || progress.playing {
            window.request_animation_frame();
        } else {
            *last_frame = None;
        }
    }
}

/// A line's scale about its pivot: the left or right edge, halfway down.
#[derive(Clone, Copy)]
struct Place {
    pivot: (f32, f32),
    scale: f32,
}

impl Place {
    fn map(&self, x: f32, y: f32) -> (f32, f32) {
        (
            self.pivot.0 + (x - self.pivot.0) * self.scale,
            self.pivot.1 + (y - self.pivot.1) * self.scale,
        )
    }
}

struct Painter<'a> {
    origin: Point<Pixels>,
    height: f32,
    color: Hsla,
    settings: &'a Settings,
    time: i64,
}

impl Painter<'_> {
    /// Fades lines out over the top and bottom edges.
    fn edge(&self, y: f32) -> f32 {
        let fade = self.settings.edge_fade;
        if fade <= 0.0 {
            return 1.0;
        }
        (y / fade).clamp(0.0, 1.0) * ((self.height - y) / fade).clamp(0.0, 1.0)
    }

    fn point(&self, (x, y): (f32, f32)) -> Point<Pixels> {
        point(self.origin.x + px(x), self.origin.y + px(y))
    }

    fn glyph(&self, window: &mut Window, glyph: &Glyph, at: (f32, f32), size: f32, alpha: f32) {
        // Quantized so a growing line reuses rasterized glyphs.
        let size = px((size * 4.0).round() / 4.0);
        if glyph.emoji {
            let _ = window.paint_emoji(self.point(at), glyph.font_id, glyph.id, size);
        } else if alpha > 0.002 {
            let color = Hsla {
                a: self.color.a * alpha,
                ..self.color
            };
            let _ = window.paint_glyph_unsnapped(self.point(at), glyph.font_id, glyph.id, size, color);
        }
    }

    /// A label or translation: one colour, scaled with its line.
    fn plain(&self, window: &mut Window, block: &Block, top: f32, place: Place, alpha: f32) {
        for glyph in &block.glyphs {
            let at = place.map(
                self.settings.padding_x + glyph.x,
                top + glyph.row as f32 * block.line_height + block.baseline,
            );
            let alpha = alpha * self.edge(at.1 - block.font_size * 0.35);
            self.glyph(window, glyph, at, block.font_size * place.scale, alpha);
        }
    }

    /// The lyric itself, word by word: `drawLyricGroup` on Android.
    fn lyric(
        &self,
        window: &mut Window,
        line: &Line,
        row: &Row,
        top: f32,
        place: Place,
        opacity: f32,
    ) {
        let s = self.settings;
        let time = self.time;
        let block = &row.text;
        let em = block.font_size;
        let active = line.active(time);
        // Unsung text keeps the same brightness while its line fades in.
        let dim = (s.unsung_alpha / opacity.max(0.001)).min(1.0);
        for (glyph, part) in block.glyphs.iter().zip(&row.parts) {
            let row_top = top + row.text_top + glyph.row as f32 * block.line_height;
            let left = s.padding_x + glyph.x;
            let baseline = row_top + block.baseline;
            let group = part
                .group
                .filter(|_| active)
                .map(|group| (group, &line.groups[group]));
            let Some((group_index, group)) = group else {
                let layer = if !active && time < line.time {
                    dim
                } else {
                    1.0
                };
                let at = place.map(left, baseline);
                let alpha = opacity * layer * self.edge(at.1 - em * 0.35);
                self.glyph(window, glyph, at, em * place.scale, alpha);
                continue;
            };

            let duration = (group.until - group.from).max(1) as f32;
            let since = (time - group.from) as f32;
            let after = (time - group.until) as f32;
            let held = smooth(since / 160.0) * (1.0 - smooth(after / 220.0));
            let lift = if line.background {
                s.background_lift
            } else {
                s.lift
            };
            let count = row.group_sizes[group_index].max(1) as f32;
            let pulse = if group.independent && duration >= s.pulse_min_ms {
                let strength = if group.last { 1.0 } else { 0.75 };
                let stagger = (duration * 0.35).min(s.pulse_stagger_ms) * part.order as f32 / count;
                let phase = ((since - stagger) / duration).clamp(0.0, 1.0);
                let release = 1.0 - smooth(after / 250.0);
                smooth(phase * 2.0) * smooth((1.0 - phase) * 2.0) * strength * release
            } else {
                0.0
            };
            // The letter grows about its middle and the word spreads and rises.
            let grow = 1.0 + s.pulse_scale * pulse;
            let spread = (part.order as f32 - (count - 1.0) / 2.0) * em * s.pulse_spread * pulse;
            let rise = -em * lift * held - em * s.pulse_lift * pulse;
            let center = (left + glyph.width / 2.0, row_top + block.line_height / 2.0);
            let left = center.0 + (left - center.0) * grow + spread;
            let baseline = center.1 + (baseline - center.1) * grow + rise;
            let at = place.map(left, baseline);
            let scale = grow * place.scale;
            let width = glyph.width * scale;
            let edge = self.edge(at.1 - em * 0.35);

            if pulse > 0.01 {
                let ink = (block.ascent + block.descent) * scale * 0.7;
                let bounds = Bounds::new(
                    self.point((at.0, at.1 - block.ascent * scale * 0.85)),
                    size(px(width), px(ink)),
                );
                window.paint_drop_shadows(
                    bounds,
                    Corners::all(px(ink.min(width) / 2.0)),
                    &[BoxShadow {
                        color: Hsla {
                            a: self.color.a * s.glow_alpha * pulse * opacity * edge,
                            ..self.color
                        },
                        offset: point(px(0.0), px(0.0)),
                        blur_radius: px(s.glow_blur * em * scale),
                        spread_radius: px(0.0),
                        inset: false,
                    }],
                );
            }

            let Some((fill, along)) = part.fill else {
                self.glyph(window, glyph, at, em * scale, opacity * edge);
                continue;
            };
            let timing = &line.timings[fill];
            let progress = ((time - timing.from) as f32
                / (timing.until - timing.from).max(1) as f32)
                .clamp(0.0, 1.0);
            let total = row.fill_widths[fill].max(1.0);
            let feather = total * s.feather;
            let sweep = progress * (total + feather) - feather / 2.0;
            let (lit, unlit) = (sweep - feather / 2.0, sweep + feather / 2.0);
            if progress >= 1.0 || along + glyph.width <= lit {
                self.glyph(window, glyph, at, em * scale, opacity * edge);
            } else if progress <= 0.0 || along >= unlit {
                self.glyph(window, glyph, at, em * scale, opacity * dim * edge);
            } else {
                // The glyph straddles the sweep: paint it once per slice, each
                // clipped to its stretch, from lit through the feather to dim.
                let steps = s.sweep_steps.max(1.0) as usize;
                let mut cuts = vec![(f32::NEG_INFINITY, lit, 1.0)];
                for step in 0..steps {
                    let from = lit + feather * step as f32 / steps as f32;
                    let to = lit + feather * (step + 1) as f32 / steps as f32;
                    let t = (step as f32 + 0.5) / steps as f32;
                    cuts.push((from, to, 1.0 + (dim - 1.0) * t));
                }
                cuts.push((unlit, f32::INFINITY, dim));
                let ink_top = at.1 - em * scale * 1.5;
                let ink_height = em * scale * 2.5;
                for (from, to, layer) in cuts {
                    // Distances along the fill, as x across the drawn glyph;
                    // the outer cuts run past the advance to cover overhang.
                    let x0 = if from.is_finite() {
                        at.0 + (from - along) / glyph.width.max(0.001) * width
                    } else {
                        at.0 - width
                    };
                    let x1 = if to.is_finite() {
                        at.0 + (to - along) / glyph.width.max(0.001) * width
                    } else {
                        at.0 + width * 2.0
                    };
                    let (x0, x1) = (x0.max(at.0 - width), x1.min(at.0 + width * 2.0));
                    if x1 <= x0 {
                        continue;
                    }
                    let mask = ContentMask {
                        bounds: Bounds::new(
                            self.point((x0, ink_top)),
                            size(px(x1 - x0), px(ink_height)),
                        ),
                    };
                    window.with_content_mask(Some(mask), |window| {
                        self.glyph(window, glyph, at, em * scale, opacity * layer * edge);
                    });
                }
            }
        }
    }
}

fn smooth(value: f32) -> f32 {
    let t = value.clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

impl CustomElement for LyricsElement {
    fn set_prop(&mut self, key: &str, value: serde_json::Value) {
        match key {
            "lyrics" => self.state.borrow_mut().set_lyrics(value),
            "settings" => self.state.borrow_mut().set_settings(value),
            _ => {}
        }
    }

    fn supported_props(&self) -> &'static [&'static str] {
        &["lyrics", "settings"]
    }

    fn supported_events(&self) -> &'static [&'static str] {
        &[]
    }

    fn destroy(&mut self) {}

    fn render(
        &mut self,
        ctx: CustomRenderContext,
        _window: &mut Window,
        _cx: &mut Context<crate::renderer::GpuixView>,
    ) -> AnyElement {
        let (scroll, click, paint) = (self.state.clone(), self.state.clone(), self.state.clone());
        custom_surface(
            div().id(SharedString::from(format!("__aurelia_lyrics_{}", ctx.id))),
            &ctx,
        )
        .cursor_pointer()
        .on_scroll_wheel(move |event, window, cx| {
            let state = &mut *scroll.borrow_mut();
            let delta = f32::from(event.delta.pixel_delta(px(state.settings.line_height)).y);
            state.motion.scroll(delta, &state.settings);
            state.last_frame = None;
            window.refresh();
            cx.stop_propagation();
        })
        .on_click(move |event, window, _cx| {
            let mut state = click.borrow_mut();
            let Some(time) = state.line_at(event.position()) else {
                return;
            };
            state.motion.release();
            let request = serde_json::json!({ "type": "seek", "position": time as f64 / 1000.0 });
            let _ = aurelia_desktop::runtime().request(&request.to_string());
            window.refresh();
        })
        .child(
            canvas(
                |_, _, _| {},
                move |bounds, _, window, _| paint.borrow_mut().paint(bounds, window),
            )
            .size_full(),
        )
        .into_any_element()
    }
}
