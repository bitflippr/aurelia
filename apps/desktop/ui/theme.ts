import type { StyleDesc } from "@gpuix/react";

/** Aurelia's dark palette, shared with the Android app. */
export const C = {
  frame: "#0b0c10",
  bg: "#111318",
  surface: "#191b22",
  surfaceLow: "#15171d",
  surfaceHigh: "#252832",
  surfaceHigher: "#2d303b",
  text: "#f1f0f8",
  muted: "#a6a4b2",
  faint: "#6f6d7c",
  primary: "#9b86ff",
  onPrimary: "#15122b",
  primaryContainer: "#302c55",
  onPrimaryContainer: "#e7dfff",
  pink: "#ff7ca8",
  orange: "#ffb36d",
  green: "#6fd3a0",
  error: "#ff8a8a",
  hairline: "#ffffff0f",
  hover: "#ffffff0b",
  pressed: "#ffffff14",
  selected: "#9b86ff24",
  overlay: "#1f212a",
  overlayHover: "#2a2c37",
} as const;

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
  player: 88,
  gutter: 8,
  page: 32,
} as const;

export const radius = { sm: 8, md: 10, lg: 14, xl: 20, round: 999 } as const;

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
