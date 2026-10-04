// The library as pages use it: albums and artists grouped from songs, with
// the image tags the server cache knows.
import type { AlbumData, ArtistData, LibraryData, Session, SongData } from "./native";

export type Song = SongData & {
  /** Track artists, joined for display. */
  artist: string;
  addedAt: number;
  playedAt: number;
};

export type Album = {
  id: string;
  title: string;
  artist: string;
  artistId?: string;
  imageTag?: string;
  addedAt: number;
  year?: number;
  genres: string[];
  songs: Song[];
  duration: number;
  plays: number;
};

export type Artist = {
  id: string;
  name: string;
  imageTag?: string;
  backdropTag?: string;
  albums: Album[];
  songs: Song[];
  plays: number;
  genres: Set<string>;
};

const time = (value?: string) => (value ? Date.parse(value) || 0 : 0);

export class Library {
  songs: Song[];
  song = new Map<string, Song>();
  albums: Album[] = [];
  album = new Map<string, Album>();
  artists: Artist[] = [];
  artist = new Map<string, Artist>();
  genres: { name: string; count: number }[] = [];

  constructor(data: LibraryData) {
    this.songs = data.songs.map((song) => ({
      ...song,
      artist: song.artists.length ? song.artists.join(", ") : (song.albumArtist ?? "Unknown artist"),
      addedAt: time(song.added),
      playedAt: time(song.played),
    }));
    for (const song of this.songs) this.song.set(song.id, song);

    const albumInfo = new Map<string, AlbumData>(data.albums.map((album) => [album.id, album]));
    const artistInfo = new Map<string, ArtistData>(data.artists.map((artist) => [artist.id, artist]));

    for (const song of this.songs) {
      const id = song.albumId ?? `unknown:${song.albumArtist ?? song.artist}:${song.album}`;
      let album = this.album.get(id);
      if (!album) {
        const info = song.albumId ? albumInfo.get(song.albumId) : undefined;
        album = {
          id,
          title: info?.title ?? (song.album || "Unknown album"),
          artist: info?.artist || song.albumArtist || song.artist,
          artistId: info?.artistId ?? song.albumArtistId ?? song.artistIds[0],
          imageTag: info?.imageTag,
          addedAt: time(info?.added),
          genres: [],
          songs: [],
          duration: 0,
          plays: 0,
        };
        this.album.set(id, album);
        this.albums.push(album);
      }
      album.songs.push(song);
      album.duration += song.duration;
      album.plays += song.plays;
      if (song.year && (!album.year || song.year > album.year)) album.year = song.year;
      if (!album.addedAt || (song.addedAt && song.addedAt < album.addedAt)) album.addedAt = song.addedAt || album.addedAt;
      for (const genre of song.genres ?? []) if (!album.genres.includes(genre)) album.genres.push(genre);
    }
    for (const album of this.albums) {
      album.songs.sort((a, b) => (a.disc ?? 1) - (b.disc ?? 1) || (a.track ?? 0) - (b.track ?? 0) || a.title.localeCompare(b.title));
    }

    const artistFor = (id: string | undefined, name: string) => {
      const key = id ?? `name:${name}`;
      let artist = this.artist.get(key);
      if (!artist) {
        const info = id ? artistInfo.get(id) : undefined;
        artist = {
          id: key,
          name: info?.name ?? name,
          imageTag: info?.imageTag,
          backdropTag: info?.backdropTag,
          albums: [],
          songs: [],
          plays: 0,
          genres: new Set(),
        };
        this.artist.set(key, artist);
        this.artists.push(artist);
      }
      return artist;
    };
    for (const album of this.albums) {
      const artist = artistFor(album.artistId, album.artist);
      artist.albums.push(album);
      album.genres.forEach((genre) => artist.genres.add(genre));
    }
    for (const song of this.songs) {
      const names = song.artists.length ? song.artists : [song.albumArtist ?? "Unknown artist"];
      names.forEach((name, i) => {
        const artist = artistFor(song.artistIds[i], name);
        artist.songs.push(song);
        artist.plays += song.plays;
      });
    }
    for (const artist of this.artists) artist.albums.sort((a, b) => (b.year ?? 0) - (a.year ?? 0));

    const genres = new Map<string, number>();
    for (const song of this.songs) for (const genre of song.genres ?? []) genres.set(genre, (genres.get(genre) ?? 0) + 1);
    this.genres = [...genres].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count);
  }

  /** Artists with albums of their own, for browsing. */
  albumArtists(): Artist[] {
    return this.artists.filter((artist) => artist.albums.length > 0);
  }

  favorites(): Song[] {
    return this.songs.filter((song) => song.fav);
  }

  search(query: string, limit = 50) {
    const q = fold(query.trim());
    if (!q) return { songs: [], albums: [], artists: [] };
    const score = (value: string) => {
      const v = fold(value);
      if (v.startsWith(q)) return 3;
      if (v.split(/\s+/).some((word) => word.startsWith(q))) return 2;
      return v.includes(q) ? 1 : 0;
    };
    const rank = <T>(items: T[], fields: (item: T) => string[], weight: (item: T) => number) =>
      items
        .map((item) => [item, Math.max(...fields(item).map(score))] as const)
        .filter(([, s]) => s > 0)
        .sort((a, b) => b[1] - a[1] || weight(b[0]) - weight(a[0]))
        .slice(0, limit)
        .map(([item]) => item);
    return {
      artists: rank(this.albumArtists(), (a) => [a.name], (a) => a.plays),
      albums: rank(this.albums, (a) => [a.title, a.artist], (a) => a.plays),
      songs: rank(this.songs, (s) => [s.title, s.artist, s.album], (s) => s.plays),
    };
  }
}

export function fold(value: string): string {
  return value.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
}

/** Art filling a square of `pixels` device pixels, as the server scales it. */
export function imageUrl(session: Session | null, itemId: string | undefined, tag: string | undefined, pixels: number) {
  if (!session || !itemId || !tag) return undefined;
  const base = session.serverUrl.replace(/\/$/, "");
  return `${base}/Items/${itemId}/Images/Primary?fillWidth=${pixels}&fillHeight=${pixels}&quality=90&tag=${tag}&api_key=${session.token}`;
}

export function duration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export function longDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return h ? `${h} hr ${m} min` : `${m} min`;
}

export function ago(at: number): string {
  if (!at) return "";
  const days = Math.floor((Date.now() - at) / 86_400_000);
  if (days <= 0) return "Today";
  if (days === 1) return "Yesterday";
  if (days < 7) return `${days} days ago`;
  if (days < 30) return `${Math.round(days / 7)} wk ago`;
  if (days < 365) return `${Math.round(days / 30)} mo ago`;
  return `${Math.round(days / 365)} yr ago`;
}

export function plural(count: number, one: string, many = `${one}s`) {
  return `${count.toLocaleString()} ${count === 1 ? one : many}`;
}

/** "FLAC · 44.1 kHz · 1,411 kbps" from what the server reports. */
export function format(song: Song): string {
  const parts = [];
  if (song.container) parts.push(song.container.toUpperCase());
  if (song.sampleRate) parts.push(`${(song.sampleRate / 1000).toFixed(1).replace(/\.0$/, "")} kHz`);
  if (song.bitRate) parts.push(`${Math.round(song.bitRate / 1000).toLocaleString()} kbps`);
  return parts.join(" · ");
}
