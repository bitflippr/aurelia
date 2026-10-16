// Shared controls and pieces, so spacing, type and motion stay consistent.
import { useEffect, useState, type ComponentType, type ReactNode } from "react";
import { motion as native, type EventPayload, type MotionProps, type Props, type StyleDesc } from "@gpuix/react";
import { Glass, TINTS, ink, mixOklab, type Tint } from "./glass";
import { Icon, type IconName } from "./icons";
import { useCover } from "./covers";
import { type Album, type Artist } from "./library";
import { albumMenu, artistMenu } from "./menus";
import { go, openMenu, play, useStore } from "./store";
import { C, SIZE, alpha, center, column, ellipsis, motion, radius, row, text, theme } from "./theme";

type Click = (event: EventPayload) => void;

// motion.div's published types are narrow, but it passes every prop through.
export const Motion = native.div as unknown as ComponentType<Props & MotionProps>;

/** Filled and tonal buttons are pieces of glass; ghost ones are only ink. */
export type Tone = "filled" | "tonal" | "ghost";

const ghost = (): StyleDesc => ({ backgroundColor: alpha(C.text, 0.06), hover: { backgroundColor: alpha(C.text, 0.1) } });

export function Button({
  label,
  icon,
  tone = "ghost",
  tint = TINTS.jelly,
  onClick,
  style,
  height = 44,
}: {
  label: string;
  icon?: IconName;
  tone?: Tone;
  tint?: Tint;
  onClick?: Click;
  style?: StyleDesc;
  height?: number;
}) {
  const color = tone === "ghost" ? C.text : ink(tint, tone);
  return (
    <div
      role="button"
      aria-label={label}
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => (e.key === "enter" || e.key === "space") && onClick?.(e)}
      style={{
        ...row,
        position: "relative",
        height,
        gap: 10,
        paddingLeft: height / 2 - 2,
        paddingRight: height / 2,
        borderRadius: height / 2,
        flexShrink: 0,
        cursor: "pointer",
        ...(tone === "ghost" ? ghost() : {}),
        ...style,
      }}
    >
      {tone !== "ghost" && <Glass tint={tint} variant={tone} radius={height / 2} interactive />}
      {icon && <Icon name={icon} size={18} color={color} />}
      <text style={{ fontSize: 14, fontWeight: 600, color, whiteSpace: "nowrap" }}>{label}</text>
    </div>
  );
}

/** Joined buttons, as on Android: round outer ends, tight inner corners. */
export function ButtonGroup({
  items,
  tint = TINTS.jelly,
}: {
  items: { label?: string; icon: IconName; tone: Exclude<Tone, "ghost">; onClick: Click; aria?: string }[];
  tint?: Tint;
}) {
  return (
    <div style={{ ...row, gap: 4 }}>
      {items.map((item, index) => {
        const first = index === 0;
        const last = index === items.length - 1;
        const outer = 22;
        const inner = 7;
        const color = ink(tint, item.tone);
        const left = first ? outer : inner;
        const right = last ? outer : inner;
        return (
          <div
            key={index}
            role="button"
            aria-label={item.aria ?? item.label}
            tabIndex={0}
            onClick={item.onClick}
            onKeyDown={(e) => (e.key === "enter" || e.key === "space") && item.onClick(e)}
            style={{
              ...row,
              height: 44,
              gap: 10,
              paddingLeft: item.label ? (first ? 22 : 18) : 0,
              paddingRight: item.label ? 20 : 0,
              width: item.label ? undefined : last ? 50 : 46,
              justifyContent: "center",
              position: "relative",
              cursor: "pointer",
            }}
          >
            <Glass tint={tint} variant={item.tone} radius={[left, right, right, left]} interactive />
            <Icon name={item.icon} size={item.label ? 18 : 20} color={color} />
            {item.label && <text style={{ fontSize: 14, fontWeight: 600, color, whiteSpace: "nowrap" }}>{item.label}</text>}
          </div>
        );
      })}
    </div>
  );
}

/** A round icon button. While `active` it becomes a piece of `tint`; give
 * `glass` to make it one always. */
export function IconButton({
  icon,
  label,
  onClick,
  size = 36,
  iconSize = 20,
  color,
  active,
  tint = TINTS.jelly,
  glass,
  live,
  style,
}: {
  icon: IconName;
  label: string;
  onClick?: Click;
  size?: number;
  iconSize?: number;
  color?: string;
  active?: boolean;
  tint?: Tint;
  /** Always glass, of this variant. */
  glass?: "tonal" | "filled" | "pearl";
  live?: boolean;
  style?: StyleDesc;
}) {
  const variant = glass ?? (active ? "tonal" : undefined);
  const shown = variant ? ink(tint, variant) : (color ?? C.muted);
  return (
    <div
      role="button"
      aria-label={label}
      aria-pressed={active}
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => (e.key === "enter" || e.key === "space") && onClick?.(e)}
      style={{
        ...center,
        position: "relative",
        width: size,
        height: size,
        flexShrink: 0,
        borderRadius: size / 2,
        cursor: "pointer",
        hover: variant ? undefined : { backgroundColor: alpha(C.text, 0.07) },
        ...style,
      }}
    >
      {variant && <Glass tint={tint} variant={variant} radius={size / 2} live={live} interactive />}
      <Icon name={icon} size={iconSize} color={shown} />
    </div>
  );
}

export function Chip({ label, on, onClick, icon }: { label: string; on?: boolean; onClick?: Click; icon?: IconName }) {
  const color = on ? ink(TINTS.jelly, "tonal") : C.muted;
  return (
    <div
      role="button"
      aria-pressed={!!on}
      onClick={onClick}
      style={{
        ...row,
        position: "relative",
        height: 34,
        paddingLeft: icon ? 12 : 14,
        paddingRight: 14,
        gap: 6,
        borderRadius: 17,
        flexShrink: 0,
        cursor: "pointer",
        backgroundColor: on ? undefined : alpha(C.text, 0.05),
        hover: on ? undefined : { backgroundColor: alpha(C.text, 0.09) },
      }}
    >
      {on && <Glass tint={TINTS.jelly} radius={17} interactive />}
      {icon && <Icon name={icon} size={16} color={color} />}
      <text style={{ fontSize: 13, fontWeight: 600, color, whiteSpace: "nowrap" }}>{label}</text>
    </div>
  );
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; icon: IconName; label: string }[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div style={{ ...row, gap: 2, padding: 3, borderRadius: 12, backgroundColor: alpha(C.text, 0.05), flexShrink: 0 }}>
      {options.map((option) => {
        const on = option.value === value;
        return (
          <div
            key={option.value}
            role="button"
            aria-label={option.label}
            aria-pressed={on}
            onClick={() => onChange(option.value)}
            style={{
              ...center,
              position: "relative",
              width: 34,
              height: 28,
              borderRadius: 9,
              cursor: "pointer",
            }}
          >
            {on && <Glass tint={TINTS.frost} variant="pearl" radius={9} />}
            <Icon name={option.icon} size={16} color={on ? ink(TINTS.frost, "pearl") : C.muted} />
          </div>
        );
      })}
    </div>
  );
}

/** Album art or an artist photo, with a quiet placeholder when there is none. */
export function Artwork({
  id,
  tag,
  size,
  radius: r = radius.md,
  round,
  shadow,
  style,
}: {
  id?: string;
  tag?: string;
  size: number;
  radius?: number;
  round?: boolean;
  shadow?: boolean;
  style?: StyleDesc;
}) {
  const session = useStore((s) => s.session);
  const scale = useStore((s) => s.window.scale);
  // Art that resizes with the window is fetched at the size it settles on,
  // not at every size along the way.
  const pixels = useSettled(Math.max(1, Math.round(size * scale)));
  const src = useCover(session, id, tag, pixels);
  const corner = round ? size / 2 : r;
  const box: StyleDesc = {
    width: size,
    height: size,
    flexShrink: 0,
    borderRadius: corner,
    ...(shadow ? { boxShadow: artShadow(size) } : {}),
    ...style,
  };
  if (!id || !tag) {
    return (
      <div style={{ ...center, ...box, backgroundColor: C.surfaceHigh }}>
        <Icon name={round ? "user" : "music"} size={Math.max(14, Math.round(size * 0.32))} color={C.faint} />
      </div>
    );
  }
  if (!src) return <div style={{ ...box, backgroundColor: C.surfaceHigh }} />;
  // Covers are fetched still, but a server without the animated artwork plugin
  // can still serve a GIF, and every frame of one decodes to 100–300 MB. Show
  // the first, shrunk to the size it is drawn.
  return (
    <img
      src={src}
      animated={false}
      decodeWidth={pixels}
      decodeHeight={pixels}
      objectFit="cover"
      style={{ ...box, backgroundColor: C.surfaceHigh }}
    />
  );
}

/** Art casts a soft shadow, deeper at night; bigger art sits higher. */
export function artShadow(size: number) {
  const lift = Math.min(1, size / 240);
  return {
    offsetX: 0,
    offsetY: Math.round(6 + 10 * lift),
    blurRadius: Math.round(12 + 16 * lift),
    spreadRadius: -Math.round(4 + 6 * lift),
    color: theme.name === "shore" ? "#0a1e1c4d" : "#00000099",
  };
}

/** `value`, once it has held for a moment. */
function useSettled(value: number, ms = 300): number {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    if (value === settled) return;
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, settled, ms]);
  return settled;
}

/** Four covers in a square, for mixes and playlists without art. */
export function Collage({ albums, size, radius: r = radius.sm }: { albums: Album[]; size: number; radius?: number }) {
  const half = size / 2;
  const cells = [0, 1, 2, 3].map((i) => albums[i % Math.max(1, albums.length)]);
  return (
    <div style={{ display: "flex", flexDirection: "row", flexWrap: "wrap", width: size, height: size, flexShrink: 0 }}>
      {cells.map((album, i) => (
        <Artwork
          key={i}
          id={album?.id}
          tag={album?.imageTag}
          size={half}
          radius={0}
          style={{
            borderTopLeftRadius: i === 0 ? r : 0,
            borderTopRightRadius: i === 1 ? r : 0,
            borderBottomLeftRadius: i === 2 ? r : 0,
            borderBottomRightRadius: i === 3 ? r : 0,
          }}
        />
      ))}
    </div>
  );
}

function PlayBadge({ visible, onClick, size = 44 }: { visible: boolean; onClick: Click; size?: number }) {
  return (
    <Motion
      initial={false}
      animate={{ opacity: visible ? 1 : 0, bottom: visible ? 8 : 2 }}
      transition={{ duration: 0.18, ease: motion.ease }}
      onClick={(e) => {
        onClick(e);
      }}
      role="button"
      aria-label="Play"
      style={{
        ...center,
        position: "absolute",
        right: 8,
        width: size,
        height: size,
        borderRadius: size / 2,
        cursor: "pointer",
      }}
    >
      <Glass tint={TINTS.jelly} variant="filled" radius={size / 2} interactive />
      <Icon name="play" size={20} color={ink(TINTS.jelly, "filled")} />
    </Motion>
  );
}

export function AlbumCard({ album, width, sub }: { album: Album; width: number; sub?: string }) {
  const [hover, setHover] = useState(false);
  const art = width - 20;
  return (
    <div
      role="button"
      aria-label={`${album.title} by ${album.artist}`}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={() => go({ name: "album", id: album.id })}
      onAuxClick={(e) => e.isRightClick && openMenu({ x: e.x ?? 0, y: e.y ?? 0, items: albumMenu(album) })}
      style={{
        ...column,
        width,
        flexShrink: 0,
        padding: 10,
        borderRadius: radius.lg,
        cursor: "pointer",
        backgroundColor: hover ? alpha(C.text, 0.045) : alpha(C.bg, 0),
      }}
    >
      <div style={{ position: "relative", width: art, height: art }}>
        <Artwork id={album.id} tag={album.imageTag} size={art} shadow />
        <PlayBadge visible={hover} onClick={() => play(album.songs.map((song) => song.id))} />
      </div>
      <text style={{ ...text.title(14), ...ellipsis, marginTop: 10 }}>{album.title}</text>
      <text style={{ ...text.body(13, C.muted), ...ellipsis, marginTop: 3 }}>{sub ?? album.artist}</text>
    </div>
  );
}

export function ArtistCard({ artist, width }: { artist: Artist; width: number }) {
  const [hover, setHover] = useState(false);
  const art = width - 20;
  const cover = artist.imageTag ? undefined : artist.albums.find((album) => album.imageTag);
  return (
    <div
      role="button"
      aria-label={artist.name}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={() => go({ name: "artist", id: artist.id })}
      onAuxClick={(e) => e.isRightClick && openMenu({ x: e.x ?? 0, y: e.y ?? 0, items: artistMenu(artist) })}
      style={{
        ...column,
        alignItems: "center",
        width,
        flexShrink: 0,
        padding: 10,
        borderRadius: radius.lg,
        cursor: "pointer",
        backgroundColor: hover ? alpha(C.text, 0.045) : alpha(C.bg, 0),
      }}
    >
      <div style={{ position: "relative", width: art, height: art }}>
        <Artwork
          id={cover ? cover.id : artist.id}
          tag={cover ? cover.imageTag : artist.imageTag}
          size={art}
          round
          shadow
        />
        <PlayBadge
          visible={hover}
          onClick={() => play([...artist.songs].sort((a, b) => b.plays - a.plays).map((song) => song.id))}
        />
      </div>
      <text style={{ ...text.title(14), ...ellipsis, marginTop: 10, textAlign: "center", width: art }}>{artist.name}</text>
      <text style={{ ...text.body(13, C.muted), marginTop: 3 }}>Artist</text>
    </div>
  );
}

/** The width the page content has, between the sidebar and the panel. */
export function useContentWidth(): number {
  const width = useStore((s) => s.window.width);
  const collapsed = useStore((s) => s.collapsed);
  const panel = useStore((s) => s.panel);
  const sidebar = collapsed ? SIZE.sidebarCollapsed : SIZE.sidebar;
  return width - sidebar - SIZE.gutter - (panel ? SIZE.panel + SIZE.gutter : SIZE.gutter) - SIZE.page * 2;
}

/** How many cards of at least `min` fit, and how wide each is. */
export function useColumns(min = 176): { columns: number; width: number } {
  const available = useContentWidth() + 20;
  const columns = Math.max(2, Math.floor(available / min));
  return { columns, width: Math.floor(available / columns) };
}

/** One row of cards that fits the page; the rest are behind "Show all". */
export function Shelf<T>({ items, render, min = 176 }: { items: T[]; render: (item: T, width: number) => ReactNode; min?: number }) {
  const { columns, width } = useColumns(min);
  return (
    <div style={{ ...row, alignItems: "flex-start", marginLeft: -10, marginRight: -10 }}>
      {items.slice(0, columns).map((item) => render(item, width))}
    </div>
  );
}

export function Grid<T>({ items, render, min = 176 }: { items: T[]; render: (item: T, width: number) => ReactNode; min?: number }) {
  const { columns, width } = useColumns(min);
  const rows: T[][] = [];
  for (let i = 0; i < items.length; i += columns) rows.push(items.slice(i, i + columns));
  return (
    <div style={{ ...column, marginLeft: -10, marginRight: -10, gap: 6 }}>
      {rows.map((cells, i) => (
        <div key={i} style={{ ...row, alignItems: "flex-start" }}>
          {cells.map((item) => render(item, width))}
        </div>
      ))}
    </div>
  );
}

export function SectionHead({ title, action, onAction }: { title: string; action?: string; onAction?: Click }) {
  return (
    <div style={{ ...row, justifyContent: "space-between", marginBottom: 14 }}>
      <text style={{ fontSize: 22, fontWeight: 700, color: C.text }}>{title}</text>
      {action && (
        <div role="button" onClick={onAction} style={{ cursor: "pointer", color: C.muted, hover: { color: C.text } }}>
          <text style={{ fontSize: 13, fontWeight: 600 }}>{action}</text>
        </div>
      )}
    </div>
  );
}

/** Soft color from the artwork, behind the top of a page, as light through
 * glass falls on the ground. Each blob is only a wide blurred shadow: GPUI
 * paints a shadow under its box too, so with no fill of its own the blob has
 * no hard edge, and it fades out on its own before the page's bottom edge. */
export function Ambient({ palette, height = 460, strength = 1 }: { palette?: [string, string, string]; height?: number; strength?: number }) {
  const width = useContentWidth() + SIZE.page * 2;
  if (!palette) return null;
  const shore = theme.name === "shore";
  // Pale pools on the shore; at night, deep color in the water, not glow.
  const tone = (color: string) => (shore ? mixOklab(color, "#ffffff", 0.45) : mixOklab(color, "#000000", 0.25));
  const level = (shore ? 0.55 : 0.45) * strength;
  const blob = (color: string, left: number, top: number, size: number, opacity: number) => (
    <div
      style={{
        position: "absolute",
        left,
        top,
        width: size,
        height: size * 0.62,
        borderRadius: size,
        opacity: opacity * level,
        boxShadow: { offsetX: 0, offsetY: 0, blurRadius: Math.round(height * 0.32), spreadRadius: 40, color: tone(color) },
      }}
    />
  );
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top: 0, height, pointerEvents: "none" }}>
      {blob(palette[0], -80, -200, 380, 1)}
      {blob(palette[1], width * 0.42, -240, 360, 0.75)}
      {blob(palette[2], width * 0.82, -110, 320, 0.55)}
    </div>
  );
}

export function Empty({ title, detail, children }: { title: string; detail?: string; children?: ReactNode }) {
  return (
    <div style={{ ...column, alignItems: "center", paddingTop: 80, paddingBottom: 80, gap: 8 }}>
      <text style={{ fontSize: 18, fontWeight: 700, color: C.text }}>{title}</text>
      {detail && <text style={{ fontSize: 14, color: C.muted, textAlign: "center" }}>{detail}</text>}
      {children}
    </div>
  );
}

export const pageTitle = (): StyleDesc => ({ ...text.display(44), lineHeight: 50 });
