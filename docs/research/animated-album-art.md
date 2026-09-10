# Animated album artwork research

## Recommendation

Add an **optional, user-supplied short video loop** that appears only in the expanded Now Playing artwork. Keep Jellyfin's existing primary image as the poster, list/mini-player/lock-screen art, colour source, and universal fallback. A candidate initial product default is disabled until the person enables it; confirm that choice in product review. Add a small, optional Jellyfin plugin as the preferred asset index and authenticated delivery mechanism later. An unmodified Jellyfin server must return the same static experience it does today.

Apple is not a documented general-purpose animated-artwork source for Jellyfin playback. MusicKit is for access to a person's Apple Music subscription and requires their permission. Its current license terms restrict downloading, modifying, and synchronizing MusicKit Content, and say artwork from that API may not be used separately from MusicKit playback or playlist management ([MusicKit overview](https://developer.apple.com/documentation/musickit), [Apple Developer Program License Agreement, section 3.3.6.D](https://developer.apple.com/support/terms/apple-developer-program-license-agreement/)). Any Apple-supplied provider would need a separate rights and product review; this recommendation instead starts with artwork the server owner created, owns, or has permission to use.

The recommended first asset profile is a square, silent **H.264 AVC Baseline MP4** with `yuv420p`, a fast-start `moov` atom, 3–8 seconds, 24 or 30 fps, and a conservative 720 px / about 1.5 Mbps ceiling. Android guarantees H.264 Baseline decoding in an MP4 container and documents that streamed MP4 needs its `moov` atom before media data ([Android supported formats](https://developer.android.com/media/platform/supported-formats)). Validate the same asset on the supported iOS devices before accepting it. Do not make animated GIF, APNG, WebP, HEVC, AV1, alpha video, audio tracks, or a third-party streaming service the baseline format.

## Direct Apple sourcing

**Technically possible from an observed public web page; no documented retrieval API was found.** On 2026-09-09, an anonymous request for Apple Music's public US page for [*Heart On My Sleeve*](https://music.apple.com/us/album/heart-on-my-sleeve/1616728060) returned the page's embedded web-client payload with `videoArtwork.dictionary.motionDetailSquare` and `tallVideoArtwork.dictionary.motionDetailTall`. Each contained a static `previewFrame` plus a `video` URL; the page also contained an `amp-ambient-video` element whose source was an Apple `mvod.itunes.apple.com` HLS `.m3u8` manifest. A header-only request to that exact manifest returned `200`, `Content-Type: application/vnd.apple.mpegurl`, and a 14,999-byte manifest length. No playlist, segment, or video bytes were fetched.

That proves a public page can currently expose animated-cover HLS metadata for a particular album/storefront. It does **not** establish an API contract: the names above are page implementation fields, not Apple Music API schema, may differ by album/storefront/account or disappear, and have no documented stability, entitlement, caching, or redistribution semantics. This inspection found `videoArtwork`; it did not find a documented `editorialVideo` or `motionSquareVideo` album field.

Apple's documented catalog `Albums` resource names `attributes`, `relationships`, and `views` ([Albums](https://developer.apple.com/documentation/applemusicapi/albums)); the documented `Artwork` object supplies a static image URL, dimensions, and colour fields only ([Artwork](https://developer.apple.com/documentation/applemusicapi/artwork)). It exposes no animated-artwork/video URL field. An experimental web-page fetcher is therefore a technically plausible optional provider, with album matching, storefront selection, missing-art handling, and parser maintenance still to implement and test. Keep that provider separate from the native renderer and optional Jellyfin delivery contract, so losing access cannot break normal playback. Permission to reuse Apple's supplied artwork alongside Jellyfin audio is a separate unresolved issue; public URL accessibility alone does not settle it.

## Alternative sources

Prefer a loop supplied by its owner. Apple Music for Artists says an artist creates Motion Artwork and their distributor delivers it, which confirms an artist/label/distributor can be the legitimate source, but is not a public catalog-download API ([Create Motion Artwork](https://artists.apple.com/support/5544-create-motion-artwork)). Bandcamp is a useful manual-import path when the artist includes a loop in a purchased album download: its bonus items are delivered in the album ZIP, and its supported types include GIF, MP4, M4V, and MOV ([bonus downloads](https://get.bandcamp.help/en/articles/15263435-what-are-bonus-download-items-and-how-do-i-use-them), [supported types](https://get.bandcamp.help/en/articles/15263457-what-file-types-can-i-include-as-bonus-items-in-an-album-download)). It is not a general animated-cover catalog; import only an artist-provided file whose use is authorized.

| Service/catalog | What is documented | Practical decision |
| --- | --- | --- |
| Spotify Canvas | Canvas is a 3–8 second **track-level**, vertical loop which replaces album art in Spotify mobile Now Playing ([Spotify for Artists](https://support.spotify.com/us/artists/article/adding-a-canvas/?category=managing-your-music)). The Web API's documented `Get Track` response exposes album `images` as image URLs and no Canvas field; it also prohibits downloading Spotify content and requires visual content to retain its original form ([Get Track](https://developer.spotify.com/documentation/web-api/reference/get-track)). | **No documented Canvas fetch/reuse API.** Track-level vertical video also needs different matching and presentation from square album covers. A separately delivered source file from the artist is another possibility. |
| TIDAL | TIDAL's public developer quick start requires an OAuth bearer token, and its API reference exposes album cover-art relationships; this bounded review found no documented animated-cover/video-cover field or retrieval endpoint ([quick start](https://developer.tidal.com/documentation/api-sdk/api-sdk-quick-start), [API reference](https://tidal-music.github.io/tidal-api-reference/)). Its developer terms treat album art as TIDAL content and restrict content access, scraping, local hosting, and synchronization with visual media ([developer terms](https://developer.tidal.com/documentation/guidelines/guidelines-developer-terms)). | **Concrete unofficial integration lead.** The open-source `tidalapi` library documents `Album.video_cover` and `Album.video(dimensions)`, which constructs an MP4 video-cover URL ([library documentation](https://tidalapi.netlify.app/api#tidalapi.album.Album.video)). This makes TIDAL the strongest alternative technical candidate found, but it is not an official TIDAL API guarantee. Live retrieval, account requirements, catalog coverage, and permission to reuse the assets remain unverified. |
| Deezer | Deezer's developer album-reference URL currently requires a developer login; no public animated-album-art contract was found in this limited review ([album API](https://developers.deezer.com/api/album)). | **Unverified.** No concrete retrieval route identified in this review. |
| TheAudioDB, fanart.tv, Cover Art Archive | TheAudioDB's album example exposes image fields such as `strAlbumThumb`, `strAlbumThumbBack`, and `strAlbumCDart`, with no motion field ([API example](https://www.theaudiodb.com/docs_json)). fanart.tv documents image categories, while the Cover Art Archive documents an image/thumbnail API; these may improve the static poster but are not dedicated animated-artwork catalogs ([fanart.tv API](https://api.fanart.tv/), [CAA API](https://musicbrainz.org/doc/Cover_Art_Archive/API)). | **Useful primarily for static posters.** CAA permits GIF uploads, so this is not a claim that every stored asset is static; no dedicated motion-cover discovery contract or broad animation coverage was verified. |

The open-source [`m8tec/apple-music-animated-artworks`](https://github.com/m8tec/apple-music-animated-artworks) project is an implementation reference for Apple web-page scraping, not an independent asset source or a supported API.

### GAMDL compatibility

Current upstream GAMDL has no animated-cover support. Its Apple Music interface obtains `attributes.artwork.url`, derives the image URL, and writes/probes the resulting image bytes for `--save-cover`; it has no `videoArtwork`, `motion`, `editorialVideo`, or video-cover handling ([implementation](https://github.com/glomatico/gamdl/blob/478c3f26464b499f3a6b875c4ceb1d3c9fc2eefc/gamdl/interface/base.py#L219), [GAMDL README](https://github.com/glomatico/gamdl)). That makes it useful for a static poster only. The specialised `m8tec` tool above can find Apple page animation metadata, but remains an Apple scraper rather than an alternative catalog or entitlement path.

## What Aurelia has today

`aurelia-core` maps a Jellyfin `AlbumId`/`ImageTags` into a single `album_art_url` for `Song` and `Album` ([`models/music.rs`](../../crates/aurelia-core/src/models/music.rs), [`mapping.rs`](../../crates/aurelia-core/src/services/jellyfin/mapping.rs)). The URL is the conventional `/Items/{id}/Images/Primary` endpoint and includes a size/quality-friendly token query. Its image-only record is exported through UniFFI.

Android's player snapshot contains only `albumArtUrl` ([`PlayerController.kt`](../../apps/mobile/android/app/src/main/java/com/aurelia/app/player/PlayerController.kt)). `PlayerMorph` has one Coil `AsyncImagePainter` shared through the mini/full-player transition and `PlayerArtwork` draws it as a bitmap ([`PlayerMorph.kt`](../../apps/mobile/android/app/src/main/java/com/aurelia/app/ui/PlayerMorph.kt)). `PlayerScreen` separately derives its palette from a 200 px bitmap and creates a blurred static backdrop ([`PlayerScreen.kt`](../../apps/mobile/android/app/src/main/java/com/aurelia/app/ui/PlayerScreen.kt)); `optimizedArtworkUrl` only knows Jellyfin image URLs ([`ImageUrlUtils.kt`](../../apps/mobile/android/app/src/main/java/com/aurelia/app/utils/ImageUrlUtils.kt)). Coil 2.7 is the only artwork loader declared in the Android app ([`build.gradle.kts`](../../apps/mobile/android/app/build.gradle.kts)).

iOS has the same single-URL snapshot ([`AppModels.swift`](../../apps/mobile/ios/Aurelia/Models/AppModels.swift)). `AlbumArtView` fetches a `UIImage` from `ImageCache`, and `ImageCache` explicitly creates a thumbnail for index zero of an `ImageIO` source, so it is a static-image cache rather than a media cache ([`AlbumArtView.swift`](../../apps/mobile/ios/Aurelia/Components/AlbumArtView.swift), [`ImageCache.swift`](../../apps/mobile/ios/Aurelia/Services/ImageCache.swift)). The full player and mini player both use that view ([`PlayerView.swift`](../../apps/mobile/ios/Aurelia/Views/PlayerView.swift), [`MiniPlayerView.swift`](../../apps/mobile/ios/Aurelia/Components/MiniPlayerView.swift)). iOS Now Playing also intentionally installs a static `MPMediaItemArtwork` ([`AudioPlayerController.swift`](../../apps/mobile/ios/Aurelia/Player/AudioPlayerController.swift)).

This is a good boundary: animated art should not replace `albumArtUrl`, alter the Media3 audio controller/AVQueuePlayer, or enter the existing image cache.

Jellyfin's public item DTO exposes `ImageTags`, backdrop tags, and related image fields, but no animated-artwork field ([`BaseItemDto.cs`](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Dto/BaseItemDto.cs)). Therefore treating a normal primary image as an animated resource is neither a documented contract nor a safe compatibility path.

## Delivery choices

| Choice | What the owner supplies | Unmodified Jellyfin | Strengths | Costs / recommendation |
| --- | --- | --- | --- | --- |
| Client-local sidecar manifest | A configured HTTPS manifest and loop files, owned by the user or served from their NAS/reverse proxy | Fully usable; failed/missing manifest means static art | No server installation; fastest experiment | Needs a configuration screen, duplicate album matching, and its own authorization/storage rules. Use only as an opt-in preview or for users who cannot install plugins. |
| Jellyfin plugin endpoint | Plugin-managed mapping plus files under its application-data directory or an explicitly configured asset root | Fully usable; client sees an absent capability and never asks again that session | Stable album IDs, server-side authorization, single configuration point, no library pollution | Requires a separately maintained .NET plugin tied to Jellyfin's plugin API/version. **Preferred production route.** |
| Core Jellyfin change / arbitrary image type | A new server DTO/API image/media contract | Requires server upgrade | Could benefit every Jellyfin client | Large upstream design and compatibility effort. Do not make it a prerequisite for Aurelia. |

Jellyfin's official plugin template documents both `ControllerBase` for custom REST endpoints and `IResolverIgnoreRule` for plugin-owned files that should not become library media ([plugin template](https://github.com/jellyfin/jellyfin-plugin-template/blob/master/README.md)). Those are sufficient for a self-contained optional plugin; a fork of Jellyfin is not needed. Avoid putting `loop.mp4` beside music files unless the plugin also has an explicit ignore rule, since a media scan could otherwise expose it as a separate video item.

### Plugin contract

Use a versioned, authenticated namespace that is owned by the plugin, for example:

```text
GET /Aurelia/AnimatedArtwork/Capabilities
GET /Aurelia/AnimatedArtwork/Albums/{albumId}
GET /Aurelia/AnimatedArtwork/Assets/{assetId}
```

`Capabilities` returns `200` only when the installed plugin supports this contract, for example:

```json
{
  "schemaVersion": 1,
  "assetDelivery": "authenticated-relative-url",
  "offlineDownload": true
}
```

The album response returns `404` when the plugin is installed but no animation is assigned, and otherwise returns only a server-relative asset path and validation metadata:

```json
{
  "schemaVersion": 1,
  "albumId": "jellyfin-album-guid",
  "posterImageTag": "current-primary-image-tag",
  "asset": {
    "path": "/Aurelia/AnimatedArtwork/Assets/8c...",
    "mimeType": "video/mp4",
    "codec": "h264-avc-baseline",
    "width": 720,
    "height": 720,
    "durationMs": 6000,
    "byteLength": 984321,
    "sha256": "...",
    "etag": "..."
  }
}
```

The plugin must authorize every request using the same Jellyfin user context as the music request, check that the user can access the album/library, resolve `albumId` server-side, reject path traversal, and set `Content-Type`, `Content-Length`, `ETag`, and range support. Do not return an arbitrary external URL. A relative path keeps assets on the authenticated Jellyfin origin and prevents a compromised/mistyped manifest from turning Aurelia into a general remote-video client. Keep asset IDs opaque and keep authentication out of logs/cache keys where the platform allows it.

Capability detection is deliberately passive: after login, or on the first full-player expansion, request `Capabilities` with the normal Jellyfin authorization header. Cache a `404`/`405`/network failure as “unsupported” per normalized server URL for the session; cache a compatible `200` with its schema version. Never probe an endpoint from normal album grids or send this custom route to a server after it is known absent. A later settings refresh may clear that negative cache. This makes stock Jellyfin a first-class supported server, rather than an error case.

The core can expose a small `AnimatedArtwork` UniFFI record and an `AnimatedArtworkResolver` result, but it should resolve **on demand by album ID** and return `None` for unsupported/no mapping. Do not add loop URLs to every `Song`/`Album` library response or persist them in existing song-cache records during the first implementation. The player can request it when its full view becomes visible and use the response only while its album ID and primary-image tag still match.

### Sidecar manifest option

For an early experiment, let an owner configure one HTTPS manifest URL in Aurelia. The manifest uses the same versioned album-ID mapping and relative paths, but does not receive the Jellyfin access token and does not grant a remote host access to it. Require HTTPS, reject cross-origin asset paths by default, limit manifest/asset response sizes, and store the manifest's `ETag`. A manual mapping may offer MusicBrainz release IDs as a secondary key, but Jellyfin album IDs remain the only precise key within a server.

This route works without changing Jellyfin, but it is less convenient: a Jellyfin rescan/recreated server can change IDs, and the owner must keep their manifest synchronized. A candidate setting is “Animated artwork source: disabled / sidecar / installed Jellyfin extension”; choose its shipped default in product review.

## Native rendering design

Use two independent, muted video renderers. They are visual effects, not part of the music item's audio player, media session, queue, position reporting, or lock-screen metadata.

| Platform | Recommended implementation | Why |
| --- | --- | --- |
| Android | A remembered, separate `ExoPlayer` in the full-player artwork composable, rendered by an `AndroidView` hosting a controller-less `PlayerView` from aligned `media3-ui`. Select `texture_view`, not the default `surface_view`, for this small card. Set a one-item source, zero volume, `REPEAT_MODE_ONE`, and release it when the full player disappears. | `TextureView` is a regular view that supports alpha, arbitrary rotation, and complex clipping, which Aurelia needs for its rounded shared morph and Compose overlays; it costs more power than `SurfaceView`, so keep the loop small and full-player-only ([TextureView](https://developer.android.com/reference/android/view/TextureView), [Media3 surface guidance](https://developer.android.com/media/media3/ui/surface)). Media3 `PlayerView` renders video and attaches to an `ExoPlayer` ([official guide](https://developer.android.com/media/media3/ui/playerview)); `REPEAT_MODE_ONE` endlessly repeats the item ([playlist guide](https://developer.android.com/media/media3/exoplayer/playlists)). |
| iOS | First version: download the authenticated MP4 to the dedicated artwork-media cache with a normal `URLSessionDownloadTask`, validate it, then create the `AVQueuePlayer`/retained `AVPlayerLooper` from the local file. Display it with a controller-less `AVPlayerLayer` in `UIViewRepresentable`, owned by a coordinator and torn down with the view. | This uses public URLSession authentication and never relies on an undocumented `AVURLAsset` HTTP-header option or leaves a bearer credential in an asset URL. `AVPlayerLooper` is Apple's purpose-built API for a single looping item ([AVPlayerLooper](https://developer.apple.com/documentation/avfoundation/avplayerlooper)). `VideoPlayer` is viable for a standard player UI ([VideoPlayer](https://developer.apple.com/documentation/avkit/videoplayer)), but the custom layer preserves Aurelia's gestures, overlays, clipping, and no-controls design. |

Keep `PlayerMorph`/`AlbumArtView` as the static poster during the mini-to-full transition. After the full artwork container has reached its target geometry and the video renderer reports its first frame, crossfade the video over the poster (~150–200 ms). On failure, leave the poster unchanged. Keep the palette and blurred backdrop driven by the existing static primary image; repeatedly sampling/blurring video frames would add work with little visual benefit.

The Android renderer must be excluded from the `MediaSession`, use an asset with no audio track, and be built with `setAudioAttributes(..., false)` so it never handles audio focus; set volume to zero as a defensive fallback. Media3 documents that the boolean in `setAudioAttributes` controls audio-focus handling ([ExoPlayer.Builder](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer.Builder)). It must never reuse the music player's audio session. Render only while all of these are true: the expanded player is visible, its scene/activity is foreground-active, an eligible animation resolved for the current album, and the user has enabled animated artwork. A candidate policy is to tie playback to audio `isPlaying`; pause/freeze it when audio pauses and release it on backgrounding or player collapse. List cells, album detail, mini player, Android Auto, and external media controls remain static. **V1 intentionally leaves iOS Now Playing static; that is a scope choice, not an OS limit.** Apple documents `MPMediaItemAnimatedArtwork` for supplying a local video asset plus a static preview to system views such as the lock screen, with automatic static fallback under low power/data, thermal, and motion settings ([Providing animated artwork for media items](https://developer.apple.com/documentation/mediaplayer/providing-animated-artwork-for-media-items)). Revisit it only after the local artwork-media cache and iOS renderer are proven.

Honor both a candidate Aurelia setting (`Off`, `Wi-Fi only`, `Always`, or a simpler `Off`/`On` for v1) and accessibility/system motion preferences. SwiftUI exposes `accessibilityReduceMotion` and says UI should avoid large motion when it is true ([Apple documentation](https://developer.apple.com/documentation/swiftui/environmentvalues/accessibilityreducemotion)); iOS also exposes an animated-image preference in the same environment. On Android, use an explicit app preference and treat `ValueAnimator.areAnimatorsEnabled()` as an additional suppressor: Android documents it as the system-wide animator setting, including Battery Saver and duration-scale zero ([ValueAnimator](https://developer.android.com/reference/android/animation/ValueAnimator)). The fallback is always the static poster, never a blank rectangle or an error affordance.

## Network, cache, and offline policy

Start with the poster already visible, then download the authenticated MP4 only when the full player needs it. Reject an asset before playback if the manifest claims unsupported MIME/codec, non-square/extreme dimensions, zero/excessive duration, or a configured size limit; handle malformed video, timeouts, HTTP errors, and checksum mismatch by retaining static art and recording a bounded diagnostic. Do not prefetch while browsing the library. The plugin can support byte ranges for future direct streaming, but ranges are not a v1 client dependency.

For optional offline support, add a separate bounded `animated-artwork-cache`, keyed by `(server identity, album ID, asset ETag or SHA-256)`, with LRU eviction and a user-visible clear action. Never reuse iOS `ImageCache`: it transforms input into a decoded still and stores a `.bin` by URL. Android may use Media3 `CacheDataSource`, whose documented behavior reads cached data when available and writes upstream data on a miss ([CacheDataSource](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource)); iOS can atomically download the MP4 to Application Support and give `AVURLAsset` the local URL. Only add background HLS downloading if the contract deliberately gains HLS; Apple's `AVAssetDownloadURLSession` is the platform facility for persistent asset download tasks ([AVAssetDownloadURLSession](https://developer.apple.com/documentation/avfoundation/avassetdownloadurlsession)).

Use a provisional small quota (for example 50 MB), no automatic cellular prefetch, and an explicit “download animated artwork” action if offline is enabled. Validate `Content-Length` before allocating where supplied, write to a temporary file then rename atomically after hash verification, and delete partial/corrupt files. Invalidate on ETag/SHA change, static-poster tag mismatch, logout, cache clear, or a server change.

## Details to validate before implementation

- The exact Jellyfin server/plugin package version and authorization policy for the custom controller. The template requires plugin package versions to match the server and supports custom controllers, but the plugin must be tested against the target server release rather than assuming cross-version binary compatibility.
- Android's authenticated `DataSource` configuration and the exact Media3 UI artifact version compatible with the pinned 1.9.0 dependencies. iOS v1 avoids this question by downloading with `URLSession` first. A future iOS streaming implementation should use a documented `AVAssetResourceLoader` delegate with a custom scheme, which Apple exposes specifically to mediate asset resource requests ([AVAssetResourceLoader](https://developer.apple.com/documentation/avfoundation/avassetresourceloader)), and must prove range/cancellation behavior; do not assume arbitrary AVURLAsset header options are public API.
- Whether a plugin-managed asset root can remain outside every configured media-library root. If it cannot, implement and test the plugin's resolver ignore rule before accepting co-located loop files.
- The user-facing default and cache quota after measuring real devices. The format limits here are starting constraints, not performance evidence.

## Delivery plan

1. Define the JSON schema, asset validator, metrics/error taxonomy, settings, and static fallback behavior. Add fixtures for missing mapping, unsupported server, stale poster tag, HTTP/range failure, invalid video, reduced motion, backgrounding, and offline cache eviction.
2. Add the Android and iOS full-player-only renderers using a local fixture loop. Confirm no audio-session/Now Playing/mini-player regression, transition crossfade behavior, decoder release, and battery/network behavior on physical devices.
3. Add the on-demand resolver and sidecar manifest behind the disabled setting. Test a plain stock Jellyfin server first: it must make no visible request/error and retain current static art.
4. Build the optional plugin with server access checks, opaque relative asset URLs, range/ETag responses, and a configuration UI for user-owned assets. Test stock Jellyfin, plugin absent, plugin installed/no mapping, mapping added/removed, restricted library access, and server/plugin upgrades.
5. Only then offer explicit offline downloads and measure actual decoder, cache, and bandwidth use before selecting a shipped quota.

The principal risks are server-plugin version coupling, video decoder/battery cost, and rights to the visual assets. The proposed capability endpoint, static poster-first renderer, narrow visibility lifecycle, user/motion controls, and hard static fallback contain those risks without changing the behavior of ordinary Jellyfin installations.
