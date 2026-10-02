# Download System

**Date:** 2026-10-02 · ADR-005 · Module: `:downloads`.

## 1. Goals
Queue, pause, resume, retry, cancel, progress, storage accounting, duplicate prevention, metadata persistence, artwork caching, offline playback, corruption detection, deletion, album/playlist/liked/artist group downloads — with downloads behaving as real library items.

## 2. Components
```
DownloadManager (API used by UI/repos)
 ├─ DownloadPlanner      expands groups → per-track requests; checks canDownload(); dedupes
 ├─ DownloadStore        Room `download`, `download_group` (single source of truth)
 ├─ DownloadExecutor     interface; UidtExecutor (API 34+) | WorkManagerExecutor (API 29–33)
 │    └─ TransferEngine  OkHttp + Range resume → .part → fsync → rename
 ├─ Verifier             size → optional checksum → decode probe → sha256 record
 ├─ ArtworkPinner        copies album art to files/artwork/<key>.webp, sets artwork_cache.local_path
 ├─ StorageAccountant    sums bytes by group/source; free-space checks; volume selection
 └─ DownloadIndex        fast lookup for StreamResolver (trackId → verified file)
```

## 3. State machine (per track)
```
           enqueue                 constraints ok              bytes done          verify ok
  (none) ─────────► QUEUED ─────────────────────► RUNNING ───────────► VERIFYING ─────────► COMPLETED
                      ▲  │ no network                 │  │ pause            │ verify fail          │
                      │  ▼                            │  ▼                  ▼                      │ re-verify fail
                WAITING_NETWORK ◄── network lost ─────┘ PAUSED            CORRUPT ── auto-retry ×1 ┘ (→ CORRUPT)
                      │                               │  │ resume              │
                      └──────────── retry/backoff ◄── FAILED ◄── error ────────┘ (after retry)
  cancel (any state) → row + files deleted
```
- Retries: transient errors (timeouts, 5xx, connection reset) → exponential backoff 30 s → 2 min → 10 min → 1 h (max 5 attempts) then `FAILED` (user can retry). 4xx (403 not permitted, 404) → `FAILED` immediately with a clear reason.
- Pause is per track, per group, or global ("Pause all"). Pause keeps `.part` files for Range resume.

## 4. Planning & duplicate prevention
- `download.track_id` is the primary key → a track can only be downloaded once; requesting it again via another group attaches the group reference (download serves both groups; deleting one group keeps the file if another references it).
- Groups: `ALBUM`, `PLAYLIST`, `LIKED`, `ARTIST`. With `auto_sync`, adding a track to a downloaded playlist (or liking a track when Liked is downloaded) enqueues it; removing it removes the download unless another group or a manual download references it.
- Non-permitted tracks in a group are skipped with a summary: "12 of 14 songs downloaded. 2 aren't available for download."

## 5. Execution
| API | Executor | Notes |
|---|---|---|
| 34+ | **UIDT** `JobScheduler` job (`setUserInitiated(true)`, `RUN_USER_INITIATED_JOBS`), network constraint per policy, `setEstimatedNetworkBytes` | Must be scheduled while the app is visible (it is: user tapped Download). Notification via `setNotification`. |
| 29–33 | WorkManager `CoroutineWorker` with `setForeground` (`dataSync`) | Unique work name per batch; constraints mirror policy. |
- Concurrency: 2 parallel transfers (configurable 1–4); FIFO within priority; manual single downloads outrank group backfill.
- Network policy: Wi-Fi only by default; "Download on cellular" toggle; respects metered flag.
- Notification: one ongoing notification — title "Downloading Kind of Blue", text "3 of 14 songs" — with Pause/Cancel actions; completion notification summarises; requires `POST_NOTIFICATIONS` (requested at first download with rationale).

## 6. Transfer & verification
1. Resolve download URL via `StreamResolver.resolve(ResolveRequest(track, DOWNLOAD, tier))` using the source's `DownloadFacet` (Subsonic `download` for Original; `stream?format=` for transcoded tiers).
2. GET with `Range: bytes=<done>-` if `.part` exists and server supports ranges (`Accept-Ranges`/206), else restart.
3. Write to `<volume>/downloads/<kind>/<sha1(trackId)>.<ext>.part`; periodic progress to Room (≤ 2 Hz).
4. Verify: (a) bytes == Content-Length or source-reported size; (b) source checksum if provided; (c) decode probe — Media3 `MetadataRetriever` returns a track `Format` with the expected codec and duration within 2% of metadata; (d) compute sha256 → store.
5. `fsync`, atomic rename, `COMPLETED`, record `format_json` (actual), pin artwork.
- Free-space guard: before start, require `estimated + 200 MB` free; else `FAILED(StorageFull)` with a Storage link.

## 7. Integrity over time
- On app start (idle) and weekly: `stat` all completed files; missing → mark `FAILED(missing)` and surface "2 downloads went missing. Download again?"; size mismatch → `CORRUPT`.
- Playback of a downloaded file that fails decoding → `CORRUPT`, falls back to streaming if online.
- Full sha256 re-verification is on demand (Settings ▸ Storage ▸ Verify downloads).

## 8. Deletion & storage accounting
- Delete track/group/all → files removed first, then rows (crash between steps is healed by the integrity pass).
- Storage screen: totals for Downloads (by group, by source), Streaming cache, Artwork cache, Lyrics cache, Database; actions: Clear streaming cache, Clear artwork cache (unpinned only), Delete all downloads (confirm, shows size).
- Volume choice (Internal / SD card via `getExternalFilesDirs`) — moving downloads between volumes is a background copy + verify.

## 9. Offline playback integration
`StreamResolver` checks `DownloadIndex` first → `file://` with the downloaded format → Now Playing label reflects the *downloaded* format (which may differ from streaming tier).

## 10. Edge cases (tested)
Interrupted transfer (app killed) → resumes from `.part`; partially downloaded track → never playable offline until verified; source removed → downloads remain playable, marked "Source removed"; credentials changed → resolve fails `AuthRequired` → group paused with banner; duplicate requests from rapid taps → single row; disk full mid-write → `.part` kept, `FAILED(StorageFull)`.
