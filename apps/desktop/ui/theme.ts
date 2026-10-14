import type { StyleDesc } from "@gpuix/react";

export type ThemeName = "shore" | "night";

/** Night tide: dark, the glass lit from behind. Pages sit on frosted slate,
 * a step up from the ground (`bg` is how the slate shows). */
const NIGHT = {
  frame: "#09090f",
  bg: "#2a2b37",
  surface: "#33343f",
  surfaceLow: "#2a2b37",
  surfaceHigh: "#393a47",
  surfaceHigher: "#434453",
  text: "#eef0f7",
  muted: "#a6a9bc",
  faint: "#7f8297",
  primary: "#c9c4ff",
  onPrimary: "#221f6b",
  primaryContainer: "#2b2869",
  onPrimaryContainer: "#e9e6ff",
  pink: "#f3a9bd",
  orange: "#ffc98a",
  green: "#8fe0bd",
  error: "#ff9b9b",
  hairline: "#ffffff12",
  hover: "#ffffff0d",
  pressed: "#ffffff17",
  selected: "#bdb6ff26",
  overlay: "#33343f",
  overlayHover: "#3d3e4a",
};

/** Shore: pale, the glass lit from above. */
const SHORE: typeof NIGHT = {
  frame: "#ebf0ee",
  bg: "#f1f5f4",
  surface: "#f7faf9",
  surfaceLow: "#eef3f1",
  surfaceHigh: "#e3eae8",
  surfaceHigher: "#d8e1de",
  text: "#13211f",
  muted: "#4b5e5b",
  faint: "#6f817e",
  primary: "#4d44c5",
  onPrimary: "#ffffff",
  primaryContainer: "#cbc9f7",
  onPrimaryContainer: "#221f6b",
  pink: "#a12c53",
  orange: "#a85e0a",
  green: "#22876c",
  error: "#b3261e",
  hairline: "#13211f14",
  hover: "#13211f0a",
  pressed: "#13211f14",
  selected: "#4d44c51f",
  overlay: "#f8fbfa",
  overlayHover: "#e9efed",
};

/** The current theme's colors. They change in place when the theme does, so
 * read them while rendering; the root re-renders everything on a change. */
export const C: typeof NIGHT = { ...NIGHT };

export const theme = { name: "night" as ThemeName };

export function applyTheme(name: ThemeName) {
  theme.name = name;
  Object.assign(C, name === "shore" ? SHORE : NIGHT);
}

/** Bundled static cuts of Google Sans Flex with rounded corners. */
export const F = {
  sans: "Aurelia Sans",
  display: "Aurelia Display",
} as const;

export const SIZE = {
  top: 52,
  sidebar: 264,
  sidebarCollapsed: 76,
  panel: 360,
  /** The player dock and the gap around it. */
  player: 96,
  dock: 80,
  gutter: 8,
  page: 32,
} as const;

export const radius = { sm: 8, md: 10, lg: 14, xl: 20, slab: 26, round: 999 } as const;

export const motion = {
  fast: 0.12,
  base: 0.22,
  slow: 0.36,
  ease: [0.22, 1, 0.36, 1] as [number, number, number, number],
};

export const row: StyleDesc = { display: "flex", flexDirection: "row", alignItems: "center" };
export const column: StyleDesc = { display: "flex", flexDirection: "column", minWidth: 0, minHeight: 0 };
export const center: StyleDesc = { display: "flex", alignItems: "center", justifyContent: "center" };
export const fill: StyleDesc = { position: "absolute", top: 0, left: 0, right: 0, bottom: 0 };

/** `#rrggbb` with an alpha from 0 to 1. */
export function alpha(color: string, amount: number): string {
  const hex = Math.round(Math.max(0, Math.min(1, amount)) * 255)
    .toString(16)
    .padStart(2, "0");
  return `${color.slice(0, 7)}${hex}`;
}

/** Mix two `#rrggbb` colors. */
export function mix(a: string, b: string, amount: number): string {
  const parse = (color: string) => [1, 3, 5].map((i) => parseInt(color.slice(i, i + 2), 16));
  const [x, y] = [parse(a), parse(b)];
  return `#${x.map((v, i) => Math.round(v + (y[i] - v) * amount).toString(16).padStart(2, "0")).join("")}`;
}

export const ellipsis: StyleDesc = { whiteSpace: "nowrap", textOverflow: "ellipsis", minWidth: 0 };

export const text = {
  display: (size: number): StyleDesc => ({ fontFamily: F.display, fontWeight: 900, fontSize: size, color: C.text }),
  title: (size = 14, weight = 600): StyleDesc => ({ fontSize: size, fontWeight: weight, color: C.text }),
  body: (size = 14, color: string = C.text): StyleDesc => ({ fontSize: size, color }),
  label: (size = 12): StyleDesc => ({ fontSize: size, fontWeight: 600, color: C.faint }),
};
