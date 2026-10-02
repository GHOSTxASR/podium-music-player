# Data Model

**Date:** 2026-10-02 · Database: Room 3 + bundled SQLite (ADR-004) · Preferences: Proto DataStore (D-10) · Storage classes: ADR-008.

## 1. Principles
1. **Source-qualified identity** — every remote entity's primary key is `"<sourceId>:<sourceEntityId>"`. Title+artist is never identity.
2. **User data references, doesn't copy** — likes, playlists, history, queue, downloads reference `track.id`. Referenced tracks are **pinned** (never garbage-collected).
3. **Metadata needed offline is stored once** — in `track`/`album`/`artist`; denormalised display strings (`artist_display`, `album_title`) live on `track` to render rows without joins.
4. **No redundant derived data** unless it removes a hot-path join; each denormalisation is noted.
5. **Every write path has a test; every schema change has a migration test.**

## 2. Entity overview

```
source_account 1─* track *─1 album *─1? (album_artist via artist)
                   track *─* artist (track_artist)
track 1─? liked_track            track 1─? download        track 1─* playback_history
playlist 1─* playlist_track *─1 track                       track 1─? lyrics_cache
queue_state (singleton) 1─* queue_item *─1 track            artwork_cache (by artwork_key)
search_history    source_metadata_cache    outbox    track_fts (FTS5, external content)
```

## 3. Tables

Types: `TEXT`, `INTEGER`, `REAL`, `BLOB`. Timestamps are epoch millis `INTEGER`. `NN` = NOT NULL.

### source_account — configured sources
| Column | Type | Notes |
|---|---|---|
| id PK | TEXT | `local`, `audius`, `subsonic:<uuid>` |
| kind | TEXT NN | `LOCAL`, `SUBSONIC`, `AUDIUS`, `FIXTURE` |
| display_name | TEXT NN | "Home server" |
| base_url | TEXT | null for local |
| username | TEXT | |
| credential_ref | TEXT | key into encrypted credential store (never the secret itself) |
| capabilities_json | TEXT NN | last probed `SourceCapabilities` |
| allow_cleartext | INTEGER NN | 0/1, only legal for LAN hosts (D-09) |
| enabled | INTEGER NN | |
| sync_cursor | TEXT | adapter-opaque |
| last_sync_at, created_at | INTEGER | |

### track
| Column | Type | Notes |
|---|---|---|
| id PK | TEXT | `TrackId` |
| source_id | TEXT NN FK→source_account ON DELETE CASCADE | |
| source_track_id | TEXT NN | |
| title | TEXT NN | |
| title_sort | TEXT NN | normalised: lowercased, leading articles/punctuation stripped, NFKD |
| artist_display | TEXT NN | denormalised "A, B & C" (row rendering) |
| album_id | TEXT FK→album | nullable (singles in catalogs) |
| album_title | TEXT | denormalised (row rendering) |
| album_artist_display | TEXT | |
| track_no, disc_no, year | INTEGER | |
| genre | TEXT | primary genre |
| duration_ms | INTEGER | |
| isrc, mbid | TEXT | matching hints only |
| explicit | INTEGER NN DEFAULT 0 | |
| artwork_key | TEXT | → artwork_cache |
| reported_format_json | TEXT | `AudioFormatInfo` as reported by source (may be null) |
| last_played_format_json | TEXT | measured at last play (honest Now Playing for offline/history) |
| in_library | INTEGER NN | 1 if from a Library source sync |
| pin_count | INTEGER NN DEFAULT 0 | maintained by triggers on liked/playlist/download/queue/history refs |
| removed_at | INTEGER | set when a sync no longer sees it; GC after 30 days if `pin_count = 0` |
| updated_at | INTEGER NN | |

Indices: `(source_id, source_track_id)` UNIQUE; `(album_id, disc_no, track_no)`; `(title_sort)`; `(in_library, title_sort)`; `(genre)`.

### album / artist / track_artist
- `album(id PK, source_id, source_album_id, title, title_sort, artist_display, artist_id, year, genre, track_count, duration_ms, artwork_key, in_library, updated_at)`; index `(in_library, title_sort)`, `(artist_id, year)`.
- `artist(id PK, source_id, source_artist_id, name, name_sort, artwork_key, album_count, in_library, updated_at)`; index `(in_library, name_sort)`.
- `track_artist(track_id, artist_id, position, role)` PK `(track_id, artist_id, role)`; role ∈ `PRIMARY`, `FEATURED`, `COMPOSER`.

### playlist / playlist_track — Podium-owned (independent of providers)
| playlist | |
|---|---|
| id PK TEXT (UUID) | |
| name TEXT NN, description TEXT | |
| created_at, updated_at INTEGER NN | |
| remote_source_id, remote_playlist_id TEXT | linked remote playlist (P1 two-way) |
| artwork_mode TEXT NN | `MOSAIC` (first 4 distinct albums) or `CUSTOM` |
| download_auto_sync INTEGER NN | new additions auto-download if the playlist is downloaded |

| playlist_track | |
|---|---|
| id PK INTEGER autoincrement | allows deliberate duplicates |
| playlist_id TEXT NN FK ON DELETE CASCADE | |
| track_id TEXT NN FK | |
| position REAL NN | fractional indexing: insert between neighbours without rewriting; renormalise when gap < 1e-6 |
| added_at INTEGER NN | |
Index `(playlist_id, position)`. Duplicate policy is UI-level: adding an existing track asks "Already in this playlist — Add again / Skip".

### liked_track
`track_id PK FK`, `liked_at NN`, `sync_state` (`LOCAL_ONLY`, `PENDING`, `SYNCED`). Index `(liked_at DESC)`.

### download
| Column | Notes |
|---|---|
| track_id PK | one download per track (duplicate prevention by construction) |
| state NN | `QUEUED`, `WAITING_NETWORK`, `RUNNING`, `PAUSED`, `VERIFYING`, `COMPLETED`, `FAILED`, `CORRUPT`, `CANCELLED` (row deleted on cancel; enum kept for events) |
| quality_tier NN, format_json | requested tier and the actual downloaded format |
| relative_path | `downloads/<kind>/<sha1>.<ext>` relative to the chosen volume |
| volume | `INTERNAL` / `EXTERNAL:<uuid>` |
| bytes_total, bytes_done | |
| sha256 | computed after write; verified on demand |
| group_id FK→download_group | nullable (manual single download) |
| error_code, attempt_count, next_attempt_at | |
| created_at, completed_at, last_verified_at | |
Index `(state, created_at)`, `(group_id)`.

`download_group(id PK, type ALBUM|PLAYLIST|LIKED|ARTIST, ref_id, quality_tier, auto_sync, created_at)`.

### playback_history
`id PK autoincrement`, `track_id NN`, `started_at NN`, `played_ms NN`, `counted INTEGER NN` (≥ min(30 s, 50%)), `context_type` (`ALBUM`, `PLAYLIST`, `SEARCH`, `AUTOPLAY`, `LIKED`, …), `context_ref`, `format_json`, `output_json` (device type, resampled?). Index `(started_at DESC)`, `(track_id, started_at DESC)`. Retention: 18 months or 50k rows, whichever first (user can clear).

### queue_state (singleton row id = 1) & queue_item
- `queue_state`: `current_uid`, `position_ms`, `repeat_mode`, `shuffle_enabled`, `shuffle_seed`, `autoplay_enabled`, `context_type`, `context_ref`, `context_label`, `updated_at`.
- `queue_item`: `uid PK TEXT`, `ordinal INTEGER NN` (current order), `original_ordinal INTEGER` (pre-shuffle order for un-shuffle), `track_id NN`, `origin` (`PLAY_NEXT`, `USER_QUEUED`, `CONTEXT`, `AUTOPLAY`), `added_at`. Index `(ordinal)`.
Writes are full-snapshot replace in one transaction when > 50 items changed, otherwise row-level.

### search_history
`id PK`, `query NN`, `normalized NN UNIQUE`, `last_used_at NN`, `use_count`. Cap 50 (oldest evicted). Disabled by "Save search history: Off".

### lyrics_cache
`track_id PK`, `provider` (`SOURCE`, `EMBEDDED`, `SIDECAR`, `LRCLIB`), `is_synced`, `content` (LRC or plain), `language`, `fetched_at`, `not_found` (negative cache), `expires_at` (positive: 180 days; negative: 7 days).

### artwork_cache
`artwork_key PK` (stable per source entity, e.g. `subsonic:<acct>:al-123`), `url`, `local_path` (pinned copy for downloaded/offline items; else null and Coil's disk cache holds bytes), `width`, `height`, `palette_json` (`dominant`, `ambient`, `luminanceTop`, `luminanceBottom`, `isDark`), `computed_at`.

### source_metadata_cache — catalog responses
`key PK` (`<sourceId>|<kind>|<arg>`), `source_id`, `kind` (`SEARCH`, `ALBUM`, `ARTIST`, `PLAYLIST`, `TRENDING`), `payload_json`, `fetched_at`, `expires_at`. Evictable.

### outbox — offline writes to sync
`id PK`, `source_id`, `op` (`STAR`, `UNSTAR`, `PLAYLIST_ADD`, …), `payload_json`, `created_at`, `attempts`, `last_error`. Coalesced (STAR then UNSTAR of the same id cancel).

### track_fts (FTS5)
External-content FTS5 over `track(title, artist_display, album_title, genre)` with `unicode61 remove_diacritics 2` tokenizer + prefix indexes `prefix='2 3'`. Maintained by triggers. Scope: library + pinned tracks (catalog-only cached tracks are not indexed).

## 4. Preferences (Proto DataStore)

```proto
message PodiumSettings {
  Theme theme = 1;                   // AUTO, LIGHT, DARK
  Highlight highlight = 2;           // BLUE, GRAPHITE, ARTWORK
  Transparency transparency = 3;     // FULL, REDUCED, OFF
  WheelSettings wheel = 4;           // size (S/M/L), handedness, visibility (ALWAYS, AUTO, HIDDEN)
  bool haptics = 5;
  Clicker clicker = 6;               // OFF, ON
  bool show_now_playing_on_play = 7;
  QualityPolicy quality = 8;         // wifi tier, cellular tier, download tier, allow cellular downloads
  bool gapless = 9; ReplayGain replay_gain = 10; Crossfade crossfade = 11;
  bool autoplay = 12;
  bool save_search_history = 13;
  LyricsConsent lyrics_online = 14;  // UNASKED, ALLOWED, DENIED
  bool skip_unavailable_offline = 15;
  bool bit_perfect_usb = 16;
  string onboarding_version_seen = 17;
}
```
Credentials are **not** here: they live in an encrypted store (AES-256-GCM key in Android Keystore; ciphertext in a separate DataStore file excluded from backup).

## 5. Migrations
- Schema JSON exported to `core/database/schemas/` and committed.
- Auto-migrations for additive changes; manual `Migration` classes for renames/splits.
- `MigrationTestHelper` test for every version pair `n → n+1` plus `1 → latest`.
- Pre-migration file backup and safe-mode behaviour per ADR-004.
- Downgrades are not supported; installing an older build over a newer schema opens safe mode with export.

## 6. Garbage collection
Daily (`LibraryMaintenanceWorker`, idle + charging): delete `track` rows with `pin_count = 0` and (`in_library = 0` or `removed_at < now − 30 d`); orphan albums/artists; expired `source_metadata_cache`/`lyrics_cache`; artwork files whose key is unreferenced. Downloads are never touched by GC (they pin their track).

## 7. Size estimates
50k-track library: `track` ≈ 18 MB, FTS ≈ 6 MB, albums/artists ≈ 2 MB, history (1 yr heavy use ≈ 15k rows) ≈ 3 MB. Acceptable.
