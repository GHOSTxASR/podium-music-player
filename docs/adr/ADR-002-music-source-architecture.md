# ADR-002 — Music source architecture

**Status:** Accepted, **superseded in part by ADR-013 and D-44** · **Date:** 2026-10-02 (updated 2026-10-06)
> The Library (synced) vs Catalog (live) distinction, source-qualified identity, and lazy per-play resolution below remain in force. The early "no YouTube adapter" restriction has been superseded by D-44: YouTube Music is the sole online provider with direct in-app streaming in Media3 for Podium's sideloaded personal player.

## Context
The initial brief required a provider-agnostic `MusicSource` abstraction and asked for YouTube Music research, while exploring initial constraints. Research (`research/2026-10-02-music-sources.md`) documented how existing clients operate.

## Options considered
1. **Wire the app to YouTube Music (InnerTune-style).** (Initially rejected; later re-evaluated under D-44 for sideloaded personal use with BitChord as reference).
2. **YouTube Data API v3 + embedded player.** Rejected — no audio streams, visible-player requirement, 100 searches/day quota.
3. **Provider-agnostic core with legitimate adapters at launch (Local, OpenSubsonic, Audius) and a documented extension point.** Chosen initially.
4. Subscription SDKs (TIDAL, Apple MusicKit for Android). Deferred — TIDAL full playback is not publicly available to third parties; MusicKit needs the user's paid developer membership. Watch items.

## Decision
- `sources:api` (pure Kotlin) defines `MusicSource` with **declared capabilities**; the app layer only talks to `SourceRegistry` and domain models. No provider type escapes its adapter module.
- Two source **kinds**:
  - **Library** (enumerable, user-owned: Local, OpenSubsonic, later Jellyfin) → metadata is **synchronised** into Room by `LibrarySync`; browsing reads the DB.
  - **Catalog** (searchable, not enumerable: Audius, YouTube Music) → queried live with TTL caches; items enter the DB only when the user likes, playlists, plays, or downloads them.
- Stream resolution is lazy and per-play: `MusicSource.resolveStream(trackRef, QualityRequest)` returns a `ResolvedStream` with **actual** format metadata and an expiry.
- Track identity is source-qualified: `TrackId("<sourceId>|<id>")`. Never title+artist.
- **Superseded policy note:** The original clause stating Podium will not implement a YouTube adapter is superseded by D-44. YouTube Music is the sole online provider.

## Why
Delivers a complete, honest, distributable product today; each adapter maps cleanly to the capability model; Library-sync makes the wheel feel instant on 50k-track libraries (no network paging during scrolling).

## Tradeoffs
- No "free full mainstream catalog" out of the box. Users without a server or local files get Audius (independent catalog) only.
- Sync costs storage (~20 MB DB for 50k tracks) and an initial sync time.

## Future implications
- New providers = new module implementing `MusicSource` + capability flags. UI adapts to capabilities (e.g., hides Download when `canDownload(track) == false`).
- Official partner SDKs (TIDAL, MusicKit) fit as Catalog sources whose `resolveStream` returns a `PlaybackDelegate` instead of a URL (designed into `ResolvedStream` as a sealed type).
- See `music-source-analysis.md` for the full analysis and the open user decision.
