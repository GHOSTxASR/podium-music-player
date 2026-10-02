# ADR-005 — Download architecture

**Status:** Accepted · **Date:** 2026-10-02

## Context
Downloads are user-owned library items: they must survive cache cleanup, be verifiable, be accounted for in storage, be deletable precisely, and play offline through the same pipeline as streams. Android 14+ prefers **User-Initiated Data Transfer (UIDT)** jobs for long user-started transfers; `dataSync` foreground services are time-limited on Android 15+.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Media3 `DownloadManager` + `DownloadService` + `SimpleCache` | Built-in, pause/resume, requirements, works with `CacheDataSource` | Downloads stored as cache spans keyed by URL (remote URLs expire → keying needs custom cache keys); download index separate from Podium DB (two sources of truth); uses a `dataSync` FGS; per-file integrity/accounting awkward |
| **Podium `DownloadManager` writing verified files to app-private storage** | One source of truth (Room `download` table); plain files with checksums; precise accounting & deletion; executor can be UIDT on 34+ | We own retry/resume/verification code (~600 LOC + tests) |
| WorkManager only | Simple | Long-running workers ride `dataSync` FGS limits on 15+ |

## Decision
- Podium owns a `DownloadManager` (`:downloads`) with a persisted state machine in the Room `download` table.
- Transport: OkHttp with HTTP `Range` resume into `<file>.part`, atomic rename on success.
- Verification: size check (Content-Length / source-reported size), optional source checksum, then a **decode probe** (Media3 `MetadataRetriever` reads format; first frames decode) before marking `COMPLETED`. Failure → `CORRUPT` → auto-retry once.
- Execution: `DownloadExecutor` interface — **UIDT JobService** on API 34+ (notification via `setNotification`), **WorkManager long-running worker** (foreground `dataSync`) on API 29–33.
- Location: `filesDir/downloads/<sourceKind>/<sha1(trackId)>.<ext>` (internal, private, no permissions). Optional app-specific external volume (SD card) in Settings. Filenames are hashes — no metadata in paths, no traversal risk.
- Only tracks whose `TrackCapabilities.canDownload` is `Allowed` (from the source's `DownloadFacet`) are offered (Subsonic `download`/transcoded `stream`; Audius only when `is_downloadable`; Local = already offline).
- Media3 `SimpleCache` is used **only** for the evictable streaming cache (separate directory, LRU, user-clearable). Clearing caches never touches downloads.

## Why
User-owned files need user-owned semantics. A single DB table drives UI, offline availability, accounting, and recovery.

## Tradeoffs
More code to own and test; no adaptive-stream (HLS/DASH) download support in v1 (none of the launch sources need it).

## Future implications
Adaptive formats could be added by delegating those specific downloads to Media3's downloader behind the same executor interface.
