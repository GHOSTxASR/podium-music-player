# ADR-008 — Offline architecture

**Status:** Accepted · **Date:** 2026-10-02

## Context
Offline must be a normal mode, not an error: startup, browsing, search, playback of available items, and editing of likes/playlists must all work without network, and cleanup must never destroy user data.

## Options considered
- Cache-everything-opportunistically (HTTP cache as the offline store). Rejected — eviction makes offline behaviour unpredictable.
- **Explicit storage classes + synced library metadata + connectivity-aware domain.** Chosen.

## Decision
Three storage classes with different lifetimes:
| Class | Contents | Eviction |
|---|---|---|
| **User-owned** | Downloads (files), likes, playlists, history, queue, preferences, credentials | Never automatic. Only explicit user action. |
| **Pinned metadata** | Track/album/artist rows and artwork referenced by any user-owned item or by a Library source sync | Never while referenced; garbage-collected when unreferenced |
| **Evictable cache** | Streaming audio cache (Media3 `SimpleCache`, LRU), unpinned artwork, search results, catalog metadata, lyrics | LRU/TTL; "Clear cache" in Settings |

- Library sources are synchronised (ADR-002) → all browsing is local.
- `ConnectivityMonitor` exposes `NetworkState(online, validated, metered)`; repositories and the playback resolver consult it, never the UI directly.
- Playability is computed per track: `Local` ∨ `Downloaded` ∨ (`online` ∧ source reachable). Unplayable rows are dimmed with a reason, never hidden.
- Writes made offline (like, playlist edits) are applied locally immediately and queued in an `outbox` table for two-way sync with sources that support it.

## Why
Predictability: users can reason about what works offline. Correctness: no cleanup path can reach user-owned data.

## Tradeoffs
Library sync uses storage and time; outbox sync needs conflict rules (last-writer-wins per field, documented in `offline-architecture.md`).

## Future implications
"Offline mode" toggle (pretend offline) is free to add because everything already branches on `NetworkState`.
