# AGENTS.md

## Purpose

Aurelia is a native mobile music client for Jellyfin. It consists of native Android and iOS applications backed by shared Rust domain logic and generated UniFFI bindings.

## GitHub identity

- Use the owner's main GitHub account, `skulldogged`, for this repository, with personal `gh` and normal `git`.
- This is a standing project preference. Do not request account approval again for later tasks or releases.

## Repository map

- `apps/mobile/android/` — Kotlin, Jetpack Compose, and Media3 Android app.
- `apps/mobile/ios/` — SwiftUI and AVFoundation iOS app plus its Swift package.
- `apps/desktop/` — desktop app: React/TypeScript interface on GPUIX (`ui/`), Rust runtime (`src/`), and the renderer extension (`native/aurelia.rs`).
- `vendor/gpuix/` — the `skulldogged/gpuix` fork, shared with Slate, pinned to its `aurelia` branch.
- `crates/aurelia-core/` — shared Jellyfin services, models, caching, persistence, and mobile-facing UniFFI exports.
- `crates/aurelia-lyrics/` — reusable lyrics parsing and models.
- `crates/uniffi-bindgen/` — thin UniFFI CLI wrapper used by mobile builds.

## Architecture boundaries

- Keep platform UI, lifecycle, playback, and OS integration in the native app that owns it.
- Put reusable domain and service behavior in Rust when the UniFFI boundary supports it.
- Android playback stays behind its Media3 controller; iOS playback stays behind its AVFoundation controller; desktop playback stays in the Rust runtime (`apps/desktop/src/player.rs`).
- Desktop interface code is TypeScript in `apps/desktop/ui`. Native GPUIX changes that Slate could use belong on the fork's `shared` branch; Aurelia-only integration belongs in `apps/desktop/native/aurelia.rs`, with the fork's `aurelia` branch rebased onto `shared`.
- Do not introduce a local web server or web-view layer between mobile code and `aurelia-core`.
- Keep generated bindings synchronized with Rust exports and records.

## Working method

1. Inspect `git status`, relevant manifests, neighboring source, and tests before editing.
2. Preserve unrelated worktree changes.
3. Make the smallest coherent end-to-end change.
4. Add or update tests for behavior changes when practical.
5. Run narrow checks first, then the appropriate platform suite.
6. Review generated output and the final diff before finishing.

## Generated code

Do not hand-edit generated bindings under:

- `apps/mobile/android/app/src/main/java/uniffi/`
- `apps/mobile/ios/AureliaCore/Sources/`

Android regenerates Kotlin bindings during Gradle pre-build. The iOS `build-rust.sh` script regenerates Swift bindings and its XCFramework.

## Validation

- Rust: `cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`
- Android: from `apps/mobile/android`, run `./gradlew ktlintCheck testDebugUnitTest assembleDebug`
- iOS: on macOS, run `./apps/mobile/ios/build-rust.sh`, then `swift test` from `apps/mobile/ios/AureliaCore`
- Desktop: `bun run desktop:typecheck` and `bun run desktop:native`, then run `bun run desktop`

Always report changed paths, checks run, and platform checks that could not be performed.
