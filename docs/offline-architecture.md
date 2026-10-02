# Offline Architecture

**Date:** 2026-10-02 · ADR-008 · Related: `download-system.md`, `data-model.md` §6.

## 1. What "offline" means in Podium
Podium is usable offline by design, not as a degraded mode:
| Capability | Offline behaviour |
|---|---|
| Launch | Instant; no network calls block first frame |
| Browse library | Full (Library sources are synced; catalog items the user saved are pinned) |
| Search | Local FTS over library + pinned tracks; banner "Showing results from your library" |
| Play | Local files, verified downloads, and stream-cache-complete tracks |
| Like / playlists / queue edits | Applied locally; synced later via outbox |
| Lyrics | Cached lyrics and embedded lyrics |
| Artwork | Pinned artwork for downloaded/liked/playlisted items; others show a monogram placeholder |
| Downloads | Queue persists; transfers wait in `WAITING_NETWORK` |

## 2. Connectivity model
`ConnectivityMonitor` (`ConnectivityManager.registerDefaultNetworkCallback`) emits:
```kotlin
data class NetworkState(val online: Boolean, val validated: Boolean, val metered: Boolean, val transport: Transport)
```
- `online` = has a default network; `validated` = `NET_CAPABILITY_VALIDATED`. Captive portals → online but not validated → treated as offline for remote sources.
- Per-source **health** is separate: a LAN Subsonic server can be unreachable while the internet works (and vice versa when away from home). `SourceHealth` comes from the adapter's last calls with a lightweight `ping` on transitions; never from the global network state alone.

## 3. Playability
```kotlin
fun playability(track, net, health, downloads): Playability =
  when {
    track.isLocal                        -> Playable(LOCAL)
    downloads.isVerified(track.id)       -> Playable(DOWNLOADED)
    streamCache.isFullyCached(track.id)  -> Playable(CACHED)
    !net.validated                       -> Unavailable(OFFLINE)
    health[track.source] is Unreachable  -> Unavailable(SOURCE_UNREACHABLE)
    health[track.source] is AuthRequired -> Unavailable(SIGN_IN_REQUIRED)
    else                                 -> Playable(STREAM)
  }
```
Rows render unavailable items at 40% label opacity with a trailing reason glyph; focusing one shows the reason in the status line; Center on it explains instead of failing silently ("You're offline. Download this album to play it anywhere."). Never hidden — hiding would make the library appear to change.

**"Downloaded only" filter:** each library list's More menu offers it; when offline, a one-tap chip "Show downloaded" appears at the top of lists with unavailable items.

## 4. Offline writes & sync (outbox)
- Local write first, always (optimistic). Outbox rows record remote effects for sources with matching capabilities.
- `SyncWorker` (WorkManager, `CONNECTED` constraint, expedited when the app is visible) flushes per source in order; coalesces opposite ops.
- **Conflicts:** likes — last writer wins by timestamp (server `starred` date vs local `liked_at`). Linked playlists (P1) — Podium is the editor of record; a server-side change after the last sync creates a "Conflicted copy" playlist rather than silently merging.
- Failures: 4xx (not permitted/not found) drop the op and notify once; 5xx/timeouts back off.

## 5. Caches vs user data (lifetimes)
| Store | Class | Location | Cleared by |
|---|---|---|---|
| Downloads (audio files) | User-owned | `files/downloads` (or app-specific external volume) | Only explicit delete |
| Database (likes, playlists, history, queue, synced metadata) | User-owned + pinned | `databases/podium.db` | Never (export/reset in Settings) |
| Credentials | User-owned | encrypted DataStore file | Remove source |
| Pinned artwork | Pinned | `files/artwork` | GC when unreferenced |
| Streaming cache | Evictable | `cache/stream` (Media3 SimpleCache, LRU 512 MB default) | LRU, "Clear streaming cache", OS cache pressure |
| Image cache (Coil) | Evictable | `cache/images` (256 MB) | LRU, "Clear artwork cache" |
| Catalog/search/lyrics caches | Evictable | Room tables with TTL | TTL + GC |
| Diagnostics log | Evictable | `cache/diagnostics` (2 MB ring) | Rotation |

`cacheDir` content may be removed by the OS at any time; nothing user-owned lives there.

## 6. Startup offline (cold)
1. Open DB (no network), read settings, restore back stack and queue (paused).
2. `ConnectivityMonitor` initial state; sources' health = `Unknown` → rows render as playable-if-local/downloaded, others "Checking…" for ≤ 2 s, then resolved.
3. No sync, no catalog calls until validated network.

## 7. Backup
`dataExtractionRules`: include `databases/podium.db` and settings DataStore (likes, playlists, history are precious); exclude credentials, downloads, caches. Restored installs re-download nothing automatically; downloads list shows "Download again" for groups that were downloaded (group rows are in the DB).
