// Song tables: album pages, the songs list, favorites, playlists and search.
import { memo, useState } from "react";
import type { StyleDesc } from "@gpuix/react";
import { Icon } from "./icons";
import { Artwork, useContentWidth } from "./components";
import { ago, duration, type Song } from "./library";
import { songMenu } from "./menus";
import { go, openMenu, playSong, toggleFavorite, useStore, type SongSort } from "./store";
import { C, alpha, center, ellipsis, radius, row, text } from "./theme";

export type Variant = "album" | "full" | "artist" | "playlist";

const COLUMN = { num: 44, added: 116, plays: 64, fav: 36, time: 56, more: 36 };

/** Columns that fit: secondary ones go first as the page narrows. */
function useColumns(variant: Variant) {
  const width = useContentWidth();
  return {
    art: variant !== "album",
    album: variant !== "album" && width >= 560,
    added: (variant === "full" || variant === "playlist") && width >= 860,
    plays: width >= 700 || variant === "album",
  };
}

export const ROW_HEIGHT = 56;
export const ALBUM_ROW_HEIGHT = 50;

/** The playing song's ID, for highlighting its row. */
export function useCurrentId(): string | undefined {
  return useStore((s) => s.queue.find((entry) => entry.entry === s.player.current)?.id);
}

function Bars({ color }: { color: string }) {
  return (
    <div style={{ ...row, alignItems: "flex-end", gap: 2, height: 14 }}>
      {[8, 14, 10].map((h, i) => (
        <div key={i} style={{ width: 3, height: h, borderRadius: 2, backgroundColor: color }} />
      ))}
    </div>
  );
}

export const TrackRow = memo(function TrackRow({
  song,
  number,
  variant,
  list,
  current,
  fav,
  added,
  tag,
}: {
  song: Song;
  number: number;
  variant: Variant;
  list: string[];
  current: boolean;
  fav: boolean;
  added?: number;
  /** The album art's image tag. */
  tag?: string;
}) {
  const [hover, setHover] = useState(false);
  const cols = useColumns(variant);
  const showArtist = variant !== "album" || (song.albumArtist && song.artist !== song.albumArtist);
  const height = variant === "album" ? ALBUM_ROW_HEIGHT : ROW_HEIGHT;
  return (
    <div
      role="row"
      aria-label={`${song.title} by ${song.artist}`}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={(e) => e.clickCount === 2 && playSong(list, song.id)}
      onAuxClick={(e) => e.isRightClick && openMenu({ x: e.x ?? 0, y: e.y ?? 0, items: songMenu(song) })}
      style={{
        ...row,
        height,
        flexShrink: 0,
        gap: 12,
        paddingLeft: 10,
        paddingRight: 10,
        borderRadius: radius.md,
        backgroundColor: hover ? alpha(C.text, 0.045) : alpha(C.bg, 0),
      }}
    >
      <div style={{ ...center, width: COLUMN.num - 12, justifyContent: "flex-end", flexShrink: 0 }}>
        {hover ? (
          <div role="button" aria-label="Play" onClick={() => playSong(list, song.id)} style={{ ...center, cursor: "pointer" }}>
            <Icon name="play" size={16} color={C.text} />
          </div>
        ) : current ? (
          <Bars color={C.primary} />
        ) : (
          <text style={{ fontSize: 14, color: C.muted }}>{number}</text>
        )}
      </div>
      <div style={{ ...row, flexGrow: 4, flexBasis: 0, minWidth: 0, gap: 12 }}>
        {cols.art && <Artwork id={song.albumId} tag={tag} size={40} radius={7} />}
        <div style={{ display: "flex", flexDirection: "column", minWidth: 0, flexGrow: 1, gap: 3 }}>
          <text style={{ ...text.title(14), ...ellipsis, color: current ? C.primary : C.text }}>{song.title}</text>
          {showArtist && <text style={{ ...text.body(13, C.muted), ...ellipsis }}>{song.artist}</text>}
        </div>
      </div>
      {cols.album && (
        <div
          role="link"
          onClick={() => song.albumId && go({ name: "album", id: song.albumId })}
          style={{ flexGrow: 3, flexBasis: 0, minWidth: 0, cursor: "pointer", color: C.muted, hover: { color: C.text } }}
        >
          <text style={{ fontSize: 13, ...ellipsis }}>{song.album}</text>
        </div>
      )}
      {cols.added && (
        <text style={{ width: COLUMN.added, flexShrink: 0, fontSize: 13, color: C.muted, ...ellipsis }}>
          {ago(added ?? song.addedAt)}
        </text>
      )}
      {cols.plays && (
        <text style={{ width: COLUMN.plays, flexShrink: 0, fontSize: 13, color: C.muted, textAlign: "right" }}>
          {song.plays ? song.plays.toLocaleString() : "–"}
        </text>
      )}
      <div
        role="button"
        aria-label={fav ? "Remove from Favorites" : "Add to Favorites"}
        onClick={() => toggleFavorite(song.id)}
        style={{ ...center, width: COLUMN.fav, height: 32, borderRadius: 16, flexShrink: 0, cursor: "pointer", opacity: fav || hover ? 1 : 0 }}
      >
        <Icon name={fav ? "heartFilled" : "heart"} size={16} color={fav ? C.pink : C.muted} />
      </div>
      <text style={{ width: COLUMN.time, flexShrink: 0, fontSize: 13, color: C.muted, textAlign: "right" }}>
        {duration(song.duration)}
      </text>
      <div
        role="button"
        aria-label="More"
        onClick={(e) => openMenu({ x: (e.x ?? 0) - 200, y: (e.y ?? 0) + 12, items: songMenu(song) })}
        style={{
          ...center,
          width: COLUMN.more,
          height: 32,
          borderRadius: 16,
          flexShrink: 0,
          cursor: "pointer",
          opacity: hover ? 1 : 0,
          hover: { backgroundColor: alpha(C.text, 0.07) },
        }}
      >
        <Icon name="more" size={18} color={C.muted} />
      </div>
    </div>
  );
});

export function TrackHeader({
  variant,
  sort,
  onSort,
  style,
}: {
  variant: Variant;
  sort?: SongSort;
  onSort?: (key: SongSort["key"]) => void;
  style?: StyleDesc;
}) {
  const cols = useColumns(variant);
  const label = (title: string, key?: SongSort["key"], align: "left" | "right" = "left", width?: number, grow?: number) => {
    const active = !!key && sort?.key === key;
    return (
      <div
        role={key ? "button" : undefined}
        onClick={key && onSort ? () => onSort(key) : undefined}
        style={{
          ...row,
          justifyContent: align === "right" ? "flex-end" : "flex-start",
          gap: 4,
          ...(width ? { width, flexShrink: 0 } : { flexGrow: grow, flexBasis: 0, minWidth: 0 }),
          cursor: key && onSort ? "pointer" : "default",
          color: active ? C.text : C.faint,
          hover: key && onSort ? { color: C.text } : undefined,
        }}
      >
        <text style={{ fontSize: 12.5, fontWeight: 600 }}>{title}</text>
        {active && <Icon name="down" size={12} color={C.text} />}
      </div>
    );
  };
  return (
    <div
      style={{
        ...row,
        height: 38,
        flexShrink: 0,
        gap: 12,
        paddingLeft: 10,
        paddingRight: 10,
        borderBottomWidth: 1,
        borderColor: C.hairline,
        ...style,
      }}
    >
      <div style={{ width: COLUMN.num - 12, flexShrink: 0, ...row, justifyContent: "flex-end" }}>
        <text style={{ fontSize: 12.5, fontWeight: 600, color: C.faint }}>#</text>
      </div>
      {label("Title", onSort ? "title" : undefined, "left", undefined, 4)}
      {cols.album && label("Album", onSort ? "album" : undefined, "left", undefined, 3)}
      {cols.added && label("Date added", onSort ? "added" : undefined, "left", COLUMN.added)}
      {cols.plays && label("Plays", onSort ? "plays" : undefined, "right", COLUMN.plays)}
      <div style={{ width: COLUMN.fav, flexShrink: 0 }} />
      <div style={{ ...row, width: COLUMN.time, flexShrink: 0, justifyContent: "flex-end" }}>
        <Icon name="clock" size={14} color={C.faint} />
      </div>
      <div style={{ width: COLUMN.more, flexShrink: 0 }} />
    </div>
  );
}
