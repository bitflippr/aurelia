# Aurelia

Aurelia is a native music client for Jellyfin.

- Android is built with Kotlin, Jetpack Compose, and Media3.
- iOS is built with SwiftUI and AVFoundation.
- The desktop prototype is built in Rust with mainline GPUI and Aurelia-owned controls.
- Shared Jellyfin, library, cache, and lyrics behavior lives in Rust. Mobile uses it through UniFFI; desktop links it directly.

## Repository layout

```text
apps/mobile/android/      Android application
apps/mobile/ios/          iOS application and Swift package
apps/desktop/             GPUI desktop prototype
crates/aurelia-core/      Shared domain, persistence, and Jellyfin logic
crates/aurelia-lyrics/    Lyrics parsing and models
crates/uniffi-bindgen/    Mobile binding-generation CLI wrapper
plugins/jellyfin-animated-artwork/  Optional server plugin for animated album artwork
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

Run the desktop prototype:

```bash
cargo run -p aurelia-desktop
```

The desktop app can authenticate with a Jellyfin server, persist the session,
sync a profile-specific library cache, and populate the home screen from that
cache. It streams audio through Rodio, reports playback progress to Jellyfin,
and publishes metadata and transport controls through the operating system's
media-session interface.

iOS builds require macOS with Xcode:

```bash
./apps/mobile/ios/build-rust.sh
open apps/mobile/ios/Aurelia.xcworkspace
```

See [docs/BUILDING.md](docs/BUILDING.md) and [docs/TESTING.md](docs/TESTING.md) for the complete workflows.

The optional [Animated Album Artwork plugin](plugins/jellyfin-animated-artwork/README.md)
provides animated covers in Jellyfin Web and an authenticated MP4 artwork API used
by Aurelia’s Android album and player views.
