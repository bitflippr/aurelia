# Player background motion

Investigated September 11, 2026.

Apple's WWDC26 session demonstrates an Apple Music-inspired podcast player: blurred cover art, a Metal layer shader, two successive noise samples, and a timeline. The second sample uses the first to change its coordinates, producing flowing color regions. The published sample changes animation speed with playback state. It does not measure audio amplitude or beats. This establishes a useful public technique, not Apple Music's private implementation. [Session and downloadable sample](https://developer.apple.com/videos/play/wwdc2026/322/), [sample project](https://developer.apple.com/documentation/swiftui/composing-advanced-graphics-effects-with-swiftui).

There is no evidence in these sources that Apple Music's background follows bass or loudness. Apparent pulsing can come from continuous color motion. Audio analysis should therefore be a separate design decision, rather than an assumed requirement for matching this appearance.

The iOS simulator cannot provide a direct inspection of Music here: Apple DTS states that the Music app is not installed in the simulator. A recording from a physical device can reveal visible motion and pause behavior, but would not establish its underlying algorithm. [Apple DTS discussion](https://developer.apple.com/forums/thread/818541).

## Aurelia implementation

`PlayerArtworkBackdrop.swift` softens the complete cover once at thumbnail resolution. `PlayerArtworkWarp.metal` adapts the sample's domain warp, with a smaller noise texture, bounded image sampling, a slower timeline, and a dark overlay for readable controls. The source and texture retain Apple's sample license in `Aurelia/Shaders/AppleSampleLicense.txt`.

The effect runs at a maximum of 20 updates per second while playing. Pausing, backgrounding the app, or enabling Reduce Motion freezes the timeline. Resuming continues from the frozen phase. Missing artwork uses a muted fallback and clears the previous album's colors. Animated covers continue to supply their own blurred video frames.

The player layout changes are independent: smaller mini-player artwork, a plain iPad play button, a centered closed-panel layout, and individually dismissible lyrics and queue panels.

The player owns the artwork border. `AlbumArtView` accepts the player's corner radius and disables its own border there; the wrapper uses the same continuous rounded shape for clipping and its single inset stroke. This fixes the overlapping outlines caused by combining the reusable image's scaled radius with the player's fixed radii. The swipe offset applies to the entire decorated artwork so its border follows it.

The border correction passed the simulator Debug build and iOS Release archive. The updated simulator app installed successfully, but screenshot and accessibility requests timed out, so a fresh visual check of that correction remains pending.

## Verification limits

The final simulator Debug build and arm64 iOS Release archive passed with the Metal shader, texture, and license included. The four native playback/navigation tests passed after the panel changes, before adding the shader. The mini-player's smaller artwork was visually checked on iPhone. Rust and Android suites were not rerun for these native UI changes.

After the iPad simulator became available, the player was inspected in portrait and landscape with static artwork and animated test artwork. This exposed excess empty space beneath the closed player in portrait; the player column now centers vertically within the available height. Paused lyrics also retained an outdated scroll offset after rotation; they now recenter the active line when the panel size changes. Both orientations were checked again with the same paused lyric. The corrected Debug build and Release archive passed.

Verified on iPad: plain play/pause controls, closed-panel layout, expanded lyrics in both orientations, queue layout, closing lyrics with the close button, and closing the queue by tapping its active button. Paused background captures had zero pixel difference in an unobstructed region. Captures during the same song showed changing color shapes, and advancing to another album changed the palette. The animated-artwork and visualizer settings were disabled in the simulator test profile to isolate the static-cover shader. This is functional and visual verification, not a sustained GPU or battery benchmark.
