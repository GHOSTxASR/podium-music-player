# ADR-002 — Music source architecture

**Status:** Accepted · **Date:** 2026-10-02

## Context
The brief requires a provider-agnostic `MusicSource` abstraction and asks for YouTube Music research, while forbidding circumvention of access controls, DRM, subscription restrictions, or authentication barriers. Research (`research/2026-10-02-music-sources.md`) shows that every working YouTube Music client relies on client impersonation, cipher deobfuscation (held to be TPM circumvention by OLG Hamburg, 2024), and BotGuard/PoToken generation (anti-bot evasion), and offers Premium-only features for free.

## Options considered
1. **Wire the app to YouTube Music (InnerTune-style).** Rejected — violates the brief's own §65 rule and YouTube's ToS; breaks monthly; region-locked; GPL contagion if code is borrowed; blocks Play distribution.
2. **YouTube Data API v3 + embedded player.** Rejected — no audio streams, visible-player requirement, 100 searches/day quota.
3. **Provider-agnostic core with legitimate adapters at launch (Local, OpenSubsonic, Audius) and a documented extension point.** Chosen.
4. Subscription SDKs (TIDAL, Apple MusicKit for Android). Deferred — TIDAL full playback is not publicly available to third parties; MusicKit needs the user's paid developer membership. Watch items.

## Decision
- `sources:api` (pure Kotlin) defines `MusicSource` with **declared capabilities**; the app layer only talks to `SourceRegistry` and domain models. No provider type escapes its adapter module.
- Two source **kinds**:
  - **Library** (enumerable, user-owned: Local, OpenSubsonic, later Jellyfin) → metadata is **synchronised** into Room by `LibrarySync`; browsing reads the DB.
  - **Catalog** (searchable, not enumerable: Audius, future services) → queried live with TTL caches; items enter the DB only when the user likes, playlists, plays, or downloads them.
- Stream resolution is lazy and per-play: `MusicSource.resolveStream(trackRef, QualityRequest)` returns a `ResolvedStream` with **actual** format metadata and an expiry.
- Track identity is source-qualified: `TrackId("subsonic:<accountId>:<id>")`. Never title+artist.
- Podium will not implement a YouTube adapter or any component that deciphers signatures, generates PoTokens, impersonates official clients, or downloads from services that don't sanction it.

## Why
Delivers a complete, honest, distributable product today; each adapter maps cleanly to the capability model; Library-sync makes the wheel feel instant on 50k-track libraries (no network paging during scrolling).

## Tradeoffs
- No "free full mainstream catalog" out of the box. Users without a server or local files get Audius (independent catalog) only.
- Sync costs storage (~20 MB DB for 50k tracks) and an initial sync time.

## Future implications
- New providers = new module implementing `MusicSource` + capability flags. UI adapts to capabilities (e.g., hides Download when `canDownload(track) == false`).
- Official partner SDKs (TIDAL, MusicKit) fit as Catalog sources whose `resolveStream` returns a `PlaybackDelegate` instead of a URL (designed into `ResolvedStream` as a sealed type).
- See `music-source-analysis.md` for the full analysis and the open user decision.
