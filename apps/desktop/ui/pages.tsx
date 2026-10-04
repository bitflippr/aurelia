// Library pages: home, albums, artists, songs, favorites, playlists, search
// and settings.
import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  AlbumCard,
  Ambient,
  ArtistCard,
  Artwork,
  Button,
  ButtonGroup,
  Chip,
  Collage,
  Empty,
  Segmented,
  SectionHead,
  Shelf,
  useColumns,
  pageTitle,
} from "./components";
import { Icon } from "./icons";
import { longDuration, plural, type Album, type Artist, type Library, type Song } from "./library";
import { albumMenu, songMenu } from "./menus";
import { Page, Pad, usePalette, type Sticky } from "./page";
import {
  ensureArtwork,
  go,
  newPlaylist,
  openMenu,
  play,
  playSong,
  runSync,
  set,
  signOut,
  useStore,
  type AlbumSort,
  type Mix,
  type SongSort,
} from "./store";
import { ROW_HEIGHT, TrackHeader, TrackRow, useCurrentId } from "./tracks";
import { C, F, SIZE, alpha, center, column, ellipsis, mix, radius, row, text } from "./theme";

const GREETINGS = ["Tune in", "Hit play", "Your music", "Find a vibe", "Good tunes"];
const greeting = GREETINGS[Math.floor(Math.random() * GREETINGS.length)];

function Header({ title, sub, children }: { title: string; sub?: string; children?: ReactNode }) {
  return (
    <div style={{ ...row, alignItems: "flex-end", justifyContent: "space-between", paddingTop: 36, paddingBottom: 22, gap: 24 }}>
      <div style={{ ...column }}>
        <text style={pageTitle}>{title}</text>
        {sub && <text style={{ ...text.body(14, C.muted), marginTop: 8 }}>{sub}</text>}
      </div>
      {children && <div style={{ ...row, gap: 8 }}>{children}</div>}
    </div>
  );
}

// ---- Home -------------------------------------------------------------------

export function Home() {
  const library = useStore((s) => s.library)!;
  const mixes = useStore((s) => s.mixes);
  const currentId = useCurrentId();
  const current = currentId ? library.song.get(currentId) : undefined;
  const currentAlbum = current?.albumId ? library.album.get(current.albumId) : undefined;
  useEffect(() => ensureArtwork(currentAlbum?.id, currentAlbum?.imageTag), [currentAlbum?.id]);
  const palette = usePalette(currentAlbum?.id) ?? (["#4b3a9a", "#7a3560", "#2f4f80"] as [string, string, string]);

  const quick = useMemo(() => {
    const forgotten = library.songs.filter((s) => s.fav).sort((a, b) => a.playedAt - b.playedAt).slice(0, 3);
    const taken = new Set(forgotten.map((s) => s.id));
    const most = [...library.songs].sort((a, b) => b.plays - a.plays).filter((s) => !taken.has(s.id) && s.plays > 0).slice(0, 6 - forgotten.length);
    return [...forgotten, ...most];
  }, [library]);
  const fresh = useMemo(() => [...library.albums].sort((a, b) => b.addedAt - a.addedAt), [library]);
  const top = useMemo(() => [...library.albumArtists()].sort((a, b) => b.plays - a.plays), [library]);

  const sections: ReactNode[] = [
    <Pad key="head">
      <div style={{ position: "relative" }}>
        <div style={{ ...row, alignItems: "flex-end", justifyContent: "space-between", flexWrap: "wrap", paddingTop: 44, paddingBottom: 6, columnGap: 24, rowGap: 16 }}>
          <text style={{ ...text.display(56), lineHeight: 60 }}>{greeting}</text>
          <div style={{ ...row, gap: 8 }}>
            <Button label="Shuffle library" icon="shuffle" tone="filled" onClick={() => play(library.songs.map((s) => s.id), 0, true)} />
            <Button
              label="Surprise me"
              icon="dice"
              tone="tonal"
              onClick={() => {
                const favs = library.songs.filter((s) => s.fav);
                play((favs.length ? favs : library.songs).map((s) => s.id), 0, true);
              }}
            />
          </div>
        </div>
      </div>
    </Pad>,
  ];
  if (mixes === null || mixes.length) {
    sections.push(
      <Pad key="mixes" top={20}>
        <SectionHead title="Let it play" />
        <Mixes mixes={mixes} library={library} />
      </Pad>,
    );
  }
  if (quick.length) {
    sections.push(
      <Pad key="quick" top={36}>
        <SectionHead title="Worth another listen" />
        <QuickPicks songs={quick} library={library} />
      </Pad>,
    );
  }
  sections.push(
    <Pad key="fresh" top={36}>
      <SectionHead
        title="Fresh on your shelf"
        action="Show all"
        onAction={() => {
          set({ albumSort: "added", albumGenre: null });
          go({ name: "albums" });
        }}
      />
      <Shelf items={fresh} render={(album, width) => <AlbumCard key={album.id} album={album} width={width} />} />
    </Pad>,
  );
  if (top.length) {
    sections.push(
      <Pad key="artists" top={28}>
        <SectionHead title="Artists you come back to" action="Show all" onAction={() => go({ name: "artists" })} />
        <Shelf items={top} render={(artist, width) => <ArtistCard key={artist.id} artist={artist} width={width} />} />
      </Pad>,
    );
  }
  if (library.genres.length) {
    sections.push(
      <Pad key="genres" top={28} bottom={48}>
        <SectionHead title="A little of everything" />
        <GenreWall library={library} />
      </Pad>,
    );
  }
  return (
    <Page
      count={sections.length}
      estimate={260}
      row={(i) =>
        i === 0 ? (
          <div key="head" style={{ position: "relative" }}>
            <Ambient palette={palette} height={520} />
            {sections[0]}
          </div>
        ) : (
          sections[i]
        )
      }
    />
  );
}

function Mixes({ mixes, library }: { mixes: Mix[] | null; library: Library }) {
  const { columns } = useColumns(300);
  const count = Math.min(columns, 3);
  const cards = mixes ?? Array.from({ length: count }, () => null);
  return (
    <div style={{ ...row, gap: 12 }}>
      {cards.slice(0, count).map((mix_, i) => (
        <MixCard key={mix_?.seed ?? i} mix={mix_} library={library} />
      ))}
    </div>
  );
}

function MixCard({ mix: m, library }: { mix: Mix | null; library: Library }) {
  if (!m) return <div style={{ flexGrow: 1, flexBasis: 0, height: 148, borderRadius: 20, backgroundColor: C.surface }} />;
  const albums = m.covers.map((id) => library.album.get(id)).filter((a): a is Album => !!a);
  return (
    <div
      role="button"
      aria-label={`Play ${m.title}`}
      onClick={() => play(m.songs)}
      style={{
        ...row,
        flexGrow: 1,
        flexBasis: 0,
        minWidth: 0,
        height: 148,
        gap: 16,
        paddingLeft: 20,
        paddingRight: 16,
        borderRadius: 20,
        cursor: "pointer",
        background: {
          type: "linear-gradient",
          angle: 115,
          stops: [
            { color: mix(C.surface, m.color, 0.36), position: 0 },
            { color: C.surface, position: 0.75 },
          ],
        },
        hover: { opacity: 0.92 },
      }}
    >
      <Collage albums={albums} size={88} radius={16} />
      <div style={{ ...column, flexGrow: 1, gap: 6 }}>
        <text style={{ fontSize: 18, fontWeight: 700, color: C.text, lineClamp: 2, lineHeight: 22 }}>{m.title}</text>
        <text style={{ fontSize: 13, color: C.muted, lineClamp: 2, lineHeight: 18 }}>{m.subtitle}</text>
      </div>
      <div style={{ ...center, alignSelf: "flex-end", marginBottom: 16, width: 40, height: 40, borderRadius: 20, backgroundColor: C.primary, flexShrink: 0 }}>
        <Icon name="play" size={18} color={C.onPrimary} />
      </div>
    </div>
  );
}

function QuickPicks({ songs, library }: { songs: Song[]; library: Library }) {
  const current = useCurrentId();
  const { columns } = useColumns(320);
  const perRow = Math.min(3, Math.max(1, columns));
  const list = songs.map((s) => s.id);
  const rows: Song[][] = [];
  for (let i = 0; i < songs.length; i += perRow) rows.push(songs.slice(i, i + perRow));
  return (
    <div style={{ ...column, gap: 6 }}>
      {rows.map((cells, r) => (
        <div key={r} style={{ ...row, gap: 12 }}>
          {cells.map((song) => (
            <QuickPick key={song.id} song={song} list={list} playing={song.id === current} tag={song.albumId ? library.album.get(song.albumId)?.imageTag : undefined} />
          ))}
          {Array.from({ length: perRow - cells.length }, (_, i) => (
            <div key={`gap${i}`} style={{ flexGrow: 1, flexBasis: 0 }} />
          ))}
        </div>
      ))}
    </div>
  );
}

function QuickPick({ song, list, playing, tag }: { song: Song; list: string[]; playing: boolean; tag?: string }) {
  const [hover, setHover] = useState(false);
  return (
    <div
      role="button"
      aria-label={`Play ${song.title}`}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={() => playSong(list, song.id)}
      onAuxClick={(e) => e.isRightClick && openMenu({ x: e.x ?? 0, y: e.y ?? 0, items: songMenu(song) })}
      style={{
        ...row,
        flexGrow: 1,
        flexBasis: 0,
        minWidth: 0,
        gap: 12,
        padding: 6,
        paddingRight: 12,
        borderRadius: 12,
        cursor: "pointer",
        backgroundColor: hover ? alpha(C.text, 0.05) : alpha(C.bg, 0),
      }}
    >
      <div style={{ position: "relative", width: 52, height: 52, flexShrink: 0 }}>
        <Artwork id={song.albumId} tag={tag} size={52} radius={9} />
        {(hover || playing) && (
          <div style={{ ...center, position: "absolute", left: 0, top: 0, width: 52, height: 52, borderRadius: 9, backgroundColor: "#00000073" }}>
            <Icon name="play" size={20} color={C.text} />
          </div>
        )}
      </div>
      <div style={{ ...column, gap: 3, flexGrow: 1 }}>
        <text style={{ ...text.title(14), ...ellipsis, color: playing ? C.primary : C.text }}>{song.title}</text>
        <text style={{ ...text.body(13, C.muted), ...ellipsis }}>{`${song.artist} · ${song.fav ? "Forgotten favorite" : plural(song.plays, "play")}`}</text>
      </div>
    </div>
  );
}

function GenreWall({ library }: { library: Library }) {
  const genres = library.genres.slice(0, 9);
  const max = genres[0]?.count ?? 1;
  return (
    <div style={{ display: "flex", flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
      {genres.map((genre, i) => {
        const weight = genre.count / max;
        const display = i < 2;
        return (
          <div
            key={genre.name}
            role="button"
            aria-label={`Shuffle ${genre.name}`}
            onClick={() => play(library.songs.filter((s) => s.genres?.includes(genre.name)).map((s) => s.id), 0, true)}
            style={{
              ...center,
              flexGrow: 1,
              height: 64,
              paddingLeft: 22,
              paddingRight: 22,
              borderRadius: 18,
              backgroundColor: C.surface,
              borderWidth: 1,
              borderColor: C.hairline,
              cursor: "pointer",
              hover: { backgroundColor: C.surfaceHigh },
            }}
          >
            <text
              style={{
                fontFamily: display ? F.display : F.sans,
                fontWeight: display ? 900 : [500, 600, 700, 800][i % 4],
                fontSize: Math.round(16 + weight * 6),
                color: C.text,
                whiteSpace: "nowrap",
              }}
            >
              {genre.name}
            </text>
          </div>
        );
      })}
    </div>
  );
}

// ---- Albums -----------------------------------------------------------------

const ALBUM_SORTS: { key: AlbumSort; label: string }[] = [
  { key: "added", label: "Recently added" },
  { key: "title", label: "Title" },
  { key: "artist", label: "Artist" },
  { key: "year", label: "Release year" },
  { key: "plays", label: "Most played" },
];

export function Albums() {
  const library = useStore((s) => s.library)!;
  const sort = useStore((s) => s.albumSort);
  const genre = useStore((s) => s.albumGenre);
  const layout = useStore((s) => s.albumLayout);
  const { columns, width } = useColumns();
  const albums = useMemo(() => {
    const list = genre ? library.albums.filter((a) => a.genres.includes(genre)) : [...library.albums];
    const by: Record<AlbumSort, (a: Album, b: Album) => number> = {
      added: (a, b) => b.addedAt - a.addedAt,
      title: (a, b) => a.title.localeCompare(b.title),
      artist: (a, b) => a.artist.localeCompare(b.artist) || (b.year ?? 0) - (a.year ?? 0),
      year: (a, b) => (b.year ?? 0) - (a.year ?? 0),
      plays: (a, b) => b.plays - a.plays,
    };
    return list.sort(by[sort]);
  }, [library, sort, genre]);
  const genres = library.genres.slice(0, 8).map((g) => g.name);
  const perRow = layout === "grid" ? columns : 1;
  const rowCount = Math.ceil(albums.length / perRow);

  const header = (
    <Pad key="head">
      <Header title="Albums" sub={plural(albums.length, "album")} />
      <div style={{ ...row, gap: 8, paddingBottom: 18 }}>
        <div style={{ ...row, gap: 8, flexGrow: 1, minWidth: 0, overflow: "hidden" }}>
          <Chip label="All" on={!genre} onClick={() => set({ albumGenre: null })} />
          {genres.map((g) => (
            <Chip key={g} label={g} on={genre === g} onClick={() => set({ albumGenre: g })} />
          ))}
        </div>
        <Chip
          label={ALBUM_SORTS.find((s) => s.key === sort)!.label}
          icon="sort"
          onClick={(e) =>
            openMenu({
              x: e.x ?? 0,
              y: (e.y ?? 0) + 14,
              items: ALBUM_SORTS.map((s) => ({ label: s.label, icon: s.key === sort ? "check" : undefined, run: () => set({ albumSort: s.key }) })),
            })
          }
        />
        <Segmented
          value={layout}
          onChange={(value) => set({ albumLayout: value })}
          options={[
            { value: "grid", icon: "grid", label: "Grid" },
            { value: "list", icon: "list", label: "List" },
          ]}
        />
      </div>
    </Pad>
  );
  return (
    <Page
      count={rowCount + 1}
      estimate={layout === "grid" ? width + 50 : 64}
      sticky={{ title: "Albums" }}
      row={(i) => {
        if (i === 0) return header;
        if (layout === "list") {
          const album = albums[i - 1];
          return <AlbumRow key={album.id} album={album} />;
        }
        const cells = albums.slice((i - 1) * perRow, i * perRow);
        return (
          <div key={`r${i}`} style={{ ...row, alignItems: "flex-start", paddingLeft: SIZE.page - 10, paddingRight: SIZE.page - 10, paddingBottom: 6 }}>
            {cells.map((album) => (
              <AlbumCard key={album.id} album={album} width={width} sub={`${album.artist}${album.year ? ` · ${album.year}` : ""}`} />
            ))}
          </div>
        );
      }}
    />
  );
}

function AlbumRow({ album }: { album: Album }) {
  return (
    <div style={{ paddingLeft: SIZE.page, paddingRight: SIZE.page }}>
      <div
        role="button"
        onClick={() => go({ name: "album", id: album.id })}
        onAuxClick={(e) => e.isRightClick && openMenu({ x: e.x ?? 0, y: e.y ?? 0, items: albumMenu(album) })}
        style={{ ...row, height: 64, gap: 14, paddingLeft: 10, paddingRight: 10, borderRadius: radius.md, cursor: "pointer", hover: { backgroundColor: alpha(C.text, 0.045) } }}
      >
        <Artwork id={album.id} tag={album.imageTag} size={48} radius={8} />
        <text style={{ ...text.title(14), ...ellipsis, flexGrow: 2, flexBasis: 0 }}>{album.title}</text>
        <text style={{ ...text.body(13, C.muted), ...ellipsis, flexGrow: 1.4, flexBasis: 0 }}>{album.artist}</text>
        <text style={{ ...text.body(13, C.muted), width: 60 }}>{album.year ?? ""}</text>
        <text style={{ ...text.body(13, C.muted), width: 90, textAlign: "right" }}>{plural(album.songs.length, "song")}</text>
        <text style={{ ...text.body(13, C.muted), width: 80, textAlign: "right" }}>{longDuration(album.duration)}</text>
      </div>
    </div>
  );
}

// ---- Artists ----------------------------------------------------------------

export function Artists() {
  const library = useStore((s) => s.library)!;
  const { columns, width } = useColumns();
  const artists = useMemo(() => [...library.albumArtists()].sort((a, b) => a.name.localeCompare(b.name)), [library]);
  const rowCount = Math.ceil(artists.length / columns);
  return (
    <Page
      count={rowCount + 1}
      estimate={width + 50}
      sticky={{ title: "Artists" }}
      row={(i) =>
        i === 0 ? (
          <Pad key="head">
            <Header title="Artists" sub={plural(artists.length, "artist")} />
          </Pad>
        ) : (
          <div key={`r${i}`} style={{ ...row, alignItems: "flex-start", paddingLeft: SIZE.page - 10, paddingRight: SIZE.page - 10, paddingBottom: 6 }}>
            {artists.slice((i - 1) * columns, i * columns).map((artist) => (
              <ArtistCard key={artist.id} artist={artist} width={width} />
            ))}
          </div>
        )
      }
    />
  );
}

// ---- Song tables ------------------------------------------------------------

function sortSongs(songs: Song[], sort: SongSort): Song[] {
  const by: Record<SongSort["key"], (a: Song, b: Song) => number> = {
    title: (a, b) => a.title.localeCompare(b.title),
    album: (a, b) => a.album.localeCompare(b.album) || (a.disc ?? 1) - (b.disc ?? 1) || (a.track ?? 0) - (b.track ?? 0),
    artist: (a, b) => a.artist.localeCompare(b.artist),
    added: (a, b) => b.addedAt - a.addedAt || a.album.localeCompare(b.album) || (a.track ?? 0) - (b.track ?? 0),
    plays: (a, b) => b.plays - a.plays,
    duration: (a, b) => b.duration - a.duration,
  };
  return [...songs].sort((a, b) => by[sort.key](a, b) * sort.dir);
}

/** A long song table as one page: its header row, then a row per song. */
export function SongPage({
  songs,
  head,
  title,
  sortable,
  added,
  tint,
}: {
  songs: Song[];
  head: ReactNode;
  title: string;
  sortable?: boolean;
  added?: number[];
  tint?: string;
}) {
  const library = useStore((s) => s.library)!;
  const sort = useStore((s) => s.songSort);
  const current = useCurrentId();
  useStore((s) => s.favVersion);
  const sorted = useMemo(() => (sortable ? sortSongs(songs, sort) : songs), [songs, sort, sortable]);
  const list = useMemo(() => sorted.map((s) => s.id), [sorted]);
  const onSort = sortable
    ? (key: SongSort["key"]) => set((s) => ({ songSort: { key, dir: s.songSort.key === key ? ((-s.songSort.dir) as 1 | -1) : 1 } }))
    : undefined;
  const header = <TrackHeader variant="full" sort={sortable ? sort : undefined} onSort={onSort} />;
  const sticky: Sticky = { title, onPlay: () => play(list), below: header, tint };
  return (
    <Page
      count={sorted.length + 1}
      estimate={ROW_HEIGHT}
      sticky={sticky}
      row={(i) =>
        i === 0 ? (
          <div key="head" style={{ position: "relative" }}>
            {head}
            <Pad>{header}</Pad>
          </div>
        ) : (
          <div key={`${sorted[i - 1].id}:${i}`} style={{ paddingLeft: SIZE.page, paddingRight: SIZE.page }}>
            <TrackRow
              song={sorted[i - 1]}
              number={i}
              variant="full"
              list={list}
              current={sorted[i - 1].id === current}
              fav={sorted[i - 1].fav}
              added={added?.[i - 1]}
              tag={sorted[i - 1].albumId ? library.album.get(sorted[i - 1].albumId!)?.imageTag : undefined}
            />
          </div>
        )
      }
    />
  );
}

export function Songs() {
  const library = useStore((s) => s.library)!;
  const total = useMemo(() => library.songs.reduce((sum, s) => sum + s.duration, 0), [library]);
  return (
    <SongPage
      songs={library.songs}
      title="Songs"
      sortable
      head={
        <Pad>
          <Header title="Songs" sub={`${plural(library.songs.length, "song")} · ${longDuration(total)}`}>
            <Button label="Shuffle all" icon="shuffle" tone="filled" onClick={() => play(library.songs.map((s) => s.id), 0, true)} />
          </Header>
        </Pad>
      }
    />
  );
}

export function Favorites() {
  const library = useStore((s) => s.library)!;
  const version = useStore((s) => s.favVersion);
  const sort = useStore((s) => s.songSort);
  const songs = useMemo(() => library.favorites(), [library, version]);
  const total = songs.reduce((sum, s) => sum + s.duration, 0);
  const palette: [string, string, string] = ["#8a3a6a", "#4b3a9a", "#7a4a2a"];
  // In the order the table shows them.
  const ids = useMemo(() => sortSongs(songs, sort).map((s) => s.id), [songs, sort]);
  return (
    <SongPage
      songs={songs}
      title="Favorites"
      sortable
      tint="#8a3a6a"
      head={
        <>
          <Ambient palette={palette} />
          <Pad>
            <div style={{ ...row, alignItems: "flex-end", gap: 28, paddingTop: 40, paddingBottom: 28 }}>
              <div
                style={{
                  ...center,
                  width: 232,
                  height: 232,
                  borderRadius: 16,
                  flexShrink: 0,
                  background: {
                    type: "linear-gradient",
                    angle: 135,
                    stops: [
                      { color: C.pink, position: 0 },
                      { color: "#6650d9", position: 1 },
                    ],
                  },
                  boxShadow: { offsetX: 0, offsetY: 24, blurRadius: 60, spreadRadius: 0, color: "#00000080" },
                }}
              >
                <Icon name="heartFilled" size={84} color="#fffffff2" />
              </div>
              <div style={{ ...column, paddingBottom: 4 }}>
                <text style={text.label()}>Collection</text>
                <text style={{ ...text.display(64), marginTop: 8, marginBottom: 14 }}>Favorites</text>
                <text style={text.body(14, C.muted)}>{`${plural(songs.length, "song")}, ${longDuration(total)} · Synced with Jellyfin`}</text>
              </div>
            </div>
            <div style={{ ...row, gap: 10, paddingTop: 4, paddingBottom: 20 }}>
              <ButtonGroup
                items={[
                  { label: "Play", icon: "play", tone: "filled", onClick: () => play(ids) },
                  { label: "Shuffle", icon: "shuffle", tone: "tonal", onClick: () => play(ids, 0, true) },
                ]}
              />
            </div>
          </Pad>
        </>
      }
    />
  );
}

// ---- Playlists --------------------------------------------------------------

export function Playlists() {
  const playlists = useStore((s) => s.playlists);
  const { columns, width } = useColumns();
  const items: (string | null)[] = [null, ...playlists.map((p) => p.id)];
  const rowCount = Math.ceil(items.length / columns);
  return (
    <Page
      count={rowCount + 1}
      estimate={width + 50}
      sticky={{ title: "Playlists" }}
      row={(i) =>
        i === 0 ? (
          <Pad key="head">
            <Header title="Playlists" sub={plural(playlists.length, "playlist")} />
          </Pad>
        ) : (
          <div key={`r${i}`} style={{ ...row, alignItems: "flex-start", paddingLeft: SIZE.page - 10, paddingRight: SIZE.page - 10, paddingBottom: 6 }}>
            {items.slice((i - 1) * columns, i * columns).map((id) =>
              id === null ? <NewPlaylistCard key="new" width={width} /> : <PlaylistCard key={id} id={id} width={width} />,
            )}
          </div>
        )
      }
    />
  );
}

function NewPlaylistCard({ width }: { width: number }) {
  const art = width - 20;
  return (
    <div
      role="button"
      aria-label="New playlist"
      onClick={() => newPlaylist([])}
      style={{ ...column, width, padding: 10, borderRadius: radius.lg, cursor: "pointer", hover: { backgroundColor: alpha(C.text, 0.045) } }}
    >
      <div style={{ ...center, width: art, height: art, borderRadius: radius.md, backgroundColor: C.surface, borderWidth: 1, borderColor: alpha(C.text, 0.14) }}>
        <Icon name="plus" size={36} color={C.muted} />
      </div>
      <text style={{ ...text.title(14), marginTop: 10 }}>New playlist</text>
      <text style={{ ...text.body(13, C.muted), marginTop: 3, ...ellipsis }}>Or start one from a song's menu</text>
    </div>
  );
}

function PlaylistCard({ id, width }: { id: string; width: number }) {
  const playlist = useStore((s) => s.playlists.find((p) => p.id === id));
  const [hover, setHover] = useState(false);
  if (!playlist) return null;
  const art = width - 20;
  return (
    <div
      role="button"
      aria-label={playlist.name}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={() => go({ name: "playlist", id })}
      style={{ ...column, width, padding: 10, borderRadius: radius.lg, cursor: "pointer", backgroundColor: hover ? alpha(C.text, 0.045) : alpha(C.bg, 0) }}
    >
      <Artwork id={playlist.id} tag={playlist.imageTag} size={art} shadow />
      <text style={{ ...text.title(14), ...ellipsis, marginTop: 10 }}>{playlist.name}</text>
      <text style={{ ...text.body(13, C.muted), marginTop: 3 }}>{plural(playlist.count, "song")}</text>
    </div>
  );
}

// ---- Search -----------------------------------------------------------------

export function Search({ q }: { q: string }) {
  const library = useStore((s) => s.library)!;
  const results = useMemo(() => library.search(q), [library, q]);
  const top = results.artists[0] ?? results.albums[0];
  const sections: ReactNode[] = [
    <Pad key="head">
      <div style={{ ...column, paddingTop: 36, paddingBottom: 18 }}>
        <text style={text.label()}>Search</text>
        <text style={{ ...pageTitle, marginTop: 6 }}>{`“${q}”`}</text>
      </div>
    </Pad>,
  ];
  if (!results.songs.length && !results.albums.length && !results.artists.length) {
    sections.push(
      <Pad key="none">
        <Empty title="No matches in your library" detail="Try an artist, album or song title." />
      </Pad>,
    );
  } else {
    sections.push(
      <Pad key="top">
        <div style={{ ...row, alignItems: "flex-start", gap: 28 }}>
          {top && (
            <div style={{ ...column, flexGrow: 0.8, flexBasis: 0, minWidth: 260 }}>
              <SectionHead title="Top result" />
              <TopResult item={top} />
            </div>
          )}
          {results.songs.length > 0 && (
            <div style={{ ...column, flexGrow: 1.6, flexBasis: 0 }}>
              <SectionHead title="Songs" />
              <CompactSongs songs={results.songs.slice(0, 5)} />
            </div>
          )}
        </div>
      </Pad>,
    );
    if (results.albums.length)
      sections.push(
        <Pad key="albums" top={36}>
          <SectionHead title="Albums" />
          <Shelf items={results.albums} render={(album, width) => <AlbumCard key={album.id} album={album} width={width} />} />
        </Pad>,
      );
    if (results.artists.length)
      sections.push(
        <Pad key="artists" top={28} bottom={40}>
          <SectionHead title="Artists" />
          <Shelf items={results.artists} render={(artist, width) => <ArtistCard key={artist.id} artist={artist} width={width} />} />
        </Pad>,
      );
    if (results.songs.length > 5)
      sections.push(
        <Pad key="more" top={8} bottom={40}>
          <SectionHead title={`All songs (${results.songs.length})`} />
          <CompactSongs songs={results.songs} />
        </Pad>,
      );
  }
  return <Page count={sections.length} estimate={240} row={(i) => sections[i]} />;
}

function TopResult({ item }: { item: Artist | Album }) {
  const [hover, setHover] = useState(false);
  const isArtist = "albums" in item;
  const name = isArtist ? item.name : item.title;
  const cover = isArtist ? (item.imageTag ? undefined : item.albums.find((a) => a.imageTag)) : undefined;
  return (
    <div
      role="button"
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={() => go(isArtist ? { name: "artist", id: item.id } : { name: "album", id: item.id })}
      style={{ ...column, padding: 22, borderRadius: 18, cursor: "pointer", backgroundColor: hover ? C.surfaceHigh : C.surface }}
    >
      <Artwork
        id={cover ? cover.id : item.id}
        tag={cover ? cover.imageTag : item.imageTag}
        size={104}
        round={isArtist}
        radius={12}
        shadow
      />
      <text style={{ fontFamily: F.display, fontWeight: 900, fontSize: 30, color: C.text, marginTop: 18, ...ellipsis }}>{name}</text>
      <text style={{ ...text.body(14, C.muted), marginTop: 6 }}>{isArtist ? "Artist" : `Album · ${item.artist}`}</text>
    </div>
  );
}

function CompactSongs({ songs }: { songs: Song[] }) {
  const library = useStore((s) => s.library)!;
  const current = useCurrentId();
  useStore((s) => s.favVersion);
  const list = useMemo(() => songs.map((s) => s.id), [songs]);
  return (
    <div style={{ ...column }}>
      {songs.map((song, i) => (
        <TrackRow
          key={song.id}
          song={song}
          number={i + 1}
          variant="artist"
          list={list}
          current={song.id === current}
          fav={song.fav}
          tag={song.albumId ? library.album.get(song.albumId)?.imageTag : undefined}
        />
      ))}
    </div>
  );
}

// ---- Settings -----------------------------------------------------------------

function Setting({ label, detail, children }: { label: string; detail?: string; children?: ReactNode }) {
  return (
    <div style={{ ...row, gap: 16, paddingLeft: 18, paddingRight: 18, paddingTop: 16, paddingBottom: 16, minHeight: 64 }}>
      <div style={{ ...column, flexGrow: 1, gap: 3 }}>
        <text style={text.title(14)}>{label}</text>
        {detail && <text style={text.body(13, C.muted)}>{detail}</text>}
      </div>
      {children}
    </div>
  );
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div style={{ ...column, marginBottom: 32 }}>
      <text style={{ fontSize: 13, fontWeight: 600, color: C.primary, marginBottom: 10 }}>{title}</text>
      <div style={{ ...column, borderRadius: 18, backgroundColor: C.surfaceLow }}>{children}</div>
    </div>
  );
}

const Divider = () => <div style={{ height: 1, backgroundColor: C.hairline }} />;

const SHORTCUTS: [string, string][] = [
  ["Space", "Play or pause"],
  ["Ctrl+K", "Search"],
  ["Ctrl+→ / Ctrl+←", "Next or previous song"],
  ["Alt+← / Alt+→", "Back or forward"],
  ["L / Q", "Lyrics or queue"],
  ["F", "Full-screen player"],
  ["Ctrl+B", "Collapse the sidebar"],
];

export function Settings() {
  const session = useStore((s) => s.session);
  const library = useStore((s) => s.library);
  const sync = useStore((s) => s.sync);
  const host = session ? session.serverUrl.replace(/^https?:\/\//, "") : "";
  const sections: ReactNode[] = [
    <Pad key="head">
      <Header title="Settings" />
    </Pad>,
    <Pad key="body" bottom={48}>
      <div style={{ ...column, maxWidth: 680 }}>
        <Group title="Library">
          <Setting
            label="Sync library"
            detail={
              sync?.running
                ? `${sync.stage}${sync.total ? ` · ${sync.current} of ${sync.total}` : ""}`
                : sync?.error
                  ? `Last sync failed: ${sync.error}`
                  : library
                    ? `${plural(library.songs.length, "song")} · ${plural(library.albums.length, "album")} · ${plural(library.albumArtists().length, "artist")}`
                    : undefined
            }
          >
            <Button label={sync?.running ? "Syncing…" : "Sync now"} icon="sync" height={36} onClick={() => !sync?.running && runSync()} />
          </Setting>
        </Group>
        <Group title="Account">
          <Setting label={host} detail={session ? `Signed in as ${session.username}` : undefined} />
          <Divider />
          <Setting label="Log out" detail="Removes this account's cached library from this computer.">
            <Button label="Log out" icon="logout" height={36} onClick={() => signOut()} style={{ color: C.error }} />
          </Setting>
        </Group>
        <Group title="Keyboard">
          {SHORTCUTS.map(([keys, action], i) => (
            <div key={keys} style={{ ...column }}>
              {i > 0 && <Divider />}
              <Setting label={action}>
                <text style={{ fontSize: 13, color: C.muted }}>{keys}</text>
              </Setting>
            </div>
          ))}
        </Group>
      </div>
    </Pad>,
  ];
  return <Page count={sections.length} estimate={400} row={(i) => sections[i]} />;
}

