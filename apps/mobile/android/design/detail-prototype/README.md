# Artist and album detail mockups

Throwaway visual exploration, September 10, 2026. The HTML has no server connection, persistence, or real playback. Sora Vale and the library are fictional; photography is illustrative. The selected direction is now implemented in the native Android artist and album detail screens.

Open `index.html` directly in a browser. Or, from the repository root:

```sh
node apps/mobile/android/design/detail-prototype/serve.mjs
```

Then open http://127.0.0.1:8765/design/detail-prototype/index.html?variant=A.

## Directions

- **A · Immersive:** full-width artist artwork, home-style album shelf, generous album cover.
- **B · Catalog:** compact artist identity, Albums / Songs / About tabs, horizontal album header and more visible tracks.
- **C · Soft cards:** contained artist identity, album spotlight, album listening card and grouped track surface.

Use the floating switcher or left/right arrow keys. Each phone scrolls independently. Light theme and missing-artwork toggles exercise alternate appearances. Track selection, play/pause, skip, shuffle, and menus update mock state. Artist/album links connect the two examples. Other albums and navigation destinations are illustrative. The mock artist Songs view includes eight sample songs, rather than the full fictional 27-song library.

Selected direction: A for both artist and album pages. Remove the “In your library” label above the artist name. The user prefers direct headings without decorative subtitle labels. Native Compose implementation is authorized.

## Relationship to the Android app

Uses the existing Google Sans Flex asset, wide rounded titles, fallback Material palette, 20px content gutters, rounded album artwork, home-style shelves, and grouped player controls. The 64px miniplayer and 72px navigation content are approximations of the existing Compose components. Artwork glow is static. This HTML is solely a design artifact; it is not a proposed WebView or app architecture.

Content is based on fields available in `ArtistDetailScreen.kt` and `AlbumDetailScreen.kt`: name, image, artist biography, songs, album grouping, and duration. No streaming popularity counts, following model, or recommendation service is assumed. Production implementation must retain disc grouping, context actions, loading/empty states, and missing metadata handling. These mockups focus on the populated single-disc layout; native font scaling, TalkBack, motion, and device insets still require Compose validation.

## Assets

- `assets/artist.jpg`: illustrative stage microphone, https://images.unsplash.com/photo-1516280440614-37939bbacd81
- `assets/album.jpg`: illustrative sunset, https://images.unsplash.com/photo-1475924156734-496f6cac6ec1
- Other covers use CSS artwork. Fonts reference the existing Android asset.
- `direction-A.png`, `direction-B.png`, `direction-C.png`: static comparison boards captured from the prototype.
- `native-artist.png`, `native-album.png`: screenshots of the implemented Compose screens in the emulator, with the existing miniplayer visible.

## Verification

Rendered and visually inspected all three directions in Chromium. Exercised variant switching, artist tabs, track selection, play/pause, track menu and mock queue, light theme, and missing artwork. Checked the 390px viewport for horizontal document overflow. No browser script errors. Native validation: `ktlintCheck`, `testDebugUnitTest` (38 tests), `assembleDebug`, and `assembleDebugAndroidTest` passed. Six emulator tests passed, including the four new detail-screen checks and the existing library navigation checks. Native layouts were visually inspected with the sample artwork, in light/dark mode and at 150% text size. An additional manual scroll check could not be completed: the host emulator crashed in its rendering thread, including after changing the software rendering mode. The six automated emulator tests completed successfully. Rust and iOS suites were not run for this Android-only change.

Keep this prototype out of production. The selected direction has been implemented in native Compose. Archive the exploration on a throwaway branch when git publication is authorized.
