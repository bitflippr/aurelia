#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
emulator_state="${XDG_STATE_HOME:-$HOME/.local/state}/aurelia"
mkdir -p "$emulator_state"
nix build .#android-emulator-sdk --out-link "$emulator_state/emulator-sdk"
emulator_sdk="$emulator_state/emulator-sdk/libexec/android-sdk"
emulator_avds="${ANDROID_AVD_HOME:-${XDG_CONFIG_HOME:-$HOME/.config}/.android/avd}"
emulator_name="Aurelia_Pixel_10_Pro_XL"
mkdir -p "$emulator_avds"

if [[ ! -f "$emulator_avds/$emulator_name.ini" ]]; then
  emulator_manager="$(rg --files --follow "$emulator_sdk/cmdline-tools" | rg '/bin/avdmanager$' | head -n 1)"
  nix develop -c env ANDROID_HOME="$emulator_sdk" ANDROID_AVD_HOME="$emulator_avds" \
    "$emulator_manager" create avd --name "$emulator_name" \
    --package 'system-images;android-36;google_apis;x86_64' --device pixel_10_pro_xl
fi

exec nix develop -c env ANDROID_HOME="$emulator_sdk" ANDROID_SDK_ROOT="$emulator_sdk" \
  ANDROID_AVD_HOME="$emulator_avds" "$emulator_sdk/emulator/emulator" \
  -avd "$emulator_name" -gpu swiftshader -no-snapshot-save "$@"
