# Building Aurelia

## Shared prerequisites

- Rust stable
- Java 17
- Android SDK 36 and build tools 36.0.0
- Android NDK 29.0.14206865
- `cargo-ndk`

On NixOS, `nix develop` supplies the Rust toolchain, Java, Android SDK/NDK, and `cargo-ndk`.

## Rust core

```bash
cargo build --workspace
```

The workspace contains `aurelia-core`, `aurelia-lyrics`, and the UniFFI binding generator used by both mobile builds.

## Android

```bash
cd apps/mobile/android
./gradlew assembleDebug
```

Gradle builds `aurelia-core` for the Android ABIs, regenerates the Kotlin UniFFI bindings, and packages the native libraries automatically. Release builds use:

```bash
./gradlew assembleRelease
```

The debug APK is written under `apps/mobile/android/app/build/outputs/apk/debug/`. `AURELIA_VERSION_CODE` and `AURELIA_VERSION_NAME` can override the default Android version for automated builds.

### Automated Android releases

`.github/workflows/android-release.yml` creates a debug-signed, optimized APK and GitHub Release whenever Android or its shared Rust dependencies change on `main`. It also supports manual runs from the Actions tab. Each release has a monotonically increasing version code, an `android-v0.1.<run>` tag, one universal APK, and a SHA-256 checksum suitable for Obtainium. No GitHub secrets are required.

This project intentionally uses automatic debug signing for release artifacts because its Obtainium deployment targets a CorePatch device. These APKs are not suitable for normal distribution: stock Android requires updates to retain the same signing key.

After the first workflow release succeeds, add `https://github.com/bitflippr/aurelia` to Obtainium using its GitHub source. The single `aurelia-<version>.apk` asset in the latest release is the installable update.

On an unpatched device, use a private, persistent release keystore instead.

## Desktop

The desktop interface is React/TypeScript in `apps/desktop/ui`, rendered as
native GPUI elements by GPUIX. `apps/desktop/src` is the Rust runtime: session,
library sync, the playback queue, Rodio playback, Souvlaki media controls and
artwork colors. `apps/desktop/native/aurelia.rs` connects the two inside the
renderer and paints the seek bar; `apps/desktop/native/lyrics.rs` draws synced
lyrics the way the Android app does, tuned by the settings in
`apps/desktop/ui/lyrics.tsx`.

GPUIX comes from the `bitflippr/gpuix` fork in `vendor/gpuix`, pinned to its
`aurelia` branch: the `shared` branch (upstream plus the changes Aurelia and
Slate share) and one commit linking Aurelia's runtime. See
`vendor/gpuix/downstream/README.md` for what the fork adds.

Prerequisites: Bun, Rust, and the platform C/C++ build tools (MSVC on
Windows). On NixOS, `nix develop` supplies the Linux libraries.

```bash
git submodule update --init vendor/gpuix
bun install
bun run desktop:native
bun run desktop
```

- `desktop:native` applies the fork's GPUI patches to its Zed checkout, compiles
  the GPUIX packages' TypeScript, and builds the renderer in release mode. It
  publishes `.local/native/aurelia-<hash>.node` and names it in
  `.local/native/latest.json`, so a build never replaces a binding a running
  Aurelia has loaded. `CARGO_TARGET_DIR` moves the build cache, which defaults
  to `.local/native-target`.
- `desktop` starts with Bun's hot reload: saving interface code updates the
  window while the queue and playback carry on. Saving native code (the
  runtime, the renderer extension, `crates`, the GPUIX fork or its GPUI)
  rebuilds the renderer and restarts the app on it; a failed build leaves the
  app running. `desktop:start` runs once, without either.
- `desktop:typecheck` checks the interface's types.

Interface fonts are static cuts of Google Sans Flex (`ui/assets/fonts`, SIL
Open Font License), loaded by the renderer at startup.

## iOS

iOS requires macOS and Xcode. Build the Rust XCFramework and regenerate Swift UniFFI bindings with:

```bash
./apps/mobile/ios/build-rust.sh
```

Use `--release` for optimized Rust libraries:

```bash
./apps/mobile/ios/build-rust.sh --release
```

Then open `apps/mobile/ios/Aurelia.xcworkspace` in Xcode, or build an unsigned archive from the command line:

```bash
xcodebuild archive \
  -workspace apps/mobile/ios/Aurelia.xcworkspace \
  -scheme Aurelia \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath build/ios.xcarchive \
  CODE_SIGN_IDENTITY='' \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO
```

## Generated bindings

Do not edit generated Kotlin or Swift UniFFI sources by hand. Android regenerates Kotlin bindings during Gradle pre-build; `apps/mobile/ios/build-rust.sh` regenerates Swift bindings and the XCFramework.
