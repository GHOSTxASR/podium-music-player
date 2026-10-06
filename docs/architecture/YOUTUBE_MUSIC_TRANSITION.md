# YouTube Music Transition

**Date:** 2026-10-06 · **Status:** proposal, now implemented — see *Execution outcome* at the end and `YOUTUBE_MUSIC_ARCHITECTURE.md` · **Baseline:** `main` at `6f0e6af` (O11 `7798c59` + database-backup fix)
**Inputs:** the code at that commit; [decision-log.md](../decision-log.md) (D-13, D-17–D-21, D-34–D-37); [MUSIC_SOURCE_ARCHITECTURE.md](MUSIC_SOURCE_ARCHITECTURE.md); [PLAYBACK_TARGETS.md](PLAYBACK_TARGETS.md); [SOURCE_CAPABILITY_MATRIX.md](SOURCE_CAPABILITY_MATRIX.md); [research/YOUTUBE_SOURCE_INVESTIGATION.md](../research/YOUTUBE_SOURCE_INVESTIGATION.md) (2026-10-04); [research/BITCHORD_ARCHITECTURE_REVIEW.md](../research/BITCHORD_ARCHITECTURE_REVIEW.md) (2026-10-02); BitChord's public README, v1.8 release notes (2026-10-05) and repository metadata.

**Hygiene (ADR-014).** BitChord is a reference, not a base. Its source files weren't opened for this document because the next phase implements Podium's source layer, and ADR-014 rule 2 keeps BitChord's source closed while doing that. Statements about BitChord internals come from the two hygiene-compliant reviews above, which studied its source in prose. Nothing here reproduces code, constants, request shapes or client identities.

**Evidence labels** (as in the investigation): **[P]** verified in Podium's code; **[B]** BitChord (via the reviews or its public materials); **[YT]** official YouTube/Google documentation as cited and dated in the investigation; **[A]** Android platform behaviour; **[I]** inference; **[?]** unknown, needs verification.

**What this document does not do.** It doesn't decide D-20, and it doesn't override the investigation. Where it adds something the investigation didn't cover (§13.4, delegated playback), it says so.

---

## 1. Executive summary

1. **Offline is stable and can stay frozen.** Its data, screens and scanning are separate from Online. It does share one spine with Online: the source registry, the resolver, the playback engine, the queue and the media session. So the boundary is drawn *at* that spine, not by forking it (§3).
2. **Most of the multi-source layer has no job once there's one online provider.** Fan-out, aggregation, grouping, equivalence, cross-source fallback, source priority and user-added server forms exist to combine several online sources (§6).
3. **The rest carries over unchanged or nearly so:**
   - the facet contracts and models;
   - the Online UI contract (`OnlineRepository`, `OnlinePlace`);
   - the account-keyed online tables;
   - sign-in state and the Keystore credential store;
   - miss vs failure and the breaker;
   - the environment boundary;
   - the autoplay engine.

   O11 built most of what a signed-in provider needs (§5).
4. **YouTube Music fits as one ONLINE `MusicSource`** in its own module. The current Online screens already render search, home shelves, artists, albums, playlists, likes and history from any such source (§7).
5. **The blocker is playback ownership.** Podium can't legitimately make YouTube Music audio play through *its own* Media3 player in the background with its own notification:
   - Every app that does so (BitChord, Convx) relies on the stream-unlock layer: client-identity rotation, PoToken/BotGuard, cipher solving **[B]**. CLAUDE.md and ADR-013 exclude that.
   - YouTube's policies forbid separating audio from video and background players for API clients **[YT]**.

   This is marked **BLOCKED** (§13, §21).
6. **Three legitimate playback models remain:**
   - **P1, the official embedded player** (D-20 Y2): visible video, foreground only, no background.
   - **P2, delegating playback to the official YouTube Music app**, controlled through Android's media-session APIs. This is **new**; the investigation didn't evaluate it. It matches PLAYBACK_TARGETS §5.2 and §6 ("remote target, provider owns system controls"). Background play follows the listener's own YouTube Music entitlement. It's unverified and needs a device spike.
   - **P0, hand-off only** ("Open in YouTube Music").

   P2 is closest to "my YouTube Music library through an iPod".
7. **The YouTube Music product model exists only in the unofficial InnerTube API.** That model is home, albums, artists, the library, Liked music, history and radio.
   - InnerTube is D-20 option Y1: `Basis.UNOFFICIAL_API`, opt-in, not in Play builds (D-19).
   - The official Data API with OAuth exposes a *YouTube video* model instead: playlists, liked videos, subscriptions and a very small search quota. It has no home feed, albums or history **[YT]**.
8. **D-20 needs re-deciding.** As written, Y1 plays songs through EXACT matches on the listener's other sources. That route disappears under the new direction: Audius and OpenSubsonic go, and Online may never fall back to local files.
9. **Next step: Y0.** The user decides the catalogue path and the account model, and two device spikes (P2 delegated, P1 embed) settle the playback model *before* any product code. Y1 then ships a read-only catalogue with P0 hand-off, which is useful whatever the spikes show (§23).

---

## 2. Current Podium architecture

```
                                   app (AppGraph = composition root)
   ┌──────────────┬──────────────┬───────────────┬────────────────┬───────────────┐
feature:library  feature:online  feature:nowplaying  feature:settings     (PodiumApp: Dest, navigation)
   │                 │                  │                   │
   └───── core:designsystem / core:interaction (paper, Wheel, glass rules) ─────────┘
   │                 │                  │                   │
player:api ── PlaybackController, QueueManager, QueueItemResolver, TrackCatalog, AutoplayEngine,
   │           SourceRecommendationEngine, PlaybackRouter (types; not wired), QueueStore
player:service ── PlaybackService (MediaLibraryService), PodiumPlaybackEngine (ExoPlayer,
   │               ResolvingDataSource → DirectStream only), MediaControllerPlaybackController
sources:api ── MusicSource + facets, Capabilities, SourceDescriptor/Basis/environment,
   │            SourceRegistry, SourceSettings, SourceHealthMonitor, StreamResolver, TrackMatcher,
   │            aggregate/{SourceFanOut, MultiSourceCatalog, TrackGrouper}, resolve/Equivalence,
   │            SourceSetup (ConfiguredSources, CredentialStore, SetupForm), NetworkPolicy
sources:local   sources:audius   sources:subsonic   sources:test (debug)
core:database (Room 3, schema v4) ── LibraryStore, DatabaseFavorites, DatabaseQueueStore,
                                       OnlineLibraryStore, DatabaseEquivalenceStore
core:model / core:common (pure Kotlin)
```

**One play, end to end [P]:**
1. A screen calls `PlaybackController.playContext(ids…)`, implemented by `MediaControllerPlaybackController`.
2. The command reaches `PlaybackService` as a custom session command.
3. `PodiumPlaybackEngine` hands it to the `QueueManager`, the single writer.
4. Each item is resolved before it opens: `QueueItemResolver` → `StreamResolver` (pinned → owned copy → own source → EXACT fallback within the environment).
5. The source's `PlaybackFacet` returns `PlaybackTarget.DirectStream(PlayableMedia)`.
6. ExoPlayer reads it through `ResolvingDataSource`, with `NetworkPolicy.permits` checked first.

The session publishes who served the item (D-36).

**Tests:** 429 JVM/Robolectric tests at `6f0e6af`: 428 passing at O11, plus the database-backup test.

---

## 3. Stable offline architecture

### 3.1 Frozen: do not touch

| Area | Components | Where |
|---|---|---|
| Scanning and folders | `LocalMusicSource` (MediaStore; Library, Folder, Catalog, Playback → `DirectStream(content://…)`, Artwork facets), `LocalTrackMapper`, `RegistryMusicFolders`, `MusicFoldersScreen` | `sources:local`, `app`, `feature:settings` |
| Library cache | `LibraryStore`, `DatabaseLibraryRepository`, tables `track` (rows with `in_library = 1`), `album`, `artist`, `track_fts*` with their triggers, `source_account` row `local` | `core:database`, `app` |
| Favorites | `DatabaseFavorites` (`liked_track`), `LegacyFavorites` | `core:database`, `app` |
| Library UI | `LibraryRepository`, `LibraryIndex`, `LibraryBrowse`, `LibraryScreens` | `feature:library` |
| Saved queue | `DatabaseQueueStore` (`queue_state`, `queue_item`) and its restore-paused behaviour | `core:database`, `player:service` |
| Device shell | Boot self-test, startup chord, clicks, battery and disk LEDs, power, `DeviceSounds`, `BootReport`, `BatteryMonitor` | `app` |
| Database safety | `PodiumDatabase` migrations 1→4, `DatabaseBackup` | `core:database` |
| Test tones | `TestMusicSource` PRIMARY and the debug commands device tests rely on (`play-test-tones`, `library-test-only`, `keep-screen-on`…) | `sources:test`, `app/src/debug` |

### 3.2 The shared spine local playback depends on

These can only be extended *additively*; their behaviour for local items must not change.

| Component | What local needs from it [P] |
|---|---|
| `SourceRegistry`, `MusicSource`, facets, `Capabilities` | Library sync (sources with a usable `LIBRARY`), folders, catalogue lookups, artwork |
| `StreamResolver` | Pinned → own source → `DirectStream`. (EXACT fallback for local runs only between debug test sources; release builds have one LOCAL source, so it never happens there.) |
| `SourceHealthMonitor` | Records local playback errors (per item, never trips local) |
| `TrackCatalog`, `ArtworkResolver` | Id → track; `podium-art://` refs → local artwork |
| `QueueManager`, `QueueItemResolver`, `PodiumPlaybackEngine`, `PlaybackService`, `MediaControllerPlaybackController`, `PlaybackController` | Every local play |
| `feature:nowplaying` (Now Playing, Up Next) | Every local play |

### 3.3 Interfaces that mix local and online concerns (the seams)

| # | Seam | Today [P] | Consequence for YouTube Music |
|---|---|---|---|
| S1 | **`track` table** | Holds local library rows (`in_library = 1`) and online cache rows (`in_library = 0`). `track.source_id` → `source_account` is **ON DELETE CASCADE** | Removing a provider's `source_account` row deletes its cached tracks, while queue, likes and history rows (no foreign keys) keep pointing at them |
| S2 | **One saved queue** | `queue_state`/`queue_item` hold items from either environment. The queue has **no environment rule**: Online's "Add to Up Next" puts an online song into a local queue | Must be settled before a second playback owner exists (§12) |
| S3 | **`Environments.of(id)`** | An unregistered source defaults to **LOCAL** | Once Audius/OpenSubsonic are removed, their old ids in history or the queue would read as *local* (the heart, the history recorder, the Now Playing "Online" note) |
| S4 | **`EnvironmentFavorites`** | Now Playing's heart routes to local favorites or ONLINE likes by environment | Keep; the online side becomes the account's likes |
| S5 | **Settings screen** | Hosts Music folders (local) next to Online sources, Autoplay and Online recommendations | Online rows are replaced (§16); local rows stay |
| S6 | **`SourceSettings`** priority list | Contains local, test and online ids | Priority goes away with multi-source |
| S7 | **`PlaybackDependencies.playedSince`** | Reads ONLINE history (autoplay avoid-repeats) | Online-only in effect; keep |
| S8 | **`TrackCatalog.get`** | Falls back to `registry.get(sourceId).catalog.track` | Fine for one online source |
| S9 | **`DeviceSettings`** autoplay flags | Autoplay only ever runs for ONLINE (`keepOnlineMusicGoing` returns for LOCAL) | Online-only; keep |

### 3.4 Reusable as they are
`PlaybackController`, `QueueManager`, `PlaybackTarget` (`DirectStream`/`RemoteProvider`/`Embedded`), `PlaybackSnapshot.owner`/`ControlSet`, `FavoritesRepository`, `OnlineRepository`, `OnlinePlace`, the `Track`/`TrackId`/`SourceRef` model and capability states. All **[P]**.

---

## 4. Current online architecture

| Component | Role | Depends on / used by [P] |
|---|---|---|
| `AudiusMusicSource` (`sources:audius`) | Anonymous public catalogue, discovery, recommendations, artwork, `DirectStream` | Registered in `AppGraph` |
| `SubsonicMusicSource` + `SubsonicSourceFactory` (`sources:subsonic`) | User-added servers, token auth, `DirectStream` | `ConfiguredSources` |
| `SourceSettings` + `SharedPrefsSourcePreferences` | On/off and priority, persisted | Settings ▸ Online sources; `AppGraph` |
| `ConfiguredSources`, `SetupForm`, `SourceProfileStore`, `SourceFormScreen` | Add, restore and remove user-configured sources | O11 |
| `CredentialStore` / `KeystoreCredentialStore` | Sealed secrets per source | O11 |
| `NetworkPolicy` + `usesCleartextTraffic="true"` | https anywhere; http only on the listener's own network (for user servers) | Transport, player |
| `SourceFanOut` | Concurrent asks, timeouts, health recording | `MultiSourceCatalog` |
| `MultiSourceCatalog` | Progressive search, merged shelves and genres, per-source paging, routing by id | `AppOnlineRepository` |
| `TrackGrouper`, `TrackMatcher`, `TrackNormalizer`, `VersionLexicon` | "One song, many sources" at EXACT | Catalogue, resolver fallback, autoplay de-dupe |
| `EquivalenceStore` / `DatabaseEquivalenceStore` (`track_equivalence`) | Persisted EXACT pairs and rejections | Grouper, resolver, recommendations |
| `StreamResolver` EXACT-fallback step | Another source's copy within the environment | Online only in release |
| `AppOnlineRepository` (implements `OnlineRepository`) | Everything the Online screens read and write | `feature:online` |
| `OnlineLibraryStore` (`online_liked_track`, `online_playlist`, `online_playlist_track`, `online_history`) | ONLINE's own likes, playlists, history; rows keyed by `account_key` (always `""` today) | Repository, history recorder |
| `OnlineHistoryRecorder`, `EnvironmentFavorites`, `Environments` | Records online listens; routes the heart | `AppGraph` |
| `SourceRecommendationEngine`, `AutoplayEngine` | Radio and autoplay within one environment | Engine (`keepOnlineMusicGoing`) |
| `feature:online` screens | Menu (Home, Explore, Search, Liked songs, Playlists, Radio, History), shelves, artist, album/playlist, search, playlists editing | `OnlineRepository`, `OnlineActions` |
| Settings ▸ Online sources | Rows per source, on/off, move, sign in/out, remove, add a server | `RegistryOnlineSourceSettings` |

---

## 5. What O11 accomplished

| O11 piece | Useful for YouTube Music? |
|---|---|
| Configured sources (`ConfiguredSources`, `SetupForm`, `SourceFactory`, profiles) | **No.** YouTube Music is one built-in source with one account, not user-added servers |
| `AuthFacet.signIn/signOut`, `AuthState`, sign-in rows in Settings | **Partly.** The state and sign-out carry over; a field form doesn't fit a Google sign-in (§8) |
| `KeystoreCredentialStore` (AES-256-GCM, Keystore key, ciphertext only) | **Yes.** The place for any session material |
| Breaker fix (no escalation while already open) | **Yes.** An unofficial catalogue fails in bursts |
| Pins dropped when a source stops serving | **Yes.** Sign-out must stop using account-bound targets |
| `NetworkPolicy.permits` on every request and stream | **Yes** as a defence (https only). The cleartext manifest flag can go when OpenSubsonic goes |
| `ConnectResult`/credential `toString` masking, logging rules | **Yes**, as patterns |
| `sources:subsonic` | **No** (removed eventually) |
| Device-acceptance method (scripted local servers, snapshot/diff/restore of the phone's state) | **Yes**, as the template for YouTube Music device tests |

---

## 6. KEEP / ADAPT / DEPRECATE / REMOVE

Definitions:
- **KEEP:** unchanged.
- **ADAPT:** keep, but reshape for one provider.
- **DEPRECATE:** keep working until YouTube Music replaces it, then remove.
- **REMOVE:** delete when its last user goes.
- **UNKNOWN:** depends on a Y0 decision.

| Component | Verdict | Traced reason [P] |
|---|---|---|
| Source registry (`SourceRegistry`) | **KEEP** | Local library sync, folders, resolver, catalogue and artwork all go through it. Still the single lookup with one online source |
| Source priority (`SourceSettings` order, Move up/down) | **REMOVE** (via DEPRECATE) | Consumed by fallback order, `ordered()` and merged search. With one local and one online source it orders nothing |
| Source enable/disable | **ADAPT** | Becomes "YouTube Music on/off" (or Online mode on/off). The on/off persistence is reusable |
| Source health (`SourceHealthMonitor`) | **KEEP** | Local error recording; miss vs failure and breaker for the YouTube client |
| `SourceFanOut` | **ADAPT** → shrink | The fan-out goes; a single-source "ask with timeout, cancel, record health" (`askOne`) is worth keeping |
| `MultiSourceCatalog` | **REMOVE** | Only for combining sources; the repository can call the one source |
| `TrackGrouper` | **REMOVE** | Cross-source grouping only |
| Source fallback (resolver EXACT step) | **REMOVE** | No peer ONLINE source; LOCAL is off limits (D-36). For local it's reachable only between debug test sources |
| Track equivalence (`EquivalenceStore`, `DatabaseEquivalenceStore`, `track_equivalence`, `not-same`) | **REMOVE** (via DEPRECATE) | Consumers are the grouper, fallback and recommendations, all multi-source |
| `TrackMatcher` / `TrackNormalizer` / `VersionLexicon` | **UNKNOWN** → likely REMOVE | No consumer after grouping, fallback and cross-source de-dupe go. A possible future use is de-duplicating YouTube's single/album/video copies. Decide at Y6 |
| Source ids (`TrackId = "<sourceId>|<key>"`, `ArtistId`, `AlbumId`, `PlaylistId`, `ScopedKey`) | **KEEP** | Keeps LOCAL and ONLINE identities apart by construction; queue, likes and history are keyed this way. YouTube ids become e.g. `ytmusic|<videoId>` |
| Audius (`sources:audius`) | **DEPRECATE** → REMOVE | Keep Online working until YouTube Music covers it |
| OpenSubsonic (`sources:subsonic`) | **DEPRECATE** → REMOVE | Same; its removal also removes the only reason for app-wide cleartext |
| Configured sources (`ConfiguredSources`, `SetupForm`, `SourceFactory`, `SourceProfileStore`, `SourceFormScreen`) | **REMOVE** | User-added servers only |
| Online history (`online_history`, `OnlineHistoryRecorder`) | **ADAPT** | Becomes Podium's own record of what it played or observed (avoid-repeats, Recently played) next to the account's history (§11). `served_by` loses meaning |
| Online likes (`online_liked_track`) | **ADAPT** | A cache of the account's likes, keyed by `account_key` (the column already exists); the account is authoritative |
| Online playlists (`online_playlist`, `online_playlist_track`) | **ADAPT** | The account's playlists (`remote_playlist_id` already exists). Whether device-only online playlists survive is a product question (§25) |
| Authentication (`AuthFacet`, `AuthState`, sign-in rows) | **ADAPT** | Needs a browser-based flow (`begin() → AuthFlow`, already in the spec §6.4), not a field form |
| Credential storage (`KeystoreCredentialStore`) | **KEEP** | Refresh token or session material, sealed |
| `NetworkPolicy` | **ADAPT** | Keep `permits` (https only); drop the cleartext manifest flag and the local-network rule with OpenSubsonic |
| Search (`OnlineRepository.search` progressive `Flow`) | **KEEP** contract, **ADAPT** implementation | One source: one emission; YouTube Music's typed filters map onto `SearchQuery.kinds` |
| Catalogue models (`Track`, `SearchResults`, `Shelf`/`ShelfPage`, `ArtistDetail`, `PlaylistDetail`, summaries) | **KEEP** + small **ADAPT** | Possibly a provider-neutral media kind (audio track / music video / upload), the investigation's Q-5, and subscription availability |
| Playback resolver (`StreamResolver`, `QueueItemResolver`) | **ADAPT** | Keep pinned → own source; drop fallback; return the chosen non-direct route for YouTube Music items |
| Queue (`QueueManager`, saved queue) | **KEEP** for local, **ADAPT** for online | Depends on the playback model (§12) |
| Autoplay / radio (`AutoplayEngine`, `SourceRecommendationEngine`) | **ADAPT** | One source; YouTube Music's own radio/up-next as the `RecommendationFacet`. With a provider-owned queue (P2), Podium's autoplay stands down for YouTube Music |
| Artwork (`ArtworkResolver`, `ArtworkFacet`, `podium-art://`) | **KEEP** | YouTube Music thumbnails are plain https images (no tokens) **[B]**; either path works |
| Account model | **ADAPT** (+ build) | `account_key` exists in online tables; there's no account record or account switching yet |
| Capabilities | **KEEP** | `REQUIRES_SIGN_IN`, `REQUIRES_PROVIDER_APP`, `REQUIRES_SUBSCRIPTION`, `EMBEDDED_PLAYBACK`, `REMOTE_PLAYBACK` already exist and gate the Online menus |
| `PlaybackRouter` + `RemoteProvider`/`Embedded` types | **ADAPT** (wire in) | Types and router exist and are tested, but nothing in the service uses them. They're the core of YouTube Music playback |
| `AppOnlineRepository` | **ADAPT** → replace | A YouTube Music repository implementing `OnlineRepository` over one source and the online store |
| `OnlineRepository`, `OnlinePlace`, `feature:online` screens | **KEEP**, reshape menus | Add library albums/artists and account state; drop multi-source wording |
| Settings ▸ Online sources | **REPLACE** | Settings ▸ YouTube Music: account, sign in/out, the permissions the playback model needs |
| `Environments` / `EnvironmentFavorites` | **KEEP** (fix seam S3) | An unknown source should never be treated as local |
| `sources:test` MIRROR variant | **REMOVE** with fallback | It exists to test EXACT fallback; PRIMARY (test tones) stays |

---

## 7. YouTube Music target architecture

```
                                ┌─────────────────── PODIUM UI ───────────────────┐
                                │ VirtualScreen · paper · Wheel · Now Playing ·    │
                                │ Up Next · Settings (no provider names in music)  │
                                └───────┬──────────────────────────────┬──────────┘
                                        │                              │
              ┌──────────── OFFLINE ────┴────────┐        ┌────────────┴──── ONLINE ─────────────┐
              │ feature:library                   │        │ feature:online (OnlineRepository)    │
              │ DatabaseLibraryRepository         │        │ YouTubeMusicRepository (app)          │
              │ LibraryStore · liked_track        │        │   ├─ account state, library caches    │
              │ LocalMusicSource (MediaStore)     │        │   │   (online_* tables, account_key)   │
              │   └ Playback → DirectStream       │        │   └─ OnlineLibraryStore               │
              └──────────────┬────────────────────┘        │ sources:youtubemusic (NEW)            │
                             │                             │   Catalog · Discovery · Recommendation │
                             │                             │   · Artwork · Auth                    │
                             │                             │   · Playback → RemoteProvider (P2)    │
                             │                             │              or Embedded (P1)          │
                             │                             │              never DirectStream        │
                             │                             └──────────────┬───────────────────────┘
                             │                                            │
              ┌──────────────┴──────────── SHARED SPINE (additive changes only) ───┴──────────────┐
              │ SourceRegistry · SourceHealthMonitor · StreamResolver (pinned → own source)        │
              │ PlaybackController → PlaybackService → PlaybackRouter                              │
              │      ├─ DirectStream engine (Media3, existing)        ← LOCAL items                │
              │      └─ RemoteEngine (P2) or EmbeddedEngine (P1)      ← YouTube Music items        │
              │ QueueManager (Podium-owned queue) · provider session (P2)                          │
              └───────────────────────────────────────────────────────────────────────────────────┘
```

**Build:**
- **`sources:youtubemusic`:** a `MusicSource` with descriptor `environment = ONLINE` and `basis` per the chosen catalogue path. Behind an internal `YouTubeMusicClient`, so InnerTube vs Data API is a swap inside the module.
- **One playback engine for the chosen model.**
- **A YouTube Music repository** implementing `OnlineRepository`.
- **Settings ▸ YouTube Music.**

**Reuse:** everything in the KEEP and ADAPT rows. **Delete** later: the REMOVE rows of §6, in phase Y6 (§23).

---

## 8. Account architecture

### 8.1 Options

| Option | Session material | Where it comes from | Gives | Status |
|---|---|---|---|---|
| **A0 Anonymous** | Possibly a guest visitor id (InnerTube) | The client | Public catalogue, home, radio | Feasible (unofficial) **[B]** |
| **A1 Google OAuth** (Data API scopes) | Refresh token (long-lived), access token (1 h) | Custom Tabs + PKCE (AppAuth pattern) | YouTube playlists, liked videos, subscriptions, playlist edits, ratings | Official **[YT]**. In "testing" status, refresh tokens may expire after 7 days **[?]**. Verification is needed beyond test users **[?]** |
| **A2 YouTube Music web session** (InnerTube) | Google account session cookies (+ per-request hash derived from one of them) | A WebView showing Google's own sign-in; cookies captured afterwards (BitChord's model **[B]**) | The full YouTube Music library: Liked music, library albums and artists, history, home | Unofficial. Google may refuse sign-in inside embedded WebViews **[?]**. The investigation flags session capture outside OAuth |
| **A3 No account in Podium** (with P2) | None | The official YouTube Music app is signed in itself | Personal playback through the app (e.g. hand it the Liked music list); Podium can't *list* the library | Credential-free **[I]** |

### 8.2 Where it can safely live
- **Secrets:** refresh token or cookies only in `KeystoreCredentialStore`, sealed with a non-exportable Keystore key. Never in the database, logs, URLs, crash text or instance state.
- **Access tokens and per-request hashes:** memory only.
- **The WebView's own cookie jar (A2) must be cleared right after capture.** Otherwise the platform keeps an unencrypted copy in the app's data directory **[A]**.
- **Non-secret profile** (display name, avatar URL, channel or brand-account id): small prefs, like `source-profiles` today.

### 8.3 Sign-in and sign-out effects on cached data
- **Sign-in:** account-scoped caches are filled lazily (liked songs, playlists, albums, artists), keyed by `account_key = <account id>`.
- **Sign-out must:**
  1. revoke the OAuth token (A1) or delete the captured session (A2);
  2. delete the sealed material and clear WebView cookies and storage;
  3. delete that account's rows in `online_liked_track`, `online_playlist`/`online_playlist_track` and `online_history`;
  4. drop pins served under that account (O11's mechanism);
  5. leave the local library, favorites and local queue untouched.

  Cached `track` rows still referenced by the saved queue stay, as the queue needs them. Others become garbage-collectable.
- **Never persisted:**
  - passwords (Podium never sees one; Google's page does);
  - raw cookies outside the sealed store;
  - authorization headers and request hashes;
  - stream URLs (there are none in P1/P2);
  - any YouTube audio or video bytes (III.E.1.a);
  - API keys in git.

  A Data API key, if used, must be per-user or local (`local.properties`), never committed.

**A2 carries a special risk.** Captured cookies are a *full Google account session*: unscoped and unrevocable by Podium, and far more powerful than an OAuth token. If A2 is chosen, it needs its own threat-model entry and an explicit user decision.

---

## 9. Search/catalogue architecture

```
OnlineRepository.search(text) ─► YouTubeMusicSource.catalog.search(SearchQuery(kinds = songs/albums/artists/playlists))
                                   └─► YouTubeMusicClient (InnerTube | Data API) ─► parser ─► Track / AlbumSummary /
                                       ArtistSummary / PlaylistSummary (ids: ytmusic|<id>)
```
- **One source:** no fan-out, grouping or merging. The progressive `Flow` contract stays and simply emits once.
- **Paging:** the provider's continuation tokens live in `providerData` or `ScopedKey` (already provider-neutral).
- **Metadata vs playable:** a row is metadata until playback resolves. Rows carry `Availability.Unknown`, or the route's requirement, e.g. "Needs the YouTube Music app" (`REQUIRES_PROVIDER_APP`).
- **Media kind:** an audio track vs a music video vs an upload is a real distinction (different edits) **[B]**. It's the investigation's Q-5.
- **Caching:** catalogue answers in memory only. Persist only what's referenced (the queue, likes, history), as today (`cacheTracks`).
- **Official path:** Data API `search.list` is capped at about 100 calls per day per project by default **[YT]**. Workable only for a personal build with the user's own project. It has no albums, artists or explicit flags.

## 10. Library architecture

| Podium Online | YouTube Music concept | Access |
|---|---|---|
| Home | Home shelves, mixes, Listen again | InnerTube (A0/A2) only |
| Search | Typed search | InnerTube, or Data API (video-shaped, tiny quota) |
| Library ▸ Liked songs | Liked music | A2 (InnerTube); A1 gives "liked videos", which may not equal Liked music **[?]** |
| Library ▸ Playlists | The account's playlists | A1 or A2 |
| Library ▸ Albums / Artists | Saved albums, subscribed artists | A2 (A1: subscriptions only, as channels) |
| History | Account history | A2 only. The Data API doesn't expose watch history **[YT, re-verify]** |
| Radio | Radio/up-next for a song or artist | InnerTube (A0) |

The account is authoritative. Podium keeps account-scoped caches for instant menus and refreshes them on open. Under the Data API the 30-day refresh-or-delete rule (III.E.4) applies to stored data **[YT]**.

## 11. Likes/history/playlists architecture

- **Likes:**
  - The heart on Now Playing keeps routing by environment (`EnvironmentFavorites`). Local favorites never change from online actions (D-34).
  - Online likes write through to the account (A1: rating; A2: unofficial like call) with an optimistic cache.
  - Signed out, there are no online likes.
- **History, two kinds:**
  - **The account's history** (what YouTube recorded). Under P1/P2 the official player or app records plays *itself*, so Podium never writes to YouTube history. Impersonated playback reporting is excluded **[B]**.
  - **Podium's own observation log** (`online_history`). It feeds Recently played and avoid-repeats. Under P2 it's built from the provider's media-session metadata; under P1, from the embed's events.
- **Playlists:** the account's playlists, read-only at first. Editing later if the chosen path allows it; entries would need the provider's per-entry id (a column, §15). Whether Podium keeps *device-only* online playlists (today's behaviour) is a product question (§25).

## 12. Queue architecture

| Regime | Owner | Behaviour |
|---|---|---|
| **LOCAL** | Podium (`QueueManager` → Media3) | **Unchanged**, including the saved queue |
| **ONLINE, P2 delegated** | **Provider** (PLAYBACK_TARGETS §6.1 `QueueOwnership.PROVIDER`) | Playing an album, playlist or radio hands that *context* to the YouTube Music app. Podium's queue is suspended (preserved, restorable). Up Next mirrors the provider's queue read-only, if exposed **[?]**, with "Back to my music". Nothing is interleaved |
| **ONLINE, P1 embedded** | Podium | Podium sequences items one by one into the embed (`loadVideoById`). Foreground only, so items can't play from the background |

**Recommendation:** one playback owner at a time and no mixed LOCAL/ONLINE queues. This is a product change on the *online* side: Online's "Play next" and "Add to Up Next" would act only within an online session. Local queue behaviour is untouched. Today mixing is allowed (seam S2).

## 13. Playback architecture

### 13.1 Ten concepts, kept apart

| # | Concept | LOCAL today [P] | YouTube Music, P2 delegated | YouTube Music, P1 embed | YouTube Music through Media3 |
|---|---|---|---|---|---|
| 1 | Catalogue access | MediaStore scan → `LibraryStore` | Catalogue path (§9) | Same | Same |
| 2 | Account/library access | — | A1/A2/A3 (§8) | A1/A2 | — |
| 3 | Metadata | `track` rows | Client parser; while playing, the provider session's metadata | Client parser; embed events | — |
| 4 | Playback resolution | `StreamResolver` → own source | Resolve to `RemoteProvider(controllerId = app session, providerItemRef = content id/link)` | Resolve to `Embedded(embedRef = video id, constraints)` | Would need a stream URL: **BLOCKED** |
| 5 | Audio delivery | Media3 reads `content://` | The official app | The official player inside a WebView | **BLOCKED** (stream unlock) |
| 6 | Media3 playback | Yes | No (Podium's player idle) | No | **BLOCKED** |
| 7 | Background | Yes | Yes, per the listener's YouTube Music entitlement (Premium) **[YT]** | **No** (III.I.9) | **BLOCKED** |
| 8 | Media session | Podium's | The app's; Podium releases its own (PLAYBACK_TARGETS §5.2) | None for the embed; WebView behaviour is Q-8 **[?]** | — |
| 9 | Queue | Podium | Provider (§12) | Podium | — |
| 10 | Seeking | Media3 | Session transport controls, if the app's session allows it **[?]** | IFrame API `seekTo` **[YT]** | — |

### 13.2 What the current player expects [P]
- **The controller path.** The UI talks to `PlaybackService` through a Media3 `MediaController`. The service runs one `PodiumPlaybackEngine` that plays **only** `DirectStream`; any other target fails with "needs another engine".
- **What's already prepared:**
  - `PlaybackRouter` (hard-cut handoffs);
  - the `RemoteProvider` and `Embedded` targets and `RemotePolicy` (queue owner, controls owner, required provider app, subscription);
  - `EmbedConstraints`;
  - `PlaybackSnapshot.owner` (`Podium` / `Remote` / `Embedded`) and `ControlSet`.

  None of these is wired into the service.
- **So the current architecture supports DirectStream sources only** (local files, Audius, OpenSubsonic). YouTube Music needs:
  - the router wired into the service;
  - one new engine;
  - an owner-aware Now Playing and Up Next;
  - a session policy for each owner.

### 13.3 Blocked: Podium-owned YouTube Music audio
Every known way to obtain playable YouTube audio for Media3 goes through the stream-unlock layer: client-identity walks, PoToken/BotGuard, signature and throttle transforms, NewPipe scraping **[B]**. CLAUDE.md and ADR-013 exclude all of it, and YouTube's policies forbid audio-only (III.I.7) and background (III.I.9) players **[YT]**. So these are **BLOCKED**, with no workaround to be built:
- Podium as the audio player for YouTube Music;
- Podium's own notification and lock screen for YouTube Music;
- downloads;
- caching.

### 13.4 P2: delegated playback (new; not in the investigation)
- **Idea.** The official YouTube Music app plays. Podium is the iPod: it browses, chooses, controls and mirrors.
- **Mechanism (Android platform APIs, not YouTube APIs) [A]:**
  - With the listener's grant of notification access, Podium reads the app's active `MediaSession` through `MediaSessionManager.getActiveSessions(…)`.
  - It gets play/pause/seek/skip through `MediaController.TransportControls`, and state, metadata (title, artist, artwork, duration, position) and possibly the queue through `MediaController.Callback`.
  - Content starts through a public link to the song, album or playlist (`ACTION_VIEW` on a music.youtube.com URL), or a session `playFromSearch`/`playFromUri` if the app supports one **[?]**.
- **Fit.** This is PLAYBACK_TARGETS' remote target with `queueOwnership = PROVIDER`, `systemControlsOwner = PROVIDER`, `requiresProviderApp = YouTube Music`. Podium's session steps aside while it's active.
- **What it gets right:**
  - background playback and lock-screen controls come from the official app under the listener's own entitlement;
  - nothing is extracted or bypassed;
  - YouTube records history itself;
  - ads, if any, play where YouTube puts them;
  - with A3, Podium needs no Google credentials at all.
- **Unknowns, which decide feasibility (Y0 spike):**

  | # | Question |
  |---|---|
  | U1 | Can specific content start *without* bringing the app's UI to the foreground? Background-activity-start limits mean Podium can't pull itself back to the front afterwards **[A]** |
  | U2 | Does the app's session honour seek, skip and previous, and expose its queue? |
  | U3 | Can a link start a context (album or playlist) at a given song? |
  | U4 | How long does a hand-off take, and what does a cold start of the app feel like? |
  | U5 | Notification-access UX and policy. It's a sensitive special permission that technically exposes *all* notifications; Podium must read only media sessions (§20) |
  | U6 | Behaviour without Premium (foreground-only playback in the app) |

### 13.5 P1: official embedded player (D-20 Y2)
- **As in the investigation:** an `EmbeddedEngine` over the IFrame Player API in a WebView.
- **The surface:** visible, at least 200×200 px, no overlays; autoplay only when more than half of it is on screen; foreground only; ads untouched **[YT]**.
- **Constraints for Podium:**
  - The video takes the artwork's place on Now Playing, which needs a design pass against D-26/D-29.
  - Leaving the screen or switching the display off pauses it, so there's no pocket listening.
  - III.I.1 ("substitute for YouTube applications") needs a policy reading (Q-7).
  - The WebView's own media session is Q-8.

### 13.6 P0: hand-off only
"Open in YouTube Music" (song, album, playlist): no control or mirroring. Always legitimate, trivial to build, and a safe default for Y1.

## 14. Local/online environment boundaries

| Rule | Enforced today [P] | YouTube Music addition |
|---|---|---|
| Local never falls back to online; online never to local | `StreamResolver` restricts fallback to the track's environment (D-36) | Fallback is removed altogether with one online source |
| Identities never collide | `TrackId` source prefix | `ytmusic|…` ids; never bare video ids in the app (BitChord's leak is the anti-pattern **[B]**) |
| Local data stays local | Separate tables and favorites; `EnvironmentFavorites` | Account caches keyed by `account_key`; sign-out wipes only them |
| Autoplay never crosses | `sameEnvironment` in the engine and recommendation engine | Provider-owned autoplay (P2) or YouTube radio (P1); never local |
| Queue | Per-item resolution in its own environment; **mixing allowed** | One owner at a time; no mixed queues (§12) |
| Unknown sources | **Default to LOCAL** (seam S3) | Must not default to local before any provider is removed |
| Equivalence | EXACT only, never across environments | Not needed; removed |

## 15. Database strategy

- **Frozen:**
  - `track` rows with `in_library = 1`;
  - `album`, `artist`, `track_fts*` and triggers;
  - `liked_track`;
  - `queue_state`/`queue_item` semantics;
  - the `local` row of `source_account`;
  - the migration chain 1→4 and `DatabaseBackup`.
- **Already sufficient for YouTube Music (v4):**
  - `online_liked_track` (PK `account_key + track_id`);
  - `online_playlist` (`account_key`, `remote_playlist_id`);
  - `online_playlist_track`;
  - `online_history` (`account_key`);
  - `track` with `in_library = 0` as the metadata cache.

  These were designed for "until a source can be signed in" and need no migration for read-only library caches.
- **Additive, when needed (Y3/Y4, v5):**
  - a provider entry id on `online_playlist_track` (for edits);
  - optionally an account-scoped cache for saved albums and artists (otherwise in memory);
  - optionally a small account record (or keep it in prefs, like source profiles).
- **Cleanup migration (Y6):**
  - drop `track_equivalence`;
  - delete the online rows of retired providers.

  This needs care: `track.source_id` cascades from `source_account`, and `queue_item`, likes and history rows have no foreign keys. Prune the saved queue consistently (or keep cached rows the queue refers to), and never touch local rows.
- **Local history:** none exists today; local listens aren't recorded. Nothing to keep or add.

## 16. UI/navigation strategy

- **Shell:** unchanged (D-26 shell, Carbon and Bone D-29, the paper, the Wheel, previews, Now Playing, Up Next). The provider never dictates layout.
- **Online menu, reshaped to the YouTube Music model:**
  - Home
  - Search
  - Library (Liked songs, Playlists, Albums, Artists)
  - History
  - Radio (optional)

  `OnlinePlace` already has most of these places; Albums and Artists under Library are new. Explore/genres can go unless the catalogue path supplies moods and charts cleanly.
- **Now Playing** becomes owner-aware:
  - **Podium (local):** unchanged.
  - **Provider (P2):** mirrored title, artist, artwork and position, transport through the provider. The Wheel's volume behaviour is the same system volume (D-16).
  - **Embedded (P1):** the video in place of the artwork. This is the one place a moving image appears, so it needs a design decision.
- **Settings:** Online sources becomes **Settings ▸ YouTube Music**, with:
  - the account, and sign in/out;
  - the playback model's requirements: "YouTube Music app installed", "Notification access" for P2;
  - the Basis label "Unofficial — may stop working" if InnerTube is used (D-19).

  No provider name appears in browsing (D-35).
- **Attribution:** the Data API and the embed require YouTube branding where their data or player shows (III.F.2.a) **[YT]**. That means a generic attribution slot, as the investigation recommended.

## 17. BitChord comparison

| Component | Podium current | BitChord | Recommended for Podium |
|---|---|---|---|
| Account | `AuthFacet` state + O11 sign-in for servers; Keystore store | WebView Google sign-in, captured cookies and page config, encrypted storage, multi-account and brand channels **[B]** | One account; A1 (official) or A2 (explicit decision, §8), or A3 with P2; sealed storage; brand accounts later |
| Search | Progressive multi-source search | InnerTube typed search, continuations, suggestions **[B]** | One source; typed kinds; continuation paging |
| Home | Shelves merged across sources | InnerTube home/explore **[B]** | Provider home shelves through `DiscoveryFacet` |
| Library | Local library only; online likes/playlists device-only | Account library tabs **[B]** | Account-scoped caches (`account_key`) |
| Liked songs | `online_liked_track` (device) | Account likes **[B]** | Account likes, write-through, cached |
| Playlists | Device-only online playlists | Account playlists, editing (v1.8 adds reorder/multi-add) **[B]** | Account playlists; read first, edit later |
| Artists | Per-source artist pages | Redesigned artist page (v1.8) **[B]** | Provider artist page through `CatalogFacet.artist` |
| Albums | Per-source album pages | Album browse **[B]** | Same, through `CatalogFacet.album` |
| History | `online_history` (Podium-observed) | Plays reported to YouTube history by impersonated pings **[B]** | Account history read; Podium never writes plays; the official player/app records them |
| Queue | Podium `QueueManager`, mixed environments | Own queue; Listen-together shared queue model (v1.8) **[B]** | Podium queue for local; provider-owned session for P2 |
| Autoplay | `AutoplayEngine`, environment-bound | YouTube up-next continuation **[B]** | Provider radio/up-next (P1), or the provider's own autoplay (P2) |
| Playback | Media3 `DirectStream` only; router unwired | Media3 progressive audio from unlocked YouTube streams **[B]** | P2 or P1; **never** unlocked streams |
| Media3 | `MediaLibraryService`, resolving data source | Same, plus HLS/DASH for other sources **[B]** | Unchanged for local; idle for YouTube Music items |
| Background playback | Yes (DirectStream) | Yes, via its own service **[B]** | Through the official app only (P2), per entitlement |
| Authentication | Field forms (O11) | WebView session capture **[B]** | Browser-based flow; no field form |
| Metadata | Provider-neutral `Track`, ISRC/MBID, version tags | Bare video ids app-wide; music-video type, explicit badge **[B]** | `ytmusic|…` ids; media kind (Q-5); explicit badge ≠ "clean" when absent |
| Artwork | `ArtworkResolver`, `podium-art://` | Direct thumbnails, motion artwork **[B]** | Plain https thumbnails through the existing loader |
| Database | Room v4, local + online separated | Singletons and prefs; caches **[B]** | Reuse v4 online tables; additive v5 only when needed |
| Caching | No media cache; pinning | Audio cache, "unlimited cache" (v1.8), URL cache **[B]** | No YouTube bytes cached (III.E.1.a); metadata caches only |
| Source abstraction | Facets, registry, multi-source | Small contract with YouTube as the undeletable "spine" **[B]** | Facets + registry, one online source; provider never leaks |

## 18. BitChord concepts worth learning from

| Concept | Why | Podium form |
|---|---|---|
| **Metadata ≠ playable** (a row isn't playable until resolution says so) | Avoids promising what can't play | Rows `Availability.Unknown` or route-gated (§9) |
| **Permanent vs retryable unplayable** (age gate, takedown, region) | Stops re-asking for what will never play | A per-item "known unavailable until T" memory (optional) |
| **Account library as tabs** (liked, playlists, albums, artists) | Matches what listeners expect from YouTube Music | Library submenu (§16) |
| **Provider radio/up-next as the autoplay source** | YouTube Music's continuation is its strength | `RecommendationFacet` backed by the provider |
| **Encrypted session storage, brand accounts** | Real accounts have several channels | Sealed store; account switching later |
| **Typed search with continuations** | Fast, paged search | `SearchQuery.kinds`, `ScopedKey` paging |

**Not to learn from:** stream unlock (client identities, PoToken, cipher, NewPipe, remote cipher feeds), YouTube audio caching and downloads, impersonated playback reporting, bare video ids across the app, a monolithic playback service, mid-track source switching, races deciding identity **[B]**. All excluded by ADR-013/014 and the BitChord review §11.

## 19. YouTube Music capability matrix

Legend:
- ✓ supported;
- ◐ partial;
- U unofficial (InnerTube; D-19);
- ✗ not available;
- ⛔ blocked by policy or Podium rules;
- ? unknown.

| Capability | Data API + OAuth (official) | InnerTube (unofficial) | P1 embed (official) | P2 official app (delegated) |
|---|---|---|---|---|
| Sign in / account | ✓ (A1) | U (A2 cookie session) | — | Uses the app's own account (A3) |
| Home / recommendations | ✗ | U | — | ◐ (opening Home in the app) |
| Search: songs | ◐ (videos; ~100/day default) | U | — | — |
| Search: albums / artists | ✗ / ◐ (channels) | U | — | — |
| Search: playlists | ✓ | U | — | — |
| Album page / tracklist | ◐ (album playlists) **?** | U | — | — |
| Artist page | ◐ (channel) | U | — | — |
| Liked songs | ◐ (liked videos ≟ Liked music **?**) | U | — | ◐ (play the Liked music list in the app) |
| Playlists (read / edit) | ✓ / ✓ | U / U | — | ◐ (play in the app) |
| Saved albums / artists | ✗ / ◐ (subscriptions) | U | — | — |
| History (read) | ✗ **[YT, re-verify]** | U | — | — |
| Radio / up-next | ✗ | U | ◐ (Podium-sequenced) | ✓ (the app's own) |
| Artwork | ✓ | U | — | ✓ (session metadata) |
| Playback inside Podium's UI | — | — | ✓ visible video | ◐ (Podium controls; the app plays) |
| Play / pause / next / previous | — | — | ✓ | ✓ **?** (session commands) |
| Seek | — | — | ✓ | ? |
| Background playback | — | — | ⛔ (III.I.9) | ✓ per entitlement |
| Lock screen / system controls | — | — | ✗ | ✓ (the app's session) |
| Podium-owned queue | — | — | ✓ | ✗ (provider-owned) |
| Media3 audio playback by Podium | ⛔ | ⛔ (stream unlock) | ⛔ | ⛔ |
| Downloads / offline | ⛔ | ⛔ | ⛔ | Via the app only (Premium) |

## 20. Security considerations

- **Credentials:** §8.2. Session material only in `KeystoreCredentialStore`; access tokens in memory; WebView cookies cleared after capture; nothing in the database, logs, URLs or instance state.
- **Sign-out:** §8.3. Revoke where possible, delete, wipe account caches, drop pins.
- **P2 notification access:**
  - It's a special permission that technically exposes every notification.
  - Podium must implement the listener service with *no* `onNotificationPosted` handling and use it only to obtain media sessions.
  - That needs a test, and must be documented in the privacy notice.
- **WebView (P1, A2):**
  - JavaScript only for Google and YouTube origins;
  - no JavaScript bridge exposing app internals;
  - no file access;
  - Safe Browsing on;
  - storage cleared on sign-out.
- **Unofficial client (InnerTube):** treat every answer as untrusted input (size caps, timeouts, parser errors are failures, not crashes), as O11 does for servers.
- **Network:** keep `NetworkPolicy.permits` (https only once OpenSubsonic goes); remove `usesCleartextTraffic`.
- **Never build:** the excluded mechanisms in §21 and the final decisions §8.

## 21. Technical limitations

| Limitation | Kind |
|---|---|
| Podium can't own YouTube Music audio (Media3, background, notification, downloads, cache) | **Blocked** (stream unlock; III.I.7/9; III.E.1.a) |
| The YouTube Music product model (home, albums, history, Liked music) exists only in the unofficial API | Policy/fragility (D-19: opt-in, not in Play builds) |
| Official search quota (~100 searches a day per project by default) | Official limit **[YT]** |
| P1: foreground-only, visible video, ads, design conflict with the shell | Official constraints **[YT]** |
| P2: provider-owned queue, needs the app installed and notification access, background per entitlement, unknown start latency and UI behaviour (U1–U6) | Platform; to be measured |
| No ISRC from YouTube; identity relies on video type and explicit badge **[B]** | Data |
| Unofficial clients break with layout changes (BitChord: 58 InnerTube commits Aug–Sep 2026) **[B]** | Maintenance |

## 22. Migration strategy

1. **Strangler, not rewrite.** YouTube Music arrives as one more ONLINE `MusicSource` next to Audius, behind a debug or developer flag. The current Online UI renders it from day one.
2. **Offline first-class and untouched.** Each phase runs the local regression suite and a short device check of local playback, queue restore and favorites.
3. **Decide before building.** The playback model and catalogue path (Y0) come before account or library work, because P2 + A3 may need no Podium account at all.
4. **Additive schema only** until the final cleanup.
5. **Delete last.** Audius, OpenSubsonic and the multi-source layer go only after YouTube Music covers every Online menu, and after the environment seam S3 is fixed.
6. **Rollback by flag.** Until Y5 the YouTube Music source is off by default, so reverting is a flag flip or a revert of one module plus `AppGraph` lines.

## 23. Proposed implementation phases

| Phase | Objective | Modules affected | Untouched | Tests | DB change | Device testing | Rollback |
|---|---|---|---|---|---|---|---|
| **Y0 Decide + spike** | Record the D-20 revision (catalogue path, account model, playback model) and ADR-015. Spike P2 and P1 on the phone; re-verify policies | None in `main`. Spikes on throwaway branches, not merged | Everything | Spike notes with measurements (U1–U6, Q-7, Q-8) | No | Yes: the YouTube Music app plus Podium on the user's phone, within the phone rules | Delete the branches |
| **Y1 Read-only catalogue + P0** | Search, home, album, artist, playlist pages from YouTube Music, anonymous; "Open in YouTube Music" hand-off | New `sources:youtubemusic`; `AppGraph` (debug registration); `BuildSources` | Offline, player, database, Audius, OpenSubsonic | Parser tests on hand-written fixtures (no captured responses committed); facet contract tests; miss vs failure | No | Browse on device; hand-off opens the right item | Unregister the source / revert the module |
| **Y2 Playback engine** | P2 (or P1) through `PlaybackRouter`; owner-aware snapshot, Now Playing and Up Next; session hand-over | `player:api`, `player:service`, `feature:nowplaying`, `sources:youtubemusic` (route) | Local playback path (DirectStream engine), saved local queue | Router hand-off tests; fake remote engine; local regression; no-mixing rule tests | No | Yes: background, lock screen, Bluetooth, hand-over between local and YouTube Music, local queue restore | Router keeps DirectStream only (flag) |
| **Y3 Account + library (read)** | Sign in/out (chosen flow), account caches, Liked songs, Playlists, Albums, Artists, History | `sources:youtubemusic` (Auth), `app` (repository, credential use), `feature:settings` (YouTube Music row), `core:database` (only if a v5 column is needed) | Local tables and favorites | Credential tests (sealed, wiped), sign-out wipe tests, cache tests, log scan | Maybe additive v5 | Yes: sign in/out, persistence across restart, no secrets in logs or files | Sign out + revert; additive schema stays |
| **Y4 Write + radio** | Like/unlike, playlist edits (if the path allows), radio/autoplay from the provider | Same + autoplay | Local | Write-through and reconcile tests; autoplay environment tests | Possibly (playlist entry ids) | Yes | Feature flags |
| **Y5 Online becomes YouTube Music** | YouTube Music default ON; Audius OFF; Online menu reshaped; Settings ▸ YouTube Music replaces Online sources | `feature:online`, `feature:settings`, `app` | Offline UI | UI layout tests; capability gating | No | Full acceptance (template: O11 §4.1) | Re-enable Audius by default |
| **Y6 Remove obsolete online machinery** | Delete Audius, OpenSubsonic, configured sources, fan-out, multi-source catalogue, grouper, equivalence, EXACT fallback, priority, test MIRROR, cleartext flag; fix seam S3 | `sources:*`, `sources:api`, `app`, `core:database` (v5 cleanup migration) | Offline behaviour (proven by its regression suite) | Migration test from a v4 snapshot with local data; full suite; offline device check | **Yes** (drop `track_equivalence`, prune retired providers' rows safely) | Yes: library, favorites, local queue intact after upgrade | Pre-migration backup (`bak-v4`); revert commit |
| **Y7 Boundary hardening** | Make LOCAL/ONLINE structural: no mixed queues enforced in `QueueManager` policy, unknown source ≠ local, online graph isolated in `AppGraph` | `player:api`, `app` | Local semantics | Boundary tests | No | Short | Revert |

## 24. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| P2 can't start content without dragging the app's UI forward, or its session lacks seek/queue | Medium **[?]** | Y0 spike before investing; P1 or P0 fallback |
| The unofficial catalogue breaks (layout changes, attestation creeping into browse) | High over time **[B]** | Client behind an interface; failures as failures (breaker); D-19 label; official Data API as a degraded fallback |
| Google blocks WebView sign-in (A2) | Medium **[?]** | Prefer A1 or A3; decide in Y0 |
| Captured cookie session leaks | Low with sealing, high impact | Avoid A2 if possible; sealed; wiped; never logged |
| Policy changes (YouTube API Services, RMF, Play) | Medium | Re-verify at Y0 and before release |
| Removing providers corrupts the saved queue or history (cascade, seam S1/S3) | Medium | Y6 migration tests from a real v4 snapshot; fix S3 first |
| Offline regressions from shared-spine edits | Low–medium | Additive changes only; local regression suite + device check every phase |
| Product disappointment: no background playback without the official app or Premium | High if P1 only | Make the trade-off explicit in D-20 before building |

## 25. Unresolved questions

1. **D-20 revision.** Which catalogue path (InnerTube Y1 vs Data API), which account model (A0–A3), which playback model (P2, P1, P0)?
2. **P2 feasibility (U1–U6).** Start latency, foreground behaviour, seek/queue exposure, behaviour without Premium.
3. **P1 design** inside D-26/D-29, and the III.I.1 reading (investigation Q-4, Q-7, Q-8).
4. **Mixed queues:** forbid them (recommended) or keep today's mixing?
5. **Device-only online playlists:** keep them as "Podium playlists" in Online, or only the account's?
6. **What happens to the existing Audius listens** (5 history rows on the user's phone) at Y6: delete or keep as orphans?
7. **The matcher's future** after cross-source features go (de-duplicating YouTube's own copies?).
8. **Play distribution:** if InnerTube is used, the build stays sideloaded (D-19).
9. **Investigation Q-5:** a provider-neutral media kind on `Track`?

## 26. Recommended next step

**Y0, which is decisions plus two device spikes, with no product code on `main`:**
1. The user records the D-20 revision: catalogue path, account model, playback model.
2. **Spike P2** (delegated playback) on the user's phone, measuring U1–U6.
3. **Spike P1** (embed in the VirtualScreen), only if P2 fails or the user prefers it.
4. Write ADR-015 with the results. Then start Y1, the read-only catalogue with P0 hand-off, which is useful whatever the spikes show.

---

## Final decisions (answers)

1. **What should remain?**
   - All of Offline (§3.1).
   - The shared spine: registry, health, resolver (pinned → own source), queue, engine, session, controller.
   - Source-qualified ids, facets and models, capabilities.
   - The Online UI contract and screens.
   - The account-keyed online tables.
   - The Keystore credential store, `NetworkPolicy.permits`, miss vs failure and the breaker.
   - The environment boundary and the autoplay engine.
   - The `PlaybackRouter` and remote/embedded types (to be wired).
2. **What parts of O11 are now unnecessary?**
   - `sources:subsonic`;
   - configured sources (`ConfiguredSources`, `SetupForm`, `SourceFactory`, `SourceProfileStore`, `SourceFormScreen`);
   - the local-network cleartext rule and `usesCleartextTraffic`;
   - multi-row source management (priority, add a server).

   Its credential store, sign-out semantics, pin dropping, breaker fix and security patterns stay.
3. **How should YouTube Music fit?**
   - As one ONLINE `MusicSource` in `sources:youtubemusic`, rendered by the existing Online UI.
   - Playback goes through `PlaybackRouter` to a remote engine (P2: the official app) or an embedded engine (P1), never Media3 audio.
   - Account data lives in account-scoped online tables; credentials, if any, are sealed.
4. **What can we learn from BitChord?** That metadata isn't playability; permanent vs retryable unavailability; the account library shape; provider radio as autoplay; sealed sessions and brand accounts; typed search with continuations. Equally: what *not* to do (§18).
5. **What's the hardest remaining technical problem?** Legitimate playback: making YouTube Music *play* with an iPod-like feel (background, controls, queue) without Podium owning the audio. Concretely, P2's U1–U3: starting content in the official app quietly, and mirroring and controlling it reliably.
6. **What's the minimum architecture for a first useful version?** `sources:youtubemusic` (catalogue, home, radio; anonymous) + P0 hand-off + the existing Online UI. Then, after Y0, one non-Media3 engine wired through `PlaybackRouter` (P2 preferred), with an owner-aware Now Playing. No account, no database change.
7. **What should Y1 implement?** The read-only, anonymous catalogue:
   - search, home shelves, album, artist and playlist pages;
   - registered in debug builds only, behind the Basis label;
   - rows not playable in Podium, with "Open in YouTube Music" (P0) as their action.

   No account, no engine, no database, and no change to Audius, OpenSubsonic or Offline.
8. **What should we absolutely not build yet?**
   - Anything from the stream-unlock family (client-identity rotation, PoToken/BotGuard, cipher/n solving, player-JS execution, NewPipe/InnerTubeX, remote cipher feeds, watch-page scraping).
   - YouTube audio in Media3, background or audio-only YouTube playback, downloads or caches of YouTube media.
   - Impersonated playback reporting.
   - A2 cookie capture before an explicit decision.
   - Account write features.
   - Removing Audius or OpenSubsonic before Y5.
   - Database migrations before they're needed.
   - Mixed local/online queue features.
   - Any other provider.
9. **What should the next prompt be?** Y0, below.

### Proposed next prompt (Y0)

> **Podium Y0: YouTube Music decisions and playback spikes.** Read `docs/architecture/YOUTUBE_MUSIC_TRANSITION.md` and `docs/research/YOUTUBE_SOURCE_INVESTIGATION.md`.
>
> 1. **Decisions to record.** Present the D-20 revision as choices for me to decide (catalogue path, account model, playback model) with this document's trade-offs. Record my answers in `decision-log.md` and draft ADR-015.
> 2. **P2 spike, on a throwaway branch `spike/y0-delegated-playback`, never merged.** A minimal debug-only screen and a notification-listener service with no notification handling. On my phone, with the official YouTube Music app installed, measure:
>    - (U1) can a song, album or playlist start without the app coming to the foreground?
>    - (U2) do play/pause/seek/next/previous work through its media session, and is its queue exposed?
>    - (U3) can a context start at a given item?
>    - (U4) latency;
>    - (U6) behaviour without Premium.
>
>    Follow the phone-testing rules: no volume or system settings changes; grant and revoke notification access only with my approval; clean up afterwards.
> 3. **P1 spike, only if P2 fails or I ask.** The IFrame player in a WebView inside the VirtualScreen: RMF size and visibility, behaviour when the screen turns off, its media session (Q-8).
> 4. **Re-verify policies** (YouTube API Services Developer Policies, RMF, IFrame API, quota, Google OAuth testing-mode limits) and note the dates.
> 5. **Report** results in the transition document's §13.4/§13.5 and the ADR draft.
>
> **Hard rules.** No stream extraction or protection bypass of any kind. No GPL code. No InnerTube player calls. No change to Offline, the database, Audius or OpenSubsonic, and no merged product code. STOP after the report.

---

## Execution outcome (2026-10-06)

The staged plan above (Y0 spikes, then Y1…) was superseded the same day by the user's direction to
implement the whole transition at once (D-38). What was built, against this proposal:

| Proposal | Outcome |
|---|---|
| §7 target architecture: one ONLINE `MusicSource`, rendered by the existing Online UI | built: `sources:youtubemusic`; Online menus Home, Search, Library, Radio, History |
| §8 account options | A2 (the listener's web session from Google's own sign-in page) chosen and built, sealed at rest; the "not before an explicit decision" item in answer 8 was decided by D-38 |
| §13.4 P2 delegated playback | built (`player:remote`, `OwnerAwarePlaybackController`); U1–U6 still need the phone |
| §13.5 P1 embedded player | not built (P2 + P0 cover playback; P1 needs a visible video) |
| §13.6 P0 hand-off | built (fallback and start path) |
| §15 database | v5 additive (`track.media_kind`); account-scoped online operations |
| §6 REMOVE list | Audius, OpenSubsonic, configured sources, multi-source aggregation removed; credentials of retired servers deleted at startup; cleartext off again |
| §6 KEEP list | kept; Offline untouched (all Offline tests pass) |
| Y0 "decisions to record" | D-38 |
| Y0 P2 spike on the phone | not possible from the build container; replaced by runtime handling of each unknown and a debug probe (`remote-probe`) |

`research/YOUTUBE_SOURCE_INVESTIGATION.md`, cited above, is not in this repository (it was never
committed); the evidence for the implementation is in `research/YOUTUBE_MUSIC_IMPLEMENTATION_NOTES.md`.

