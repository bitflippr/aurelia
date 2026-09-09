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

## Genre typography study

With the same server running, open `/genres?variant=B` (or A, C, all).
Uses the existing Google Sans Flex font, including its actual width, weight,
and roundness axes. This remains a standalone design reference.

- A: two justified rows with consistent typography.
- B (selected and implemented in Android): dynamically justified rows. Measure the labels,
  choose row breaks together, and distribute widths proportionally so every
  row fills the container. No prescribed column counts or hand-picked
  proportions. Fit typography afterward, within a moderate width-axis range.
- C: two joined strips with equal-width cells and contrasting typography.

All genres remain in their input order. B adapts to the container width, type
size, and genre set. A and C retain groups of four for comparison. Text is
measured after the font loads and fitted again when the viewport changes.
Try the type-size slider, original/larger/longer genre sets, and Mix the order
to see different packings. Repeated fitting of identical inputs is stable;
Mix the order is an explicit mockup action, not automatic random reordering.
Controls and selection previews are local only. The native implementation in
`GenreChips.kt` adds accessibility scaling and live library integration, with
one consistent background color for every chip.

[Primary-source inspiration and rationale](genre-inspiration.md) covers
Flickr's justified row geometry, DJR's Fit specimen, and variable-font fitting.
