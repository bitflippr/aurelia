// Art kept on disk. Jellyfin scales a cover for every request, which can take
// it a second or two, so each cover is fetched once at each of a few sizes and
// kept, rather than again on every launch and at every pixel size. The colors
// taken from art are kept too, so tinted glass shows its color at once.
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { readdir, rename, stat, unlink } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { useCallback, useEffect, useSyncExternalStore } from "react";
import type { Artwork, Session } from "./native";

/** The sizes covers are fetched at; each draws scaled down from the next one up. */
const SIZES = [64, 128, 256, 512, 1024];
/** Downloads at once. The server scales each cover itself, so more mostly wait there. */
const PARALLEL = 6;
/** A cover larger than this is something else. */
const MAX_BYTES = 16 * 1024 * 1024;
/** Past this, the covers fetched longest ago go when Aurelia starts. */
const BUDGET = 512 * 1024 * 1024;

function cacheRoot() {
  if (process.platform === "win32") return process.env.LOCALAPPDATA || join(homedir(), "AppData", "Local");
  if (process.platform === "darwin") return join(homedir(), "Library", "Caches");
  return process.env.XDG_CACHE_HOME || join(homedir(), ".cache");
}
const ROOT = join(cacheRoot(), "Aurelia");
const COVERS = join(ROOT, "covers");
const ARTWORK = join(ROOT, "artwork.json");
mkdirSync(COVERS, { recursive: true });

const safe = (value: string) => value.replace(/[^A-Za-z0-9]/g, "_");

// ---- Covers -----------------------------------------------------------------

type Job = { url: string; token: string };

/** Whether a cover's file is on disk, checked once per file. */
const ready = new Map<string, boolean>();
/** How many mounted covers want each file; a file nobody wants any more isn't fetched. */
const wanted = new Map<string, number>();
const jobs = new Map<string, Job>();
/** Waiting files, in one batch per burst of requests: the newest batch goes
 * first, as it's the page on screen, and in order within it, top rows first. */
const batches: string[][] = [];
let open: string[] | null = null;
let running = 0;
const listeners = new Map<string, Set<() => void>>();

function isReady(path: string) {
  let value = ready.get(path);
  if (value === undefined) ready.set(path, (value = existsSync(path)));
  return value;
}

function listen(path: string, notify: () => void) {
  let set = listeners.get(path);
  if (!set) listeners.set(path, (set = new Set()));
  set.add(notify);
  return () => {
    set.delete(notify);
    if (!set.size) listeners.delete(path);
  };
}

function enqueue(path: string, job: Job) {
  if (jobs.has(path)) return;
  jobs.set(path, job);
  if (!open) {
    batches.push((open = []));
    setTimeout(() => (open = null));
  }
  open.push(path);
  pump();
}

function next() {
  while (batches.length) {
    const batch = batches[batches.length - 1];
    const path = batch.shift();
    if (!batch.length) {
      batches.pop();
      if (batch === open) open = null;
    }
    if (path === undefined) continue;
    if (wanted.get(path)) return path;
    jobs.delete(path);
  }
}

function pump() {
  while (running < PARALLEL) {
    const path = next();
    if (path === undefined) return;
    running++;
    download(path, jobs.get(path)!)
      .then(
        () => ready.set(path, true),
        () => {},
      )
      .finally(() => {
        running--;
        jobs.delete(path);
        listeners.get(path)?.forEach((notify) => notify());
        pump();
      });
  }
}

async function download(path: string, { url, token }: Job) {
  const response = await fetch(url, {
    headers: { Authorization: `MediaBrowser Token="${token}"` },
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new Error(`The server answered ${response.status}`);
  if (Number(response.headers.get("content-length")) > MAX_BYTES) throw new Error("Not a cover");
  const bytes = await response.arrayBuffer();
  if (bytes.byteLength > MAX_BYTES) throw new Error("Not a cover");
  // Written aside and renamed, so a cover is never read half-written.
  await Bun.write(`${path}.part`, bytes);
  await rename(`${path}.part`, path);
}

/**
 * The file for a cover at the size that fills `pixels`, once it's on disk.
 * A missing one is fetched while the cover is mounted; one that fails to
 * fetch tries again the next time it mounts.
 */
export function useCover(session: Session | null, id: string | undefined, tag: string | undefined, pixels: number) {
  const size = SIZES.find((s) => s >= pixels) ?? SIZES[SIZES.length - 1];
  const path = session && id && tag ? join(COVERS, `${safe(id)}-${safe(tag)}-${size}`) : undefined;
  const subscribe = useCallback((notify: () => void) => (path ? listen(path, notify) : () => {}), [path]);
  const shown = useSyncExternalStore(subscribe, () => !!path && isReady(path));
  useEffect(() => {
    if (!path || !session || !id || !tag || isReady(path)) return;
    wanted.set(path, (wanted.get(path) ?? 0) + 1);
    // `animated=false` asks Aurelia's animated artwork plugin for the still
    // cover, which the server scales; its GIFs come at full size.
    const query = new URLSearchParams({ fillWidth: String(size), fillHeight: String(size), quality: "90", tag, animated: "false" });
    const base = session.serverUrl.replace(/\/$/, "");
    enqueue(path, { url: `${base}/Items/${encodeURIComponent(id)}/Images/Primary?${query}`, token: session.token });
    return () => {
      const count = (wanted.get(path) ?? 1) - 1;
      if (count > 0) wanted.set(path, count);
      else wanted.delete(path);
    };
  }, [path, session, id, tag, size]);
  return shown ? path : undefined;
}

/** Keep the cache under budget, oldest fetches out first, and clear any
 * download a quit cut short. */
async function prune() {
  const files = await Promise.all(
    (await readdir(COVERS).catch(() => [])).map(async (name) => {
      const path = join(COVERS, name);
      const info = await stat(path).catch(() => null);
      return info ? { path, size: info.size, time: info.mtimeMs, part: name.endsWith(".part") } : null;
    }),
  );
  let total = 0;
  const kept = [];
  for (const file of files) {
    if (!file) continue;
    if (file.part) await unlink(file.path).catch(() => {});
    else {
      total += file.size;
      kept.push(file);
    }
  }
  if (total <= BUDGET) return;
  kept.sort((a, b) => a.time - b.time);
  for (const file of kept) {
    if (total <= BUDGET * 0.75) break;
    if (wanted.has(file.path) || listeners.has(file.path)) continue;
    await unlink(file.path).catch(() => {});
    ready.delete(file.path);
    total -= file.size;
  }
}
setTimeout(() => void prune(), 10_000);

// ---- Colors -----------------------------------------------------------------

type SavedArtwork = Record<string, Artwork & { tag: string }>;

let savedArtwork: SavedArtwork = {};
try {
  savedArtwork = JSON.parse(readFileSync(ARTWORK, "utf8"));
} catch {}
let saving: ReturnType<typeof setTimeout> | undefined;

/** Colors taken from art in earlier sessions, by item, with the art's tag. */
export function loadSavedArtwork() {
  return savedArtwork;
}

export function saveArtwork(id: string, tag: string, artwork: Artwork) {
  savedArtwork[id] = { ...artwork, tag };
  clearTimeout(saving);
  saving = setTimeout(() => void Bun.write(ARTWORK, JSON.stringify(savedArtwork)).catch(() => {}), 2000);
}
