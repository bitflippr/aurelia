# Animated Album Artwork for Jellyfin

A small Jellyfin **12.0.0 / .NET 10** plugin. It automatically discovers artwork beside each album's tracks, serves GIF covers through Jellyfin's normal image URLs, and exposes the original MP4s through an authenticated API for Aurelia.

## Album files

```text
Artist/Album/
  01. Track.flac
  cover.jpg
  animated-cover.mp4        # square animation for API clients
  animated-cover-tall.mp4   # optional tall animation
  animated-cover.gif        # looping cover for Jellyfin Web
```

There is no central artwork directory, index, library-root setting, or album-title matching. The plugin uses the album directory already known to Jellyfin. New files are discovered on the next artwork request; changed files invalidate cached metadata. The plugin probes media with Jellyfin's existing media encoder and caches dimensions, duration and checksums. It never changes audio, static covers, or Jellyfin's image metadata.

The existing library now has 53 MP4s and 28 GIFs beside the tracks in 28 album folders. To place future downloads using the downloader's manifest:

```sh
node scripts/prepare-artwork.mjs /home/marshall/Music/animated-artwork
```

The script verifies source checksums, creates the MP4 sidecars and generates 480×480 GIFs at 12 fps. It refuses to overwrite different existing sidecars. The manifest is only an input to this one-time preparation step; the plugin does not read it. FFmpeg and Node.js are needed for preparation.

Downloads must include the entire HLS timeline. `scripts/full-artwork.mjs` fetches Apple's complete underlying MP4 directly and checks its decoded duration against every playlist segment. This avoids an FFmpeg remote HLS byte-range failure that can silently save only the first segment. `node --test scripts/full-artwork.test.mjs` covers complete and truncated downloads using a real multi-segment fixture.

To repair downloads made with the earlier downloader, `node scripts/repair-artwork.mjs DOWNLOADED_ARTWORK_DIRECTORY` stages complete videos and regenerated GIFs, validates their durations and checksums, then replaces only files matching the original manifests. It retains backups and writes `full-loop-repair-report.json`. The corrected downloader is also installed in the existing download directory's `bin/`.

## NixOS

The host integration lives in `~/nix-config`:

- `pkgs/jellyfin-animated-artwork/`: vendored plugin source, pinned NuGet dependencies, and a .NET 10 Nix derivation.
- `modules/config/nixpkgs.nix`: exposes `pkgs.local.jellyfin-animated-artwork`.
- `hosts/polaris/modules/services/media.nix`: links the Nix-built DLL into Jellyfin's plugin directory when its service starts. Other installed plugins are preserved.

Activate it through the normal NixOS rebuild/switch workflow. No standalone installer, extra artwork mount, or additional path configuration is needed. The active rebuild was not interrupted and no production service restart was performed during development.

The source snapshot in nix-config is intentionally self-contained so system builds do not depend on an uncommitted checkout elsewhere in the home directory. When updating the plugin, synchronize its `.cs`, `.csproj`, and configuration HTML files into that package's `src/` and update NuGet pins if dependencies change.

## Build and checks

```sh
dotnet test tests -c Release
dotnet build Jellyfin.Plugin.AnimatedArtwork -c Release -t:PackagePlugin
JELLYFIN_BINARY=/path/to/jellyfin node scripts/smoke-test.mjs
```

The smoke test launches a separate Jellyfin instance with synthetic music and its own database, port and base URL. It checks automatic sidecar discovery, that sidecars do not become music tracks, album/track mapping, GIF image responses, static fallback, authentication, restricted-library access, HEAD/ranges/ETags, and the compatibility toggle. It refuses to configure an already-running server. No graphical browser or native-device tests were run here.

With Podman instead of a local .NET SDK, prefix a build/test command with:

```sh
podman run --rm --userns=keep-id -v "$PWD:/src" -w /src mcr.microsoft.com/dotnet/sdk:10.0
```

The optional ZIP for other hosts is `artifacts/AnimatedArtwork_0.1.0.0.zip`. This plugin is compiled for Jellyfin 12.0.0; other server ABI versions need a matching build.

## API for Aurelia

Use Jellyfin's normal `Authorization: MediaBrowser ... Token="..."` header on metadata and media requests. Jellyfin 12 may disable legacy token headers and query parameters. No separate login is needed.

| Request | Response |
|---|---|
| `GET /AnimatedArtwork` | `ApiVersion: 1` and supported formats |
| `GET /AnimatedArtwork/Items/{albumOrTrackId}` | Album ID and available variant metadata |
| `GET` / `HEAD /AnimatedArtwork/Items/{albumOrTrackId}/square` | `animated-cover.mp4` |
| `GET` / `HEAD /AnimatedArtwork/Items/{albumOrTrackId}/tall` | `animated-cover-tall.mp4`, if present |
| `GET` / `HEAD /AnimatedArtwork/Items/{albumOrTrackId}/gif` | `animated-cover.gif`, if present |

Metadata contains `ApiVersion`, `ItemId`, `AlbumId`, `Square`, and optional `Tall`/`Gif`. Each variant has `Url`, `ContentType`, `Sha256`, `Bytes`, `Width`, `Height`, and `Duration` in seconds. The returned URL includes Jellyfin's configured base path; resolve it against the server origin and send the authentication header again. It contains neither a token nor a filesystem path.

Authentication failures return `401`. Missing plugins, unknown/inaccessible items, absent animations and unknown variants return `404`. Media responses support byte ranges, HEAD and conditional ETags with private caching. Standard image routes retain Jellyfin's existing anonymous-image behavior and use a one-hour public cache lifetime.

Aurelia's Android app requests this metadata for the expanded player, its blurred background, and album detail artwork, then loops the square MP4 silently using Media3. The background crops the video to fill the screen and retains the existing blur, tint and gradient overlays. It keeps static artwork visible until the first video frame and falls back to it on errors or when system animations are disabled. Artwork video is released when the screen becomes inactive, the player collapses, or lyrics replace the cover. Music playback and audio focus remain with the existing music controller.

Metadata reads are shared by album and session, including missing-artwork results, so screen recreation does not refetch them. MP4 data uses a 128 MiB LRU cache keyed by server, account and checksum. Session tokens travel in the authorization header; media redirects are disabled. Changing or restarting the session refreshes the metadata cache. Thumbnails, mini-player art and palette extraction request `animated=false` to keep those images static. iOS rendering has not been added; the shared Rust API and generated mobile bindings are available.

## Web behavior

For Primary image index 0, Jellyfin Web receives the album's GIF at its existing image URL. No custom web build or JavaScript injection is required. The filter also handles track images and indexed/legacy image routes. Missing GIFs, other image types, `animated=false`, or a disabled compatibility setting use Jellyfin's normal image action.

Clear old image caches once after enabling the plugin: the original image tags are preserved, so already-cached static covers cannot be invalidated by the plugin. GIFs are less efficient than MP4s and loop continuously. The plugin setting can disable them globally; GIFs do not automatically follow individual browser motion preferences. Native notification, lock-screen and older-client behavior can differ. See [compatibility research](../../docs/research/jellyfin-animated-artwork-plugin.md).

Artwork files must be ordinary files, at most 64 MiB each, containing a loop of at most 90 seconds. Sidecar symlinks are rejected. Metadata caching is bounded and detects file replacement. Disabling/removing the plugin restores normal images; original covers need no restoration.
