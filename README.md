# Aurelia

Aurelia is a native music client for Jellyfin.

- Android is built with Kotlin, Jetpack Compose, and Media3.
- iOS is built with SwiftUI and AVFoundation.
- The desktop app's interface is React/TypeScript rendered natively by [GPUIX](https://github.com/bitflippr/gpuix) on GPUI; its runtime is Rust.
- Shared Jellyfin, library, cache, and lyrics behavior lives in Rust. Mobile uses it through UniFFI; desktop links it directly.

## Repository layout

```text
apps/mobile/android/      Android application
apps/mobile/ios/          iOS application and Swift package
apps/desktop/             Desktop app: GPUIX interface (ui/), Rust runtime (src/), renderer extension (native/)
crates/aurelia-core/      Shared domain, persistence, and Jellyfin logic
crates/aurelia-lyrics/    Lyrics parsing and models
crates/uniffi-bindgen/    Mobile binding-generation CLI wrapper
plugins/jellyfin-animated-artwork/  Optional server plugin for animated album artwork
vendor/gpuix/             GPUIX fork shared with Slate (submodule, `aurelia` branch)
```

## Quick start

Enter the Nix development shell when using NixOS:

```bash
nix develop
```

Build and test Android:

```bash
cd apps/mobile/android
./gradlew testDebugUnitTest assembleDebug
```

Build the Rust core and run its tests:

```bash
cargo test --workspace
```

Run the desktop app (requires [Bun](https://bun.sh)):

```bash
git submodule update --init vendor/gpuix
bun install
bun run desktop:native   # build the renderer with Aurelia's runtime
bun run desktop          # start; edits reload the interface or rebuild native code
```

The desktop app signs in to Jellyfin, syncs a profile-specific library cache,
and browses albums, artists, songs, favorites and playlists. It streams audio
through Rodio with gapless transitions, keeps an editable queue, shows synced
lyrics, reports playback to Jellyfin, and publishes metadata and transport
controls through the operating system's media session.

iOS builds require macOS with Xcode:

```bash
./apps/mobile/ios/build-rust.sh
open apps/mobile/ios/Aurelia.xcworkspace
```

See [docs/BUILDING.md](docs/BUILDING.md) and [docs/TESTING.md](docs/TESTING.md) for the complete workflows.

The optional [Animated Album Artwork plugin](plugins/jellyfin-animated-artwork/README.md)
provides animated covers in Jellyfin Web and an authenticated MP4 artwork API used
by Aurelia’s Android album and player views.
