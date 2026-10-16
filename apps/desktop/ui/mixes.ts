// "Let it play": mixes built from the synced library, so they show with the
// rest of Home instead of waiting on the server. The date seeds every choice,
// and Home keeps the day's first build, so the row turns over once a day.
import type { Library, Song } from "./library";

export type Mix = { seed: string; title: string; subtitle: string; covers: string[]; songs: string[] };

const DAY = 86_400_000;
/** The most songs a mix holds. */
const SIZE = 50;
/** A mix with fewer songs than this isn't worth a card. */
const SMALLEST = 10;
/** Names that group unrelated songs, so they make poor seeds. */
const NOT_ARTISTS = /^(various artists|unknown artist)$/i;

/** A repeatable sequence in [0, 1): mulberry32, seeded by an FNV-1a hash. */
function random(seed: string) {
  let state = [...seed].reduce((hash, char) => Math.imul(hash ^ char.charCodeAt(0), 16777619), 2166136261);
  return () => {
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** A song's artists, keyed as `Library.artist` keys them. */
function artistsOf(song: Song) {
  const names = song.artists.length ? song.artists : [song.albumArtist ?? "Unknown artist"];
  return names.map((name, i) => song.artistIds[i] ?? `name:${name}`);
}

function cosine(a: Map<string, number>, b: Map<string, number>) {
  let dot = 0;
  let sizeA = 0;
  let sizeB = 0;
  for (const [key, value] of a) {
    sizeA += value * value;
    dot += value * (b.get(key) ?? 0);
  }
  for (const value of b.values()) sizeB += value * value;
  return sizeA && sizeB ? dot / Math.sqrt(sizeA * sizeB) : 0;
}

/**
 * Today's mixes, up to three: the artist you've played most lately and the
 * artists around them, a genre you've leaned into, and an artist you loved
 * but haven't played in two months. Jellyfin keeps only each song's play
 * count and last play, so "lately" fades each song's plays by its last one.
 */
export function dailyMixes(library: Library, now = Date.now()): Mix[] {
  const date = new Date(now);
  const day = `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
  const artistsBy = new Map(library.songs.map((song) => [song, artistsOf(song)]));
  const lead = (song: Song) => artistsBy.get(song)![0];

  /** Plays fading by half every three weeks since the song's last play. */
  const lately = (song: Song) => (song.playedAt ? song.plays * 0.5 ** ((now - song.playedAt) / (21 * DAY)) : 0);
  /** How much a song belongs in a mix: favorites and well-played songs more,
   * anything played in the last few days less. */
  const liking = (song: Song) =>
    (1 + 1.5 * Number(song.fav) + 0.35 * Math.log1p(song.plays)) * (song.playedAt && now - song.playedAt < 3 * DAY ? 0.3 : 1);

  const artists = library.artists.filter((artist) => artist.songs.length && !NOT_ARTISTS.test(artist.name));
  const recent = new Map<string, number>();
  const last = new Map<string, number>();
  const favorites = new Map<string, number>();
  const genres = new Map<string, Map<string, number>>();
  for (const artist of artists) {
    const counts = new Map<string, number>();
    let weight = 0;
    let latest = 0;
    let favs = 0;
    for (const song of artist.songs) {
      weight += lately(song);
      latest = Math.max(latest, song.playedAt);
      favs += Number(song.fav);
      for (const genre of song.genres ?? []) counts.set(genre, (counts.get(genre) ?? 0) + 1);
    }
    recent.set(artist.id, weight);
    last.set(artist.id, latest);
    favorites.set(artist.id, favs);
    genres.set(artist.id, counts);
  }

  // Artists played in the same sitting: songs whose last plays fall within
  // 45 minutes of each other. Many songs carry no genre, so this finds
  // neighbors that genres miss.
  const pair = (a: string, b: string) => (a < b ? `${a}\n${b}` : `${b}\n${a}`);
  const together = new Map<string, number>();
  const timeline = library.songs.filter((song) => song.playedAt).sort((a, b) => a.playedAt - b.playedAt);
  for (let i = 0; i < timeline.length; i++) {
    for (let j = i + 1; j < Math.min(timeline.length, i + 50); j++) {
      if (timeline[j].playedAt - timeline[i].playedAt > 45 * 60_000) break;
      const [a, b] = [lead(timeline[i]), lead(timeline[j])];
      if (a !== b) together.set(pair(a, b), (together.get(pair(a, b)) ?? 0) + 1);
    }
  }

  /** The artists most like `seed`: shared genres, features, and sittings. */
  function similar(seed: string, limit: number) {
    const features = new Map<string, number>();
    for (const song of library.artist.get(seed)!.songs)
      for (const id of artistsBy.get(song)!) if (id !== seed) features.set(id, (features.get(id) ?? 0) + 1);
    const scored: [string, number][] = [];
    for (const { id } of artists) {
      if (id === seed) continue;
      const score =
        cosine(genres.get(seed)!, genres.get(id)!) +
        0.5 * Math.min(1, (features.get(id) ?? 0) / 3) +
        0.6 * Math.min(1, (together.get(pair(seed, id)) ?? 0) / 6);
      if (score > 0.25) scored.push([id, score]);
    }
    return scored.sort((a, b) => b[1] - a[1]).slice(0, limit);
  }

  /** One of the strongest few, by weight. */
  function pick<T>(scored: [T, number][], salt: string, top = 4): T | undefined {
    const options = scored.filter(([, weight]) => weight > 0).sort((a, b) => b[1] - a[1]).slice(0, top);
    let roll = random(`${day}:${salt}`)() * options.reduce((sum, [, weight]) => sum + weight, 0);
    for (const [value, weight] of options) if ((roll -= weight) <= 0) return value;
    return options[0]?.[0];
  }

  /** Draw songs by weight, at most `cap` from each lead artist, then keep
   * one artist from playing twice in a row. */
  function draw(weighted: Map<Song, number>, salt: string, cap: (artist: string) => number) {
    const roll = random(`${day}:${salt}:songs`);
    const order = [...weighted]
      .filter(([, weight]) => weight > 0)
      .map(([song, weight]) => [song, roll() ** (1 / weight)] as const)
      .sort((a, b) => b[1] - a[1]);
    const counts = new Map<string, number>();
    const chosen: Song[] = [];
    for (const [song] of order) {
      const count = counts.get(lead(song)) ?? 0;
      if (count >= cap(lead(song))) continue;
      counts.set(lead(song), count + 1);
      chosen.push(song);
      if (chosen.length === SIZE) break;
    }
    const spread: Song[] = [];
    while (chosen.length) {
      const previous = spread.length ? lead(spread[spread.length - 1]) : undefined;
      spread.push(...chosen.splice(Math.max(0, chosen.findIndex((song) => lead(song) !== previous)), 1));
    }
    return spread;
  }

  /** A mix around `artist`, weighing their songs by `own`, and the artists
   * most like them. */
  function around(
    artist: string,
    own: (song: Song) => number,
    near: [string, number][],
    nearWeight: number,
    salt: string,
    caps: [number, number],
  ) {
    const weighted = new Map<Song, number>();
    for (const song of library.artist.get(artist)!.songs) weighted.set(song, own(song));
    for (const [id, score] of near)
      for (const song of library.artist.get(id)!.songs) if (!weighted.has(song)) weighted.set(song, nearWeight * score * liking(song));
    return draw(weighted, salt, (id) => (id === artist ? caps[0] : caps[1]));
  }

  function mix(seed: string, title: string, songs: Song[], artist?: string): Mix | undefined {
    if (songs.length < SMALLEST) return;
    const by = (song: Song) => !!artist && artistsBy.get(song)!.includes(artist);
    // Open with the artist the mix is named for, and lead with their covers.
    const first = songs.findIndex(by);
    if (first > 0) songs.unshift(...songs.splice(first, 1));
    const counts = new Map<string, number>();
    for (const song of songs) for (const id of artistsBy.get(song)!) counts.set(id, (counts.get(id) ?? 0) + 1);
    const names = [...counts]
      .sort((a, b) => Number(b[0] === artist) - Number(a[0] === artist) || b[1] - a[1])
      .map(([id]) => library.artist.get(id)?.name ?? "")
      .filter((name) => name && !NOT_ARTISTS.test(name));
    const covers: string[] = [];
    for (const song of [...songs.filter(by), ...songs.filter((song) => !by(song))])
      if (song.albumId && !covers.includes(song.albumId) && library.album.get(song.albumId)?.imageTag) covers.push(song.albumId);
    return { seed, title, subtitle: names.slice(0, 3).join(", "), covers: covers.slice(0, 4), songs: songs.map((song) => song.id) };
  }

  const mixes: (Mix | undefined)[] = [];
  const nameOf = (id: string) => library.artist.get(id)!.name;

  // Lately. A library with no plays yet starts from its favorites.
  const seed =
    pick(artists.map(({ id }) => [id, recent.get(id)!]), "lately") ?? pick(artists.map(({ id }) => [id, favorites.get(id)!]), "lately");
  if (seed) mixes.push(mix(`lately:${seed}`, `${nameOf(seed)} mix`, around(seed, liking, similar(seed, 8), 0.8, "lately", [18, 6]), seed));

  // A genre other than the first mix's two biggest.
  const taken = new Set(seed ? [...genres.get(seed)!].sort((a, b) => b[1] - a[1]).slice(0, 2).map(([genre]) => genre) : []);
  const leaning = new Map<string, number>();
  for (const song of library.songs) {
    const weight = lately(song);
    if (weight) for (const genre of song.genres ?? []) leaning.set(genre, (leaning.get(genre) ?? 0) + weight);
  }
  const genre =
    pick([...leaning].filter(([genre]) => !taken.has(genre)), "genre") ??
    pick(library.genres.filter(({ name }) => !taken.has(name)).map(({ name, count }) => [name, count]), "genre");
  if (genre) {
    const weighted = new Map(library.songs.filter((song) => song.genres?.includes(genre)).map((song) => [song, liking(song)]));
    mixes.push(mix(`genre:${genre}`, `${genre} mix`, draw(weighted, "genre", () => 5)));
  }

  // Back to an artist: one of your eight most played that's been quiet for two months.
  const quiet = artists
    .filter(({ id, plays }) => id !== seed && plays >= 10 && now - last.get(id)! > 60 * DAY)
    .sort((a, b) => b.plays - a.plays)
    .slice(0, 8);
  const back = pick(quiet.map(({ id }) => [id, 1]), "back", 8);
  if (back) {
    // Lean on the songs you played most back then.
    const own = (song: Song) => (1 + 0.4 * Math.log1p(song.plays)) * liking(song);
    mixes.push(mix(`back:${back}`, `Back to ${nameOf(back)}`, around(back, own, similar(back, 6), 0.5, "back", [24, 5]), back));
  }

  return mixes.filter((found): found is Mix => !!found);
}
