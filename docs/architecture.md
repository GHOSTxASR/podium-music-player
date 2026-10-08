# Architecture

**Status:** v0.1 · **Date:** 2026-10-02 · Decisions referenced: ADR-001 … ADR-012, D-01 … D-16.

## 1. Shape of the system

```
┌──────────────────────────────── UI process (single process app) ───────────────────────────────┐
│                                                                                                │
│  MainActivity ── NavDisplay(backStack) ── feature screens (stateless composables)               │
│        │                 ▲                        │  UiState (StateFlow)   ▲ intents             │
│        │                 │                        ▼                        │                     │
│        │          InputRouter ◄── PodWheel / keys / rotary / TalkBack actions                    │
│        │                 │                                                                       │
│        ▼                 ▼                                                                       │
│   ViewModels ──► Repositories (core:data) ──► Room 3 (core:database)   DataStore (prefs)         │
│        │                 │                                                                       │
│        │                 └──► SourceRegistry ──► MusicSource adapters ──► OkHttp ──► network    │
│        │                                                                                         │
│        └──► PlaybackController (MediaController) ═══ binder ═══╗                                 │
│                                                                ▼                                 │
│   PlaybackService : MediaLibraryService  ── ExoPlayer (main looper)                              │
│        ├─ QueueManager (single writer) ── QueuePersister ──► Room                                │
│        ├─ ResolvingDataSource ──► StreamResolver ──► SourceRegistry / DownloadIndex / Cache      │
│        ├─ QualityInspector (format + output path)                                               │
│        ├─ AutoplayEngine                                                                         │
│        └─ SessionPolicy (onConnectAsync grants)  ◄══ SystemUI, Bluetooth, Auto, Wear            │
│                                                                                                │
│   DownloadJobService (UIDT, API 34+) / DownloadWorker (WorkManager, API 29–33)                  │
│        └─ DownloadManager ── OkHttp ── files/downloads ── Verifier ──► Room                     │
│                                                                                                │
│   LibrarySyncWorker (WorkManager, periodic + on-demand) ── Library sources ──► Room             │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

One process. Components share one `AppGraph` (ADR-009). The playback service is reached **only** through `PlaybackController`, the same channel system controllers use.

## 2. Layering rules

| Layer | May depend on | Must not depend on |
|---|---|---|
| `feature/*` (UI) | `core:designsystem`, `core:interaction`, `core:model`, `player:api`, repository **interfaces** | other features, Media3, Room, OkHttp, any `sources:*` impl |
| `core:data` (repositories) | `core:database`, `sources:api`, `core:model`, `core:common` | features, Compose |
| `sources:*` adapters | `sources:api`, `core:network`, `core:model` | Room, UI, other adapters |
| `player:service` | `player:api`, Media3, `sources:api`, `downloads` (read index), `core:database` | features |
| `player:api`, `sources:api`, `core:model`, `core:common` | Kotlin stdlib, coroutines, serialization | **anything Android** |

Enforced by Gradle module boundaries + a `dependency-guard`-style test listing allowed edges.

## 3. Module dependency graph (v1)

```
app ─► feature/* ─► core:designsystem ─► core:model
                 ├► core:interaction ─► core:designsystem (haptics tokens)
                 ├► core:data ─► core:database ─► core:model
                 │            └► sources:api ─► core:model
                 └► player:api ─► core:model
app ─► player:service ─► player:api, sources:api, core:database, downloads
app ─► sources:{local,subsonic,audius,fixture(debug)} ─► sources:api, core:network
app ─► downloads ─► core:database, sources:api, core:network
app ─► lyrics ─► sources:api, core:network, core:database
```

## 4. Key contracts

These are normative sketches; names may be refined, semantics may not.

### 4.1 Identity
```kotlin
@JvmInline value class SourceId(val value: String)          // "local", "ytmusic"
@JvmInline value class TrackId(val value: String)           // "<sourceId>|<sourceTrackId>"
@JvmInline value class AlbumId(val value: String)
@JvmInline value class ArtistId(val value: String)
@JvmInline value class PlaylistId(val value: String)        // Podium-local UUID; remote refs stored separately
```
Identity is always source-qualified. Cross-source "same song" matching (ISRC/MBID) is a *hint* for dedupe UI, never a primary key.

### 4.2 Music sources (`sources:api`)
**Normative specs: [`architecture/MUSIC_SOURCE_ARCHITECTURE.md`](architecture/MUSIC_SOURCE_ARCHITECTURE.md) and [`architecture/YOUTUBE_MUSIC_ARCHITECTURE.md`](architecture/YOUTUBE_MUSIC_ARCHITECTURE.md)** (ADR-013, updated by D-48). Summary:
- `MusicSource` = `SourceDescriptor` (incl. `Basis`) + observable effective `SourceCapabilities` + optional facets (`catalog`, `library`, `playback`, `artwork`, `lyrics`, `recommendations`, `downloads`, `auth`, `queueSync`).
- Canonical provider-neutral `Track` (identifiers, version info, explicitness, advertised qualities, availability) with an opaque `SourceRef`.
- `StreamResolver` → `ResolveOutcome` → **`PlaybackTarget`** = `DirectStream(PlayableMedia)` | `RemoteProvider(...)` | `Embedded(...)` — see [`architecture/PLAYBACK_TARGETS.md`](architecture/PLAYBACK_TARGETS.md). Under D-48, YouTube Music resolves to `DirectStream(PlayableMedia)`.
- `TrackMatcher` (identity-preserving fallback & dedupe), `SourceHealth` (circuit breaker), `ConnectedSource` (auth/connection state).
- Library sources are still synchronised into Room (ADR-002/ADR-008); provider availability per [`architecture/SOURCE_CAPABILITY_MATRIX.md`](architecture/SOURCE_CAPABILITY_MATRIX.md).
UI asks capabilities, availability, route, and owner — never provider identity.

### 4.3 Playback (`player:api`)
```kotlin
interface PlaybackController {
    val state: StateFlow<PlaybackSnapshot>      // status (state machine), item, modes, error — see playback-state-machine.md
    val queue: StateFlow<QueueState>
    fun positionMs(): Long                      // read lazily in draw phase; not a StateFlow (perf)
    fun play(); fun pause(); fun togglePlayPause()
    fun next(); fun previous()                  // previous: restart if > 3 s
    fun seekTo(ms: Long)
    fun beginScan(direction: ScanDirection); fun endScan()
    fun setRepeat(mode: RepeatMode); fun setShuffle(enabled: Boolean); fun setAutoplay(enabled: Boolean)
    fun playContext(context: PlayContext, startAt: Int, shuffle: Boolean = false)
    fun playNext(tracks: List<TrackId>); fun addToQueue(tracks: List<TrackId>)
    fun moveQueueItem(uid: QueueUid, toIndex: Int); fun removeQueueItems(uids: Set<QueueUid>)
    fun skipTo(uid: QueueUid); fun clearUpcoming()
    val output: StateFlow<OutputInfo>           // device type, mixer rate, bit-perfect, volume fixed
}
```
Commands travel as Media3 session commands (standard ones where they exist, `SessionCommand` customs for queue operations), so every mutation is executed inside the service by `QueueManager`. `PlaybackSnapshot` also carries `owner` (Podium / Remote / Embedded) and `controls` (what the active engine supports); the service's `PlaybackRouter` chooses the engine per resolved target (`architecture/PLAYBACK_TARGETS.md` §5).

### 4.4 Input (`core:interaction`)
```kotlin
sealed interface PodiumInput {
    data class Rotate(val detents: Int, val velocityDetentsPerSec: Float, val source: InputSource) : PodiumInput
    data class Press(val button: WheelButton) : PodiumInput          // MENU, PREVIOUS, NEXT, PLAY_PAUSE, CENTER
    data class LongPress(val button: WheelButton) : PodiumInput
    data class Release(val button: WheelButton) : PodiumInput        // ends scan
}
interface InputTarget { val context: WheelContext; fun onInput(input: PodiumInput): Boolean } // true = consumed
```
`InputRouter` keeps a stack of targets (screen at bottom, overlays above). Detail in `interaction-model.md`.

### 4.5 Repositories (`core:data`)
`LibraryRepository` (browse queries over synced/cached metadata, FTS search), `LikesRepository`, `PlaylistRepository`, `HistoryRepository`, `SearchRepository` (fan-out + merge), `SettingsRepository` (DataStore), `ArtworkRepository` (palette/atmosphere), `SourceAccountRepository`, `SyncRepository` (outbox). All expose `Flow` for reads and `suspend` functions returning `Outcome` for writes.

## 5. Runtime flows

### 5.1 Play a song from an album
1. Album screen: Center on track 4 → `AlbumViewModel.play(4)` → `PlaybackController.playContext(PlayContext.Album(albumId), startAt = 4)`.
2. Controller sends custom command → service `QueueManager.replaceContext(...)` builds `QueueItem`s (origin `CONTEXT`), maps to `MediaItem(podium://track/<id>)`, `player.setMediaItems(items, 4, 0)`, `prepare()`, `play()`; persists.
3. ExoPlayer opens item → `ResolvingDataSource` → `StreamResolver`: downloaded? → `file://`; local? → content URI; else `StreamResolver.resolve(ResolveRequest(track, PLAYBACK, qualityFor(network)))` → `PlaybackTarget.DirectStream(PlayableMedia)` (cached until `expiresAt − 60 s`; full pipeline in `architecture/MUSIC_SOURCE_ARCHITECTURE.md` §8).
4. `QualityInspector` combines `PlayableMedia.advertised` (reported) with decoder `Format` (measured) → `PlaybackSnapshot.format`.
5. Navigator auto-pushes Now Playing (D-05). Wheel context becomes `Volume`.

### 5.2 Like a track
UI → `LikesRepository.setLiked(trackId, true)` → Room `liked_track` upsert + `track` row pinned → if source has `remoteLikes`, enqueue outbox `Star(trackId)` → `SyncWorker` flushes when online. UI observes `Flow<Boolean>`; optimistic, never blocks.

### 5.3 Download an album
UI → `DownloadManager.enqueueGroup(Album(albumId), quality)` → rows `QUEUED` per playable/permitted track (duplicates skipped by PK) → executor scheduled (UIDT/WorkManager) → per-track transfer → verify → `COMPLETED` → `StreamResolver` now prefers the file. Details in `download-system.md`.

### 5.4 Search
`SearchViewModel` debounces 250 ms → `SearchRepository.search(q)` → parallel: local FTS (instant) + each enabled source with `search` capability (timeout 6 s each) → results stream into sections as they arrive; failures become per-source inline notices. Details in `screen-inventory.md` (Search).

## 6. Dependency injection & scoping (ADR-009)

| Scope | Lifetime | Examples |
|---|---|---|
| App singleton (`AppGraph`) | process | Database, OkHttpClient, SourceRegistry, repositories, DownloadManager, ConnectivityMonitor, SettingsRepository |
| Service | `PlaybackService` lifetime | ExoPlayer, QueueManager, AutoplayEngine, MediaLibrarySession |
| Controller | UI process while any UI is alive | `MediaControllerPlaybackController` (connect on `onStart`, keep across config changes via a retained holder) |
| Nav entry | while the key is on the back stack | ViewModels (Nav3 `rememberViewModelStoreNavEntryDecorator`) |
| Composition | while composed | `FocusList` state, animation state |

## 7. Threading & concurrency

- Main thread: Compose, ExoPlayer & `MediaSession` (Media3 1.11 requires the application looper for session access).
- `DispatcherProvider.io`: Room, file I/O, OkHttp calls. `default`: palette extraction, FTS ranking, autoplay scoring, LRC parsing.
- Structured concurrency only; no `GlobalScope`. App-scoped work uses `AppScope = CoroutineScope(SupervisorJob() + default)`.
- Stream resolution runs on ExoPlayer's loader thread via `runBlocking`-free bridge: `ResolvingDataSource.Resolver` calls a blocking `StreamResolver.resolveBlocking(id)` that awaits a cached `Deferred` with a hard 8 s timeout; rapid skips cancel by item uid.
- Database writes for the queue are debounced and serialised through one `Mutex`.

## 8. UI state management

- Unidirectional: `ViewModel` exposes `StateFlow<XUiState>` (immutable, `@Immutable` data classes) and handles intents as plain functions.
- Composables are stateless and previewable; screens receive `UiState` + callbacks + an `InputTarget` binding.
- **Hot-path rule:** values that change every frame (playback position, wheel angle, lens offset) are never in `UiState`. They're read in the draw/layout phase via lambdas (`{ controller.positionMs() }`) to avoid recomposition.
- Loading/empty/error/offline are explicit members of every screen's `UiState` (`ContentState<T>`: `Loading`, `Empty(reason)`, `Error(PodiumError)`, `Ready(data, isStale)`).

## 9. Error model

```kotlin
sealed interface PodiumError {
    data object Offline : PodiumError
    data class Network(val kind: NetworkFailure) : PodiumError          // Timeout, DnsFailure, TlsFailure, Reset
    data class SourceUnavailable(val source: SourceId, val httpStatus: Int?) : PodiumError
    data class AuthRequired(val source: SourceId) : PodiumError
    data class RateLimited(val source: SourceId, val retryAfter: Duration?) : PodiumError
    data class NotFound(val what: String) : PodiumError
    data class StreamUnavailable(val track: TrackId, val reason: String?) : PodiumError
    data class UnsupportedFormat(val codec: String?) : PodiumError
    data class StorageFull(val neededBytes: Long?) : PodiumError
    data class Corrupt(val what: String) : PodiumError
    data class Unexpected(val cause: Throwable) : PodiumError                // logged; shown generically
}
```
`Outcome<T> = Success(T) | Failure(PodiumError)`. Each error maps to user copy in one table (`design-system.md` §11). Raw exceptions never reach UI text.

## 10. Observability

- `Logger` facade (`core:common`) with tags: `playback.state`, `queue`, `source.<id>`, `download`, `db`, `net`, `input` (debug only).
- Release builds log **ids and states only** — no titles, artists, queries, or URLs with tokens. As implemented (D-37) there's no `Redactor`: server code never logs a URL or an exception message at all, only error class names.
- Ring-buffer log (last 2,000 lines) in memory + file in `cacheDir/diagnostics`, exportable by the user from Settings ▸ About ▸ Diagnostics. Nothing is uploaded.
- Debug-only overlay: playback state machine, current queue origins, glass tier, frame timing.

## 11. Configuration

- No secrets in the client. Launch sources need none (Audius: `app_name`; LRCLIB: User-Agent).
- `BuildConfig` fields: `USER_AGENT = "Podium/<version> (Android; +<project-url>)"`, feature flags for unfinished phases (compile-time, removed before release).
- Local developer config in `local.properties` (gitignored); release signing via environment variables in CI.

## 12. Process death & restoration

| What | Mechanism |
|---|---|
| Back stack | Nav3 `rememberNavBackStack` (serializable keys) |
| Focus positions | `rememberSaveable` per screen (`FocusList` saver: index + item key) |
| Queue + position | Room `queue_state`/`queue_item`, saved on mutation (debounced), every 10 s while playing, on pause, and in `onTaskRemoved`/`onDestroy` |
| Playback | Not auto-resumed on cold start; prepared paused. System resumption via `onPlaybackResumption`. |
| Downloads | Persisted state machine; executor re-enqueues `RUNNING` rows as `QUEUED` on start |

## 13. Risk register

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-01 | Users expect a mainstream free catalog (YouTube) | High | High | Addressed by D-48: YouTube Music is the sole online provider for personal sideloaded player |
| R-02 | Glass performance on mid-range GPUs | Medium | High | Single capture, ≤ 4 persistent glass surfaces, tiers, frame-time auto-downgrade |
| R-03 | Wheel feel (latency, gesture ambiguity) not "right" | Medium | High | Physical-device tuning loop, tokenised detent/slop/acceleration, measured latency budget |
| R-04 | Room 3 maturity | Low–Med | Medium | Fallback to Room 2.8 documented (ADR-004) |
| R-05 | Backdrop single maintainer / RenderThread crash class | Low–Med | Medium | Wrapper API; Solid tier needs no library; device tests for sheets |
| R-06 | Subsonic server variance (Navidrome vs Gonic vs Ampache) | High | Medium | Subsonic retired; preserved as historical note |
| R-07 | Media3 1.11 session/notification regressions on Android 17 | Medium | High | Explicit `onConnectAsync` grants; verify on API 36/37 in slice; can pin 1.10.x |
| R-08 | Low-RAM dev machine slows iteration | High | Medium | Physical device, JVM screenshot tests, Gradle heap caps |
| R-09 | Lyrics copyright | Medium | Low–Med | Consent, local cache only, no redistribution, source-provided lyrics first |
| R-10 | Name/trademark ("Podium") | Medium | Medium | Clearance before public release; name isolated to resources |
| R-11 | Large-screen requirements (target 36 ignores orientation locks) | High | Medium | Adaptive layouts planned (P1), landscape phone layout in P0 |
| R-12 | Cross-source fallback plays the wrong recording | Medium | High | Matcher dedupe; single online provider eliminates cross-source fallback risk |
| R-13 | Provider policy changes (Spotify Nov 2024 / May 2025 / Feb 2026; YouTube policies Sep 2026) | High | Medium–High | Capability states with `DISABLED_BY_POLICY`; providers isolated in modules; InnerTube client fallback |
| R-14 | Accidental GPL derivative work from studying BitChord | Low | High | Superseded in part by D-48: BitChord is an active implementation reference; factual GPL-3.0 licensing notices respected on extracted/shared modules |
| R-15 | Remote/embedded engines complicate the router and session ownership | Medium | Medium | Hard-cut handoffs only; session release/restore rules; fakes + tests (PLAYBACK_TARGETS.md §9) |
