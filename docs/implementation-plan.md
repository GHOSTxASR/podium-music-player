# Implementation Plan

**Date:** 2026-10-02 (revised after the source-architecture checkpoint) · Loop for every phase: **plan → implement → run → test → inspect → fix → document → commit**. Each phase ends with its exit criteria met, the relevant device checklist passed, docs updated, and a tagged commit.

## Status
| Phase | State |
|---|---|
| 0 Audit · 1 Product/UX spec · 2 Architecture | ✅ 2026-10-02 |
| S0 Source research (BitChord, providers) | ✅ 2026-10-02 — `research/BITCHORD_ARCHITECTURE_REVIEW.md`, `architecture/*`, ADR-013/014 |
| **Checkpoint** | Awaiting user decisions (platform, optional providers D-20/D-21, license D-13, device) |

## Revised order

The directive's source phases (S1–S6) are merged with the design/benchmark phases. Two deliberate changes to the directive's order are explained below the table.

| # | Phase | Goal | Key deliverables | Exit criteria |
|---|---|---|---|---|
| 1 | **S1 — Source & playback contracts** (pure Kotlin) | Make the architecture real and testable before any provider | Gradle skeleton (build-logic, catalog); `core:model` (Track, identifiers, VersionInfo, AudioQuality…); `sources:api` (descriptor/Basis, capabilities, facets, SourceRegistry, SourceHealthMonitor, StreamResolver, ResolveOutcome, PlaybackTarget, PlayableMedia, TrackNormalizer, TrackMatcher, QualityReport formatter); `player:api` (QueueManager, status reducer, PlaybackRouter + engine interfaces); fakes (`FakeMusicSource`, `FakeRemoteProviderController`, `FakeEngine`) | JVM suite green: registration, capability detection, normalization, the full matching corpus (same song two sources; same title different recording; remix/original; live/studio; explicit/clean; album/single/radio edit; remaster; re-recording; sped-up), duplicate detection, resolution & expiry, health/breaker, fallback tiers, router handoffs, queue invariants, quality honesty |
| 2 | **Phase 3 — Design system** | Tokens & primitives | Theme, type, glass engine + tiers, PodWheel + gesture detector + haptics, FocusLens, rows, TitleBar, state components, Wheel Lab, Roborazzi baselines | Components in light/dark/HC × tiers; wheel tuned on a device |
| 3 | **S2 — Local source** | First real provider | `sources:local` (MediaStore facets, permissions), Room 3 minimal schema (track/album/artist/source_account/track_equivalence/queue), LibrarySync | Real on-device library browsable from DB; capability snapshot correct with/without permission |
| 4 | **S3 — Media3 direct-stream engine** | Real playback through the contracts | `player:service`: PlaybackService, `DirectStreamEngine`, ResolvingDataSource ↔ `StreamResolver`, pinning, 401/403 re-resolve, measured-quality reconciliation, session policy (`onConnectAsync`), notification on API 36/37 (R-07) | Plays local library; background, lock screen, Bluetooth verified; quality labels honest |
| 5 | **Phase 4 — Benchmark vertical slice** | Prove the feel on real data | Home → Music → Songs → Now Playing → Up Next on the Local source (fixture source for tests/emulator) — `vertical-slice-plan.md` | Slice exit criteria; **user reviews the benchmark** |
| 6 | **S4 — OpenSubsonic source** | User-owned streaming & lossless | Setup/probe/auth (API key or token), sync, resolve (raw/transcoded), stars, playlists, lyrics, similar songs, health | Contract tests vs Navidrome/Gonic/Ampache fixtures; streams verified on device |
| 7 | **S5 — Remote playback** | Second playback route | `RemoteEngine`, router hard-cut handoffs, session ownership release/restore, OpenSubsonic **jukebox** controller (D-22) | PLAYBACK_TARGETS.md §9 tests; jukebox on a real server (if supported) |
| 8 | **S6 — Source UI integration** | UI consumes sources generically | Settings ▸ Music Sources (ConnectedSource list, add/connect flows, priority, health), capability-driven rendering, availability reasons, "Playing from …"/owner chip, Signal Path | No provider-name branching in features (lint + dependency-guard green) |
| 9 | Phase 7 — Library | Likes, playlists, history, browse screens, context menus, outbox sync | | as before |
| 10 | Phase 8 — Offline | Downloads (DownloadFacet-gated), storage, offline UI | | as before |
| 11 | Phase 9 — Discovery | Search fan-out + FTS, autoplay via RecommendationFacets, Album Flow, lyrics chain | | as before |
| 12 | **S7 — Audius** | Public catalogue | Search/browse/stream, permitted downloads, attribution | Contract tests; rate-limit handling |
| 13 | **S8 — Optional providers (per user decision)** | YouTube Y1/Y2; Spotify S-a/S-b | Provider modules only; no UI changes beyond generic renderings | Matrix re-verified on the day of implementation; policy notes in About |
| 14 | Phases 10–12 | Polish, testing, ship | | as before |

## Why the order differs from the directive's S2→S3(YouTube)→S4(Media3)
1. **Media3 before more providers.** Wiring `DirectStream` → Media3 with the Local source proves resolution, pinning, quality reconciliation, and system integration with zero network variance. Every later provider then plugs into a working pipeline.
2. **YouTube moves to S8 and is conditional.** Its direct-audio path (Y3) is outside ADR-013's boundary and the brief's §65, so a "BitChord-inspired YouTube prototype" can't be the second source. The buildable options (Y1 catalogue + matched playback, Y2 official embed) both depend on the matcher and on at least one authorized playable source (Local/OpenSubsonic) already working.

## Working agreements
- Conventional commits; docs updated in the same commit as behaviour.
- ADR-014 hygiene: BitChord source is not consulted while implementing; no GPL code or dependencies while D-13 is open.
- Each provider phase starts by re-verifying its row in `SOURCE_CAPABILITY_MATRIX.md` (APIs and policies change).
- Device-only claims are backed by recorded measurements or dated checklist entries.

## Estimated effort (relative units ≈ one focused session)
S1 ≈ 1.5 · Phase 3 ≈ 1.0 · S2 ≈ 1.0 · S3 ≈ 1.0 · Phase 4 ≈ 1.0 · S4 ≈ 1.5 · S5 ≈ 1.0 · S6 ≈ 1.0 · Phases 7–9 ≈ 3.5 · S7 ≈ 0.5 · S8 ≈ 1.0 per approved option · Phases 10–12 ≈ 2.0.
