// Lyrics, in the side panel and the full player. Synced lyrics are drawn by
// the native `aurelia-lyrics` element (apps/desktop/native/lyrics.rs), which
// animates them the way the Android app does. Every number that shapes that
// look is in `ANDROID` below, so tuning it hot-reloads.
import { createElement, useEffect } from "react";
import { ensureLyrics, useStore } from "./store";
import { column, F } from "./theme";

type Size = "panel" | "full";

/** The Android app's lyrics, at its 28sp text size. Lengths are pixels. */
const ANDROID = {
  fontFamily: F.sans,
  fontWeight: 700,
  fontSize: 28,
  lineHeight: 36,
  /** Backing vocals: smaller, italic and lifted further. */
  backgroundFontSize: 22,
  backgroundLineHeight: 30,
  translationSize: 17,
  translationLineHeight: 22,
  translationWeight: 500,
  translationAlpha: 0.65,
  translationGap: 5,
  /** Section names ("CHORUS") above the line that opens them. */
  labelSize: 11,
  labelLineHeight: 16,
  labelWeight: 500,
  labelSpacing: 1.5,
  labelAlpha: 0.6,
  labelGap: 8,
  paddingX: 8,
  paddingY: 12,
  /** Lines fade out over this much of the top and bottom edges. */
  edgeFade: 36,
  color: "#ffffff",
  /** Where the current line's middle sits, as a fraction of the height. */
  anchor: 0.38,
  unsungAlpha: 0.45,
  pastAlpha: 0.3,
  /** Sung lines while the user scrolls by hand. */
  pastAlphaScrolled: 0.5,
  inactiveScale: 0.94,
  positionStiffness: 200,
  positionDamping: 28,
  scaleStiffness: 120,
  scaleDamping: 22,
  /** A line shrinks back at this fraction of the speed it grew. */
  scaleRelease: 0.6,
  scrollStiffness: 240,
  scrollDamping: 28,
  /** Seconds each line below the current one waits before following. */
  stagger: 0.05,
  staggerDecay: 1.05,
  fadeIn: 0.12,
  fadeOut: 0.4,
  /** Seconds a hand scroll holds before the lyrics follow the song again. */
  manualHold: 2,
  /** The soft edge of a word's fill, as a fraction of the word's width. */
  feather: 0.1,
  sweepSteps: 8,
  /** How far a held word rises, in ems. */
  lift: 0.05,
  backgroundLift: 0.1,
  /** Words held at least this long pulse letter by letter. */
  pulseMinMs: 1000,
  pulseScale: 0.12,
  pulseSpread: 0.018,
  pulseLift: 0.06,
  pulseStaggerMs: 250,
  glowAlpha: 0.6,
  glowBlur: 0.22,
};

const LENGTHS = [
  "fontSize",
  "lineHeight",
  "backgroundFontSize",
  "backgroundLineHeight",
  "translationSize",
  "translationLineHeight",
  "translationGap",
  "labelSize",
  "labelLineHeight",
  "labelSpacing",
  "labelGap",
  "paddingX",
  "paddingY",
  "edgeFade",
] as const;

function scaled(fontSize: number): typeof ANDROID {
  const settings = { ...ANDROID };
  for (const key of LENGTHS) settings[key] = (ANDROID[key] * fontSize) / ANDROID.fontSize;
  return settings;
}

const SETTINGS: Record<Size, typeof ANDROID> = { panel: scaled(24), full: scaled(34) };

export function Lyrics({ size }: { size: Size }) {
  const songId = useStore((s) => s.queue.find((e) => e.entry === s.player.current)?.id);
  const lyrics = useStore((s) => (songId ? s.lyrics[songId] : undefined));
  useEffect(() => ensureLyrics(songId), [songId]);
  if (!songId) return <Message size={size} text="Lyrics appear here while a song plays." />;
  if (!lyrics || lyrics === "loading") return <Message size={size} text="" />;
  if (lyrics === "none") return <Message size={size} text="No lyrics available" />;
  if (!lyrics.synced.length) return <Plain size={size} lines={lyrics.plain} />;
  return (
    <div style={{ ...column, flexGrow: 1, minHeight: 0, paddingLeft: size === "panel" ? 10 : 0, paddingRight: size === "panel" ? 10 : 24 }}>
      {createElement("aurelia-lyrics", {
        key: songId,
        lyrics,
        settings: SETTINGS[size],
        style: { flexGrow: 1, minHeight: 0, width: "100%" },
      })}
    </div>
  );
}

function Message({ size, text }: { size: Size; text: string }) {
  return (
    <div style={{ ...column, flexGrow: 1, justifyContent: "center", alignItems: "center", padding: 24 }}>
      <text style={{ fontSize: size === "full" ? 18 : 14, color: "#ffffff8c", textAlign: "center" }}>{text}</text>
    </div>
  );
}

/** Unsynced lyrics, centred, as on Android. */
function Plain({ size, lines }: { size: Size; lines: string[] }) {
  const s = SETTINGS[size];
  const font = (24 * s.fontSize) / ANDROID.fontSize;
  return (
    <div
      style={{
        ...column,
        flexGrow: 1,
        minHeight: 0,
        overflowY: "scroll",
        gap: (12 * s.fontSize) / ANDROID.fontSize,
        paddingLeft: 16,
        paddingRight: 16,
        paddingTop: size === "full" ? 160 : 80,
        paddingBottom: size === "full" ? 160 : 80,
      }}
    >
      {lines.map((line, i) => (
        <text key={i} style={{ fontSize: font, lineHeight: font * 1.3, fontWeight: 700, color: "#ffffffb8", textAlign: "center", width: "100%" }}>
          {line || " "}
        </text>
      ))}
    </div>
  );
}
