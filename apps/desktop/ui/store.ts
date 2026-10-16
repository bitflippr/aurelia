// Application state and the actions that change it. Components read slices
// with `useStore`, so a poll that only moves the clock re-renders the clock.
import { useSyncExternalStore } from "react";
import type { WindowState } from "@gpuix/native";
import { Library, type Song } from "./library";
import type { Mix } from "./mixes";
import {
  command,
  host,
  request,
  type Artwork,
  type LibraryData,
  type Lyrics,
  type PlayerView,
  type PlaylistData,
  type Session,
  type SongData,
  type SyncStatus,
} from "./native";
import { applyTheme, type ThemeName } from "./theme";

export type Route =
  | { name: "home" }
  | { name: "albums" }
  | { name: "album"; id: string }
  | { name: "artists" }
  | { name: "artist"; id: string }
  | { name: "songs" }
  | { name: "favorites" }
  | { name: "playlists" }
  | { name: "playlist"; id: string }
  | { name: "search"; q: string }
  | { name: "settings" };

export type MenuItem =
  | { label: string; icon?: import("./icons").IconName; hint?: string; danger?: boolean; checked?: boolean; run: () => void }
  | "-";
export type Menu = { x: number; y: number; items: MenuItem[]; width?: number };

/** Today's "Let it play", kept so a sync or a relaunch later in the day
 * doesn't reshuffle Home. */
export type SavedMixes = { day: string; user: string; list: Mix[] };

export type AlbumSort = "added" | "title" | "artist" | "year" | "plays";
/** Follow the system's light or dark appearance, or keep one. */
export type ThemePref = "system" | ThemeName;
export type SongSort = { key: "title" | "album" | "artist" | "added" | "plays" | "duration"; dir: 1 | -1 };

export type State = {
  stage: "boot" | "login" | "sync" | "ready";
  session: Session | null;
  library: Library | null;
  playlists: PlaylistData[];
  playlistSongs: Record<string, Song[] | "loading">;
  player: Omit<PlayerView, "queue" | "position">;
  /** Whole seconds played, for clocks. */
  second: number;
  queue: { entry: number; id: string }[];
  sync: SyncStatus | null;
  route: Route;
  back: { route: Route; scroll: number }[];
  forward: { route: Route; scroll: number }[];
  /** Scroll offset to restore when the page mounts. */
  restoreScroll: number;
  panel: null | "queue" | "lyrics";
  full: boolean;
  fullTab: "lyrics" | "queue" | "about";
  collapsed: boolean;
  menu: Menu | null;
  toast: { id: number; text: string } | null;
  window: WindowState;
  artwork: Record<string, Artwork | "loading" | "none">;
  lyrics: Record<string, Lyrics | "loading" | "none">;
  artistInfo: Record<string, { overview?: string; related: string[] } | "loading">;
  mixes: SavedMixes | null;
  albumSort: AlbumSort;
  albumGenre: string | null;
  albumLayout: "grid" | "list";
  songSort: SongSort;
  query: string;
  /** The results under the search box, until something else is opened. */
  searchOpen: boolean;
  login: { busy: boolean; error: string | null };
  /** Favorites live on the songs; this moves when one changes. */
  favVersion: number;
  dialog: { kind: "newPlaylist"; ids: string[]; name: string } | null;
  themePref: ThemePref;
  /** The theme showing: the preference, or the system's appearance. */
  theme: ThemeName;
};

const initial: State = {
  stage: "boot",
  session: null,
  library: null,
  playlists: [],
  playlistSongs: {},
  player: {
    revision: 0,
    current: null,
    playing: false,
    duration: 0,
    loading: false,
    error: null,
    shuffle: false,
    repeat: "off",
    volume: 0.72,
  },
  second: 0,
  queue: [],
  sync: null,
  route: { name: "home" },
  back: [],
  forward: [],
  restoreScroll: 0,
  panel: null,
  full: false,
  fullTab: "lyrics",
  collapsed: false,
  menu: null,
  toast: null,
  window: { closed: false, maximized: false, fullscreen: false, active: true, dark: true, width: 1280, height: 820, scale: 1 },
  artwork: {},
  lyrics: {},
  artistInfo: {},
  mixes: null,
  albumSort: "added",
  albumGenre: null,
  albumLayout: "grid",
  songSort: { key: "added", dir: 1 },
  query: "",
  searchOpen: false,
  login: { busy: false, error: null },
  favVersion: 0,
  dialog: null,
  themePref: "system",
  theme: "night",
};

type Store = { state: State; listeners: Set<() => void> };
declare global {
  // eslint-disable-next-line no-var
  var __aureliaStore: Store | undefined;
}
// Kept across hot reloads, so saving a component keeps the page and queue.
const store: Store = (globalThis.__aureliaStore ??= { state: initial, listeners: new Set() });
// A hot reload starts the theme module over; show the theme the state kept.
applyTheme(store.state.theme);

export function get(): State {
  return store.state;
}

export function set(patch: Partial<State> | ((state: State) => Partial<State>)) {
  const next = typeof patch === "function" ? patch(store.state) : patch;
  store.state = { ...store.state, ...next };
  for (const listener of store.listeners) listener();
}

function subscribe(listener: () => void) {
  store.listeners.add(listener);
  return () => store.listeners.delete(listener);
}

/** Re-render when the selected value changes. Selectors return stored values,
 * never new objects. */
export function useStore<T>(select: (state: State) => T): T {
  return useSyncExternalStore(subscribe, () => select(store.state));
}

// ---- Startup, session and sync -------------------------------------------

export async function boot() {
  loadThemePref();
  const { session, synced } = await request<{ session: Session | null; synced: boolean }>({ type: "init" });
  if (!session) return set({ stage: "login", session: null });
  set({ session });
  if (synced) {
    await loadLibrary();
    set({ stage: "ready" });
    runSync();
  } else {
    set({ stage: "sync" });
    runSync();
  }
}

export async function signIn(server: string, username: string, password: string) {
  set({ login: { busy: true, error: null } });
  try {
    const { session, synced } = await request<{ session: Session; synced: boolean }>({
      type: "signIn",
      server,
      username,
      password,
    });
    set({ session, login: { busy: false, error: null } });
    if (synced) {
      await loadLibrary();
      set({ stage: "ready" });
      runSync();
    } else {
      set({ stage: "sync" });
      runSync();
    }
  } catch (error) {
    set({ login: { busy: false, error: (error as Error).message } });
  }
}

export async function signOut() {
  await request({ type: "signOut" });
  set({ ...initial, stage: "login", window: get().window });
}

export async function runSync() {
  try {
    await request({ type: "sync" });
  } catch (error) {
    // Progress and the error arrive through the poll.
    if (get().stage === "sync") set({ sync: { stage: "", current: 0, total: 0, running: false, error: (error as Error).message } });
    return;
  }
  await loadLibrary();
  if (get().stage === "sync") set({ stage: "ready" });
}

export async function loadLibrary() {
  const data = await request<LibraryData>({ type: "library" });
  const library = new Library(data);
  if (get().library) set({ library });
  else {
    // Home opens on today's saved mixes rather than swapping them in after.
    set({ library, mixes: await loadMixes() });
    command("restoreQueue");
  }
  loadPlaylists();
}

const MIXES_SETTING = "homeMixes";

async function loadMixes(): Promise<SavedMixes | null> {
  try {
    const saved: SavedMixes | null = JSON.parse((await request<string | null>({ type: "getSetting", key: MIXES_SETTING })) ?? "null");
    return saved?.user === get().session?.userId ? saved : null;
  } catch {
    return null;
  }
}

/** Keep `list` as the day's mixes, across launches. */
export function keepMixes(day: string, list: Mix[]) {
  const mixes = { day, user: get().session?.userId ?? "", list };
  set({ mixes });
  request({ type: "setSetting", key: MIXES_SETTING, value: JSON.stringify(mixes) }).catch(() => {});
}

export async function loadPlaylists() {
  try {
    set({ playlists: await request<PlaylistData[]>({ type: "playlists" }) });
  } catch {}
}

export async function loadPlaylistSongs(id: string, force = false) {
  const current = get().playlistSongs[id];
  if (current && !force) return;
  set((s) => ({ playlistSongs: { ...s.playlistSongs, [id]: "loading" } }));
  try {
    const data = await request<SongData[]>({ type: "playlistSongs", id });
    const library = get().library;
    const songs = data.map(
      (song) =>
        library?.song.get(song.id) ?? {
          ...song,
          artist: song.artists.join(", ") || (song.albumArtist ?? ""),
          addedAt: 0,
          playedAt: 0,
        },
    );
    set((s) => ({ playlistSongs: { ...s.playlistSongs, [id]: songs } }));
  } catch {
    set((s) => ({ playlistSongs: { ...s.playlistSongs, [id]: [] } }));
  }
}

// ---- Poll -----------------------------------------------------------------

export function onPoll(
  poll: { player: PlayerView; sync: SyncStatus | null },
  window: WindowState,
) {
  const s = get();
  const { queue, position, ...player } = poll.player;
  const patch: Partial<State> = {};
  const changed = (Object.keys(player) as (keyof typeof player)[]).some((key) => player[key] !== s.player[key]);
  if (changed) patch.player = player;
  const second = Math.floor(position);
  if (second !== s.second) patch.second = second;
  if (queue) patch.queue = queue;
  const sync = poll.sync;
  if (
    sync?.stage !== s.sync?.stage ||
    sync?.current !== s.sync?.current ||
    sync?.running !== s.sync?.running ||
    sync?.error !== s.sync?.error
  )
    patch.sync = sync;
  if (
    window.maximized !== s.window.maximized ||
    window.active !== s.window.active ||
    window.width !== s.window.width ||
    window.height !== s.window.height ||
    window.scale !== s.window.scale ||
    window.dark !== s.window.dark
  )
    patch.window = window;
  if (Object.keys(patch).length) set(patch);
  if (patch.window) resolveTheme();
}

// ---- Theme --------------------------------------------------------------------

/** Show the preferred theme, or the system's when following it. */
export function resolveTheme() {
  const s = get();
  const name: ThemeName = s.themePref === "system" ? (s.window.dark ? "night" : "shore") : s.themePref;
  if (name === s.theme) return;
  applyTheme(name);
  set({ theme: name });
}

export function setThemePref(themePref: ThemePref) {
  set({ themePref });
  resolveTheme();
  request({ type: "setSetting", key: "theme", value: themePref === "system" ? null : themePref }).catch(() => {});
}

async function loadThemePref() {
  try {
    const value = await request<string | null>({ type: "getSetting", key: "theme" });
    if (value === "shore" || value === "night") set({ themePref: value });
  } catch {}
  resolveTheme();
}

// ---- Navigation -------------------------------------------------------------

let scrollReader: () => number = () => 0;
/** The page registers how to read its scroll offset, for history. */
export function setScrollReader(read: () => number) {
  scrollReader = read;
}

export function go(route: Route) {
  const s = get();
  if (JSON.stringify(route) === JSON.stringify(s.route)) return;
  set({
    route,
    back: [...s.back, { route: s.route, scroll: scrollReader() }].slice(-50),
    forward: [],
    restoreScroll: 0,
    menu: null,
    full: false,
    searchOpen: false,
  });
}

export function goBack() {
  const s = get();
  const previous = s.back[s.back.length - 1];
  if (!previous) return;
  set({
    route: previous.route,
    back: s.back.slice(0, -1),
    forward: [...s.forward, { route: s.route, scroll: scrollReader() }],
    restoreScroll: previous.scroll,
    menu: null,
  });
}

export function goForward() {
  const s = get();
  const next = s.forward[s.forward.length - 1];
  if (!next) return;
  set({
    route: next.route,
    forward: s.forward.slice(0, -1),
    back: [...s.back, { route: s.route, scroll: scrollReader() }],
    restoreScroll: next.scroll,
    menu: null,
  });
}

// ---- Playback -----------------------------------------------------------------

export function play(ids: string[], start = 0, shuffle = false) {
  if (!ids.length) return;
  command("play", { ids, start, shuffle });
}

export function playSong(list: string[], id: string) {
  play(list, Math.max(0, list.indexOf(id)));
}

export function playNext(ids: string[]) {
  command("playNext", { ids });
  toast(ids.length === 1 ? "Playing next" : `${ids.length} songs play next`);
}

export function enqueue(ids: string[]) {
  command("enqueue", { ids });
  toast(ids.length === 1 ? "Added to queue" : `${ids.length} songs added to queue`);
}

export async function startMix(seed: string, label: string) {
  try {
    const songs = await request<SongData[]>({ type: "mix", id: seed });
    if (!songs.length) return toast("No mix for this one yet");
    play(songs.map((song) => song.id));
    toast(`Mix started from “${label}”`);
  } catch (error) {
    toast((error as Error).message);
  }
}

export async function toggleFavorite(id: string) {
  const library = get().library;
  const song = library?.song.get(id);
  if (!song) return;
  const value = !song.fav;
  song.fav = value;
  set((s) => ({ favVersion: s.favVersion + 1 }));
  try {
    await request({ type: "favorite", id, value });
    toast(value ? "Added to Favorites" : "Removed from Favorites");
  } catch (error) {
    song.fav = !value;
    set((s) => ({ favVersion: s.favVersion + 1 }));
    toast((error as Error).message);
  }
}

export async function addToPlaylist(playlistId: string, ids: string[]) {
  try {
    await request({ type: "addToPlaylist", id: playlistId, ids });
    const playlist = get().playlists.find((p) => p.id === playlistId);
    toast(`Added to ${playlist?.name ?? "playlist"}`);
    loadPlaylists();
    if (get().playlistSongs[playlistId]) loadPlaylistSongs(playlistId, true);
  } catch (error) {
    toast((error as Error).message);
  }
}

/** Ask for a name, then create a playlist holding `ids`. */
export function newPlaylist(ids: string[], name = "") {
  set({ dialog: { kind: "newPlaylist", ids, name }, menu: null });
}

export async function createPlaylist(name: string, ids: string[] = []) {
  try {
    const playlist = await request<PlaylistData>({ type: "createPlaylist", name, ids });
    await loadPlaylists();
    toast(`Created ${playlist.name}`);
    go({ name: "playlist", id: playlist.id });
  } catch (error) {
    toast((error as Error).message);
  }
}

// ---- Artwork, lyrics, artists -----------------------------------------------

export function ensureArtwork(id: string | undefined, tag: string | undefined) {
  if (!id || !tag) return;
  const key = id;
  if (get().artwork[key]) return;
  set((s) => ({ artwork: { ...s.artwork, [key]: "loading" } }));
  request<Artwork>({ type: "artwork", id, tag })
    .then((art) => set((s) => ({ artwork: { ...s.artwork, [key]: art } })))
    .catch(() => set((s) => ({ artwork: { ...s.artwork, [key]: "none" } })));
}

export function ensureLyrics(id: string | undefined) {
  if (!id || get().lyrics[id]) return;
  set((s) => ({ lyrics: { ...s.lyrics, [id]: "loading" } }));
  request<Lyrics>({ type: "lyrics", id })
    .then((lyrics) =>
      set((s) => ({ lyrics: { ...s.lyrics, [id]: lyrics.synced.length || lyrics.plain.length ? lyrics : "none" } })),
    )
    .catch(() => set((s) => ({ lyrics: { ...s.lyrics, [id]: "none" } })));
}

export function ensureArtistInfo(id: string) {
  if (id.startsWith("name:") || get().artistInfo[id]) return;
  set((s) => ({ artistInfo: { ...s.artistInfo, [id]: "loading" } }));
  request<{ overview?: string; related: string[] }>({ type: "artist", id })
    .then((info) => set((s) => ({ artistInfo: { ...s.artistInfo, [id]: info } })))
    .catch(() => set((s) => ({ artistInfo: { ...s.artistInfo, [id]: { related: [] } } })));
}

// ---- Overlays ---------------------------------------------------------------

export function openMenu(menu: Menu) {
  set({ menu });
}

export function closeMenu() {
  if (get().menu) set({ menu: null });
}

let toastTimer: ReturnType<typeof setTimeout> | undefined;
let toastId = 0;
export function toast(text: string) {
  set({ toast: { id: ++toastId, text } });
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => set({ toast: null }), 2200);
}

export function setPanel(panel: State["panel"]) {
  set({ panel });
}

export function togglePanel(panel: "queue" | "lyrics") {
  setPanel(get().panel === panel ? null : panel);
}

export function setFull(full: boolean, tab?: State["fullTab"]) {
  set((s) => ({ full, fullTab: tab ?? s.fullTab, menu: null }));
}
