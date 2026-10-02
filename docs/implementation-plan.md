# Implementation Plan

**Date:** 2026-10-02 · Loop for every phase: **plan → implement → run → test → inspect → fix → document → commit**. Each phase ends with its exit criteria met, the device checklist subset passed, docs updated, and a tagged commit (`phase-N`).

## Phase map

| Phase | Goal | Key deliverables | Exit criteria |
|---|---|---|---|
| **0 — Audit** ✅ | Understand the starting point | `repository-audit.md`, research notes | Done 2026-10-02 |
| **1 — Product/UX spec** ✅ | Define what and how | product spec, screens, navigation, interaction, flows, edge cases | Done 2026-10-02 |
| **2 — Architecture** ✅ | Decide the system | architecture, ADRs, data model, playback/queue/downloads/offline | Done 2026-10-02 · **user checkpoint** |
| **3 — Design system** | Tokens & primitives in code | Gradle skeleton; `PodiumTheme`; fonts + symbol subset; glass engine + tiers; scroll-edge; PodWheel visuals; FocusLens; rows; TitleBar; state components; Roborazzi baselines; Wheel Lab | Components render in light/dark/HC × tiers; lint rule bans stray blur; screenshots reviewed |
| **4 — Interactive benchmark (vertical slice)** | Prove the feel | Home → Music → Songs → Now Playing → Up Next with real Media3 playback of fixture audio; wheel contexts; transitions; mini player | `vertical-slice-plan.md` exit criteria; **user reviews the benchmark** |
| **5 — Real sources** | Real music | `sources:local` (MediaStore sync), `sources:subsonic` (setup, auth, sync, resolve), Room 3 DB, LibrarySync, Sources settings, onboarding | Browse a real 10k+ library from DB; contract tests vs 3 server fixtures |
| **6 — Playback hardening** | Reliable everywhere | Resolver + stream cache, queue persistence & restoration, session policy (`onConnectAsync`), notification/lock screen/Bluetooth verified on 36/37, quality inspector + Signal Path, volume controller, sleep timer, error policies | Edge-case suite green; device checklist §4 items 3–7 pass |
| **7 — Library** | User data | Likes, playlists (CRUD, reorder, duplicates prompt), history, artists/albums/genres screens, context menus, add-to-playlist, outbox sync (Subsonic stars) | Repository + migration tests; offline edits sync |
| **8 — Offline** | Downloads | DownloadManager, UIDT/WorkManager executors, verification, groups & auto-sync, Downloads + Storage screens, playability & offline UI | Interrupted/resume/corrupt tests; airplane-mode checklist |
| **9 — Discovery** | Find & continue | Search (fan-out + FTS), Audius source, AutoplayEngine + inspector, Album Flow, Lyrics (source/embedded/LRCLIB with consent) | Autoplay golden tests; Flow at 0 dropped frames P90 |
| **10 — Polish** | Quality pass | Motion audit, performance budgets, accessibility matrix, all states, responsive (landscape, tablets, desktop mode), targetSdk 37 evaluation | All budgets met on mid-range device; a11y manual pass |
| **11 — Testing** | Confidence | Fill gaps, macrobenchmarks, baseline profiles, long-run soak (8 h playback), migration chain tests | CI green; soak without crash/ANR |
| **12 — Ship** | Release | License audit, About/attributions, privacy notice, README with screenshots, known limitations, signing, CI release workflow, chosen channel | Release checklist in `deployment.md` |

## Sequencing notes (deviations from the brief's order, with reasons)
- **Minimal real playback moves into Phase 4.** The benchmark must play real audio through the real `PlaybackService` (brief §59: no disconnected mockups). Phase 6 then hardens it.
- **Room arrives in Phase 5**, with the first source that needs persistence. The slice keeps the queue in the service (survives navigation; process-death restoration is Phase 6).
- **Like/Download controls are absent from the slice UI** (not shown disabled, not faked) until Phases 7/8 implement them.

## Working agreements
- Small, reviewable commits with conventional messages (`feat(ui): …`, `feat(player): …`, `fix(queue): …`, `docs: …`, `test: …`, `build: …`).
- Every significant decision → `decision-log.md` (ADR if major).
- Docs are updated in the same commit as the behaviour they describe.
- No code copied from GPL projects (D-13 pending).
- Device-only claims ("feels right", "no jank") are backed by a recorded measurement or a checklist entry with date and device.

## Estimated effort (relative)
Phase 3 ≈ 1.0 · Phase 4 ≈ 1.5 · Phase 5 ≈ 1.5 · Phase 6 ≈ 1.0 · Phase 7 ≈ 1.0 · Phase 8 ≈ 1.0 · Phase 9 ≈ 1.5 · Phase 10 ≈ 1.0 · Phase 11 ≈ 0.5 · Phase 12 ≈ 0.5 (units ≈ one focused working session each, assuming a physical test device).
