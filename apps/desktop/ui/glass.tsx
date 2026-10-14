// Sea glass: the tints pieces are made of, the ink that reads on them, and
// <Glass>, which paints a piece behind a box's content. The painting itself is
// native (apps/desktop/native/glass.rs); this file decides colors.
import { createElement, useEffect } from "react";
import { ensureArtwork, useStore } from "./store";
import { C, theme as current, type ThemeName } from "./theme";

/**
 * A glass color. `b` is the body of a pale piece, `l` the light scattering
 * inside it, `d` the deep tone (filled pieces, thick edges, colored shadow),
 * `i` the ink that reads on the pale body and `u` what it fluoresces under UV.
 */
export type Tint = { b: string; l: string; d: string; i: string; u: string };

/** Hand-tuned sea glass. Every text and icon on them passes 4.5:1 at the
 * darkest and lightest point of a piece, in both themes. */
export const TINTS = {
  jelly: { b: "#cbc9f7", l: "#f4f3ff", d: "#4d44c5", i: "#221f6b", u: "#bdb6ff" },
  seafoam: { b: "#a9e2cd", l: "#e7fcf3", d: "#006249", i: "#08402f", u: "#6effc6" },
  aqua: { b: "#a0d9e7", l: "#e9fbff", d: "#006078", i: "#063d4a", u: "#86f1ff" },
  cobalt: { b: "#b9c4f6", l: "#f0f3ff", d: "#3b4ac9", i: "#1a2270", u: "#a4c4ff" },
  lavender: { b: "#d6c8f4", l: "#f8f2ff", d: "#6141b7", i: "#331e68", u: "#e2c8ff" },
  kelp: { b: "#c9df9c", l: "#f5fde3", d: "#356000", i: "#283d09", u: "#b6ff5c" },
  amber: { b: "#f3cf92", l: "#fff5e0", d: "#834700", i: "#4a2e03", u: "#ffd06a" },
  rose: { b: "#f3c1ce", l: "#fff2f6", d: "#a12c53", i: "#5a1226", u: "#ffbfa3" },
  frost: { b: "#edf2f1", l: "#ffffff", d: "#3e4f4e", i: "#1a2928", u: "#e4fbff" },
} satisfies Record<string, Tint>;

export type GlassVariant = "tonal" | "filled" | "pearl" | "panel" | "sheet";

/**
 * The glass behind a box's content. Put it first inside a box with
 * `position: "relative"` (or absolute); it fills the box and draws behind the
 * rest. The box keeps its events. Give it the box's corner `radius`.
 */
export function Glass({
  tint = TINTS.jelly,
  variant = "tonal",
  radius = 0,
  live,
  interactive,
  plain,
}: {
  tint?: Tint;
  variant?: GlassVariant;
  /** One radius, or `[top left, top right, bottom right, bottom left]`. */
  radius?: number | [number, number, number, number];
  /** The light inside drifts while music plays; a filled piece fluoresces at night. */
  live?: boolean;
  /** Lifts under the pointer and sinks when pressed. */
  interactive?: boolean;
  /** A panel without grain: its page lays `<Frost>` under each row instead. */
  plain?: boolean;
}) {
  const theme = useStore((s) => s.theme);
  return createElement("aurelia-glass", {
    tint: { b: tint.b, l: tint.l, d: tint.d, u: tint.u },
    variant,
    theme,
    radius,
    live: !!live,
    interactive: !!interactive,
    plain: !!plain,
    style: { position: "absolute", left: 0, top: 0, right: 0, bottom: 0 },
  });
}

/**
 * A plain panel's grain, under one row of its page, so the grain scrolls with
 * the content. Put it first in a box with `position: "relative"`. `trail`
 * carries it on below the box, under the empty end of a short page.
 */
export function Frost({ trail = 0 }: { trail?: number }) {
  const theme = useStore((s) => s.theme);
  return createElement("aurelia-glass", {
    variant: "frost",
    theme,
    trail,
    style: { position: "absolute", left: 0, top: 0, right: 0, bottom: 0 },
  });
}

/** Text and icons on a piece of glass. */
export function ink(tint: Tint, variant: GlassVariant, theme: ThemeName = currentTheme()): string {
  if (variant === "panel" || variant === "sheet") return C.text;
  if (theme === "shore") return variant === "filled" ? "#ffffff" : tint.i;
  if (variant === "filled" || variant === "pearl") return tint.i;
  return mixOklab(tint.l, "#ffffff", 0.4);
}

/** Secondary text on a piece of glass. */
export function inkSoft(tint: Tint, variant: GlassVariant, theme: ThemeName = currentTheme()): string {
  return `${ink(tint, variant, theme)}c7`;
}

/** The accent for a tint on the bare ground or a panel: links, the playing
 * song, the seek bar. At night it is the fluorescent color. */
export function accent(tint: Tint, theme: ThemeName = currentTheme()): string {
  return theme === "shore" ? tint.d : tint.u;
}

function currentTheme(): ThemeName {
  return current.name;
}

// ---- Tints from album art ------------------------------------------------------

const derived = new Map<string, Tint>();

/**
 * Sea glass in an album's color: its hue, at sea glass's muted chroma, with
 * each tone solved for contrast at the darkest and lightest point of a piece,
 * so any album reads as well as the hand-tuned tints.
 */
export function tintFrom(color: string | undefined): Tint {
  if (!color) return TINTS.jelly;
  const cached = derived.get(color);
  if (cached) return cached;
  const [, chroma0, hue] = oklch(hex(color));
  // Sea glass is milky: keep the art's hue, cap how vivid it gets.
  const chroma = Math.min(chroma0, 0.16);
  const b = inGamut(0.86, clamp(chroma * 0.55, 0.012, 0.075), hue);
  const l = inGamut(0.965, clamp(chroma * 0.18, 0.004, 0.025), hue);
  let dL = 0.56;
  let d = inGamut(dL, clamp(chroma, 0.03, 0.15), hue);
  while ((filledWorst(d, b) < 4.6 || nightTonalWorst(d, l) < 4.8) && dL > 0.2) {
    dL -= 0.01;
    d = inGamut(dL, clamp(chroma, 0.03, 0.15), hue);
  }
  let iL = 0.34;
  let i = inGamut(iL, clamp(chroma * 0.6, 0.02, 0.09), hue);
  while (tonalWorst(i, b, l, d) < 4.8 && iL > 0.12) {
    iL -= 0.01;
    i = inGamut(iL, clamp(chroma * 0.6, 0.02, 0.09), hue);
  }
  let uL = 0.88;
  let u = inGamut(uL, clamp(chroma * 1.3 + 0.07, 0.08, 0.17), hue);
  while (fluorescentWorst(u, i, d) < 4.8 && uL < 0.97) {
    uL += 0.01;
    u = inGamut(uL, clamp(chroma * 1.3 + 0.07, 0.08, 0.17), hue);
  }
  const tint = { b: toHex(b), l: toHex(l), d: toHex(d), i: toHex(i), u: toHex(u) };
  derived.set(color, tint);
  return tint;
}

/** The tint of an album's art, once its palette is in; `fallback` until then. */
export function useAlbumTint(id: string | undefined, tag: string | undefined, fallback: Tint = TINTS.jelly): Tint {
  useEffect(() => ensureArtwork(id, tag), [id, tag]);
  const color = useStore((s) => {
    const art = id ? s.artwork[id] : undefined;
    return typeof art === "object" ? art.palette[0] : undefined;
  });
  return color ? tintFrom(color) : fallback;
}

/** Clouds for when nothing plays, or the art has no palette: moon jelly. */
export const PLAIN_CLOUDS: [string, string, string] = ["#4b3a9a", "#7a3560", "#2f4f80"];

/** The playing album's palette, for the ground under the glass. */
export function useNowPalette(): [string, string, string] {
  const albumId = useStore((s) => {
    const id = s.queue.find((e) => e.entry === s.player.current)?.id;
    return id ? s.library?.song.get(id)?.albumId : undefined;
  });
  const tag = useStore((s) => (albumId ? s.library?.album.get(albumId)?.imageTag : undefined));
  useEffect(() => ensureArtwork(albumId, tag), [albumId, tag]);
  const palette = useStore((s) => {
    const art = albumId ? s.artwork[albumId] : undefined;
    return typeof art === "object" ? art.palette : undefined;
  });
  return palette ?? PLAIN_CLOUDS;
}

/** The playing song's album, and its tint. */
export function useNowTint(): Tint {
  const albumId = useStore((s) => {
    const id = s.queue.find((e) => e.entry === s.player.current)?.id;
    return id ? s.library?.song.get(id)?.albumId : undefined;
  });
  const tag = useStore((s) => (albumId ? s.library?.album.get(albumId)?.imageTag : undefined));
  return useAlbumTint(albumId, tag);
}

// ---- Contrast, where text and icons sit on a piece ------------------------------

type RGB = [number, number, number];
const WHITE: RGB = [1, 1, 1];
const BLACK: RGB = [0, 0, 0];

function filledWorst(d: RGB, b: RGB): number {
  const lobe = mix(d, b, 0.45);
  return Math.min(...[d, over(d, lobe, 0.6), mix(d, mix(d, BLACK, 0.35), 0.24)].map((c) => contrast(c, WHITE)));
}

function tonalWorst(i: RGB, b: RGB, l: RGB, d: RGB): number {
  return Math.min(...[b, mix(b, d, 0.24), over(b, l, 0.85)].map((c) => contrast(c, i)));
}

function nightTonalWorst(d: RGB, l: RGB): number {
  const [L, a, bb] = oklab(d);
  const core = fromOklab([L * 0.78, a * 0.88, bb * 0.88]);
  const thin = fromOklab([Math.min(0.95, L + 0.08), a, bb]);
  const text = mix(l, WHITE, 0.4);
  return Math.min(...[core, mix(core, thin, 0.15), over(core, thin, 0.26), mix(core, thin, 0.5)].map((c) => contrast(c, text)));
}

function fluorescentWorst(u: RGB, i: RGB, d: RGB): number {
  return Math.min(...[u, mix(u, d, 0.3), mix(u, WHITE, 0.28)].map((c) => contrast(c, i)));
}

function luminance(c: RGB): number {
  const [r, g, b] = c.map(linear);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: RGB, b: RGB): number {
  const [x, y] = [luminance(a), luminance(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

// ---- Color spaces -----------------------------------------------------------

function hex(color: string): RGB {
  return [1, 3, 5].map((i) => parseInt(color.slice(i, i + 2), 16) / 255) as RGB;
}

function toHex(c: RGB): string {
  return `#${c.map((v) => Math.round(clamp(v, 0, 1) * 255).toString(16).padStart(2, "0")).join("")}`;
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

function linear(c: number): number {
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function encode(c: number): number {
  return c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055;
}

function linearRgb(c: RGB): RGB {
  return c.map(linear) as RGB;
}

function oklab(c: RGB): RGB {
  const [r, g, b] = linearRgb(c);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

/** Linear sRGB from OKLab, unclamped, so callers can tell when it left the gamut. */
function oklabToLinear([L, A, B]: RGB): RGB {
  const l = (L + 0.3963377774 * A + 0.2158037573 * B) ** 3;
  const m = (L - 0.1055613458 * A - 0.0638541728 * B) ** 3;
  const s = (L - 0.0894841775 * A - 1.291485548 * B) ** 3;
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.7076956086 * s,
  ];
}

/** sRGB from OKLab. A color outside the gamut keeps its lightness and hue
 * and gives up chroma until it fits, as the native painter does. */
function fromOklab([L, A, B]: RGB): RGB {
  const l = clamp(L, 0, 1);
  const fits = (k: number) => oklabToLinear([l, A * k, B * k]).every((c) => c >= -1e-4 && c <= 1 + 1e-4);
  let keep = 1;
  if (!fits(keep)) {
    let [inside, outside] = [0, 1];
    for (let step = 0; step < 20; step++) {
      const middle = (inside + outside) / 2;
      if (fits(middle)) inside = middle;
      else outside = middle;
    }
    keep = inside;
  }
  return oklabToLinear([l, A * keep, B * keep]).map((c) => encode(clamp(c, 0, 1))) as RGB;
}

function oklch(c: RGB): RGB {
  const [L, a, b] = oklab(c);
  return [L, Math.hypot(a, b), Math.atan2(b, a)];
}

/** OKLCH, with chroma lowered until the color is displayable. */
function inGamut(L: number, chroma: number, hue: number): RGB {
  return fromOklab([L, chroma * Math.cos(hue), chroma * Math.sin(hue)]);
}

function mix(a: RGB, b: RGB, t: number): RGB {
  const [x, y] = [oklab(a), oklab(b)];
  return fromOklab(x.map((v, i) => v + (y[i] - v) * t) as RGB);
}

/** `b` laid over `a` at `t` alpha. */
function over(a: RGB, b: RGB, t: number): RGB {
  return a.map((v, i) => v + (b[i] - v) * t) as RGB;
}

/** Two `#rrggbb` colors mixed in OKLab. */
export function mixOklab(a: string, b: string, t: number): string {
  return toHex(mix(hex(a), hex(b), t));
}
