# Android emulator testing

On Linux with Nix and access to `/dev/kvm`, launch the Pixel 10 Pro XL profile:

```bash
bash scripts/android-emulator.sh -no-window -no-audio
```

Omit `-no-window` to open the emulator's desktop window. The script installs an
API 36 Google APIs x86_64 image and uses the SDK's Pixel 10 Pro XL hardware profile
(1344 × 2992, logical density 480). It retains the SDK under the user's state
directory so garbage collection does not remove it.

Build and install from another terminal:

```bash
nix develop
cd apps/mobile/android
./gradlew ktlintCheck testDebugUnitTest assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.aurelia.app/.MainActivity
adb -s emulator-5554 shell settings put system show_touches 1
```

If `local.properties` contains an expired Nix SDK path, update its `sdk.dir` and
`ndk.dir` to `$ANDROID_HOME` and `$ANDROID_NDK_HOME` inside `nix develop`.
The device serial may differ if other emulators are running; check `adb devices`.

For a local Jellyfin test server, `adb reverse tcp:8096 tcp:8096` makes the host's
port 8096 available as `http://127.0.0.1:8096` inside the emulator. Use a test
account/library when exercising destructive user actions.

Useful checks include an empty library, two songs sharing title/artist metadata,
pause/resume, repeat, a final-track completion, and login/profile switching.
Inspect `adb logcat`, `adb shell dumpsys media_session`, and the server's playback
reports alongside the visible UI. Emulator success does not verify physical
Pixel audio hardware, Bluetooth, or every codec.

To watch from another computer on the same Tailscale network, forward the
emulator's existing ADB transport on the host (keep this terminal open):

```bash
tailscale serve --tcp=15555 tcp://127.0.0.1:5555
tailscale ip -4
```

On the viewing computer, substitute the host's Tailscale IP:

```bash
adb connect HOST_TAILSCALE_IP:15555
scrcpy -s HOST_TAILSCALE_IP:15555 --force-adb-forward --no-control --no-audio --max-size=1600 --max-fps=30
```

Port 5555 is the ADB transport for `emulator-5554`; adjust it for another
emulator. `--force-adb-forward` carries the video through that connection.
Remove `--no-control` to interact with the app. Stop the foreground Tailscale
Serve command when the viewing session is over.
