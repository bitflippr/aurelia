// Context menus for songs, albums and artists.
import type { Album, Artist, Song } from "./library";
import {
  addToPlaylist,
  newPlaylist,
  enqueue,
  get,
  go,
  openMenu,
  play,
  playNext,
  startMix,
  toggleFavorite,
  type MenuItem,
} from "./store";

/** A follow-up menu listing playlists, opened where the first one was. */
function playlistPicker(ids: string[], name: string): () => void {
  return () => {
    const { playlists, menu } = get();
    const at = menu ?? { x: 200, y: 200 };
    const items: MenuItem[] = [
      { label: "New playlist…", icon: "plus", run: () => newPlaylist(ids, name) },
      ...(playlists.length ? (["-"] as MenuItem[]) : []),
      ...playlists.map((playlist): MenuItem => ({ label: playlist.name, icon: "playlists", run: () => addToPlaylist(playlist.id, ids) })),
    ];
    // Opened after the first menu closes on this click.
    setTimeout(() => openMenu({ x: at.x, y: at.y, items }), 0);
  };
}

export function songMenu(song: Song): MenuItem[] {
  const album = song.albumId ? get().library?.album.get(song.albumId) : undefined;
  const artistId = song.artistIds[0] ?? (song.artists[0] ? `name:${song.artists[0]}` : undefined);
  return [
    { label: "Play next", icon: "playNext", run: () => playNext([song.id]) },
    { label: "Add to queue", icon: "addQueue", run: () => enqueue([song.id]) },
    { label: "Add to playlist…", icon: "playlists", run: playlistPicker([song.id], song.title) },
    "-",
    {
      label: song.fav ? "Remove from Favorites" : "Add to Favorites",
      icon: song.fav ? "heartFilled" : "heart",
      run: () => toggleFavorite(song.id),
    },
    { label: "Start a mix", icon: "radio", run: () => startMix(song.id, song.title) },
    "-",
    ...(album ? [{ label: "Go to album", icon: "albums", run: () => go({ name: "album", id: album.id }) } as MenuItem] : []),
    ...(artistId ? [{ label: "Go to artist", icon: "artists", run: () => go({ name: "artist", id: artistId }) } as MenuItem] : []),
  ];
}

export function albumMenu(album: Album): MenuItem[] {
  const ids = album.songs.map((song) => song.id);
  return [
    { label: "Play", icon: "play", run: () => play(ids) },
    { label: "Shuffle", icon: "shuffle", run: () => play(ids, 0, true) },
    { label: "Play next", icon: "playNext", run: () => playNext(ids) },
    { label: "Add to queue", icon: "addQueue", run: () => enqueue(ids) },
    { label: "Add to playlist…", icon: "playlists", run: playlistPicker(ids, album.title) },
    "-",
    { label: "Start a mix", icon: "radio", run: () => startMix(album.id.startsWith("unknown:") ? ids[0] : album.id, album.title) },
    ...(album.artistId ? [{ label: "Go to artist", icon: "artists", run: () => go({ name: "artist", id: album.artistId! }) } as MenuItem] : []),
  ];
}

export function artistMenu(artist: Artist): MenuItem[] {
  const ids = [...artist.songs].sort((a, b) => b.plays - a.plays).map((song) => song.id);
  return [
    { label: "Play", icon: "play", run: () => play(ids) },
    { label: "Shuffle", icon: "shuffle", run: () => play(ids, 0, true) },
    { label: "Add to queue", icon: "addQueue", run: () => enqueue(ids) },
    ...(artist.id.startsWith("name:")
      ? []
      : [{ label: "Start a mix", icon: "radio", run: () => startMix(artist.id, artist.name) } as MenuItem]),
  ];
}
