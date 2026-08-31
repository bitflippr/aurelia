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

After the first workflow release succeeds, add `https://github.com/skulldogged/aurelia` to Obtainium using its GitHub source. The single `aurelia-<version>.apk` asset in the latest release is the installable update.

On an unpatched device, use a private, persistent release keystore instead.

## Desktop prototype

The desktop prototype uses the mainline GPUI revision pinned in
`apps/desktop/Cargo.toml`. It does not depend on `gpui-component`.

On NixOS, enter the development shell so Fontconfig, Wayland, X11, and Vulkan
libraries are available, then run:

```bash
nix develop
cargo run -p aurelia-desktop
```

The desktop app authenticates directly through `aurelia-core`, stores its
session in Aurelia's application-data directory, and performs the same smart
library and favorites sync used by mobile. The home screen is populated from a
profile-specific cache after sync completes. Desktop playback uses Rodio for
streaming and Souvlaki for system media-session integration, including metadata,
media keys, and transport controls.

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
