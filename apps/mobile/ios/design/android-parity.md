# Android and iOS parity

This pass brings the iOS discovery, library, search, song actions, and player artwork closer to the current Android app. Navigation, menus, typography, and playback remain native SwiftUI and AVFoundation.

| Area | iOS behavior |
| --- | --- |
| Home | Listening mixes, shuffle all, surprise me, recently added albums, rediscovery, genre playback, refresh, and empty/error states. Mix titles describe their contents. |
| Library | Albums, artists, playlists, favorites, text filtering, and title/artist/recent sorting. All credited artists are included in the artist directory. |
| Search | The shared Rust search index ranks songs, albums, and artists. A top result and category filters support both quick playback and navigation. Index construction and queries run away from the main actor; stale queries and profile results are discarded. |
| Song actions | Visible menus and long press offer Play Next, Add to Queue, Add to Playlist, favorites, album navigation, and a choice of credited artists. Playlist selection supports creating a playlist with the selected song. |
| Details | Album artwork, artist navigation, duration/count, disc grouping, play/shuffle, and song menus. Artist pages include a collapsible biography, albums, and an expandable song list. |
| Playback | Existing queue, shuffle/repeat, seeking, synced and word-level lyrics, visualizer, lock-screen integration, and mini-player expansion are retained. Empty-queue insertion prepares a track without autoplay. Duplicate queue/playlist occurrences have distinct UI identities. |
| Artwork | Optional Jellyfin artwork loops play through a separate muted AVQueuePlayer. Authenticated downloads are checksum-verified and cached within a size limit. A small blurred frame from the same video decoder supplies the backdrop. Static covers supply gently moving colors. Motion pauses with playback and in the background, and respects Reduce Motion. |
| Profiles | Existing profile switching and sign-in remain available. Library/search results and favorite state follow the active profile. |

The iPhone uses four primary tabs: Home, Library, Search, and Settings. The iPad retains sidebar navigation and wider detail/player layouts. Platform-specific Android controls such as Material You and Android performance-debug switches are not copied into iOS.

## Validation

Build the generated Rust framework and Swift bindings on macOS:

```sh
./apps/mobile/ios/build-rust.sh
(cd apps/mobile/ios/AureliaCore && swift test)
xcodebuild test -workspace apps/mobile/ios/Aurelia.xcworkspace \
  -scheme Aurelia -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

The native test target covers collaborator ID alignment, adding a track to an empty queue, and preserving duplicate queue entries when inserting Play Next. Swift package tests exercise ranked results, Unicode normalization, and independent category limits through the actual UniFFI bindings. CI runs both suites before archiving the iOS app.

Live Device Hub checks use a separate local test profile with short audio tracks, timestamped lyrics, a one-second artwork test pattern, and in-memory playlists. Test-pattern artwork is not shipped as an app asset.

Verified on September 11, 2026: regenerated Rust frameworks and bindings, three Swift package tests, four native simulator tests, and an unsigned Release archive all passed. Live iPhone checks covered discovery, filtered search and artist navigation, authenticated artwork loops and their backdrop, creating a playlist from a song menu and opening its saved song, and a natural transition into a Play Next song with lyrics remaining open and updating. The iPad discovery layout was also inspected in Device Hub. Library category links use value-based navigation consistently with their detail links, avoiding a failed second push when mixing navigation styles.

Physical-device audio routing, interruptions, lock-screen behavior, and sustained battery use still need an iPhone check. Simulator tests do not establish those properties.
