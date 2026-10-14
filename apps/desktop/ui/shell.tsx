// The window: title bar, sidebar, page, side panel, player bar, and the
// overlays above them.
import { createElement, useEffect, useMemo, useState, type ReactNode } from "react";
import { AnimatePresence, type EventPayload } from "@gpuix/react";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { logo } from "./assets";
import { Artwork, Button, IconButton, Motion } from "./components";
import { Glass, TINTS, ink, useNowPalette, useNowTint } from "./glass";
import { AlbumPage, ArtistPage, PlaylistPage } from "./detail";
import { Icon, type IconName } from "./icons";
import { Lyrics } from "./lyrics";
import { host } from "./native";
import { Albums, Artists, Favorites, Home, Playlists, Search, Settings, Songs } from "./pages";
import { FullPlayer, NowCard, PlayerBar, Queue } from "./player";
import {
  closeMenu,
  createPlaylist,
  get,
  go,
  goBack,
  goForward,
  play,
  runSync,
  set,
  setPanel,
  signIn,
  signOut,
  useStore,
  type Route,
} from "./store";
import { C, F, SIZE, alpha, center, column, ellipsis, motion, radius, row, text } from "./theme";

const LOGO = logo;

// ---- Window controls --------------------------------------------------------

/** Windows 11 draws caption glyphs from Segoe Fluent Icons; Windows 10 from
 * Segoe MDL2 Assets. */
const CAPTION_FONT = existsSync(join(process.env.WINDIR ?? "C:\\Windows", "Fonts", "SegoeIcons.ttf"))
  ? "Segoe Fluent Icons"
  : "Segoe MDL2 Assets";
const GLYPHS = { minimize: "\uE921", maximize: "\uE922", restore: "\uE923", close: "\uE8BB" };
/** Elsewhere Segoe is missing; draw the same glyphs. */
const CAPTION_ICONS = { minimize: "captionMinimize", maximize: "captionMaximize", restore: "captionRestore", close: "captionClose" } as const;

function Caption({ action, children, style }: { action: "drag" | "minimize" | "maximize" | "close"; children?: ReactNode; style?: object }) {
  return createElement("gpuix-caption", { action, style }, children);
}

export function CaptionButtons() {
  const maximized = useStore((s) => s.window.maximized);
  const active = useStore((s) => s.window.active);
  if (process.platform === "darwin") return null;
  const glyph = active ? C.text : alpha(C.text, 0.36);
  const wash = (amount: number) => alpha(C.text, amount);
  return (
    <div style={{ ...row, height: "100%", flexShrink: 0, alignSelf: "stretch" }}>
      {(["minimize", "maximize", "close"] as const).map((action) => {
        const icon = action === "maximize" && maximized ? "restore" : action;
        const close = action === "close";
        return (
          <Caption
            key={action}
            action={action}
            style={{
              ...center,
              width: 46,
              height: "100%",
              color: glyph,
              hover: close ? { backgroundColor: "#c42b1c", color: "#ffffff" } : { backgroundColor: wash(0.07), color: C.text },
              active: close ? { backgroundColor: "#c42b1ce6" } : { backgroundColor: wash(0.04) },
            }}
          >
            {process.platform === "win32" ? (
              <text style={{ fontFamily: CAPTION_FONT, fontSize: 10, lineHeight: 10, color: glyph }}>{GLYPHS[icon]}</text>
            ) : (
              <Icon name={CAPTION_ICONS[icon]} size={16} color={glyph} />
            )}
          </Caption>
        );
      })}
    </div>
  );
}

// ---- Title bar and search -----------------------------------------------------

function TopBar() {
  const collapsed = useStore((s) => s.collapsed);
  const canBack = useStore((s) => s.back.length > 0);
  const canForward = useStore((s) => s.forward.length > 0);
  const sync = useStore((s) => s.sync);
  const session = useStore((s) => s.session);
  const brand = (collapsed ? SIZE.sidebarCollapsed : SIZE.sidebar) - 18;
  return (
    <div style={{ ...row, height: SIZE.top, flexShrink: 0, paddingLeft: 18 }}>
      <Caption action="drag" style={{ ...row, width: brand, height: SIZE.top, gap: 10, flexShrink: 0, overflow: "hidden" }}>
        <img src={LOGO} objectFit="contain" style={{ width: 26, height: 26 }} />
        {!collapsed && <text style={{ fontFamily: F.display, fontWeight: 900, fontSize: 16, color: C.text }}>Aurelia</text>}
      </Caption>
      <div style={{ ...row, gap: 4 }}>
        <IconButton icon="back" label="Back" onClick={goBack} style={canBack ? undefined : { opacity: 0.35 }} />
        <IconButton icon="forward" label="Forward" onClick={goForward} style={canForward ? undefined : { opacity: 0.35 }} />
      </div>
      <Caption action="drag" style={{ flexGrow: 1, height: SIZE.top, minWidth: 16 }} />
      <SearchBox />
      <Caption action="drag" style={{ flexGrow: 1, height: SIZE.top, minWidth: 16 }} />
      <div style={{ ...row, gap: 6 }}>
        {sync?.running && (
          <div role="button" onClick={() => go({ name: "settings" })} style={{ ...row, gap: 8, height: 30, paddingLeft: 12, paddingRight: 12, cursor: "pointer" }}>
            <Icon name="sync" size={14} color={C.muted} />
            <text style={{ fontSize: 12, color: C.muted }}>Syncing</text>
          </div>
        )}
        {sync?.error && !sync.running && (
          <div role="button" onClick={() => go({ name: "settings" })} style={{ ...row, gap: 8, height: 30, paddingLeft: 12, paddingRight: 12, cursor: "pointer" }}>
            <Icon name="alert" size={14} color={C.error} />
            <text style={{ fontSize: 12, color: C.error }}>Sync failed</text>
          </div>
        )}
        <div
          role="button"
          aria-label="Account and settings"
          onClick={() => go({ name: "settings" })}
          style={{ ...center, position: "relative", width: 32, height: 32, borderRadius: 16, marginLeft: 6, marginRight: 6, cursor: "pointer" }}
        >
          <Glass tint={TINTS.rose} radius={16} interactive />
          <text style={{ fontSize: 14, fontWeight: 800, color: ink(TINTS.rose, "tonal") }}>{(session?.username[0] ?? "A").toUpperCase()}</text>
        </div>
      </div>
      <CaptionButtons />
    </div>
  );
}

export const searchInput: { current: { id: number } | null } = { current: null };
const searchBox: { current: { id: number } | null } = { current: null };

function SearchBox() {
  const query = useStore((s) => s.query);
  const [focused, setFocused] = useState(false);
  return (
    <div
      ref={searchBox as never}
      style={{
        ...row,
        position: "relative",
        width: 460,
        flexShrink: 1,
        minWidth: 220,
        height: 40,
        gap: 10,
        paddingLeft: 14,
        paddingRight: 10,
        borderRadius: 20,
      }}
    >
      <Glass variant="panel" radius={20} />
      <FocusRing on={focused} radius={20} />
      <Icon name="search" size={16} color={C.muted} />
      <input
        ref={searchInput as never}
        value={query}
        placeholder="Search songs, albums, artists"
        onChange={(e) => set({ query: e.value ?? "", searchOpen: true })}
        onFocus={() => {
          setFocused(true);
          set({ searchOpen: true });
        }}
        onBlur={() => setFocused(false)}
        onSubmit={(e) => {
          const q = (e.value ?? "").trim();
          if (q) go({ name: "search", q });
        }}
        onKeyDown={(e) => {
          if (e.key === "escape") {
            set({ searchOpen: false });
            host().renderer.blur();
          }
        }}
        style={{ flexGrow: 1, minWidth: 0, height: 36, fontSize: 14, color: C.text, backgroundColor: CLEAR }}
      />
      {query ? (
        <IconButton icon="x" label="Clear search" size={26} iconSize={14} onClick={() => set({ query: "" })} />
      ) : (
        <div style={{ ...center, height: 22, paddingLeft: 7, paddingRight: 7, borderRadius: 7, backgroundColor: alpha(C.text, 0.07) }}>
          <text style={{ fontSize: 11, fontWeight: 600, color: C.muted }}>Ctrl K</text>
        </div>
      )}
    </div>
  );
}

const CLEAR = "#00000000";

/** A keyboard-focus ring over a glass field. Glass paints over its box's own
 * border, so the ring is a layer on top. */
function FocusRing({ on, radius: r }: { on: boolean; radius: number }) {
  if (!on) return null;
  return (
    <div
      style={{
        position: "absolute",
        left: 0,
        top: 0,
        right: 0,
        bottom: 0,
        borderRadius: r,
        borderWidth: 2,
        borderColor: alpha(C.primary, 0.6),
        pointerEvents: "none",
      }}
    />
  );
}

/** Results as you type, under the search box. */
function SearchResults() {
  const query = useStore((s) => s.query);
  const library = useStore((s) => s.library);
  const open = useStore((s) => s.searchOpen);
  const results = useMemo(() => (library && query.trim() ? library.search(query, 4) : null), [library, query]);
  if (!results || !open) return null;
  type Item = { key: string; title: string; sub: string; id?: string; tag?: string; round?: boolean; open: () => void };
  const items: Item[] = [
    ...results.artists.slice(0, 2).map((a) => {
      const cover = a.imageTag ? undefined : a.albums.find((x) => x.imageTag);
      return { key: a.id, title: a.name, sub: "Artist", id: cover?.id ?? a.id, tag: cover?.imageTag ?? a.imageTag, round: true, open: () => go({ name: "artist", id: a.id }) };
    }),
    ...results.albums.slice(0, 3).map((a) => ({ key: a.id, title: a.title, sub: `Album · ${a.artist}`, id: a.id, tag: a.imageTag, open: () => go({ name: "album", id: a.id }) })),
    ...results.songs.slice(0, 4).map((s) => ({
      key: s.id,
      title: s.title,
      sub: `Song · ${s.artist}`,
      id: s.albumId,
      tag: s.albumId ? library!.album.get(s.albumId)?.imageTag : undefined,
      open: () => {
        const album = s.albumId ? library!.album.get(s.albumId) : undefined;
        play(album ? album.songs.map((x) => x.id) : [s.id], album ? album.songs.findIndex((x) => x.id === s.id) : 0);
      },
    })),
  ];
  const box = searchBox.current ? host().renderer.getElementBounds(searchBox.current.id) : null;
  const left = box?.x ?? Math.max(SIZE.sidebar, (get().window.width - 460) / 2);
  const width = box?.width ?? 460;
  return (
    <div
      style={{
        ...column,
        position: "absolute",
        left,
        top: SIZE.top - 4,
        width,
        padding: 8,
        borderRadius: 18,
        pointerEvents: "auto",
      }}
    >
      <Glass variant="sheet" radius={18} />
      {items.length === 0 && <text style={{ fontSize: 13, color: C.muted, padding: 12 }}>{`No matches for “${query}”`}</text>}
      {items.map((item) => (
        <div
          key={item.key}
          role="button"
          onClick={() => {
            item.open();
            set({ searchOpen: false });
            host().renderer.blur();
          }}
          style={{ ...row, gap: 12, padding: 6, paddingLeft: 8, paddingRight: 8, borderRadius: 12, cursor: "pointer", hover: { backgroundColor: alpha(C.text, 0.06) } }}
        >
          <Artwork id={item.id} tag={item.tag} size={40} radius={7} round={item.round} />
          <div style={{ ...column, gap: 2, flexGrow: 1 }}>
            <text style={{ ...text.title(14), ...ellipsis }}>{item.title}</text>
            <text style={{ fontSize: 12.5, color: C.muted, ...ellipsis }}>{item.sub}</text>
          </div>
        </div>
      ))}
      {items.length > 0 && (
        <div style={{ ...row, gap: 16, paddingTop: 10, paddingLeft: 8, marginTop: 6, borderTopWidth: 1, borderColor: C.hairline }}>
          <text style={{ fontSize: 12, color: C.faint }}>Enter for all results</text>
          <text style={{ fontSize: 12, color: C.faint }}>Esc to close</text>
        </div>
      )}
    </div>
  );
}

// ---- Sidebar --------------------------------------------------------------------

function NavItem({ route, label, icon, count }: { route: Route; label: string; icon: IconName; count?: number }) {
  const current = useStore((s) => s.route.name === route.name);
  const collapsed = useStore((s) => s.collapsed);
  return (
    <div
      role="button"
      aria-label={label}
      aria-current={current}
      onClick={() => go(route)}
      style={{
        ...row,
        position: "relative",
        height: 40,
        gap: 14,
        paddingLeft: collapsed ? 0 : 14,
        paddingRight: 14,
        justifyContent: collapsed ? "center" : "flex-start",
        borderRadius: 20,
        cursor: "pointer",
        hover: current ? undefined : { backgroundColor: alpha(C.text, 0.05) },
      }}
    >
      {current && <Glass tint={TINTS.jelly} radius={20} />}
      <Icon name={icon} size={20} color={current ? ink(TINTS.jelly, "tonal") : C.muted} />
      {!collapsed && (
        <text style={{ fontSize: 14, fontWeight: 600, color: current ? ink(TINTS.jelly, "tonal") : C.muted, flexGrow: 1 }}>{label}</text>
      )}
      {!collapsed && count !== undefined && (
        <text style={{ fontSize: 12, color: current ? `${ink(TINTS.jelly, "tonal")}b3` : C.faint }}>{count.toLocaleString()}</text>
      )}
    </div>
  );
}

function SidebarLabel({ label, action }: { label: string; action?: ReactNode }) {
  const collapsed = useStore((s) => s.collapsed);
  if (collapsed) return <div style={{ height: 1, marginTop: 16, marginBottom: 10, marginLeft: 18, marginRight: 18, backgroundColor: C.hairline }} />;
  return (
    <div style={{ ...row, justifyContent: "space-between", paddingLeft: 12, paddingRight: 6, paddingTop: 22, paddingBottom: 8 }}>
      <text style={{ fontSize: 12, fontWeight: 600, color: C.faint }}>{label}</text>
      {action}
    </div>
  );
}

function Sidebar() {
  const library = useStore((s) => s.library);
  const playlists = useStore((s) => s.playlists);
  const collapsed = useStore((s) => s.collapsed);
  const route = useStore((s) => s.route);
  const favVersion = useStore((s) => s.favVersion);
  const favCount = useMemo(() => library?.favorites().length ?? 0, [library, favVersion]);
  return (
    <Motion
      initial={false}
      animate={{ width: collapsed ? SIZE.sidebarCollapsed : SIZE.sidebar }}
      transition={{ duration: motion.base, ease: motion.ease }}
      style={{ ...column, height: "100%", flexShrink: 0, paddingLeft: 10, paddingRight: 10, paddingTop: 2, paddingBottom: 12, overflow: "hidden" }}
    >
      <NavItem route={{ name: "home" }} label="Home" icon="home" />
      <SidebarLabel label="Library" />
      <NavItem route={{ name: "albums" }} label="Albums" icon="albums" count={library?.albums.length} />
      <NavItem route={{ name: "artists" }} label="Artists" icon="artists" count={library?.albumArtists().length} />
      <NavItem route={{ name: "songs" }} label="Songs" icon="songs" count={library?.songs.length} />
      <NavItem route={{ name: "favorites" }} label="Favorites" icon="heart" count={favCount} />
      <SidebarLabel
        label="Playlists"
        action={<IconButton icon="plus" label="New playlist" size={28} iconSize={16} onClick={() => set({ dialog: { kind: "newPlaylist", ids: [], name: "" } })} />}
      />
      <div style={{ ...column, flexGrow: 1, overflowY: "scroll", gap: 2 }}>
        {playlists.map((playlist) => {
          const current = route.name === "playlist" && route.id === playlist.id;
          return (
            <div
              key={playlist.id}
              role="button"
              aria-label={playlist.name}
              onClick={() => go({ name: "playlist", id: playlist.id })}
              style={{
                ...row,
                position: "relative",
                gap: 12,
                padding: 6,
                justifyContent: collapsed ? "center" : "flex-start",
                borderRadius: 16,
                cursor: "pointer",
                flexShrink: 0,
                hover: current ? undefined : { backgroundColor: alpha(C.text, 0.05) },
              }}
            >
              {current && <Glass tint={TINTS.jelly} radius={16} />}
              <Artwork id={playlist.id} tag={playlist.imageTag} size={44} radius={10} />
              {!collapsed && (
                <div style={{ ...column, gap: 2, minWidth: 0 }}>
                  <text style={{ fontSize: 14, fontWeight: 600, color: current ? ink(TINTS.jelly, "tonal") : C.text, ...ellipsis }}>
                    {playlist.name}
                  </text>
                  <text style={{ fontSize: 12, color: current ? `${ink(TINTS.jelly, "tonal")}b3` : C.muted }}>
                    {`${playlist.count.toLocaleString()} songs`}
                  </text>
                </div>
              )}
            </div>
          );
        })}
      </div>
      <div style={{ paddingTop: 8 }}>
        <NavItem route={{ name: "settings" }} label="Settings" icon="settings" />
        <div
          role="button"
          aria-label={collapsed ? "Expand the sidebar" : "Collapse the sidebar"}
          onClick={() => set((s) => ({ collapsed: !s.collapsed }))}
          style={{
            ...row,
            height: 40,
            gap: 14,
            paddingLeft: collapsed ? 0 : 14,
            justifyContent: collapsed ? "center" : "flex-start",
            borderRadius: 20,
            cursor: "pointer",
            hover: { backgroundColor: alpha(C.text, 0.05) },
          }}
        >
          <Icon name="sidebar" size={20} color={C.muted} />
          {!collapsed && <text style={{ fontSize: 14, fontWeight: 600, color: C.muted }}>Collapse</text>}
        </div>
      </div>
    </Motion>
  );
}

// ---- Page and side panel ----------------------------------------------------------

function Main() {
  const route = useStore((s) => s.route);
  let page: ReactNode;
  switch (route.name) {
    case "home":
      page = <Home />;
      break;
    case "albums":
      page = <Albums />;
      break;
    case "album":
      page = <AlbumPage id={route.id} />;
      break;
    case "artists":
      page = <Artists />;
      break;
    case "artist":
      page = <ArtistPage id={route.id} />;
      break;
    case "songs":
      page = <Songs />;
      break;
    case "favorites":
      page = <Favorites />;
      break;
    case "playlists":
      page = <Playlists />;
      break;
    case "playlist":
      page = <PlaylistPage id={route.id} />;
      break;
    case "search":
      page = <Search q={route.q} />;
      break;
    case "settings":
      page = <Settings />;
      break;
  }
  return (
    <div style={{ ...column, flexGrow: 1, minWidth: 0, position: "relative" }}>
      <Glass variant="panel" radius={radius.slab} plain />
      <div style={{ ...column, flexGrow: 1, minHeight: 0, borderRadius: radius.slab, overflow: "hidden" }}>
        <Motion
          key={JSON.stringify(route)}
          initial={{ opacity: 0, top: 6 }}
          animate={{ opacity: 1, top: 0 }}
          transition={{ duration: motion.base, ease: motion.ease }}
          style={{ ...column, flexGrow: 1, minHeight: 0, position: "relative" }}
        >
          {page}
        </Motion>
      </div>
    </div>
  );
}

function SidePanel() {
  const panel = useStore((s) => s.panel);
  const tint = useNowTint();
  return (
    <Motion
      initial={false}
      animate={{ width: panel ? SIZE.panel + SIZE.gutter : 0 }}
      transition={{ duration: motion.base, ease: motion.ease }}
      style={{ height: "100%", flexShrink: 0, overflow: "hidden" }}
    >
      <div style={{ ...column, position: "relative", width: SIZE.panel, height: "100%", marginLeft: SIZE.gutter }}>
        <Glass variant="panel" radius={radius.slab} />
        <div style={{ ...column, flexGrow: 1, minHeight: 0, borderRadius: radius.slab, overflow: "hidden" }}>
        <div style={{ ...row, gap: 4, paddingLeft: 12, paddingRight: 12, paddingTop: 12, paddingBottom: 8 }}>
          {(["queue", "lyrics"] as const).map((tab) => (
            <div
              key={tab}
              role="tab"
              aria-selected={panel === tab}
              onClick={() => setPanel(tab)}
              style={{
                ...center,
                position: "relative",
                height: 32,
                paddingLeft: 14,
                paddingRight: 14,
                borderRadius: 16,
                cursor: "pointer",
                hover: panel === tab ? undefined : { backgroundColor: alpha(C.text, 0.05) },
              }}
            >
              {panel === tab && <Glass tint={tint} radius={16} />}
              <text style={{ fontSize: 13, fontWeight: 600, color: panel === tab ? ink(tint, "tonal") : C.muted }}>
                {tab === "queue" ? "Queue" : "Lyrics"}
              </text>
            </div>
          ))}
          <div style={{ flexGrow: 1 }} />
          <IconButton icon="x" label="Close" size={32} iconSize={18} onClick={() => setPanel(null)} />
        </div>
        {panel === "queue" && (
          <>
            <NowCard />
            <Queue />
          </>
        )}
        {panel === "lyrics" && <Lyrics size="panel" />}
        </div>
      </div>
    </Motion>
  );
}

// ---- Overlays -----------------------------------------------------------------

const MENU_ROW = 36;

function MenuLayer() {
  const menu = useStore((s) => s.menu);
  const win = useStore((s) => s.window);
  return (
    <AnimatePresence>
      {menu && (
        <div key="menu" style={{ position: "absolute", left: 0, top: 0, width: "100%", height: "100%" }}>
          <div onClick={closeMenu} onAuxClick={closeMenu} style={{ position: "absolute", left: 0, top: 0, width: "100%", height: "100%", pointerEvents: "auto" }} />
          {(() => {
            const width = menu.width ?? 236;
            const height = menu.items.reduce((h, item) => h + (item === "-" ? 11 : MENU_ROW), 12);
            const x = Math.max(8, Math.min(menu.x, win.width - width - 8));
            const y = menu.y + height > win.height - 8 ? Math.max(8, menu.y - height) : menu.y;
            return (
              <Motion
                initial={{ opacity: 0, top: y - 4 }}
                animate={{ opacity: 1, top: y }}
                exit={{ opacity: 0 }}
                transition={{ duration: motion.fast, ease: motion.ease }}
                role="menu"
                style={{
                  ...column,
                  position: "absolute",
                  left: x,
                  width,
                  padding: 6,
                  borderRadius: 16,
                  pointerEvents: "auto",
                }}
              >
                <Glass variant="sheet" radius={16} />
                {menu.items.map((item, i) =>
                  item === "-" ? (
                    <div key={i} style={{ height: 1, marginTop: 5, marginBottom: 5, marginLeft: 8, marginRight: 8, backgroundColor: C.hairline }} />
                  ) : (
                    <div
                      key={i}
                      role="menuitem"
                      onClick={() => {
                        closeMenu();
                        item.run();
                      }}
                      style={{ ...row, height: MENU_ROW, gap: 12, paddingLeft: 10, paddingRight: 10, borderRadius: 10, cursor: "pointer", hover: { backgroundColor: alpha(C.text, 0.06) } }}
                    >
                      {item.icon ? <Icon name={item.icon} size={18} color={item.danger ? C.error : C.muted} /> : <div style={{ width: 18 }} />}
                      <text style={{ fontSize: 13.5, color: item.danger ? C.error : C.text, flexGrow: 1, ...ellipsis }}>{item.label}</text>
                      {item.hint && <text style={{ fontSize: 12, color: C.faint }}>{item.hint}</text>}
                    </div>
                  ),
                )}
              </Motion>
            );
          })()}
        </div>
      )}
    </AnimatePresence>
  );
}

function Toast() {
  const toast = useStore((s) => s.toast);
  return (
    <AnimatePresence>
      {toast && (
        <Motion
          key={toast.id}
          initial={{ opacity: 0, bottom: SIZE.player + 4 }}
          animate={{ opacity: 1, bottom: SIZE.player + 16 }}
          exit={{ opacity: 0 }}
          transition={{ duration: motion.base, ease: motion.ease }}
          style={{ ...row, position: "absolute", left: 0, right: 0, justifyContent: "center", pointerEvents: "none" }}
        >
          <div style={{ position: "relative", paddingLeft: 18, paddingRight: 18, paddingTop: 11, paddingBottom: 11, borderRadius: 20 }}>
            <Glass tint={TINTS.jelly} variant="filled" radius={20} />
            <text style={{ fontSize: 13, fontWeight: 600, color: ink(TINTS.jelly, "filled") }}>{toast.text}</text>
          </div>
        </Motion>
      )}
    </AnimatePresence>
  );
}

function Dialog() {
  const dialog = useStore((s) => s.dialog);
  const [name, setName] = useState("");
  useEffect(() => setName(dialog?.name ?? ""), [dialog]);
  if (!dialog) return null;
  const submit = () => {
    const value = name.trim();
    if (!value) return;
    set({ dialog: null });
    createPlaylist(value, dialog.ids);
  };
  return (
    <div
      style={{
        ...center,
        position: "absolute",
        left: 0,
        top: 0,
        width: "100%",
        height: "100%",
        backgroundColor: get().theme === "shore" ? "#0a1e1c33" : "#00000080",
        pointerEvents: "auto",
      }}
    >
      <Motion
        initial={{ opacity: 0, top: 8 }}
        animate={{ opacity: 1, top: 0 }}
        transition={{ duration: motion.base, ease: motion.ease }}
        style={{ ...column, width: 420, padding: 24, gap: 16, borderRadius: 24, position: "relative" }}
      >
        <Glass variant="sheet" radius={24} />
        <text style={{ fontSize: 20, fontWeight: 800, color: C.text }}>New playlist</text>
        {dialog.ids.length > 0 && <text style={text.body(13, C.muted)}>{`With ${dialog.ids.length === 1 ? "this song" : `these ${dialog.ids.length} songs`}`}</text>}
        <input
          autoFocus
          value={name}
          placeholder="Playlist name"
          onChange={(e) => setName(e.value ?? "")}
          onSubmit={submit}
          onKeyDown={(e) => e.key === "escape" && set({ dialog: null })}
          style={{ height: 44, paddingLeft: 14, paddingRight: 14, borderRadius: 14, fontSize: 14, color: C.text, backgroundColor: alpha(C.text, 0.06) }}
        />
        <div style={{ ...row, gap: 8, justifyContent: "flex-end" }}>
          <Button label="Cancel" onClick={() => set({ dialog: null })} />
          <Button label="Create" tone="filled" onClick={submit} />
        </div>
      </Motion>
    </div>
  );
}

// ---- Sign-in and first sync ---------------------------------------------------------

function Field({ icon, label, value, onChange, secure, placeholder, onSubmit, autoFocus }: {
  icon: IconName;
  label: string;
  value: string;
  onChange: (value: string) => void;
  secure?: boolean;
  placeholder: string;
  onSubmit: () => void;
  autoFocus?: boolean;
}) {
  const [focused, setFocused] = useState(false);
  return (
    <div style={{ ...column, gap: 8 }}>
      <text style={{ fontSize: 13, fontWeight: 600, color: C.muted }}>{label}</text>
      <div
        style={{
          ...row,
          position: "relative",
          height: 48,
          gap: 12,
          paddingLeft: 14,
          paddingRight: 14,
          borderRadius: 16,
        }}
      >
        <Glass variant="panel" radius={16} />
        <FocusRing on={focused} radius={16} />
        <Icon name={icon} size={18} color={focused ? C.primary : C.muted} />
        {createElement("input", {
          value,
          placeholder,
          secure,
          autoFocus,
          onChange: (e: EventPayload) => onChange(e.value ?? ""),
          onSubmit,
          onFocus: () => setFocused(true),
          onBlur: () => setFocused(false),
          style: { flexGrow: 1, minWidth: 0, height: 40, fontSize: 14, color: C.text, backgroundColor: CLEAR },
        })}
      </div>
    </div>
  );
}

/** Before the library: the ground alone, under the title bar. */
function Backdrop({ children }: { children: ReactNode }) {
  return (
    <div style={{ ...column, flexGrow: 1, position: "relative", overflow: "hidden" }}>
      <div style={{ ...row, height: SIZE.top, flexShrink: 0 }}>
        <Caption action="drag" style={{ flexGrow: 1, height: SIZE.top }} />
        <CaptionButtons />
      </div>
      <div style={{ ...center, flexGrow: 1, paddingBottom: SIZE.top }}>{children}</div>
    </div>
  );
}

function SignIn() {
  const login = useStore((s) => s.login);
  const [server, setServer] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const submit = () => !login.busy && signIn(server, username, password);
  return (
    <Backdrop>
      <Motion
        initial={{ opacity: 0, top: 14 }}
        animate={{ opacity: 1, top: 0 }}
        transition={{ duration: motion.slow, ease: motion.ease }}
        style={{ ...column, width: 420, gap: 18, position: "relative" }}
      >
        <div style={{ ...column, alignItems: "center", gap: 14, paddingBottom: 10 }}>
          <img src={LOGO} objectFit="contain" style={{ width: 64, height: 64 }} />
          <text style={{ ...text.display(40) }}>Aurelia</text>
          <text style={text.body(14, C.muted)}>Sign in to your Jellyfin server.</text>
        </div>
        <Field icon="server" label="Server" value={server} onChange={setServer} placeholder="music.example.com" onSubmit={submit} autoFocus />
        <Field icon="user" label="Username" value={username} onChange={setUsername} placeholder="Your Jellyfin username" onSubmit={submit} />
        <Field icon="lock" label="Password" value={password} onChange={setPassword} placeholder="Your Jellyfin password" secure onSubmit={submit} />
        {login.error && (
          <div style={{ ...row, gap: 10, padding: 12, borderRadius: 12, backgroundColor: alpha(C.pink, 0.12) }}>
            <Icon name="alert" size={18} color={C.pink} />
            <text style={{ fontSize: 13, color: C.pink, flexGrow: 1 }}>{login.error}</text>
          </div>
        )}
        <Button label={login.busy ? "Connecting…" : "Connect"} tone="filled" height={48} onClick={submit} style={{ justifyContent: "center", opacity: login.busy ? 0.7 : 1 }} />
      </Motion>
    </Backdrop>
  );
}

function Syncing() {
  const sync = useStore((s) => s.sync);
  const fraction = sync && sync.total ? Math.max(0.02, Math.min(1, sync.current / sync.total)) : 0;
  const [width, setWidth] = useState(0);
  useEffect(() => setWidth(Math.round(fraction * 440)), [fraction]);
  return (
    <Backdrop>
      <div style={{ ...column, alignItems: "center", width: 440, gap: 18 }}>
        <img src={LOGO} objectFit="contain" style={{ width: 64, height: 64 }} />
        <text style={text.display(32)}>{sync?.error ? "Sync didn't finish" : "Syncing your library"}</text>
        <text style={{ ...text.body(14, sync?.error ? C.pink : C.muted), textAlign: "center" }}>
          {sync?.error ?? (sync?.stage || "Preparing your library")}
        </text>
        {!sync?.error && (
          <div style={{ position: "relative", width: 440, height: 8, borderRadius: 4, backgroundColor: alpha(C.text, 0.1), overflow: "hidden" }}>
            <Motion
              initial={false}
              animate={{ width: sync?.total ? width : 110 }}
              transition={{ duration: 0.4, ease: motion.ease }}
              style={{ position: "absolute", left: 0, top: 0, height: 8, borderRadius: 4, backgroundColor: C.primary }}
            />
          </div>
        )}
        {sync && sync.total > 0 && !sync.error && (
          <text style={{ fontSize: 12, color: C.faint }}>{`${sync.current.toLocaleString()} of ${sync.total.toLocaleString()}`}</text>
        )}
        {sync?.error && (
          <div style={{ ...row, gap: 10 }}>
            <Button label="Log out" onClick={() => signOut()} />
            <Button label="Try again" icon="sync" tone="filled" onClick={() => runSync()} />
          </div>
        )}
      </div>
    </Backdrop>
  );
}

// ---- Root ---------------------------------------------------------------------------

/** The ground the glass lies on: still pools of the playing album's colors. */
function Ground() {
  const theme = useStore((s) => s.theme);
  const colors = useNowPalette();
  return createElement("aurelia-clouds", {
    colors,
    theme,
    still: true,
    style: { position: "absolute", left: 0, top: 0, right: 0, bottom: 0 },
  });
}

export function App() {
  const stage = useStore((s) => s.stage);
  const query = useStore((s) => s.query);
  // Every color comes from the theme, so a change re-renders everything.
  useStore((s) => s.theme);
  return (
    <div style={{ ...column, width: "100%", height: "100%", backgroundColor: C.frame, color: C.text, fontFamily: F.sans, userSelect: "none" }}>
      <Ground />
      {stage === "boot" && <Backdrop>{null}</Backdrop>}
      {stage === "login" && <SignIn />}
      {stage === "sync" && <Syncing />}
      {stage === "ready" && (
        <>
          <TopBar />
          <div style={{ ...row, flexGrow: 1, minHeight: 0, alignItems: "stretch", paddingRight: SIZE.gutter }}>
            <Sidebar />
            <Main />
            <SidePanel />
          </div>
          <PlayerBar />
          {query && <SearchResults />}
          <FullPlayer />
          <MenuLayer />
          <Dialog />
          <Toast />
        </>
      )}
    </div>
  );
}
