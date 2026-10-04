// Shared controls and pieces, so spacing, type and motion stay consistent.
import { useEffect, useState, type ComponentType, type ReactNode } from "react";
import { motion as native, type EventPayload, type MotionProps, type Props, type StyleDesc } from "@gpuix/react";
import { Icon, type IconName } from "./icons";
import { imageUrl, type Album, type Artist } from "./library";
import { albumMenu, artistMenu } from "./menus";
import { go, openMenu, play, useStore } from "./store";
import { C, SIZE, alpha, center, column, ellipsis, motion, radius, row, text } from "./theme";

type Click = (event: EventPayload) => void;

// motion.div's published types are narrow, but it passes every prop through.
export const Motion = native.div as unknown as ComponentType<Props & MotionProps>;

export type Tone = "filled" | "tonal" | "ghost";

const toneStyle = (tone: Tone): StyleDesc =>
  tone === "filled"
    ? { backgroundColor: C.primary, color: C.onPrimary, hover: { backgroundColor: "#ad9cff" }, active: { opacity: 0.85 } }
    : tone === "tonal"
      ? {
          backgroundColor: C.primaryContainer,
          color: C.onPrimaryContainer,
          hover: { backgroundColor: "#3b3666" },
          active: { opacity: 0.85 },
        }
      : { backgroundColor: alpha(C.text, 0.06), color: C.text, hover: { backgroundColor: alpha(C.text, 0.1) } };

export function Button({
  label,
  icon,
  tone = "ghost",
  onClick,
  style,
  height = 44,
}: {
  label: string;
  icon?: IconName;
  tone?: Tone;
  onClick?: Click;
  style?: StyleDesc;
  height?: number;
}) {
  const color = tone === "filled" ? C.onPrimary : tone === "tonal" ? C.onPrimaryContainer : C.text;
  return (
    <div
      role="button"
      aria-label={label}
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => (e.key === "enter" || e.key === "space") && onClick?.(e)}
      style={{
        ...row,
        height,
        gap: 10,
        paddingLeft: height / 2 - 2,
        paddingRight: height / 2,
        borderRadius: height / 2,
        flexShrink: 0,
        cursor: "pointer",
        ...toneStyle(tone),
        ...style,
      }}
    >
      {icon && <Icon name={icon} size={18} color={color} />}
      <text style={{ fontSize: 14, fontWeight: 600, color, whiteSpace: "nowrap" }}>{label}</text>
    </div>
  );
}

/** Joined buttons, as on Android: round outer ends, tight inner corners. */
export function ButtonGroup({
  items,
}: {
  items: { label?: string; icon: IconName; tone: Tone; onClick: Click; aria?: string }[];
}) {
  return (
    <div style={{ ...row, gap: 3 }}>
      {items.map((item, index) => {
        const first = index === 0;
        const last = index === items.length - 1;
        const outer = 24;
        const inner = 6;
        const color = item.tone === "filled" ? C.onPrimary : C.onPrimaryContainer;
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
              borderTopLeftRadius: first ? outer : inner,
              borderBottomLeftRadius: first ? outer : inner,
              borderTopRightRadius: last ? outer : inner,
              borderBottomRightRadius: last ? outer : inner,
              cursor: "pointer",
              ...toneStyle(item.tone),
            }}
          >
            <Icon name={item.icon} size={item.label ? 18 : 20} color={color} />
            {item.label && <text style={{ fontSize: 14, fontWeight: 600, color, whiteSpace: "nowrap" }}>{item.label}</text>}
          </div>
        );
      })}
    </div>
  );
}

export function IconButton({
  icon,
  label,
  onClick,
  size = 36,
  iconSize = 20,
  color = C.muted,
  active,
  activeColor = C.primary,
  style,
}: {
  icon: IconName;
  label: string;
  onClick?: Click;
  size?: number;
  iconSize?: number;
  color?: string;
  active?: boolean;
  activeColor?: string;
  style?: StyleDesc;
}) {
  return (
    <div
      role="button"
      aria-label={label}
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => (e.key === "enter" || e.key === "space") && onClick?.(e)}
      style={{
        ...center,
        width: size,
        height: size,
        flexShrink: 0,
        borderRadius: size / 2,
        cursor: "pointer",
        color: active ? activeColor : color,
        hover: { backgroundColor: C.surfaceHigh, color: active ? activeColor : C.text },
        ...style,
      }}
    >
      <Icon name={icon} size={iconSize} color={active ? activeColor : color} />
    </div>
  );
}

export function Chip({ label, on, onClick, icon }: { label: string; on?: boolean; onClick?: Click; icon?: IconName }) {
  const color = on ? "#1b1640" : C.muted;
  return (
    <div
      role="button"
      aria-pressed={!!on}
      onClick={onClick}
      style={{
        ...row,
        height: 34,
        paddingLeft: icon ? 12 : 14,
        paddingRight: 14,
        gap: 6,
        borderRadius: 17,
        flexShrink: 0,
        cursor: "pointer",
        backgroundColor: on ? C.onPrimaryContainer : C.surface,
        hover: on ? undefined : { backgroundColor: C.surfaceHigh },
      }}
    >
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
    <div style={{ ...row, gap: 2, padding: 3, borderRadius: 12, backgroundColor: C.surface, flexShrink: 0 }}>
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
              width: 34,
              height: 28,
              borderRadius: 9,
              cursor: "pointer",
              backgroundColor: on ? C.surfaceHigher : C.surface,
            }}
          >
            <Icon name={option.icon} size={16} color={on ? C.text : C.muted} />
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
  const src = imageUrl(session, id, tag, pixels);
  const corner = round ? size / 2 : r;
  const box: StyleDesc = {
    width: size,
    height: size,
    flexShrink: 0,
    borderRadius: corner,
    ...(shadow ? { boxShadow: { offsetX: 0, offsetY: 8, blurRadius: 24, spreadRadius: 0, color: "#00000059" } } : {}),
    ...style,
  };
  if (!src) {
    return (
      <div style={{ ...center, ...box, backgroundColor: C.surfaceHigh }}>
        <Icon name={round ? "user" : "music"} size={Math.max(14, Math.round(size * 0.32))} color={C.faint} />
      </div>
    );
  }
  // Jellyfin serves animated covers as full-size GIFs whatever size is asked
  // for, and every frame of one decodes to 100–300 MB. Show the first, shrunk
  // to the size it is drawn.
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
        backgroundColor: C.primary,
        cursor: "pointer",
        boxShadow: { offsetX: 0, offsetY: 8, blurRadius: 18, spreadRadius: 0, color: "#00000073" },
      }}
    >
      <Icon name="play" size={20} color={C.onPrimary} />
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

/** Soft color from the artwork, behind the top of a page. Each blob is only a
 * wide blurred shadow: GPUI paints a shadow under its box too, so with no fill
 * of its own the blob has no hard edge. */
export function Ambient({ palette, height = 460, strength = 1 }: { palette?: [string, string, string]; height?: number; strength?: number }) {
  const width = useContentWidth() + SIZE.page * 2;
  if (!palette) return null;
  const blob = (color: string, left: number, top: number, size: number, opacity: number) => (
    <div
      style={{
        position: "absolute",
        left,
        top,
        width: size,
        height: size * 0.62,
        borderRadius: size,
        opacity: opacity * strength,
        boxShadow: { offsetX: 0, offsetY: 0, blurRadius: 240, spreadRadius: 60, color },
      }}
    />
  );
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top: 0, height, overflow: "hidden", pointerEvents: "none" }}>
      {blob(palette[0], -80, -170, 380, 0.75)}
      {blob(palette[1], width * 0.42, -210, 360, 0.55)}
      {blob(palette[2], width * 0.82, -60, 320, 0.4)}
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          bottom: 0,
          height: Math.round(height * 0.55),
          background: {
            type: "linear-gradient",
            angle: 180,
            stops: [
              { color: alpha(C.bg, 0), position: 0 },
              { color: C.bg, position: 1 },
            ],
          },
        }}
      />
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

export const pageTitle: StyleDesc = { ...text.display(44), lineHeight: 50 };
