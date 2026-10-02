# Vertical Slice 1 — "The Benchmark"

**Date:** 2026-10-02 · Phases 3 + 4 · Purpose: establish the visual and interaction benchmark that every later screen must match (brief §56), on real architecture with real playback.

```
HOME ──► MUSIC ──► SONGS ──► NOW PLAYING ──► UP NEXT
  ▲          (wheel, focus lens, strip transitions, title bar, mini player, glass tiers, light/dark)
  └────────────────────── Menu / long-press Menu ──────────────────────┘
```

## 1. Scope

**In:** Gradle skeleton; design tokens; fonts & symbols; glass engine with tiers; PodWheel + gesture detector + haptics; InputRouter + FocusList + FocusLens; Navigation 3 with strip transitions and predictive back; TitleBar; MenuRow/TrackRow; state components; Fixture source (generated audio + artwork); `PlaybackService` (Media3) + `PlaybackController` + `QueueManager` + status reducer; Now Playing (volume & scrub modes, progress, quality label); MiniPlayer with container transform; Up Next (sections, skip-to, remove, reorder, shuffle/repeat/autoplay toggles — autoplay toggle visible but engine is Phase 9, so it is **hidden** in the slice); atmosphere from artwork + background extension; light/dark/HC; Wheel Lab debug screen.

**Out (later phases):** Room/DataStore persistence, real sources, likes, playlists, downloads, search, lyrics, Album Flow, settings beyond a debug Appearance panel, tablets.

## 2. Modules created
`app`, `core:model`, `core:common`, `core:designsystem`, `core:interaction`, `player:api`, `player:service`, `sources:api`, `sources:fixture` (debug), `feature:library` (Home, Music, Songs), `feature:nowplaying` (Now Playing, Up Next), plus `build-logic` (convention plugins: `podium.android.library`, `podium.android.compose`, `podium.jvm.library`, `podium.android.application`) and `gradle/libs.versions.toml`.

## 3. Components (all in `:core:designsystem` unless noted)
| Component | Notes |
|---|---|
| `PodiumTheme` + tokens | colors (§2), type (§3), spacing/shapes (§4), motion (`animation-system.md`), haptics tokens |
| `GlassSurface` / `Modifier.glass` | materials Regular/Control/Floating/Focus/Navigation; tiers Full/Blur/Solid; `GlassCapabilities` provider; one `LayerBackdrop` per window |
| `ScrollEdgeEffect` | top & bottom |
| `Atmosphere` | palette extraction (Default dispatcher) + vertical falloff + 900 ms transition |
| `PodWheel` | visuals only; input via `:core:interaction` |
| `WheelGestureDetector`, `InputRouter`, `InputTarget`, `FocusList`, `HapticsAdapter` | `:core:interaction` |
| `FocusLens` | draw-phase position; springs `focus`/`focusFast`/`boundary` |
| `TitleBar`, `MenuRow`, `TrackRow`, `SectionHeader` | |
| `AlbumArtwork` | Coil 3, size buckets, monogram placeholder |
| `MiniPlayer` | container transform source |
| `ProgressScrubber`, `VolumeBar`, `QualityLabel`, `HudToast`, `GlassMenu` | GlassMenu for item menu (Play next / Add to queue) |
| `EmptyState`, `LoadingState`, `ErrorState` | |
| `NowPlayingScreen`, `UpNextScreen` | `:feature:nowplaying` |
| `HomeScreen`, `MusicScreen`, `SongsScreen` | `:feature:library` |
| `WheelLab` (debug) | live sliders for `detentDeg`, slop, acceleration tiers, spring params, glass params; tier switcher; haptic tester |

## 4. State
| Holder | State | Source |
|---|---|---|
| `AppState` (app) | back stack, atmosphere, glass capabilities, appearance (in-memory; debug panel) | Nav3, ArtworkRepository (fixture), settings stub |
| `HomeViewModel` | menu items (Now Playing item conditional), saved focus key | PlaybackController.state |
| `MusicViewModel` | submenu items (Songs enabled; others shown only if implemented → in slice: Songs only + "Album Flow/Artists/Albums" **hidden**) | static |
| `SongsViewModel` | `ContentState<List<TrackUi>>` | FixtureSource |
| `NowPlayingViewModel` | `NowPlayingUi` (item, intent, status, format label, index "3 of 12"), wheel mode `Volume/Scrub` (UI-local), volume level | PlaybackController, VolumeController |
| `UpNextViewModel` | sections from `QueueState` | PlaybackController.queue |
| `PlaybackService` | ExoPlayer, `QueueManager`, reducer | — |
Hot-path values (position, lens offset, wheel angle) are draw-phase reads, not state.

## 5. Navigation
Keys: `Home`, `Music`, `Songs`, `NowPlaying`, `UpNext`. Rules from `navigation-map.md` §3 (single-instance NowPlaying, auto-push on play, long-press Menu → Home). Transitions: strip (spring `navigate`), predictive back, mini player container transform, reduced-motion cross-fades.

## 6. Interactions to implement
| Where | Rotate | Center | Long Center | Menu | ⏯ ⏮ ⏭ |
|---|---|---|---|---|---|
| Home / Music | focus | push | — | back / no-op at root | global |
| Songs | focus (accelerated, IndexGlyph) | play list from track → NP auto-push | menu: Play next, Add to queue | back | global |
| Now Playing | volume | enter Scrub | menu: Up Next, (Go to album hidden) | back (Scrub: exit mode) | global; long ⏮/⏭ scan; long ⏯ sleep → **hidden in slice** (Phase 6) |
| Up Next | focus | skip to item | menu: Play next, Move, Remove | back (Reorder: cancel) | global |
| Touch | tap row = focus+activate; drag scroll; mini player tap; scrubber drag; swipe artwork prev/next; swipe-to-remove in Up Next |
Haptics per `interaction-model.md` §6. Debounce per §7.

## 7. Animations
Lens glide/fast/boundary, row weight change, strip push/pop, predictive back, container transform (mini player ↔ NP), artwork cross-fade on track change, atmosphere 900 ms, scrubber→volume bar, scrub-mode enter, HUD toast, menu rise. Reduced-motion variants for each.

## 8. Data requirements
**Fixture source** (`sources:fixture`, debug only), generated by a Gradle task using ffmpeg (fails gracefully with instructions if ffmpeg is missing; outputs cached in `build/`, not committed):
- 3 "albums" × 4 tracks, 25–40 s each, distinct harmonic tones (a few chords, not pure beeps) so gapless and transitions are audible.
- Formats spread to exercise the quality label: FLAC 16/44.1, FLAC 24/96, ALAC 16/44.1, AAC 256, Opus 160, MP3 320.
- Procedural cover art (3 distinct, color-rich, generated as PNG — e.g., geometric compositions) to exercise atmosphere extremes: one very dark, one bright/warm, one saturated blue.
- Titles include long and multilingual strings (Greek, Cyrillic, Vietnamese, Devanagari, Japanese) to exercise the per-string font fallback.
- One deliberately broken file (truncated FLAC) to exercise `SkipAfter` error policy.
Total debug-asset budget ≤ 8 MB.

## 9. Testing requirements (slice gate)
| Kind | Must include |
|---|---|
| JVM unit | WheelGestureDetector (slop, detents, hysteresis, accel, press vs rotate, long-press); InputRouter (stack, fall-through, debounce); FocusList (keep-in-view, boundaries, snap after touch scroll, saver); QueueManager property tests (invariants §2 of `queue-and-autoplay.md`) incl. shuffle/unshuffle; status reducer (every transition row reachable in slice); Navigator rules; quality label formatter; atmosphere mapping bounds & contrast; concentric radius helper |
| Robolectric | PlaybackService with fixture: play, pause, next, previous (restart < / > 3 s), auto-advance, broken-file skip, noisy pause, skipTo/remove/move mirror invariant; Compose nav journey by injected inputs (Home→Music→Songs→Center→NP→Menu→Songs; long-press Menu → Home); semantics of Wheel buttons and rows |
| Roborazzi | All slice components and 5 screens × light/dark/HC × Solid tier × font 1.0/2.0 |
| Device (manual, recorded) | Wheel feel tuning notes; Full tier rendering; notification + lock screen on API 36 **and** 37 (R-07); background playback 10 min screen-off; predictive back |
| Benchmark (record only) | Cold start, Songs fast-spin jank, push/pop frames |

## 10. Step-by-step (each step = one or more commits)
1. **S0 Skeleton** — wrapper, build-logic, catalog, empty modules, Spotless/ktlint, lint config, `.editorconfig`, dependency verification. *Commit:* `build: establish Gradle skeleton and conventions`.
2. **S1 Tokens & type** — theme, colors (+HC), type with Instrument Sans/Inter per-string fallback, symbols subset task, spacing/shapes, motion, previews, Roborazzi setup. *Commit:* `feat(ui): add Podium design tokens and typography`.
3. **S2 Glass** — `Modifier.glass`, materials, tiers, capabilities, scroll-edge, lint detector. *Commit:* `feat(ui): establish liquid glass material system`.
4. **S3 Wheel & focus** — PodWheel visuals, gesture detector, haptics, InputRouter, FocusList, FocusLens, Wheel Lab. *Commit:* `feat(interaction): add wheel input and focus system`.
5. **S4 Navigation shell** — Nav3, keys, Navigator, TitleBar, strip transitions, predictive back, Home/Music static. *Commit:* `feat(navigation): add iPod-style stack navigation`.
6. **S5 Fixture & Songs** — fixture generator + source, Songs list, artwork, states. *Commit:* `feat(library): add fixture source and songs list`.
7. **S6 Playback** — PlaybackService, controller, QueueManager, reducer, session basics (explicit `onConnectAsync`), notification. *Commit:* `feat(player): add playback service and controller`.
8. **S7 Now Playing, Mini player, Up Next** — volume/scrub, progress, quality label, container transform, queue UI & ops. *Commits:* `feat(nowplaying): …`, `feat(queue): …`.
9. **S8 Atmosphere & appearance** — artwork palette, background extension, theme/HC/transparency debug panel. *Commit:* `feat(ui): add artwork atmosphere`.
10. **S9 Review loop** — run on device, screenshot every screen/state, review against §11, fix, record benchmarks, write `docs/reviews/slice-1.md`. *Commit:* `docs: record benchmark review`.

## 11. Design review checklist (run after S5, S7, S9)
1. Does it read as an iPod in five seconds? (one list, one focus, the wheel)
2. Is the wheel the only bold thing? Count glass surfaces (≤ 4 persistent).
3. Spacing on the 4dp grid; gutters 16/20; row heights per spec.
4. Typography: styles from the scale only; tabular numerals; no all-caps except `MENU`; no dot-joined meta strings.
5. Contrast: spot-check with the worst artwork fixture in light & dark & HC.
6. Motion: lens follows the wheel without lag; nothing moves when idle; reduced motion works.
7. Glass restraint: no glass on rows/artwork/text containers; glass has real backdrop.
8. Fallback: Solid tier looks designed (classic highlight bar), not broken.
9. States: loading/empty/error shown for Songs; broken track skip message.
10. Touch parity: everything doable without the wheel.

## 12. Exit criteria
All §9 tests green; §11 checklist passes with recorded screenshots; wheel latency budget met on device; notification/lock screen confirmed on API 36 & 37; user has reviewed screenshots or a screen recording and approved (or requested changes to) the benchmark. Then tokens are frozen as **Design System v1.0** and reused for every later screen.
