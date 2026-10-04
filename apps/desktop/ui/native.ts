// The renderer, Aurelia's runtime bridge, and the state the poll keeps current.
import type { GpuixRenderer, WindowState } from "@gpuix/native";

export type AureliaRenderer = GpuixRenderer & {
  aureliaRequest(json: string): number;
  aureliaPoll(seen: number): string;
  getWindowState(): WindowState;
};

export type Session = { serverUrl: string; username: string; token: string; userId: string };

export type SongData = {
  id: string;
  title: string;
  album: string;
  albumId?: string;
  artists: string[];
  artistIds: string[];
  albumArtist?: string;
  albumArtistId?: string;
  duration: number;
  track?: number;
  disc?: number;
  year?: number;
  genres?: string[];
  plays: number;
  fav: boolean;
  added?: string;
  played?: string;
  container?: string;
  codec?: string;
  bitRate?: number;
  sampleRate?: number;
};
export type AlbumData = { id: string; title: string; artist: string; artistId?: string; imageTag?: string; added?: string };
export type ArtistData = { id: string; name: string; imageTag?: string; backdropTag?: string };
export type LibraryData = { songs: SongData[]; albums: AlbumData[]; artists: ArtistData[] };
export type PlaylistData = {
  id: string;
  name: string;
  count: number;
  duration: number;
  canDelete: boolean;
  imageTag?: string;
  description?: string;
};
export type Artwork = { palette: [string, string, string]; backdrop?: string };
export type LyricWord = { timeMs: number; endTimeMs?: number; word: string };
export type LyricLine = {
  timeMs: number;
  endTimeMs?: number;
  line: string;
  words?: LyricWord[];
  agentId?: string;
  translation?: string;
};
export type Lyrics = {
  plain: string[];
  synced: LyricLine[];
  /** Named parts of the song; the line that opens each is labelled. */
  sections?: { name: string; startTimeMs: number; endTimeMs: number; lines: LyricLine[] }[];
  /** Singers: a second `person` is right-aligned, `other` is backing vocals. */
  agents?: { id: string; agentType: string }[];
};

export type PlayerView = {
  revision: number;
  current: number | null;
  playing: boolean;
  position: number;
  duration: number;
  loading: boolean;
  error: string | null;
  shuffle: boolean;
  repeat: "off" | "all" | "one";
  volume: number;
  queue?: { entry: number; id: string }[];
};
export type SyncStatus = { stage: string; current: number; total: number; running: boolean; error: string | null };

type Poll = {
  responses: { id: number; ok: boolean; value?: unknown; error?: string }[];
  player: PlayerView;
  sync: SyncStatus | null;
};

type Pending = { resolve: (value: any) => void; reject: (error: Error) => void };

/** Survives hot reloads: the window, pending requests and the last poll. */
export type Host = {
  renderer: AureliaRenderer;
  pending: Map<number, Pending>;
  seen: number;
  timer?: ReturnType<typeof setTimeout>;
  onPoll?: (poll: Poll, window: WindowState) => void;
  onKey?: (event: import("@gpuix/react").EventPayload) => void;
  /** Bring the window forward after the first render. */
  reveal: boolean;
};

declare global {
  // eslint-disable-next-line no-var
  var __aurelia: Host | undefined;
}

export function host(): Host {
  if (!globalThis.__aurelia) throw new Error("Aurelia has not started");
  return globalThis.__aurelia;
}

/** Send a request to the runtime; it settles on a later poll. */
export function request<T = unknown>(payload: { type: string } & Record<string, unknown>): Promise<T> {
  const h = host();
  const id = h.renderer.aureliaRequest(JSON.stringify(payload));
  return new Promise<T>((resolve, reject) => h.pending.set(id, { resolve, reject }));
}

/** Fire-and-forget for playback commands; failures surface in player state. */
export function command(type: string, args: Record<string, unknown> = {}) {
  request({ type, ...args }).catch(() => {});
}

export function poll() {
  const h = host();
  let parsed: Poll;
  try {
    parsed = JSON.parse(h.renderer.aureliaPoll(h.seen));
  } catch {
    return;
  }
  h.seen = parsed.player.revision;
  for (const response of parsed.responses) {
    const pending = h.pending.get(response.id);
    if (!pending) continue;
    h.pending.delete(response.id);
    if (response.ok) pending.resolve(response.value);
    else pending.reject(new Error(response.error ?? "Request failed"));
  }
  h.onPoll?.(parsed, h.renderer.getWindowState());
}

/** Poll the runtime five times a second. */
export function schedulePolling() {
  const h = host();
  if (h.timer) clearTimeout(h.timer);
  const tick = () => {
    poll();
    h.timer = setTimeout(tick, 200);
  };
  h.timer = setTimeout(tick, 0);
}
