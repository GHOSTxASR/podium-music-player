# ADR-004 — Local database and preferences

**Status:** Accepted · **Date:** 2026-10-02

## Context
Podium stores user data (likes, playlists, history, queue, downloads) that must never be lost, plus synced library metadata (potentially 50k+ tracks) that must be queryable with low latency and full-text search offline.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| **Room 3.0 + `BundledSQLiteDriver`** | Coroutines/Flow-first, KSP-only, KMP-ready, auto-migrations + exported schemas, JVM tests without Robolectric, bundled SQLite guarantees **FTS5** and JSON functions on every API level | Stable only since 2026-07-01; fewer community examples; +~1 MB per ABI for bundled SQLite |
| Room 2.8.5 | Mature, huge ecosystem | Framework SQLite (FTS5 not guaranteed on older devices), future migration to Room 3 likely |
| SQLDelight 2 | SQL-first, KMP, excellent | Second paradigm vs Android norms; migrations hand-written |
| Realm / ObjectBox | Fast object stores | Weak relational queries; Realm Kotlin deprecated |

## Decision
- **Room 3.0.x** with `androidx.sqlite:sqlite-bundled`, schema export committed under `core/database/schemas/`, auto-migrations where possible, hand-written migrations otherwise, every migration covered by `MigrationTestHelper` tests.
- **Preferences** live in **DataStore (Proto)** — typed, transactional, not relational. (The brief lists "UserPreferences" among DB entities; DataStore is the right tool for it, and it's documented in `data-model.md`.)
- Pre-migration safety: before opening a DB whose schema version will change, copy the file to `podium.db.bak-v<old>`. On migration failure: restore, open read-only "safe mode", offer export, never `fallbackToDestructiveMigration` for user tables.

## Why
FTS5 on all devices is decisive for instant offline search over a synced library; Room 3's coroutine-first API matches the architecture; adopting Room 3 now avoids a forced migration later.

## Tradeoffs
New major version risk. **Fallback:** Room 2.8 has near-identical annotations; the switch is package renames plus replacing `SQLiteConnection` usage in two places (callbacks, FTS triggers).

## Future implications
KMP-ready if desktop/iOS ever happens. JSON columns (capabilities, format info) can be queried with SQLite JSON functions.
