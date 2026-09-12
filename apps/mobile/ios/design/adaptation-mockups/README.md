# Aurelia iOS and iPadOS adaptation concepts

September 11, 2026. Approved visual concepts, generated with the built-in imagegen tool. Their layout direction is now implemented in the native SwiftUI app. The PNGs remain illustrative mockups, not screenshots.

## Direction

Keep Android's rounded, expressive headings, artwork-led colors, compact listening mixes, album shelves, and immersive artist imagery. Adapt navigation, spacing, controls, and layout to the available Apple device window, rather than stretching phone content to fill a tablet.

- `ipad-discovery.png`: persistent sidebar, two compact mix cards, a five-column album shelf, rediscovery beside genres, and a persistent bottom player.
- `ipad-player.png`: bounded artwork and transport on the left, lyrics or queue on the right. Expanding the player replaces the browsing player rather than duplicating controls.
- `iphone-home-artist.png`: compact discovery cards, immersive artist header, shared album shelves, three primary tabs and a separate mini-player. Account/settings move into the profile menu.

The generated logo, music artwork, artist examples, decorative mix descriptions, and typography are illustrative. Preserve the real app identity and server metadata in implementation; do not add generated slogans, a new logo, or new recommendation capabilities. The duplicate artist overflow buttons in the phone concept should become one native toolbar menu. The design does not depend on those image-generation details.

## Adaptation rules

On a wide iPad window, use a roughly 200–240pt sidebar and responsive album columns with covers around 150–190pt. In portrait or a narrower window, reduce the number of columns and collapse the sidebar. At phone-like widths, use the compact navigation and single-column screens. Size by available space, not device name alone.

The full player uses two columns only when both remain comfortably readable. Otherwise show artwork or lyrics in a single panel with reachable transport controls below. Retain word-synced lyrics, optional artwork loops, queue actions, and existing playback behavior.

Use native menus/popovers, keyboard navigation, pointer feedback, and drag/reorder affordances on iPad. Keep content opaque and legible; reserve translucency for navigation and controls. Maintain at least 44pt touch targets and let Dynamic Type increase row height rather than clipping text. Light mode should use warm neutral surfaces and the same hierarchy, not a separate layout.

## Generation prompt set

The three prompts specified high-fidelity, flat native UI concept boards with no device frames or perspective:

1. **iPad discovery:** landscape 4:3; charcoal, lavender and muted plum; Android section names and rounded headings; 210pt grouped sidebar; two horizontal mix cards; five distinct album covers; rediscovery and genres side by side; continuous 78pt player across the content area.
2. **iPad player:** landscape 4:3; immersive two-column player; large bounded ocean-sunset cover, metadata, timeline and transport at left; Lyrics/Queue segmented control and readable synced lyrics at right; artwork-derived navy/plum backdrop; no sidebar or duplicate mini-player.
3. **iPhone home and artist:** two complete 390:844-style screens; same dark palette and album artwork; compact mix card and discovery sections; immersive microphone artist image with fading header; album shelves and compact song rows; 55pt mini-player above Home/Search/Library tabs; native iOS status bars, symbols and typography.

## Implementation and validation

The implementation uses actual Liquid Glass APIs on iOS 26 and later, with material and bordered-control fallbacks on iOS 18–25. Wide windows use a native navigation sidebar and persistent playback bar. Compact windows use Home, Search, and Library tabs with a native bottom accessory on iOS 26. Account and settings live in the profile menu. Home uses compact mix cards, responsive album columns, and paired rediscovery/genre sections; artist pages use a large image header.

The full player uses the redesigned responsive layout, with artwork and controls beside lyrics or queue when space permits. The mini-player retains its original design: a bounded glass capsule on iPad with transport controls, centered song identity, lyrics, queue, and favorite actions; the compact bar includes previous, play/pause, and next controls. Browsing uses a sidebar at 760pt and above, with compact navigation at accessibility Dynamic Type sizes.

Album pages use a cover-derived backdrop extending behind the status bar and, on iOS 26, behind the native sidebar using `backgroundExtensionEffect()`. They include a bounded square cover, prominent album identity, native glass playback controls, and a flat disc-grouped track list. Wide windows place the summary beside the tracks. The mini-player floats over the continuous browsing background, with space reserved for scrolling content and sidebar account controls. Album actions support adding the full ordered album to the queue or a playlist. Artist images extend behind the status bar and native back button, with toolbar contrast adapting as the image scrolls away.

Validated on iOS 26.5 iPhone 17 Pro and iPad Pro 13-inch simulators with a local Jellyfin fixture: home mixes, mini-player opening and transport, artist playback, light/dark artist rendering, full-player lyrics and queue, and iPad portrait/landscape rotation. Four native playback/navigation tests and three Swift package tests passed. Simulator builds and an unsigned arm64 device archive passed; the IPA was checked for its Payload structure, CRC integrity, iPhone/iPad device families, and iOS 18 minimum version.

The iPad genre section shows up to nine tiles in a three-column grid. Windows narrower than the sidebar breakpoint explicitly use compact size-class behavior for bottom tabs, mini-player, and browsing layouts. A live iPad window was resized from full screen to a narrow floating window and checked with playback active; the compact bottom tabs and mini-player remained usable.

On iOS 26, the compact mini-player leaves its glass surface, capsule shape, and outer width to the native tab accessory. It must not add another glass background inside that surface. Its content fits the accessory's height, and each transport button has a 44pt target. Standalone compact players retain their own glass capsule, with a material fallback on older systems. Verified the iPhone accessory in light and dark appearance, over scrolling content, with previous/next, play/pause, and opening and dismissing the full player; the native tests and release archive passed.

Physical-device behavior, pre-iOS-26 rendering, accessibility-size runtime layouts, and the full range of Stage Manager window sizes still need hands-on validation. Fixture artwork and test metadata are external test data, not bundled production assets.
