// The player bar, the queue, and the full-window player.
import { createElement, useEffect, useRef, useState } from "react";
import { AnimatePresence, type EventPayload } from "@gpuix/react";
import { Artwork, IconButton, Motion, artShadow } from "./components";
import { Glass, TINTS, accent, ink, inkSoft, useNowPalette, useNowTint, type Tint } from "./glass";
import { Icon } from "./icons";
import { duration, format, plural, type Song } from "./library";
import { Lyrics } from "./lyrics";
import { songMenu } from "./menus";
import { command, host } from "./native";
import {
  ensureArtwork,
  ensureArtistInfo,
  go,
  openMenu,
  setFull,
  toggleFavorite,
  togglePanel,
  useStore,
} from "./store";
import { C, F, SIZE, alpha, center, column, ellipsis, motion, row, text, theme } from "./theme";
import { CaptionButtons } from "./shell";

/** The song the player has loaded, looked up in the library. */
export function useCurrent(): Song | undefined {
  const id = useStore((s) => s.queue.find((e) => e.entry === s.player.current)?.id);
  const library = useStore((s) => s.library);
  return id ? library?.song.get(id) : undefined;
}

function useAlbumTag(song?: Song) {
  return useStore((s) => (song?.albumId ? s.library?.album.get(song.albumId)?.imageTag : undefined));
}

/**
 * A horizontal drag along an element, reported as 0–1. Pressing starts it and
 * releasing ends it. A move with the button already up also ends it, because
 * a release can land where the element does not hear it, such as outside the
 * window; without that, the value would go on following the pointer.
 */
function useDrag(onEnd: (fraction: number) => void) {
  const ref = useRef<{ id: number } | null>(null);
  const [preview, setPreview] = useState<number | null>(null);
  // Also kept outside state: a quick click delivers press and release before
  // React renders the press.
  const drag = useRef<number | null>(null);
  const fraction = (e: EventPayload) => {
    const box = ref.current && host().renderer.getElementBounds(ref.current.id);
    if (!box || e.x === undefined) return null;
    return Math.max(0, Math.min(1, (e.x - box.x) / box.width));
  };
  const show = (value: number | null) => {
    drag.current = value;
    setPreview(value);
  };
  const end = (value: number | null) => {
    show(null);
    if (value !== null) onEnd(value);
  };
  return {
    ref,
    preview,
    handlers: {
      onMouseDown: (e: EventPayload) => e.button === 0 && show(fraction(e)),
      onMouseMove: (e: EventPayload) => {
        if (drag.current === null) return;
        if (e.pressedButton === 0) show(fraction(e) ?? drag.current);
        else end(drag.current);
      },
      onMouseUp: (e: EventPayload) => drag.current !== null && end(fraction(e) ?? drag.current),
    },
  };
}

/** The wave seek bar, in the playing album's color: its deep tone on the
 * shore, its fluorescence at night. */
export function Seek({ height = 16, tint, amplitude = 2.6 }: { height?: number; tint: Tint; amplitude?: number }) {
  const color = accent(tint);
  const track = alpha(C.text, theme.name === "shore" ? 0.14 : 0.17);
  const second = useStore((s) => s.second);
  const length = useStore((s) => s.player.duration);
  const [hover, setHover] = useState(false);
  // Where the user let go, shown until the polled position catches up.
  const [sought, setSought] = useState<number | null>(null);
  useEffect(() => {
    if (sought === null) return;
    if (Math.abs(second - sought) < 1.5) return setSought(null);
    const timer = setTimeout(() => setSought(null), 1500);
    return () => clearTimeout(timer);
  }, [second, sought]);
  const drag = useDrag((fraction) => {
    setSought(fraction * length);
    command("seek", { position: fraction * length });
  });
  const shown = drag.preview !== null ? drag.preview * length : (sought ?? second);
  const timeStyle = { fontSize: 11.5, color: C.muted, width: 40, flexShrink: 0 };
  return (
    <div style={{ ...row, gap: 10, width: "100%" }}>
      <text style={{ ...timeStyle, textAlign: "right" }}>{duration(shown)}</text>
      <div
        ref={drag.ref as never}
        {...drag.handlers}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        role="slider"
        aria-label="Playback position"
        style={{ flexGrow: 1, height, cursor: "pointer" }}
      >
        {createElement("aurelia-wave", {
          color,
          trackColor: track,
          thumb: hover || drag.preview !== null,
          preview: drag.preview,
          amplitude,
          style: { width: "100%", height },
        })}
      </div>
      <text style={timeStyle}>{duration(length)}</text>
    </div>
  );
}

const VOLUME_WIDTH = 104;

function Volume({ tint }: { tint: Tint }) {
  const volume = useStore((s) => s.player.volume);
  const [muted, setMuted] = useState<number | null>(null);
  // The volume last asked for, shown until the player reports it. Without it
  // the bar falls back to the old value for a poll after every change.
  const [pending, setPending] = useState<number | null>(null);
  // Also kept outside state: wheel notches can arrive before a render.
  const latest = useRef<number | null>(null);
  const change = (value: number) => {
    latest.current = value;
    setPending(value);
    command("volume", { value });
  };
  useEffect(() => {
    if (pending === null) return;
    const settle = () => {
      latest.current = null;
      setPending(null);
    };
    if (Math.abs(volume - pending) < 0.001) return settle();
    // The player never reported it, so a failed change does not stick.
    const timer = setTimeout(settle, 1500);
    return () => clearTimeout(timer);
  }, [volume, pending]);
  const drag = useDrag(change);
  useEffect(() => {
    if (drag.preview !== null) change(drag.preview);
  }, [drag.preview]);
  const shown = drag.preview ?? pending ?? volume;
  return (
    <div style={{ ...row, gap: 6, marginLeft: 4 }}>
      <IconButton
        icon={shown === 0 ? "mute" : shown < 0.5 ? "volumeLow" : "volume"}
        label={shown === 0 ? "Unmute" : "Mute"}
        iconSize={19}
        onClick={() => {
          if (shown > 0) {
            setMuted(shown);
            change(0);
          } else change(muted ?? 0.7);
        }}
      />
      <div
        ref={drag.ref as never}
        {...drag.handlers}
        // GPUI reports wheel-up as a positive delta. Each notch builds on the
        // last one sent, which the player may not have reported yet.
        onScroll={(e) => change(Math.max(0, Math.min(1, (latest.current ?? volume) + Math.sign(e.deltaY ?? 0) * 0.05)))}
        role="slider"
        aria-label="Volume"
        style={{ ...row, width: VOLUME_WIDTH, height: 16, cursor: "pointer" }}
      >
        {/* A filled child would take the press for itself; the slider handles it. */}
        <div style={{ position: "relative", width: "100%", height: 6, borderRadius: 3, backgroundColor: alpha(C.text, 0.14), pointerEvents: "none" }}>
          <div
            style={{
              position: "absolute",
              left: 0,
              top: 0,
              bottom: 0,
              width: `${Math.round(shown * 100)}%`,
              borderRadius: 3,
              backgroundColor: theme.name === "shore" ? tint.d : alpha(C.text, 0.7),
              pointerEvents: "none",
            }}
          />
          <div style={{ position: "absolute", left: Math.round(shown * VOLUME_WIDTH) - 7, top: -4, width: 14, height: 14, borderRadius: 7, pointerEvents: "none" }}>
            <Glass tint={TINTS.frost} variant="pearl" radius={7} />
          </div>
        </div>
      </div>
    </div>
  );
}

/** Playback controls. Play is a piece of the album's glass that drifts while
 * it plays and fluoresces at night; shuffle and repeat turn to glass when on. */
function Transport({ size = "bar", tint }: { size?: "bar" | "full"; tint: Tint }) {
  const playing = useStore((s) => s.player.playing);
  const loading = useStore((s) => s.player.loading);
  const shuffle = useStore((s) => s.player.shuffle);
  const repeat = useStore((s) => s.player.repeat);
  const full = size === "full";
  const play = full ? 76 : 42;
  const side = full ? 64 : 36;
  const corner = playing ? (full ? 26 : 15) : play / 2;
  // White glass on the shore; at night clear glass turns green at its edges,
  // which fights the album's color, so the album's own glass instead.
  const around = theme.name === "shore" ? TINTS.frost : tint;
  return (
    <div style={{ ...row, gap: full ? 0 : 10, justifyContent: full ? "space-between" : "center", width: full ? "100%" : undefined }}>
      <IconButton
        icon="shuffle"
        label={shuffle ? "Turn shuffle off" : "Shuffle"}
        active={shuffle}
        tint={tint}
        size={full ? 48 : 36}
        iconSize={full ? 20 : 18}
        onClick={() => command("shuffle")}
      />
      <IconButton
        icon="prev"
        label="Previous"
        glass={full ? "tonal" : undefined}
        tint={around}
        color={C.muted}
        size={side}
        iconSize={full ? 26 : 22}
        onClick={() => command("previous")}
      />
      <div
        role="button"
        aria-label={playing ? "Pause" : "Play"}
        onClick={() => command("toggle")}
        style={{ ...center, position: "relative", width: play, height: play, borderRadius: corner, cursor: "pointer", opacity: loading ? 0.6 : 1 }}
      >
        {/* It fluoresces only while music plays; paused, it is plain glass. */}
        <Glass tint={tint} variant="filled" radius={corner} live={playing} interactive />
        <Icon name={playing ? "pause" : "play"} size={full ? 32 : 20} color={ink(tint, "filled")} />
      </div>
      <IconButton
        icon="next"
        label="Next"
        glass={full ? "tonal" : undefined}
        tint={around}
        color={C.muted}
        size={side}
        iconSize={full ? 26 : 22}
        onClick={() => command("next")}
      />
      <IconButton
        icon={repeat === "one" ? "repeatOne" : "repeat"}
        label={repeat === "off" ? "Repeat" : repeat === "all" ? "Repeat this song" : "Turn repeat off"}
        active={repeat !== "off"}
        tint={tint}
        size={full ? 48 : 36}
        iconSize={full ? 20 : 18}
        onClick={() => command("repeat")}
      />
    </div>
  );
}

/** The player, docked: a frosted bar floating over the ground. */
export function PlayerBar() {
  const song = useCurrent();
  const tag = useAlbumTag(song);
  const tint = useNowTint();
  const panel = useStore((s) => s.panel);
  const error = useStore((s) => s.player.error);
  const loading = useStore((s) => s.player.loading);
  useStore((s) => s.favVersion);
  const library = useStore((s) => s.library);
  return (
    <div
      style={{
        ...row,
        position: "relative",
        height: SIZE.dock,
        flexShrink: 0,
        gap: 16,
        marginLeft: 10,
        marginRight: SIZE.gutter,
        marginTop: SIZE.gutter,
        marginBottom: SIZE.gutter,
        paddingLeft: 12,
        paddingRight: 16,
        borderRadius: 28,
      }}
    >
      <Glass variant="panel" radius={28} />
      <div style={{ ...row, flexGrow: 1, flexBasis: 0, minWidth: 240, gap: 14 }}>
        {song ? (
          <>
            <div role="button" aria-label="Open the player" onClick={() => setFull(true)} style={{ cursor: "pointer", flexShrink: 0 }}>
              <Artwork id={song.albumId} tag={tag} size={56} radius={14} shadow />
            </div>
            <div style={{ ...column, gap: 3, minWidth: 0 }}>
              <div role="link" onClick={() => song.albumId && go({ name: "album", id: song.albumId })} style={{ cursor: "pointer" }}>
                <text style={{ ...text.title(14), ...ellipsis }}>{song.title}</text>
              </div>
              <text style={{ ...text.body(13, error ? C.error : loading ? C.primary : C.muted), ...ellipsis }}>
                {error ?? (loading ? "Loading…" : `${song.artist} · ${song.album}`)}
              </text>
            </div>
            <IconButton
              icon={song.fav ? "heartFilled" : "heart"}
              label={song.fav ? "Remove from Favorites" : "Add to Favorites"}
              color={song.fav ? C.pink : C.muted}
              iconSize={18}
              onClick={() => toggleFavorite(song.id)}
            />
          </>
        ) : (
          <>
            <div style={{ ...center, width: 56, height: 56, borderRadius: 14, backgroundColor: alpha(C.text, 0.06), flexShrink: 0 }}>
              <Icon name="songs" size={22} color={C.faint} />
            </div>
            <div style={{ ...column, gap: 3 }}>
              <text style={text.title(14)}>Nothing playing</text>
              {library && (
                <div role="button" onClick={() => command("play", { ids: library.songs.map((s) => s.id), shuffle: true })} style={{ cursor: "pointer" }}>
                  <text style={{ fontSize: 13, color: C.primary }}>Shuffle your library</text>
                </div>
              )}
            </div>
          </>
        )}
      </div>
      <div style={{ ...column, alignItems: "center", gap: 4, flexGrow: 1.4, flexBasis: 0, maxWidth: 640, minWidth: 360 }}>
        <Transport tint={tint} />
        <Seek tint={tint} />
      </div>
      <div style={{ ...row, flexGrow: 1, flexBasis: 0, minWidth: 240, gap: 4, justifyContent: "flex-end" }}>
        <IconButton icon="lyrics" label="Lyrics" iconSize={19} active={panel === "lyrics"} tint={tint} onClick={() => togglePanel("lyrics")} />
        <IconButton icon="queue" label="Queue" iconSize={19} active={panel === "queue"} tint={tint} onClick={() => togglePanel("queue")} />
        <Volume tint={tint} />
        <IconButton icon="expand" label="Full-screen player" iconSize={18} onClick={() => song && setFull(true)} />
      </div>
    </div>
  );
}

// ---- Queue --------------------------------------------------------------------

const QUEUE_ROW = 58;
const QUEUE_LIMIT = 200;

export function Queue() {
  const queue = useStore((s) => s.queue);
  const current = useStore((s) => s.player.current);
  const library = useStore((s) => s.library);
  const index = queue.findIndex((e) => e.entry === current);
  const upcoming = queue.slice(index + 1);
  const [drag, setDrag] = useState<{ entry: number; from: number; y0: number; to: number } | null>(null);
  const drop = () => {
    if (drag && drag.to !== drag.from) command("move", { entry: drag.entry, to: drag.to });
    setDrag(null);
  };
  if (!library) return null;
  return (
    <div style={{ ...column, flexGrow: 1, minHeight: 0, overflowY: "scroll", paddingLeft: 8, paddingRight: 8, paddingBottom: 16 }}>
      <div style={{ ...row, justifyContent: "space-between", paddingLeft: 8, paddingRight: 8, paddingTop: 14, paddingBottom: 8 }}>
        <text style={{ fontSize: 12, fontWeight: 600, color: C.faint }}>Next up</text>
        {upcoming.length > 0 && (
          <div role="button" onClick={() => command("clearUpcoming")} style={{ cursor: "pointer", color: C.muted, hover: { color: C.text } }}>
            <text style={{ fontSize: 12 }}>Clear</text>
          </div>
        )}
      </div>
      {upcoming.length === 0 && (
        <text style={{ fontSize: 13, color: C.muted, padding: 12 }}>Nothing queued after this song.</text>
      )}
      {upcoming.slice(0, QUEUE_LIMIT).map((item, i) => {
        const song = library.song.get(item.id);
        if (!song) return null;
        const position = index + 1 + i;
        // The moved row lands in this row's place: below it when moving down.
        const target = drag && drag.to === position && drag.from !== position;
        const below = target && drag.to > drag.from;
        return (
          <div
            key={item.entry}
            role="listitem"
            onClick={(e) => e.clickCount === 2 || command("jump", { entry: item.entry })}
            onAuxClick={(e) =>
              e.isRightClick &&
              openMenu({
                x: e.x ?? 0,
                y: e.y ?? 0,
                items: [{ label: "Remove from queue", icon: "x", run: () => command("remove", { entry: item.entry }) }, "-", ...songMenu(song)],
              })
            }
            style={{
              ...row,
              height: QUEUE_ROW,
              flexShrink: 0,
              gap: 12,
              paddingLeft: 4,
              paddingRight: 8,
              borderRadius: 10,
              cursor: "pointer",
              opacity: drag?.entry === item.entry ? 0.4 : 1,
              borderTopWidth: target && !below ? 2 : 0,
              borderBottomWidth: below ? 2 : 0,
              borderColor: C.primary,
              hover: { backgroundColor: alpha(C.text, 0.05) },
            }}
          >
            <div
              role="button"
              aria-label={`Reorder ${song.title}`}
              onMouseDown={(e) => e.button === 0 && setDrag({ entry: item.entry, from: position, y0: e.y ?? 0, to: position })}
              onMouseMove={(e) => {
                if (!drag) return;
                // The button is already up: the release landed out of earshot.
                if (e.pressedButton !== 0) return drop();
                setDrag({ ...drag, to: Math.max(index + 1, Math.min(queue.length - 1, drag.from + Math.round(((e.y ?? 0) - drag.y0) / QUEUE_ROW))) });
              }}
              onMouseUp={drop}
              style={{ ...center, width: 20, height: 40, cursor: "grab", active: { cursor: "grabbing" } }}
            >
              <Icon name="grip" size={16} color={C.faint} />
            </div>
            <Artwork id={song.albumId} tag={song.albumId ? library.album.get(song.albumId)?.imageTag : undefined} size={42} radius={7} />
            <div style={{ ...column, flexGrow: 1, gap: 2 }}>
              <text style={{ ...text.title(14), ...ellipsis }}>{song.title}</text>
              <text style={{ fontSize: 12.5, color: C.muted, ...ellipsis }}>{song.artist}</text>
            </div>
            <text style={{ fontSize: 12.5, color: C.muted }}>{duration(song.duration)}</text>
          </div>
        );
      })}
      {upcoming.length > QUEUE_LIMIT && (
        <text style={{ fontSize: 13, color: C.muted, padding: 12 }}>{`and ${plural(upcoming.length - QUEUE_LIMIT, "more song")}`}</text>
      )}
    </div>
  );
}

export function NowCard() {
  const song = useCurrent();
  const tag = useAlbumTag(song);
  const tint = useNowTint();
  if (!song) return null;
  const info = format(song);
  return (
    <div
      style={{
        ...row,
        position: "relative",
        gap: 14,
        padding: 12,
        marginLeft: 8,
        marginRight: 8,
        marginTop: 4,
        borderRadius: 22,
      }}
    >
      <Glass tint={tint} radius={22} live />
      <Artwork id={song.albumId} tag={tag} size={84} radius={14} shadow />
      <div style={{ ...column, gap: 3, minWidth: 0 }}>
        <text style={{ fontSize: 11, fontWeight: 600, color: inkSoft(tint, "tonal") }}>Now playing</text>
        <text style={{ fontSize: 16, fontWeight: 800, color: ink(tint, "tonal"), ...ellipsis }}>{song.title}</text>
        <text style={{ fontSize: 13, color: inkSoft(tint, "tonal"), ...ellipsis }}>{`${song.artist} · ${song.album}`}</text>
        {info && <text style={{ fontSize: 11, fontWeight: 600, color: inkSoft(tint, "tonal"), marginTop: 6 }}>{info}</text>}
      </div>
    </div>
  );
}

// ---- Full player --------------------------------------------------------------


export function FullPlayer() {
  const open = useStore((s) => s.full);
  return (
    <AnimatePresence>
      {open && (
        <Motion
          key="full"
          initial={{ opacity: 0, top: 24 }}
          animate={{ opacity: 1, top: 0 }}
          exit={{ opacity: 0, top: 24 }}
          transition={{ duration: 0.32, ease: motion.ease }}
          style={{ position: "absolute", left: 0, right: 0, height: "100%", backgroundColor: C.frame, pointerEvents: "auto" }}
        >
          <FullPlayerBody />
        </Motion>
      )}
    </AnimatePresence>
  );
}

function FullPlayerBody() {
  const song = useCurrent();
  const tag = useAlbumTag(song);
  const tab = useStore((s) => s.fullTab);
  const tint = useNowTint();
  const clouds = useNowPalette();
  const theme_ = useStore((s) => s.theme);
  const win = useStore((s) => s.window);
  useStore((s) => s.favVersion);
  useEffect(() => ensureArtwork(song?.albumId, tag), [song?.albumId, tag]);
  if (!song) return null;
  const w = win.width;
  const h = win.height;
  const artSize = Math.min(460, Math.max(240, Math.min(w * 0.36, h - 330)));

  const tabs: { key: typeof tab; label: string }[] = [
    { key: "lyrics", label: "Lyrics" },
    { key: "queue", label: "Up next" },
    { key: "about", label: "About" },
  ];
  return (
    <div style={{ position: "relative", width: "100%", height: "100%", overflow: "hidden" }}>
      {createElement("aurelia-clouds", { colors: clouds, theme: theme_, style: { position: "absolute", left: 0, top: 0, width: w, height: h } })}
      <div style={{ ...column, position: "absolute", left: 0, top: 0, width: w, height: h }}>
        <div style={{ ...row, height: 56, flexShrink: 0, paddingLeft: 16 }}>
          <IconButton icon="down" label="Close the player" color={C.text} iconSize={22} onClick={() => setFull(false)} />
          <CaptionFill />
          <div style={{ ...row, position: "relative", gap: 4, padding: 4, borderRadius: 21 }}>
            <Glass tint={tint} radius={21} />
            {tabs.map((t) => (
              <div
                key={t.key}
                role="tab"
                aria-selected={tab === t.key}
                onClick={() => setFull(true, t.key)}
                style={{
                  ...center,
                  position: "relative",
                  height: 34,
                  paddingLeft: 16,
                  paddingRight: 16,
                  borderRadius: 17,
                  cursor: "pointer",
                  hover: tab === t.key ? undefined : { backgroundColor: alpha(ink(tint, "tonal"), 0.1) },
                }}
              >
                {tab === t.key && <Glass tint={tint} variant="pearl" radius={17} />}
                <text style={{ fontSize: 14, fontWeight: 600, color: tab === t.key ? ink(tint, "pearl") : inkSoft(tint, "tonal") }}>{t.label}</text>
              </div>
            ))}
          </div>
          <CaptionFill />
          <CaptionButtons />
        </div>
        <div style={{ ...row, flexGrow: 1, minHeight: 0, alignItems: "stretch", paddingLeft: w * 0.07, paddingRight: w * 0.05, paddingBottom: 40, gap: w * 0.06 }}>
          <div style={{ ...column, justifyContent: "center", width: artSize, flexShrink: 0 }}>
            <Motion
              initial={false}
              animate={{ opacity: 1 }}
              style={{ width: artSize, height: artSize, boxShadow: { ...artShadow(artSize), offsetY: 30, blurRadius: 44, spreadRadius: -16 }, borderRadius: 30 }}
            >
              <Artwork id={song.albumId} tag={tag} size={artSize} radius={30} />
            </Motion>
            <div style={{ ...row, gap: 12, marginTop: 28, alignItems: "flex-start" }}>
              <div style={{ ...column, flexGrow: 1, gap: 4 }}>
                <text style={{ fontSize: 26, fontWeight: 800, color: C.text, ...ellipsis }}>{song.title}</text>
                <div style={{ ...row, gap: 6, minWidth: 0 }}>
                  <div
                    role="link"
                    onClick={() => song.artistIds[0] && go({ name: "artist", id: song.artistIds[0] })}
                    style={{ cursor: "pointer", flexShrink: 1, minWidth: 0 }}
                  >
                    <text style={{ fontSize: 16, color: C.muted, ...ellipsis }}>{song.artist}</text>
                  </div>
                  <text style={{ fontSize: 16, color: C.faint }}>·</text>
                  <div role="link" onClick={() => song.albumId && go({ name: "album", id: song.albumId })} style={{ cursor: "pointer", flexShrink: 1, minWidth: 0 }}>
                    <text style={{ fontSize: 16, color: C.muted, ...ellipsis }}>{song.album}</text>
                  </div>
                </div>
                <text style={{ fontSize: 12, fontWeight: 600, color: C.faint, marginTop: 4 }}>{format(song)}</text>
              </div>
              <IconButton
                icon={song.fav ? "heartFilled" : "heart"}
                label={song.fav ? "Remove from Favorites" : "Add to Favorites"}
                color={C.muted}
                glass={song.fav ? "tonal" : undefined}
                tint={TINTS.rose}
                size={44}
                iconSize={22}
                onClick={() => toggleFavorite(song.id)}
              />
            </div>
            <div style={{ marginTop: 18 }}>
              <Seek height={20} tint={tint} amplitude={3.4} />
            </div>
            <div style={{ marginTop: 14 }}>
              <Transport size="full" tint={tint} />
            </div>
          </div>
          <div style={{ ...column, flexGrow: 1, minWidth: 0 }}>
            {tab === "lyrics" && <Lyrics size="full" />}
            {tab === "queue" && <Queue />}
            {tab === "about" && <About song={song} />}
          </div>
        </div>
      </div>
    </div>
  );
}

function CaptionFill() {
  return createElement("gpuix-caption", { action: "drag", style: { flexGrow: 1, height: 56 } });
}

function About({ song }: { song: Song }) {
  const library = useStore((s) => s.library)!;
  const artistId = song.artistIds[0];
  const info = useStore((s) => (artistId ? s.artistInfo[artistId] : undefined));
  useEffect(() => {
    if (artistId) ensureArtistInfo(artistId);
  }, [artistId]);
  const album = song.albumId ? library.album.get(song.albumId) : undefined;
  const overview = typeof info === "object" ? info.overview : undefined;
  const facts: string[] = [
    album?.title ?? song.album,
    ...(album?.year ? [String(album.year)] : []),
    ...(song.genres?.length ? [song.genres.slice(0, 2).join(", ")] : []),
    ...(song.track && album ? [`Track ${song.track} of ${album.songs.length}`] : []),
  ];
  return (
    <div style={{ ...column, justifyContent: "center", flexGrow: 1, maxWidth: 560, gap: 10 }}>
      <text style={{ fontSize: 12, fontWeight: 600, color: C.faint }}>About the artist</text>
      <text style={{ fontFamily: F.display, fontWeight: 900, fontSize: 40, color: C.text }}>{song.artist}</text>
      <text style={{ fontSize: 16, lineHeight: 26, color: C.muted, lineClamp: 10 }}>
        {overview ?? (info === "loading" ? "" : "No biography for this artist on your server.")}
      </text>
      <text style={{ fontSize: 12, fontWeight: 600, color: C.faint, marginTop: 20 }}>From the album</text>
      <text style={{ fontSize: 16, color: C.muted }}>{facts.join(" · ")}</text>
    </div>
  );
}

