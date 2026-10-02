# Testing Strategy

**Date:** 2026-10-02 · Principle: the riskiest logic is pure Kotlin and tested in milliseconds; Android integration is tested with Robolectric; what only real hardware can prove (audio, haptics, glass, Bluetooth, notifications) has a short, repeatable device checklist.

## 1. Pyramid & tooling
| Layer | Scope | Tools | Runs |
|---|---|---|---|
| Unit (JVM, pure) | `core:model`, `core:common`, `player:api` (QueueManager, state reducer, AutoplayEngine, shuffle), `sources:api` contracts, LRC parser, wheel gesture math, FocusList math, NetworkPolicy, quality labelling | JUnit 4, kotlin-test, Turbine, kotest-property (property tests) | every push |
| Adapter contract | `sources:subsonic`, `sources:audius`, LRCLIB client | OkHttp `MockWebServer` + recorded fixtures (Navidrome, Gonic, Ampache; Audius; LRCLIB) | every push |
| Database | DAOs, FTS, triggers (pin counts), migrations | Room 3 + `BundledSQLiteDriver` on JVM, `MigrationTestHelper` | every push |
| Android integration (Robolectric) | PlaybackService with `media3-test-utils` (`TestExoPlayerBuilder`, `FakeClock`), DownloadManager with fake executor, repositories with real DB | Robolectric 4.17 | every push |
| Compose UI (Robolectric) | Navigation, wheel input injection, focus behaviour, semantics/a11y assertions, state screens | `createComposeRule` | every push |
| Screenshot | Design-system components & key screens across light/dark/HC × Solid tier × font 1.0/1.5/2.0 | Roborazzi (record/verify) | every push (verify) |
| Instrumented (device) | Media3 session/notification on API 36 & 37, UIDT downloads, real MediaStore, glass Full tier rendering | AndroidX Test on physical device | per phase exit |
| Macrobenchmark | Startup, scroll/spin jank, transitions; baseline profile generation | `androidx.benchmark.macro` | per phase exit |
| Manual | Audio quality by ear, haptics feel, Bluetooth/headset, real-world offline | Checklist §4 | per phase exit |

## 2. Required test areas (brief §51) → where they live
| Area | Tests |
|---|---|
| Navigation | Navigator unit tests (single-instance NowPlaying, pop-to-root, depth cap, deleted entity); Compose tests for Menu/back/predictive back |
| Wheel input | Gesture detector synthetic streams; InputRouter fall-through; context table behaviours |
| Playback state | Reducer transition table (every row of `playback-state-machine.md` §3), Media3 mapping table, error policies |
| Queue | Property tests over random op sequences with invariants; restoration; chunking |
| Shuffle / Repeat | Seeded reproducibility, unshuffle restoration, spread rule; repeat ONE/ALL with autoplay suspension |
| Autoplay | Golden batches, no-repeat windows, artist caps, fallbacks, offline filtering |
| Likes / Playlists | Repository tests incl. fractional positions, duplicates prompt policy, outbox coalescing |
| Downloads | State machine, Range resume, verification failures, duplicate requests, group semantics, integrity pass, deletion ordering |
| Offline playback | Resolver preference (download > local > cache > stream), playability computation, startup offline |
| Search | Debounce, fan-out merge, partial failure, local FTS ranking, history cap |
| Caching | Stream cache keying by track+tier, TTLs, GC never touching pinned/user-owned data |
| DB migrations | Every version step + 1 → latest; pre-migration backup and safe mode |
| Source adapters | Contract tests + capability probing per server type |
| Error handling | PodiumError → copy mapping exhaustive; no raw exception text in UI (lint + test) |

## 3. Edge cases (brief §52) → explicit tests
| Case | Test type |
|---|---|
| Empty queue / one-track queue / repeated track | unit (queue, reducer) |
| Unavailable track / deleted playlist track | unit + Robolectric |
| Offline startup | Robolectric (connectivity fake) |
| Interrupted / partially downloaded track | Robolectric (MockWebServer drops mid-body) + resume |
| Provider failure | adapter contract (5xx, timeouts, malformed JSON) |
| Rapidly skipping tracks | Robolectric service test: 10 `next()` in 1 s → one resolution, no history rows |
| Rotating wheel rapidly | gesture detector + Compose test (focus index correct, no queued work) |
| Repeated center presses | InputRouter debounce test |
| Background/foreground transitions | Robolectric lifecycle + device checklist |
| Headphones disconnected / Bluetooth controls | Media3 noisy handling (Robolectric broadcast) + device checklist |
| App killed during playback | Robolectric: persist → recreate service → restore paused at saved position |
| DB migration failure | Migration test with a corrupted fixture → safe mode |

## 4. Device checklist (per phase exit; ~20 min)
1. Wheel: detent feel at slow/medium/fast; long-press timings; no accidental presses when starting rotation on a legend.
2. Glass: Full tier on API 33+, Blur on 31–32, Solid with Transparency Off; no stutter while spinning.
3. Playback: lock screen + notification controls on API 36 and 37 (R-07); Bluetooth play/pause/skip; wired headset button; unplug → pause.
4. Calls/navigation prompts: duck/pause & resume.
5. Background 30 min with screen off: continuous playback, no kills.
6. Swipe app away while playing → keeps playing; while paused → service stops.
7. Force-stop → relaunch → queue restored paused.
8. Airplane mode: browse, play downloaded, search local, like/unlike, playlists edits; reconnect → outbox flushes.
9. TalkBack: Home → Album → play → Now Playing → volume → Up Next.
10. Font 200%, reduced motion, high contrast.

## 5. Conventions
- Test names describe behaviour: `skipping_rapidly_resolves_only_the_final_item`.
- Fakes over mocks (`FakeMusicSource`, `FakePlaybackController`, `FakeClock`, `FakeConnectivity`).
- Fixtures: generated audio (ffmpeg) and recorded JSON live under `testFixtures`; no copyrighted audio in the repo.
- Coverage is a signal, not a target; `player:api` and `downloads` state machines aim for full transition coverage.
- CI gate: unit + Robolectric + screenshot verify + lint must pass before merge.
