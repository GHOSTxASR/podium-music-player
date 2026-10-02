# Performance

**Date:** 2026-10-02 · Principle: budgets first, measure on a mid-range device, optimise with evidence.

## 1. Reference devices
| Class | Example | Role |
|---|---|---|
| Mid-range (budget target) | Pixel 6a / Galaxy A5x (API 33+, 60–120 Hz) | All budgets must pass here |
| Low-end floor | API 29–30 device, 3–4 GB RAM | Solid tier; must remain smooth |
| High-end | Recent Pixel/Galaxy | Full tier at 120 Hz |

## 2. Budgets
| Metric | Budget | How measured |
|---|---|---|
| Cold start → first frame | < 600 ms (mid-range) | Macrobenchmark `StartupTimingMetric` |
| Cold start → interactive Home with restored queue | < 900 ms | Macrobenchmark + trace section |
| Wheel detent → focus state change | same frame | trace sections `input.detent`, `focus.update` |
| Detent → lens first movement | ≤ 1 frame | frame timeline |
| List scrolling / fast spin jank | < 1% janky frames (P95 frame time < 1.0× refresh interval) | `FrameTimingMetric` on scripted spins |
| Push/pop transition | 0 dropped frames P90 | `FrameTimingMetric` |
| Glass surfaces per frame | ≤ 4 persistent + ≤ 1 transient | debug overlay counter |
| Backdrop capture | 1 per window | design rule + overlay |
| Memory (steady, browsing) | < 180 MB PSS | `dumpsys meminfo` in benchmark |
| Image memory cache | 15% of app heap | Coil config |
| Play → audio out (local file) | < 250 ms | trace `playback.start` → first `onIsPlayingChanged(true)` |
| Play → audio out (LAN Subsonic) | < 800 ms | same |
| Track transition (gapless album) | 0 ms gap (sample-accurate) | Media3 test + listening check |
| Search: local results | < 50 ms after debounce | trace |
| DB: library list query (50k tracks) | < 16 ms per page of 100 | instrumented test |
| APK (universal, release) | < 25 MB | CI check |

## 3. Design decisions that protect performance
- **Single backdrop capture** shared by all glass (ADR-007); glass never samples glass.
- **Lens** has no blur (only refraction + stain) — the most-animated glass is the cheapest.
- **Tiers** degrade automatically: power-save, thermal ≥ SEVERE, or sustained jank (> 5% janky frames over 10 s) drops one tier for the session.
- **Progress & positions** are read in draw phase (`drawBehind { … controller.positionMs() … }`), never `StateFlow` per frame; the time labels update at 4 Hz via a dedicated tiny composable.
- **Rows** are stable/immutable (`@Immutable` models, `key`ed `LazyColumn`, `contentType`), no per-row glass, no per-row shadows.
- **Artwork** requests sized to the slot bucket; palette computed once on a 64×64 decode and cached in DB.
- **Library from DB** (sync) — no network on scroll paths.
- **Large queues** pushed to the player in chunks.
- **Baseline Profiles** + Startup Profile generated in CI from Macrobenchmark journeys (Home, scroll Songs, open Album, play, Now Playing, Up Next).
- **R8 full mode**, resource shrinking, fonts subset (symbols) and variable (text).

## 4. Profiling workflow
1. Perfetto system traces with custom `trace("…")` sections (input, focus, glass draw, resolver, DB).
2. Compose recomposition counts in debug (Layout Inspector / composition tracing).
3. Macrobenchmark module (`:benchmark`) on a physical device — CI runs on demand, not every push.
4. Regressions > 10% on any budget block the phase exit.

## 5. Battery
- No polling: playback events, `NetworkCallback`, `ContentObserver`, WorkManager constraints.
- Library sync: periodic 12 h on unmetered + charging (manual "Sync now" anytime).
- Audio offload for lossy, screen-off playback when no processing is active.
- Glass rendering only while visible; no off-screen animations.
