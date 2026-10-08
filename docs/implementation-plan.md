# Implementation Plan

**Date:** 2026-10-02 (revised after the source-architecture checkpoint) · Loop for every phase: **plan → implement → run → test → inspect → fix → document → commit**. Each phase ends with its exit criteria met, the relevant device checklist passed, docs updated, and a tagged commit.

## Status
| Phase | State |
|---|---|
| 0 Audit · 1 Product/UX spec · 2 Architecture | ✅ 2026-10-02 |
| S0 Source research (BitChord, providers) | ✅ 2026-10-02 — `research/BITCHORD_ARCHITECTURE_REVIEW.md`, `architecture/*`, ADR-013/014 |
| Checkpoint decisions | ✅ 2026-10-02 — Android/Compose, physical device; D-20/D-21 still open |
| S1 contracts · Phase 3 design system · S3 Media3 · S2 local source · Phase 4 slice | ✅ 2026-10-02/03 |
| UI evolution (device shell, Now Playing, Carbon/Bone, the paper) | ✅ 2026-10-03 — D-26 … D-30 |
| S2b library database (Room 3: library cache, favorites, saved queue, FTS5) | ✅ 2026-10-03 — D-31 |
| Boot self-test, click sound, music folders; indicator lights | ✅ 2026-10-03 — D-32, D-33 |
| Online O1–O8 (contracts, Audius, ONLINE database, navigation, playback, autoplay, radio, device tests) | ✅ 2026-10-03 — D-34 (S7 Audius brought forward by the user) |
| O10 multi-source Online · O10.5 resolution stabilised (environment-bound fallback, persistent equivalence) | ✅ 2026-10-06 — D-35, D-36 |
| O11 OpenSubsonic (S4's source part: configured sources, sign-in, Keystore credentials, network policy) and multi-source hardening | ✅ 2026-10-06 — D-37; device acceptance in `testing-strategy.md` §4.1, audit in `security.md` §8. S4 leftovers: API-key auth, stars, lyrics, play-queue sync, downloads; jukebox is S5 |
| Transition to YouTube Music | ✅ 2026-10-06 — D-38 / D-48: YouTube Music established as sole online provider with direct Media3 streaming, BitChord reference |
| Next | Implementation of direct Media3 streaming extraction pipeline (D-48) while local offline playback remains frozen |

## Revised order

The directive's source phases (S1–S6) are merged with the design/benchmark phases. Two deliberate changes to the directive's order are explained below the table.

| # | Phase | Goal | Key deliverables | Exit criteria |
|---|---|---|---|---|
| 1 | **S1 — Source & playback contracts** (pure Kotlin) | Make the architecture real and testable before any provider | Gradle skeleton (build-logic, catalog); `core:model` (Track, identifiers, VersionInfo, AudioQuality…); `sources:api` (descriptor/Basis, capabilities, facets, SourceRegistry, SourceHealthMonitor, StreamResolver, ResolveOutcome, PlaybackTarget, PlayableMedia, TrackNormalizer, TrackMatcher, QualityReport formatter); `player:api` (QueueManager, status reducer, PlaybackRouter + engine interfaces); fakes (`FakeMusicSource`, `FakeRemoteProviderController`, `FakeEngine`) | JVM suite green: registration, capability detection, normalization, the full matching corpus (same song two sources; same title different recording; remix/original; live/studio; explicit/clean; album/single/radio edit; remaster; re-recording; sped-up), duplicate detection, resolution & expiry, health/breaker, fallback tiers, router handoffs, queue invariants, quality honesty |
| 2 | **Phase 3 — Design system** | Tokens & primitives | Theme, type, glass engine + tiers, PodWheel + gesture detector + haptics, FocusLens, rows, TitleBar, state components, Wheel Lab, Roborazzi baselines | Components in light/dark/HC × tiers; wheel tuned on a device |
| 3 | **S2 — Local source** | First real provider | `sources:local` (MediaStore facets, permissions), Room 3 minimal schema (track/album/artist/source_account/track_equivalence/queue), LibrarySync | Real on-device library browsable from DB; capability snapshot correct with/without permission |
| 4 | **S3 — Media3 direct-stream engine** | Real playback through the contracts | `player:service`: PlaybackService, `DirectStreamEngine`, ResolvingDataSource ↔ `StreamResolver`, pinning, 401/403 re-resolve, measured-quality reconciliation, session policy (`onConnectAsync`), notification on API 36/37 (R-07) | Plays local library; background, lock screen, Bluetooth verified; quality labels honest |
| 5 | **Phase 4 — Benchmark vertical slice** | Prove the feel on real data | Home → Music → Songs → Now Playing → Up Next on the Local source (fixture source for tests/emulator) — `vertical-slice-plan.md` | Slice exit criteria; **user reviews the benchmark** |
| 6 | **S4 — OpenSubsonic source** (historical) | User-owned streaming & lossless | Built under D-37; retired under D-48 | Multi-source setup replaced by YouTube Music under D-48 |
| 7 | **S5 — Remote playback** | Second playback route | `RemoteEngine`, router hard-cut handoffs, session ownership release/restore | PLAYBACK_TARGETS.md §9 tests |
| 8 | **S6 — Source UI integration** | UI consumes sources generically | Settings ▸ Music Sources, capability-driven rendering, availability reasons, "Playing from …"/owner chip, Signal Path | No provider-name branching in features |
| 9 | Phase 7 — Library | Likes, playlists, history, browse screens, context menus, outbox sync | | as before |
| 10 | Phase 8 — Offline | Downloads (DownloadFacet-gated), storage, offline UI | | as before |
| 11 | Phase 9 — Discovery | Search fan-out + FTS, autoplay via RecommendationFacets, Album Flow, lyrics chain | | as before |
| 12 | **S7 — Audius** (historical) | Public catalogue | Built under D-34; retired under D-48 | Retired |
| 13 | **S8 — YouTube Music (D-48)** | Sole online provider | `sources:youtubemusic`: catalogue, search, library, direct Media3 streaming (BitChord reference) | Authoritative online provider; in-app Media3 playback |
| 14 | Phases 10–12 | Polish, testing, ship | | as before |

## Evolution of Provider Architecture
1. **Media3 before more providers.** Wiring `DirectStream` → Media3 with the Local source proved resolution, pinning, quality reconciliation, and system integration with zero network variance. Local offline playback is stable and preserved untouched.
2. **YouTube Music as sole online provider (D-48).** Earlier plans deferred YouTube to S8 and treated direct streaming as out of bounds under former ADR-013. Under **D-48**, YouTube Music is the sole online provider for Podium as a personal sideloaded player, using direct in-app Media3 streaming with BitChord as reference. Audius and OpenSubsonic are retired.

## Working agreements
- Conventional commits; docs updated in the same commit as behaviour.
- Under D-48, BitChord serves as an active architectural and implementation reference. Factual GPL-3.0 licensing notices are respected on extracted/shared modules.
- Offline/local playback is stable, frozen, and regression-tested.
- Device-only claims are backed by recorded measurements or dated checklist entries.

## Estimated effort (relative units ≈ one focused session)
S1 ≈ 1.5 · Phase 3 ≈ 1.0 · S2 ≈ 1.0 · S3 ≈ 1.0 · Phase 4 ≈ 1.0 · S4 ≈ 1.5 · S5 ≈ 1.0 · S6 ≈ 1.0 · Phases 7–9 ≈ 3.5 · S7 ≈ 0.5 · S8 ≈ 1.0 per approved option · Phases 10–12 ≈ 2.0.
