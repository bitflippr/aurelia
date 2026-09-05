# Throwaway Android home study

Run `node apps/mobile/android/home-prototype/serve.mjs`, or open `index.html` directly.
Use `?variant=A`, `B`, `C`, `D`, or `all` to compare the layouts.

Approved direction: D, with **Let it play above shuffle/surprise**.

Order: greeting, one compact featured mix, shuffle/surprise, new albums,
rediscovery song rows, genres. The large artwork hero and quick-picks grid
were rejected.

The native implementation also adopts Home / Search / Library navigation,
text-only genre badges, and album covers without play overlays. Only the
mini-player floats; the compact navigation blends into the app background.
Page content fades behind the mini-player, or into navigation when no player
is visible. The existing native player transitions are preserved.

The sample music and abstract covers are visual fixtures. No real playback,
accounts, or API calls are connected. This study is not an app asset.
