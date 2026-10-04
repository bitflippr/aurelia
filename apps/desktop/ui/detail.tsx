// Album, artist and playlist pages.
import { useEffect, useMemo, type ReactNode } from "react";
import {
  AlbumCard,
  Ambient,
  ArtistCard,
  Artwork,
  ButtonGroup,
  Empty,
  Grid,
  IconButton,
  SectionHead,
  Shelf,
} from "./components";
import { format, longDuration, plural, type Artist, type Song } from "./library";
import { albumMenu, artistMenu } from "./menus";
import { Page, Pad, usePalette } from "./page";
import {
  ensureArtistInfo,
  ensureArtwork,
  go,
  loadPlaylistSongs,
  openMenu,
  play,
  startMix,
  useStore,
} from "./store";
import { ALBUM_ROW_HEIGHT, ROW_HEIGHT, TrackHeader, TrackRow, useCurrentId } from "./tracks";
import { C, SIZE, alpha, column, ellipsis, row, text } from "./theme";

function DetailHeader({
  art,
  eyebrow,
  title,
  meta,
  palette,
}: {
  art: ReactNode;
  eyebrow: string;
  title: string;
  meta: ReactNode;
  palette?: [string, string, string];
}) {
  const size = title.length > 22 ? 44 : title.length > 14 ? 54 : 64;
  return (
    <div style={{ position: "relative" }}>
      <Ambient palette={palette} />
      <Pad>
        <div style={{ ...row, alignItems: "flex-end", gap: 28, paddingTop: 40, paddingBottom: 28 }}>
          {art}
          <div style={{ ...column, paddingBottom: 4, flexGrow: 1 }}>
            <text style={text.label()}>{eyebrow}</text>
            <text style={{ ...text.display(size), lineHeight: Math.round(size * 1.08), marginTop: 8, marginBottom: 14, lineClamp: 2 }}>
              {title}
            </text>
            {meta}
          </div>
        </div>
      </Pad>
    </div>
  );
}

function Meta({ parts, who }: { parts: string[]; who?: ReactNode }) {
  return (
    <div style={{ ...row, gap: 8, flexWrap: "wrap" }}>
      {who}
      {parts.map((part, i) => (
        <text key={i} style={text.body(14, C.muted)}>
          {(who || i > 0 ? "· " : "") + part}
        </text>
      ))}
    </div>
  );
}

function Who({ artist, onClick }: { artist?: Artist; onClick?: () => void }) {
  if (!artist) return null;
  const cover = artist.imageTag ? undefined : artist.albums.find((a) => a.imageTag);
  return (
    <div role="link" onClick={onClick} style={{ ...row, gap: 8, cursor: "pointer" }}>
      <Artwork id={cover?.id ?? artist.id} tag={cover?.imageTag ?? artist.imageTag} size={24} round />
      <text style={{ fontSize: 14, fontWeight: 600, color: C.text }}>{artist.name}</text>
    </div>
  );
}

function Actions({ children }: { children: ReactNode }) {
  return (
    <Pad>
      <div style={{ ...row, gap: 10, paddingTop: 4, paddingBottom: 20 }}>{children}</div>
    </Pad>
  );
}

// ---- Album --------------------------------------------------------------------

export function AlbumPage({ id }: { id: string }) {
  const library = useStore((s) => s.library)!;
  const album = library.album.get(id);
  useEffect(() => ensureArtwork(album?.id, album?.imageTag), [album?.id]);
  const palette = usePalette(album?.id);
  const current = useCurrentId();
  useStore((s) => s.favVersion);
  const list = useMemo(() => album?.songs.map((s) => s.id) ?? [], [album]);
  if (!album) return <Empty title="This album isn't in your library" />;

  const artist = album.artistId ? library.artist.get(album.artistId) : undefined;
  const discs = new Set(album.songs.map((s) => s.disc ?? 1)).size > 1;
  const more = artist ? artist.albums.filter((a) => a.id !== album.id) : [];
  const similar = library.albums.filter((a) => a.id !== album.id && a.artistId !== album.artistId && a.genres.some((g) => album.genres.includes(g)));
  const sample = album.songs[0];

  type Row = { key: string; node: () => ReactNode };
  const rows: Row[] = [
    {
      key: "head",
      node: () => (
        <DetailHeader
          palette={palette}
          eyebrow="Album"
          title={album.title}
          art={<Artwork id={album.id} tag={album.imageTag} size={232} radius={16} shadow />}
          meta={
            <Meta
              who={<Who artist={artist} onClick={() => artist && go({ name: "artist", id: artist.id })} />}
              parts={[...(album.year ? [String(album.year)] : []), `${plural(album.songs.length, "song")}, ${longDuration(album.duration)}`]}
            />
          }
        />
      ),
    },
    {
      key: "actions",
      node: () => (
        <Actions>
          <ButtonGroup
            items={[
              { label: "Play", icon: "play", tone: "filled", onClick: () => play(list) },
              { label: "Shuffle", icon: "shuffle", tone: "tonal", onClick: () => play(list, 0, true) },
              {
                icon: "more",
                tone: "tonal",
                aria: "More",
                onClick: (e) => openMenu({ x: e.x ?? 0, y: (e.y ?? 0) + 16, items: albumMenu(album) }),
              },
            ]}
          />
          {!album.id.startsWith("unknown:") && (
            <IconButton icon="radio" label="Start a mix from this album" size={44} onClick={() => startMix(album.id, album.title)} />
          )}
        </Actions>
      ),
    },
    { key: "columns", node: () => <Pad><TrackHeader variant="album" style={{ marginBottom: 6 }} /></Pad> },
  ];
  let lastDisc: number | undefined;
  album.songs.forEach((song, index) => {
    const disc = song.disc ?? 1;
    if (discs && disc !== lastDisc) {
      lastDisc = disc;
      rows.push({
        key: `disc${disc}`,
        node: () => (
          <Pad>
            <text style={{ fontSize: 14, fontWeight: 600, color: C.muted, paddingLeft: 10, paddingTop: index ? 16 : 4, paddingBottom: 8 }}>{`Disc ${disc}`}</text>
          </Pad>
        ),
      });
    }
    rows.push({
      key: `${song.id}:${index}`,
      node: () => (
        <Pad>
          <TrackRow
            song={song}
            number={song.track ?? index + 1}
            variant="album"
            list={list}
            current={song.id === current}
            fav={song.fav}
          />
        </Pad>
      ),
    });
  });
  rows.push({
    key: "foot",
    node: () => (
      <Pad top={18}>
        <text style={{ fontSize: 13, color: C.faint, paddingLeft: 10 }}>
          {[album.year ? `Released ${album.year}` : "", album.genres.slice(0, 3).join(", "), sample ? format(sample) : ""].filter(Boolean).join(" · ")}
        </text>
      </Pad>
    ),
  });
  if (more.length && artist)
    rows.push({
      key: "more",
      node: () => (
        <Pad top={36}>
          <SectionHead title={`More by ${artist.name}`} action="Discography" onAction={() => go({ name: "artist", id: artist.id })} />
          <Shelf items={more} render={(a, w) => <AlbumCard key={a.id} album={a} width={w} sub={a.year ? String(a.year) : "Album"} />} />
        </Pad>
      ),
    });
  if (similar.length)
    rows.push({
      key: "similar",
      node: () => (
        <Pad top={28} bottom={48}>
          <SectionHead title={`More ${album.genres[0]?.toLowerCase() ?? "like this"}`} />
          <Shelf items={similar} render={(a, w) => <AlbumCard key={a.id} album={a} width={w} />} />
        </Pad>
      ),
    });
  else rows.push({ key: "end", node: () => <div style={{ height: 48 }} /> });

  return (
    <Page
      key={album.id}
      count={rows.length}
      estimate={ALBUM_ROW_HEIGHT}
      stickyAfter={2}
      sticky={{ title: album.title, onPlay: () => play(list), tint: palette?.[0] }}
      row={(i) => <div key={rows[i].key}>{rows[i].node()}</div>}
    />
  );
}

// ---- Artist -------------------------------------------------------------------

export function ArtistPage({ id }: { id: string }) {
  const library = useStore((s) => s.library)!;
  const artist = library.artist.get(id);
  const info = useStore((s) => s.artistInfo[id]);
  const current = useCurrentId();
  useStore((s) => s.favVersion);
  const coverAlbum = useMemo(
    () => (artist ? [...artist.albums].sort((a, b) => b.plays - a.plays).find((a) => a.imageTag) : undefined),
    [artist],
  );
  useEffect(() => {
    ensureArtistInfo(id);
    ensureArtwork(coverAlbum?.id, coverAlbum?.imageTag);
  }, [id, coverAlbum?.id]);
  const artwork = useStore((s) => (coverAlbum ? s.artwork[coverAlbum.id] : undefined));
  const palette = typeof artwork === "object" ? artwork.palette : undefined;
  const popular = useMemo(() => (artist ? [...artist.songs].sort((a, b) => b.plays - a.plays).slice(0, 5) : []), [artist]);
  const all = useMemo(() => (artist ? [...artist.songs].sort((a, b) => b.plays - a.plays).map((s) => s.id) : []), [artist]);
  const popularList = useMemo(() => popular.map((s) => s.id), [popular]);
  if (!artist) return <Empty title="This artist isn't in your library" />;

  const related = (
    typeof info === "object" && info.related.length
      ? info.related.map((rid) => library.artist.get(rid))
      : library.albumArtists().filter((a) => a.id !== artist.id && [...a.genres].some((g) => artist.genres.has(g)))
  ).filter((a): a is Artist => !!a && a.albums.length > 0 && a.id !== artist.id);
  const overview = typeof info === "object" ? info.overview : undefined;
  const backdrop = typeof artwork === "object" ? artwork.backdrop : undefined;
  const totalPlays = artist.plays;

  const rows: { key: string; node: () => ReactNode }[] = [
    {
      key: "hero",
      node: () => (
        <div style={{ position: "relative", height: 340, overflow: "hidden" }}>
          {backdrop && (
            <img src={backdrop} objectFit="cover" style={{ position: "absolute", left: 0, top: -40, width: "100%", height: 420, opacity: 0.7 }} />
          )}
          {!backdrop && <Ambient palette={palette} height={340} />}
          <div
            style={{
              position: "absolute",
              left: 0,
              right: 0,
              top: 0,
              bottom: 0,
              background: {
                type: "linear-gradient",
                angle: 180,
                stops: [
                  { color: alpha(C.bg, 0.1), position: 0.2 },
                  { color: C.bg, position: 1 },
                ],
              },
            }}
          />
          <div style={{ ...row, alignItems: "flex-end", gap: 28, position: "absolute", left: SIZE.page, right: SIZE.page, bottom: 28 }}>
            <Artwork
              id={artist.imageTag ? artist.id : coverAlbum?.id}
              tag={artist.imageTag ?? coverAlbum?.imageTag}
              size={184}
              round
              shadow
            />
            <div style={{ ...column, paddingBottom: 4 }}>
              <text style={text.label()}>Artist</text>
              <text style={{ ...text.display(artist.name.length > 16 ? 48 : 64), marginTop: 8, marginBottom: 14, ...ellipsis }}>{artist.name}</text>
              <Meta
                parts={[
                  plural(artist.albums.length, "album"),
                  plural(artist.songs.length, "song"),
                  ...(totalPlays ? [plural(totalPlays, "play")] : []),
                ]}
              />
            </div>
          </div>
        </div>
      ),
    },
    {
      key: "actions",
      node: () => (
        <Pad top={20}>
          <div style={{ ...row, gap: 10, paddingBottom: 8 }}>
            <ButtonGroup
              items={[
                { label: "Play", icon: "play", tone: "filled", onClick: () => play(all) },
                { label: "Shuffle", icon: "shuffle", tone: "tonal", onClick: () => play(all, 0, true) },
                {
                  icon: "more",
                  tone: "tonal",
                  aria: "More",
                  onClick: (e) => openMenu({ x: e.x ?? 0, y: (e.y ?? 0) + 16, items: artistMenu(artist) }),
                },
              ]}
            />
            {!artist.id.startsWith("name:") && (
              <IconButton icon="radio" label="Start a mix from this artist" size={44} onClick={() => startMix(artist.id, artist.name)} />
            )}
          </div>
        </Pad>
      ),
    },
  ];
  if (popular.length)
    rows.push({
      key: "popular",
      node: () => (
        <Pad top={24}>
          <SectionHead title="Popular" />
          <TrackHeader variant="artist" style={{ marginBottom: 6 }} />
          {popular.map((song, i) => (
            <TrackRow
              key={song.id}
              song={song}
              number={i + 1}
              variant="artist"
              list={popularList}
              current={song.id === current}
              fav={song.fav}
              tag={song.albumId ? library.album.get(song.albumId)?.imageTag : undefined}
            />
          ))}
        </Pad>
      ),
    });
  if (artist.albums.length)
    rows.push({
      key: "discography",
      node: () => (
        <Pad top={36}>
          <SectionHead title="Discography" />
          <Grid items={artist.albums} render={(a, w) => <AlbumCard key={a.id} album={a} width={w} sub={a.year ? `${a.year} · Album` : "Album"} />} />
        </Pad>
      ),
    });
  if (related.length)
    rows.push({
      key: "related",
      node: () => (
        <Pad top={36}>
          <SectionHead title="Fans also like" />
          <Shelf items={related} render={(a, w) => <ArtistCard key={a.id} artist={a} width={w} />} />
        </Pad>
      ),
    });
  if (overview)
    rows.push({
      key: "about",
      node: () => (
        <Pad top={36}>
          <SectionHead title={`About ${artist.name}`} />
          <text style={{ fontSize: 15, lineHeight: 24, color: C.muted, maxWidth: 680 }}>{overview}</text>
        </Pad>
      ),
    });
  rows.push({ key: "end", node: () => <div style={{ height: 48 }} /> });

  return (
    <Page
      key={artist.id}
      count={rows.length}
      estimate={300}
      sticky={{ title: artist.name, onPlay: () => play(all), tint: palette?.[0] }}
      row={(i) => <div key={rows[i].key}>{rows[i].node()}</div>}
    />
  );
}

// ---- Playlist -----------------------------------------------------------------

export function PlaylistPage({ id }: { id: string }) {
  const playlist = useStore((s) => s.playlists.find((p) => p.id === id));
  const songs = useStore((s) => s.playlistSongs[id]);
  const library = useStore((s) => s.library)!;
  const current = useCurrentId();
  useStore((s) => s.favVersion);
  useEffect(() => {
    loadPlaylistSongs(id);
  }, [id]);
  useEffect(() => ensureArtwork(playlist?.id, playlist?.imageTag), [playlist?.id]);
  const palette = usePalette(playlist?.id);
  const list = useMemo(() => (Array.isArray(songs) ? songs.map((s: Song) => s.id) : []), [songs]);
  if (!playlist) return <Empty title="This playlist is gone" />;
  const loaded = Array.isArray(songs) ? songs : [];
  const total = loaded.reduce((sum, s) => sum + s.duration, 0) || playlist.duration;

  const head = (
    <div key="head">
      <DetailHeader
        palette={palette}
        eyebrow="Playlist"
        title={playlist.name}
        art={<Artwork id={playlist.id} tag={playlist.imageTag} size={232} radius={16} shadow />}
        meta={
          <div style={{ ...column, gap: 10 }}>
            {playlist.description && <text style={text.body(14, C.muted)}>{playlist.description}</text>}
            <Meta parts={[`${plural(playlist.count || loaded.length, "song")}, ${longDuration(total)}`]} />
          </div>
        }
      />
      <Actions>
        <ButtonGroup
          items={[
            { label: "Play", icon: "play", tone: "filled", onClick: () => play(list) },
            { label: "Shuffle", icon: "shuffle", tone: "tonal", onClick: () => play(list, 0, true) },
          ]}
        />
      </Actions>
      <Pad>
        <TrackHeader variant="playlist" style={{ marginBottom: 6 }} />
      </Pad>
    </div>
  );
  return (
    <Page
      key={playlist.id}
      count={loaded.length + 2}
      estimate={ROW_HEIGHT}
      sticky={{ title: playlist.name, onPlay: () => play(list), tint: palette?.[0] }}
      row={(i) => {
        if (i === 0) return head;
        if (i === loaded.length + 1)
          return (
            <div key="foot" style={{ height: 48, paddingLeft: SIZE.page + 10, paddingTop: 18 }}>
              {songs === "loading" && <text style={{ fontSize: 13, color: C.faint }}>Loading songs…</text>}
            </div>
          );
        const song = loaded[i - 1];
        return (
          <div key={`${song.id}:${i}`} style={{ paddingLeft: SIZE.page, paddingRight: SIZE.page }}>
            <TrackRow
              song={song}
              number={i}
              variant="playlist"
              list={list}
              current={song.id === current}
              fav={song.fav}
              tag={song.albumId ? library.album.get(song.albumId)?.imageTag : undefined}
            />
          </div>
        );
      }}
    />
  );
}

