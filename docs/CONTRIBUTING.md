# Contributing

Aurelia has native Android and iOS apps and a desktop prototype. Keep platform UI and playback behavior in the native apps, and put reusable Jellyfin, persistence, library, and lyrics behavior in Rust when the UniFFI boundary supports it.

## Before changing code

1. Check the nearest implementation and tests.
2. Preserve unrelated worktree changes.
3. Make the smallest coherent Android, iOS, or shared-core change.
4. Regenerate bindings when a UniFFI export or record changes.

## Conventions

- Android uses Kotlin, Jetpack Compose, Material 3, ViewModels, StateFlow, coroutines, and Media3.
- iOS uses SwiftUI, observable models, structured concurrency, and AVFoundation.
- Rust transport errors should remain structured at the UniFFI boundary.
- Use `tracing` for Rust diagnostics.
- Keep generated Kotlin and Swift bindings machine-generated.
- Preserve accessibility labels, keyboard/switch behavior, and reduced-motion behavior where applicable.

## Validation

```bash
cargo fmt --check
cargo clippy --workspace --exclude aurelia-desktop --all-targets -- -D warnings
cargo test --workspace --exclude aurelia-desktop

cd apps/mobile/android
./gradlew ktlintCheck testDebugUnitTest assembleDebug
```

On macOS, also run `./apps/mobile/ios/build-rust.sh` followed by `swift test` in `apps/mobile/ios/AureliaCore`.

For desktop changes, run `cargo clippy -p aurelia-desktop --all-targets -- -D warnings`
and `cargo test -p aurelia-desktop`. On Linux, use `nix develop` to obtain its native
audio, graphics, and window-system dependencies. Full `--workspace` checks include
the desktop prototype; CI provisions those dependencies in a separate desktop job.
