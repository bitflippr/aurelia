// The player bar, the queue, and the full-window player.
import { createElement, useEffect, useRef, useState } from "react";
import { AnimatePresence, type EventPayload } from "@gpuix/react";
import { Artwork, IconButton, Motion } from "./components";
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
import { C, F, SIZE, alpha, center, column, ellipsis, motion, row, text } from "./theme";
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

export function Seek({ height = 16, color = C.text, track = alpha(C.text, 0.16), amplitude = 2.6, light }: {
  height?: number;
  color?: string;
  track?: string;
  amplitude?: number;
  light?: boolean;
}) {
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
  const timeStyle = { fontSize: 11.5, color: light ? "#ffffffb3" : C.muted, width: 40, flexShrink: 0 };
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
          color: hover || drag.preview !== null ? (light ? "#ffffff" : C.primary) : color,
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

function Volume() {
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
        style={{ ...row, width: 104, height: 16, cursor: "pointer" }}
      >
        {/* A filled child would take the press for itself; the slider handles it. */}
        <div style={{ position: "relative", width: "100%", height: 4, borderRadius: 2, backgroundColor: alpha(C.text, 0.16), pointerEvents: "none" }}>
          <div
            style={{
              position: "absolute",
              left: 0,
              top: 0,
              bottom: 0,
              width: `${Math.round(shown * 100)}%`,
              borderRadius: 2,
              backgroundColor: C.text,
              pointerEvents: "none",
            }}
          />
        </div>
      </div>
    </div>
  );
}

function Transport({ size = "bar" }: { size?: "bar" | "full" }) {
  const playing = useStore((s) => s.player.playing);
  const loading = useStore((s) => s.player.loading);
  const shuffle = useStore((s) => s.player.shuffle);
  const repeat = useStore((s) => s.player.repeat);
  const full = size === "full";
  const play = full ? 76 : 42;
  const side = full ? 64 : 36;
  const tint = full ? "#ffffff" : C.text;
  const quiet = full ? "#ffffffbf" : C.muted;
  return (
    <div style={{ ...row, gap: full ? 0 : 10, justifyContent: full ? "space-between" : "center", width: full ? "100%" : undefined }}>
      <IconButton
        icon="shuffle"
        label={shuffle ? "Turn shuffle off" : "Shuffle"}
        active={shuffle}
        activeColor={full ? "#ffffff" : C.primary}
        color={quiet}
        size={full ? 48 : 36}
        iconSize={full ? 20 : 18}
        onClick={() => command("shuffle")}
        style={full && shuffle ? { backgroundColor: "#ffffff2e" } : undefined}
      />
      <IconButton
        icon="prev"
        label="Previous"
        color={full ? "#ffffff" : quiet}
        size={side}
        iconSize={full ? 26 : 22}
        onClick={() => command("previous")}
        style={full ? { backgroundColor: "#ffffff24" } : undefined}
      />
      <Motion
        role="button"
        aria-label={playing ? "Pause" : "Play"}
        onClick={() => command("toggle")}
        initial={false}
        animate={{ borderRadius: playing ? (full ? 26 : 14) : play / 2 }}
        transition={{ duration: 0.26, ease: motion.ease }}
        style={{ ...center, width: play, height: play, backgroundColor: tint, cursor: "pointer", opacity: loading ? 0.6 : 1 }}
      >
        <Icon name={playing ? "pause" : "play"} size={full ? 32 : 20} color={full ? "#111111" : C.frame} />
      </Motion>
      <IconButton
        icon="next"
        label="Next"
        color={full ? "#ffffff" : quiet}
        size={side}
        iconSize={full ? 26 : 22}
        onClick={() => command("next")}
        style={full ? { backgroundColor: "#ffffff24" } : undefined}
      />
      <IconButton
        icon={repeat === "one" ? "repeatOne" : "repeat"}
        label={repeat === "off" ? "Repeat" : repeat === "all" ? "Repeat this song" : "Turn repeat off"}
        active={repeat !== "off"}
        activeColor={full ? "#ffffff" : C.primary}
        color={quiet}
        size={full ? 48 : 36}
        iconSize={full ? 20 : 18}
        onClick={() => command("repeat")}
        style={full && repeat !== "off" ? { backgroundColor: "#ffffff2e" } : undefined}
      />
    </div>
  );
}

export function PlayerBar() {
  const song = useCurrent();
  const tag = useAlbumTag(song);
  const panel = useStore((s) => s.panel);
  const error = useStore((s) => s.player.error);
  const loading = useStore((s) => s.player.loading);
  useStore((s) => s.favVersion);
  const library = useStore((s) => s.library);
  return (
    <div style={{ ...row, height: SIZE.player, flexShrink: 0, gap: 16, paddingLeft: 12, paddingRight: 18 }}>
      <div style={{ ...row, flexGrow: 1, flexBasis: 0, minWidth: 240, gap: 14 }}>
        {song ? (
          <>
            <div role="button" aria-label="Open the player" onClick={() => setFull(true)} style={{ cursor: "pointer", flexShrink: 0 }}>
              <Artwork id={song.albumId} tag={tag} size={60} radius={10} shadow />
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
            <div style={{ ...center, width: 60, height: 60, borderRadius: 10, backgroundColor: C.surface, flexShrink: 0 }}>
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
      <div style={{ ...column, alignItems: "center", gap: 6, flexGrow: 1.4, flexBasis: 0, maxWidth: 640, minWidth: 360 }}>
        <Transport />
        <Seek />
      </div>
      <div style={{ ...row, flexGrow: 1, flexBasis: 0, minWidth: 240, gap: 4, justifyContent: "flex-end" }}>
        <IconButton icon="lyrics" label="Lyrics" iconSize={19} active={panel === "lyrics"} onClick={() => togglePanel("lyrics")} />
        <IconButton icon="queue" label="Queue" iconSize={19} active={panel === "queue"} onClick={() => togglePanel("queue")} />
        <Volume />
        <IconButton icon="expand" label="Full-screen player" iconSize={18} onClick={() => song && setFull(true)} />
      </div>
    </div>
  );
}

// ---- Queue --------------------------------------------------------------------

const QUEUE_ROW = 58;
const QUEUE_LIMIT = 200;

export function Queue({ light }: { light?: boolean }) {
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
        <text style={{ fontSize: 12, fontWeight: 600, color: light ? "#ffffff8c" : C.faint }}>Next up</text>
        {upcoming.length > 0 && (
          <div role="button" onClick={() => command("clearUpcoming")} style={{ cursor: "pointer", color: light ? "#ffffffb3" : C.muted, hover: { color: C.text } }}>
            <text style={{ fontSize: 12 }}>Clear</text>
          </div>
        )}
      </div>
      {upcoming.length === 0 && (
        <text style={{ fontSize: 13, color: light ? "#ffffffa6" : C.muted, padding: 12 }}>Nothing queued after this song.</text>
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
              hover: { backgroundColor: light ? "#ffffff14" : alpha(C.text, 0.045) },
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
              <Icon name="grip" size={16} color={light ? "#ffffff59" : C.faint} />
            </div>
            <Artwork id={song.albumId} tag={song.albumId ? library.album.get(song.albumId)?.imageTag : undefined} size={42} radius={7} />
            <div style={{ ...column, flexGrow: 1, gap: 2 }}>
              <text style={{ ...text.title(14), ...ellipsis }}>{song.title}</text>
              <text style={{ fontSize: 12.5, color: light ? "#ffffff99" : C.muted, ...ellipsis }}>{song.artist}</text>
            </div>
            <text style={{ fontSize: 12.5, color: light ? "#ffffff99" : C.muted }}>{duration(song.duration)}</text>
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
  const art = useStore((s) => (song?.albumId ? s.artwork[song.albumId] : undefined));
  useEffect(() => ensureArtwork(song?.albumId, tag), [song?.albumId, tag]);
  if (!song) return null;
  const palette = typeof art === "object" ? art.palette : undefined;
  const info = format(song);
  return (
    <div
      style={{
        ...row,
        gap: 14,
        padding: 12,
        marginLeft: 8,
        marginRight: 8,
        marginTop: 4,
        borderRadius: 16,
        background: palette
          ? { type: "linear-gradient", angle: 160, stops: [{ color: alpha(palette[0], 0.75), position: 0 }, { color: alpha(palette[1], 0.3), position: 1 }] }
          : C.surface,
      }}
    >
      <Artwork id={song.albumId} tag={tag} size={84} radius={10} shadow />
      <div style={{ ...column, gap: 3, minWidth: 0 }}>
        <text style={{ fontSize: 11, fontWeight: 600, color: C.muted }}>Now playing</text>
        <text style={{ fontSize: 16, fontWeight: 800, color: C.text, ...ellipsis }}>{song.title}</text>
        <text style={{ fontSize: 13, color: C.muted, ...ellipsis }}>{`${song.artist} · ${song.album}`}</text>
        {info && <text style={{ fontSize: 11, fontWeight: 600, color: C.muted, marginTop: 6 }}>{info}</text>}
      </div>
    </div>
  );
}

// ---- Full player --------------------------------------------------------------

/** Cloud colors for a song whose art has none. */
const PLAIN_CLOUDS = ["#4b3a9a", "#7a3560", "#2f4f80"];

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
          style={{ position: "absolute", left: 0, right: 0, height: "100%", backgroundColor: "#0e0d15", pointerEvents: "auto" }}
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
  const art = useStore((s) => (song?.albumId ? s.artwork[song.albumId] : undefined));
  const win = useStore((s) => s.window);
  useStore((s) => s.favVersion);
  useEffect(() => ensureArtwork(song?.albumId, tag), [song?.albumId, tag]);
  if (!song) return null;
  // While new art loads, the clouds keep the last song's colors.
  const clouds = typeof art === "object" ? art.palette : art === "none" ? PLAIN_CLOUDS : undefined;
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
      {createElement("aurelia-clouds", { colors: clouds, style: { position: "absolute", left: 0, top: 0, width: w, height: h } })}
      <div style={{ ...column, position: "absolute", left: 0, top: 0, width: w, height: h }}>
        <div style={{ ...row, height: 56, flexShrink: 0, paddingLeft: 16 }}>
          <IconButton icon="down" label="Close the player" color="#ffffffcc" iconSize={22} onClick={() => setFull(false)} style={{ hover: { backgroundColor: "#ffffff1f" } }} />
          <CaptionFill />
          <div style={{ ...row, gap: 4 }}>
            {tabs.map((t) => (
              <div
                key={t.key}
                role="tab"
                aria-selected={tab === t.key}
                onClick={() => setFull(true, t.key)}
                style={{
                  ...center,
                  height: 34,
                  paddingLeft: 16,
                  paddingRight: 16,
                  borderRadius: 17,
                  cursor: "pointer",
                  backgroundColor: tab === t.key ? "#ffffff29" : "#ffffff00",
                  hover: { backgroundColor: tab === t.key ? "#ffffff29" : "#ffffff12" },
                }}
              >
                <text style={{ fontSize: 14, fontWeight: 600, color: tab === t.key ? "#ffffff" : "#ffffffa6" }}>{t.label}</text>
              </div>
            ))}
          </div>
          <CaptionFill />
          <CaptionButtons light />
        </div>
        <div style={{ ...row, flexGrow: 1, minHeight: 0, alignItems: "stretch", paddingLeft: w * 0.07, paddingRight: w * 0.05, paddingBottom: 40, gap: w * 0.06 }}>
          <div style={{ ...column, justifyContent: "center", width: artSize, flexShrink: 0 }}>
            <Motion
              initial={false}
              animate={{ opacity: 1 }}
              style={{ width: artSize, height: artSize, boxShadow: { offsetX: 0, offsetY: 40, blurRadius: 90, spreadRadius: 0, color: "#0000008c" }, borderRadius: 24 }}
            >
              <Artwork id={song.albumId} tag={tag} size={artSize} radius={24} />
            </Motion>
            <div style={{ ...row, gap: 12, marginTop: 28, alignItems: "flex-start" }}>
              <div style={{ ...column, flexGrow: 1, gap: 4 }}>
                <text style={{ fontSize: 26, fontWeight: 800, color: "#ffffff", ...ellipsis }}>{song.title}</text>
                <div style={{ ...row, gap: 6, minWidth: 0 }}>
                  <div
                    role="link"
                    onClick={() => song.artistIds[0] && go({ name: "artist", id: song.artistIds[0] })}
                    style={{ cursor: "pointer", flexShrink: 1, minWidth: 0 }}
                  >
                    <text style={{ fontSize: 16, color: "#ffffffb8", ...ellipsis }}>{song.artist}</text>
                  </div>
                  <text style={{ fontSize: 16, color: "#ffffff73" }}>·</text>
                  <div role="link" onClick={() => song.albumId && go({ name: "album", id: song.albumId })} style={{ cursor: "pointer", flexShrink: 1, minWidth: 0 }}>
                    <text style={{ fontSize: 16, color: "#ffffffb8", ...ellipsis }}>{song.album}</text>
                  </div>
                </div>
                <text style={{ fontSize: 12, fontWeight: 600, color: "#ffffff80", marginTop: 4 }}>{format(song)}</text>
              </div>
              <IconButton
                icon={song.fav ? "heartFilled" : "heart"}
                label={song.fav ? "Remove from Favorites" : "Add to Favorites"}
                color={song.fav ? C.pink : "#ffffffcc"}
                size={44}
                iconSize={22}
                onClick={() => toggleFavorite(song.id)}
                style={{ hover: { backgroundColor: "#ffffff1f" } }}
              />
            </div>
            <div style={{ marginTop: 18 }}>
              <Seek height={20} color="#ffffff" track="#ffffff38" amplitude={3.4} light />
            </div>
            <div style={{ marginTop: 14 }}>
              <Transport size="full" />
            </div>
          </div>
          <div style={{ ...column, flexGrow: 1, minWidth: 0 }}>
            {tab === "lyrics" && <Lyrics size="full" />}
            {tab === "queue" && <Queue light />}
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
      <text style={{ fontSize: 12, fontWeight: 600, color: "#ffffff8c" }}>About the artist</text>
      <text style={{ fontFamily: F.display, fontWeight: 900, fontSize: 40, color: "#ffffff" }}>{song.artist}</text>
      <text style={{ fontSize: 16, lineHeight: 26, color: "#ffffffbf", lineClamp: 10 }}>
        {overview ?? (info === "loading" ? "" : "No biography for this artist on your server.")}
      </text>
      <text style={{ fontSize: 12, fontWeight: 600, color: "#ffffff8c", marginTop: 20 }}>From the album</text>
      <text style={{ fontSize: 16, color: "#ffffffbf" }}>{facts.join(" · ")}</text>
    </div>
  );
}

