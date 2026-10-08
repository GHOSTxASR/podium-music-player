# Music Source Architecture

**Status:** Accepted (ADR-013, ADR-014, updated by D-48) · **Date:** 2026-10-02 (updated 2026-10-06) · **Normative:** this document supersedes `architecture.md` §4.2 and the source parts of ADR-002.
Related: [`SOURCE_CAPABILITY_MATRIX.md`](SOURCE_CAPABILITY_MATRIX.md) · [`PLAYBACK_TARGETS.md`](PLAYBACK_TARGETS.md) · [`YOUTUBE_MUSIC_ARCHITECTURE.md`](YOUTUBE_MUSIC_ARCHITECTURE.md) · [`../research/BITCHORD_ARCHITECTURE_REVIEW.md`](../research/BITCHORD_ARCHITECTURE_REVIEW.md)

## 0. The invariant

> **The UI must survive replacing the underlying music provider entirely.**

Concretely:
1. No feature module depends on a provider module. No code outside `sources:<provider>` knows a provider's name, id format, URL shape, or quirks.
2. No UI code branches on provider identity (`if (spotify)`). UI branches only on **capabilities**, **availability**, **playback route**, and **attribution requirements** — all expressed in provider-neutral types.
3. A provider can lack any capability. Nothing is assumed because the UI would like it.

Enforcement: Gradle module graph (features cannot see `sources:*` implementations), a dependency-guard test, and a custom lint rule forbidding `SourceKind`/provider-name comparisons outside `sources:*` and `app` wiring.

## 1. Layer map

```
feature/* (UI)                    reads: Track, capabilities, availability, route, attribution
   │
   ▼
core:data use-cases/repositories  LibraryRepository · SearchRepository · LikesRepository · PlaylistRepository
   │                              (Podium-owned data: likes/playlists/history are local-first, ADR-008)
   ▼
sources:api   SourceRegistry ──► MusicSource (facets) ◄── implemented by sources:local / youtubemusic
   │              │
   │              ├─ SourceHealthMonitor (circuit breaker)
   │              ├─ TrackMatcher (identity across sources)
   │              └─ StreamResolver ──► PlaybackTarget (PLAYBACK_TARGETS.md)
   ▼
player:api    PlaybackController ─► QueueManager ─► PlaybackRouter ─┬─► DirectStreamEngine (Media3)
                                                                    ├─► RemoteEngine (provider SDK/protocol)
                                                                    └─► EmbeddedEngine (official embed, foreground)
```

## 2. Source identity

```kotlin
@JvmInline value class SourceId(val value: String)    // stable per connected instance: "local", "ytmusic"

data class SourceDescriptor(
    val id: SourceId,
    val displayName: String,                 // user-facing: "Home server", "On this device"
    val providerName: String,                // "Navidrome", "Audius" — display only, never branched on
    val basis: Basis,                        // LOCAL_DEVICE | USER_SERVER | OFFICIAL_API | UNOFFICIAL_API
    val attribution: Attribution?,           // marks/link-back requirements a provider imposes (rendered generically)
    val termsUrl: String?,
    val iconKey: String?,                    // resolved by the design system's icon registry
)
```
`Basis` is shown to users (e.g., "Unofficial — may stop working") and drives **build policy**: release builds can exclude `UNOFFICIAL_API` sources entirely (see §6.3).

## 3. Capability model

### 3.1 Capabilities
```kotlin
enum class Capability {
    SEARCH, BROWSE, LIBRARY, LIKES, PLAYLISTS,
    DIRECT_STREAM, REMOTE_PLAYBACK, EMBEDDED_PLAYBACK,
    DOWNLOADS, LYRICS, RECOMMENDATIONS, ARTWORK,
    AUTHENTICATION, QUEUE_SYNC, SCROBBLE,
}

data class CapabilityState(
    val status: Status,
    val note: String? = null,                // user-facing reason when not AVAILABLE
) {
    enum class Status {
        AVAILABLE,
        REQUIRES_SIGN_IN,
        REQUIRES_SUBSCRIPTION,               // e.g., provider tier lacks on-demand playback
        REQUIRES_PROVIDER_APP,               // e.g., remote control needs the provider's app installed
        DEGRADED,                            // works with limits (quota, server feature missing)
        UNAVAILABLE,                         // provider doesn't offer it
        DISABLED_BY_POLICY,                  // Podium build/user policy turned it off
    }
}

data class SourceCapabilities(val states: Map<Capability, CapabilityState>) {
    fun has(c: Capability) = states[c]?.status == CapabilityState.Status.AVAILABLE
}
```

### 3.2 How capabilities are computed
`effective = adapter-declared ∩ runtime probes ∩ account state ∩ build/user policy`, recomputed on auth/health/server-probe changes and exposed as `StateFlow<SourceCapabilities>`.
- **Adapter-declared:** what the provider can ever do (static).
- **Runtime probes:** e.g., OpenSubsonic `getOpenSubsonicExtensions`, server download permission, transcoding support.
- **Account state:** signed out → `REQUIRES_SIGN_IN`; tier flags (e.g., a provider's on-demand capability) → `REQUIRES_SUBSCRIPTION`.
- **Policy:** build flags and user settings → `DISABLED_BY_POLICY`.

### 3.3 Track-level capabilities
A source may allow downloads but not for *this* track (artist disabled downloads; server role). So each track carries a computed snapshot:
```kotlin
data class TrackCapabilities(
    val canPlay: Availability,               // see §4
    val route: PlaybackRoute,                // DIRECT | REMOTE | EMBEDDED | NONE  (kind only; resolution is later)
    val canDownload: DownloadPermission,     // Allowed(options) | NotPermitted(reason) | NotApplicable
    val canLikeRemotely: Boolean,            // Podium-local like is always possible
    val canAddToRemotePlaylist: Boolean,
    val hasLyrics: LyricsHint,               // UNKNOWN | LIKELY | NONE
)
```
The UI renders from this; e.g., the Download item shows disabled with the provided reason.

## 4. Canonical domain model (`core:model`)

```kotlin
@JvmInline value class TrackId(val value: String)        // "<sourceId>|<opaque provider track key>" (separator `|`, as in code)

data class Track(
    val id: TrackId,
    val source: SourceRef,
    val title: String,
    val artists: List<ArtistCredit>,                      // ordered; roles PRIMARY | FEATURED | REMIXER | COMPOSER
    val artistDisplay: String,                            // as the provider formats it, for rendering
    val album: AlbumRef?,                                 // title, provider album key, album artist display
    val durationMs: Long?,
    val artwork: ArtworkRef?,
    val identifiers: RecordingIdentifiers,                // isrc, musicBrainzRecordingId, acoustId (when known)
    val explicitness: Explicitness,                       // EXPLICIT | CLEAN | NOT_EXPLICIT | UNKNOWN
    val releaseDate: PartialDate?,                        // year / year-month / full
    val discNumber: Int?, val trackNumber: Int?,
    val version: VersionInfo,                             // derived by TrackNormalizer (§9.2), never provider-specific
    val advertisedQualities: List<AudioQuality>,          // what the catalogue says it offers (not what will play)
    val availability: Availability,                       // PLAYABLE | UNAVAILABLE(reason) | REGION_BLOCKED | REQUIRES_SUBSCRIPTION | UNKNOWN
)

data class SourceRef(
    val sourceId: SourceId,
    val providerTrackKey: String,                         // opaque to everyone except the adapter
    val providerUri: String?,                             // e.g., a provider deep link for "Open in …"
    val providerData: String? = null,                     // adapter-private serialized blob; never parsed outside the adapter
)
```
Rules:
- **No provider-specific fields** in `Track`. Anything an adapter needs later goes in `providerData`.
- `TrackCapabilities` and resolved `PlaybackTarget` are **not** stored in `Track`; they're computed per context (capabilities snapshot) and per play (target).
- Persistence mapping in `data-model.md` (§ updated: `track.identifiers_json`, `track.version_json`, `track_equivalence`).

### 4.1 Recording identity across sources
```kotlin
@JvmInline value class RecordingKey(val value: String)   // "isrc:USRC17607839" | "mbid:…" | "cluster:<uuid>"
```
`track_equivalence(track_a, track_b, tier, confidence, evidence_json, decided_at, decided_by AUTO|USER)` stores matcher decisions so they're computed once, inspectable, and user-correctable ("Not the same song").

## 5. Facets (`sources:api`)

A source implements only the facets it supports; absent facet ⇒ capability `UNAVAILABLE`.

```kotlin
interface MusicSource {
    val descriptor: SourceDescriptor
    val capabilities: StateFlow<SourceCapabilities>
    val catalog: CatalogFacet?
    val library: LibraryFacet?
    val playback: PlaybackFacet?
    val artwork: ArtworkFacet?
    val lyrics: LyricsFacet?
    val recommendations: RecommendationFacet?
    val downloads: DownloadFacet?
    val auth: AuthFacet?
    val queueSync: QueueSyncFacet?
}

interface CatalogFacet {
    suspend fun search(query: SearchQuery): Outcome<SearchPage>             // typed sections; paging cursor
    suspend fun track(key: SourceRef): Outcome<Track>
    suspend fun album(key: String): Outcome<AlbumDetail>
    suspend fun artist(key: String): Outcome<ArtistDetail>
    suspend fun playlist(key: String): Outcome<PlaylistDetail>
    suspend fun browse(node: BrowseNode?): Outcome<BrowsePage>              // provider-neutral tree (charts, genres, …)
}

interface LibraryFacet {                                                     // user-owned collection at the provider
    fun sync(since: SyncCursor?): Flow<SyncEvent>                           // Upsert/Delete/Progress/Done
    suspend fun setLiked(track: SourceRef, liked: Boolean): Outcome<Unit>   // when LIKES available
    suspend fun remotePlaylists(): Outcome<List<RemotePlaylistSummary>>
    suspend fun editRemotePlaylist(edit: RemotePlaylistEdit): Outcome<Unit>
}

interface PlaybackFacet {
    val routes: Set<PlaybackRoute>                                          // DIRECT, REMOTE, EMBEDDED
    suspend fun resolve(request: ResolveRequest): ResolveOutcome            // → PlaybackTarget (PLAYBACK_TARGETS.md)
    fun remoteController(): RemoteProviderController?                       // when REMOTE ∈ routes
}

interface LyricsFacet          { suspend fun lyrics(track: Track): Outcome<Lyrics?> }
interface ArtworkFacet         { fun request(ref: ArtworkRef, sizePx: Int): ArtworkRequest }        // URL + headers or local URI
interface RecommendationFacet  { suspend fun related(seeds: List<Track>, limit: Int): Outcome<List<Track>> }
interface DownloadFacet        { fun permission(track: Track): DownloadPermission
                                 suspend fun resolveDownload(track: Track, tier: QualityTier): ResolveOutcome }
interface AuthFacet            { val state: StateFlow<AuthState>; fun begin(): AuthFlow; suspend fun signOut() }
interface QueueSyncFacet       { suspend fun save(snapshot: QueueSnapshot); suspend fun load(): QueueSnapshot? }
```

## 6. Source registry & connected sources

### 6.1 Connected source
```kotlin
data class ConnectedSource(
    val sourceId: SourceId,
    val accountId: String?,                  // provider account / username, null for local
    val displayName: String,
    val authenticationState: AuthState,      // NOT_REQUIRED | SIGNED_OUT | SIGNING_IN | SIGNED_IN(account) | EXPIRED | REJECTED
    val capabilities: SourceCapabilities,
    val connectionState: ConnectionState,    // from SourceHealth: CONNECTED | DEGRADED | UNREACHABLE | RATE_LIMITED | DISABLED
    val priority: Int,                       // user order (lower first)
    val enabled: Boolean,
)
```
Persisted in `source_account` (data-model), credentials in the encrypted credential store (security.md).

### 6.2 Lifecycle
```
Configured ─► Probing ─► (AuthRequired ─► Authenticating) ─► Ready ⇄ Degraded/Unreachable
                 │                                             │
                 └─► Rejected (bad URL/credentials) ◄──────────┘   Removed (user) → metadata GC, downloads kept if user chose
```
`SourceRegistry` owns instances (one per `SourceId`), exposes `StateFlow<List<ConnectedSource>>`, applies user priority, and is the only place adapters are constructed (from `SourceFactory`s registered by the app module — features never see factories).

### 6.3 Build & user policy
- `SourcePolicy` (compile-time per build type + user toggles) can disable a `Basis` or a specific factory. Example: a Play-distributed build ships without any `UNOFFICIAL_API` factory linked at all.
- Providers requiring the user's own developer credentials (bring-your-own client id) are configured per user and never ship shared secrets.

### 6.4 Authentication flows (provider-neutral)
`AuthFlow` variants: `None`, `Credentials(fields)` (server URL/user/password or API key), `OAuthPkce(authUri, redirect)` via Custom Tabs, `ProviderAppSso(package)` (provider app hands back a token). Tokens/secrets: Keystore-encrypted; refresh handled inside the adapter; UI only sees `AuthState`.

**As implemented (D-37):** the `Credentials(fields)` flow exists as `AuthFacet.signInFields` / `signIn(values)` / `signOut()`, built on the same `SetupField`s a source's setup form uses. Secrets go to the `CredentialStore` only. Other flows aren't built. See §11.4.

## 7. Source health

```kotlin
sealed interface SourceHealth {
    data object Healthy : SourceHealth
    data class Degraded(val reason: String, val errorRate: Float) : SourceHealth
    data class Unreachable(val since: Instant, val nextProbeAt: Instant) : SourceHealth
    data class AuthRejected(val reason: String) : SourceHealth
    data class RateLimited(val until: Instant) : SourceHealth
    data object Disabled : SourceHealth
}
```
- **What counts:** transport failures (timeouts, DNS, TLS, 5xx) and auth failures. A **miss** (track not found, not permitted) never counts.
- **Circuit breaker:** 3 consecutive transport failures → `Unreachable`, skip for 30 s → 2 min → 10 min (half-open probe with a cheap `ping`); one success closes it. 429 → `RateLimited(Retry-After or 60 s)`. 401/403 on auth endpoints → `AuthRejected` (no retries until the user acts).
- Health is **per source instance** (a LAN server can be unreachable while the internet works).
- Every resolve/search records an outcome to `SourceHealthMonitor`; health feeds `ConnectedSource.connectionState` and resolver ordering.
- **As implemented:** the breaker opens on 3 consecutive infrastructure failures (network, server, unknown). It backs off 30 s, then 2 min, then 10 min. When the backoff expires, the next real request is the probe (there is no separate `ping`). A failure while the breaker is still open (a source asked anyway because every breaker was open, §11.2) keeps it open for the same time: only a failed probe lengthens the backoff (D-37).

## 8. Stream resolution

```kotlin
data class ResolveRequest(
    val track: Track,
    val purpose: Purpose,                    // PLAYBACK | PREFETCH | DOWNLOAD
    val quality: QualityRequest,             // computed per request from network + settings (QualityPolicy)
    val queueUid: QueueUid?,                 // for pinning
)
sealed interface ResolveOutcome {
    data class Resolved(val target: PlaybackTarget, val via: ResolutionPath) : ResolveOutcome
    data class Miss(val reason: MissReason) : ResolveOutcome              // NOT_FOUND, NOT_PERMITTED, NOT_STREAMABLE, GATED
    data class Failed(val error: PodiumError, val retryable: Boolean) : ResolveOutcome
}
```

### 8.1 Pipeline (`StreamResolver`, in `sources:api`, provider-neutral)
**As implemented (D-35, D-36):**

1. Pinned selection (reused while valid).
2. An owned copy: the same song, or an EXACT copy in the same environment.
3. The track's own source, within the per-source timeout (6 s).
4. EXACT fallback on the other enabled sources of the track's environment, in priority order. A copy already known to be EXACT (`EquivalenceStore`, persisted) is used directly; otherwise that source is searched and the matcher decides.
5. Otherwise `Miss`/`Failed`.

Rules along the way:

- **Environment.** The environment comes from the track's own source. A track whose source is no longer registered has no environment, so it gets no fallback.
- **Miss vs failure.** A miss ("not found / not permitted / not streamable", or an empty search) never touches health. A failure does: a timeout, an exception (e.g. a malformed answer), a network or server error, or a broken search. "Not authorised" counts as rejected credentials only for a source that signs in.

The numbered list below is the original design, kept for the path names it introduced.

1. **Pinned?** If this `queueUid` already resolved and the target hasn't expired → reuse (no mid-play switching).
2. **Owned copy of this exact track** → verified download or local file (`ResolutionPath.DOWNLOAD/LOCAL`).
3. **Owned copy of an equivalent recording** (equivalence tier per §10 policy) → `ResolutionPath.EQUIVALENT_OWNED` (surfaced in UI).
4. **The track's own source** (if healthy and route-capable) → `PlaybackFacet.resolve`.
5. **Fallback to an equivalent track on another enabled source**, by user priority, only per §10 → `ResolutionPath.EQUIVALENT_SOURCE` (surfaced).
6. Else `Miss`/`Failed` → playback state machine applies `SkipAfter`/`AwaitNetwork`.

### 8.2 Caching, expiry, retries
- Cache key `(TrackId, QualityTierKey)`; entries live until `expiresAt − 60 s` (or 30 min when unknown).
- HTTP 401/403/410 during playback → invalidate entry → re-resolve **once** from the same source; second failure → health event + fallback step 5.
- Concurrent resolves for the same key share one in-flight job (no thundering herd on rapid skips).
- PREFETCH only for sources whose descriptor marks `cheapResolve = true`, and the result is **pinned** to that queue item.
- **A source that stops serving takes its pins with it (D-37).** When a source is turned off, signed out (or its sign-in refused) or removed, the engine unpins the queued items that source served, and they resolve afresh before they play: a pinned URL may carry the source's sign-in. The playing item keeps its pin, because audio never changes source mid-track (D-18).

## 9. Track normalization & matching (`TrackMatcher`)

Independent Podium design (ADR-014). Lexicon derived from MusicBrainz's published style guidelines (recording/release disambiguation, secondary release types) and Podium's own test corpus.

### 9.1 Uses and policies
| Use | Minimum tier | Behaviour |
|---|---|---|
| Automatic playback fallback (§8.1 step 3/5) | `EXACT` | Silent switch **of source** only, always surfaced as "Playing from Home server" |
| ~~Same, with setting "Allow close matches"~~ | — | **Not built.** D-17 (revised): automatic fallback is EXACT only, with no setting |
| Cross-source duplicate grouping in lists/search | `EXACT` (D-35; stricter than the original `STRONG`) | One row per recording; every member is a safe fallback for every other. Never names the sources |
| Playlist import / migration | `STRONG` auto; `PROBABLE` to review list | User confirms probable ones |
| Autoplay de-duplication | `EXACT` (as implemented) | Avoid re-suggesting the same recording from another source |
| Never | `PROBABLE` or `AMBIGUOUS` for automatic playback | — |

### 9.2 Normalization (`TrackNormalizer`)
1. Unicode NFKC, case-fold, unify quotes/dashes/apostrophes/whitespace; Latin-script diacritic folding for the comparison key only (display text untouched). No transliteration across scripts.
2. Extract featured/with credits from the title into `ArtistCredit(role = FEATURED)`.
3. Split the title into: **base** text; **bracketed segments**; **trailing dash segments**.
4. Classify every segment against the `VersionLexicon` into:
   - **Identity-changing** (`VersionTag`): `LIVE`, `REMIX`, `ACOUSTIC`, `INSTRUMENTAL`, `KARAOKE`, `A_CAPPELLA`, `COVER`, `DEMO`, `RE_RECORDING` (e.g., "(… Version)" re-recordings), `SPED_UP`, `SLOWED`, `REVERB`, `NIGHTCORE`, `EXTENDED`, `RADIO_EDIT`, `SINGLE_EDIT`, `MONO`, `ORCHESTRAL`, `MEDLEY`, `MASHUP`, `REPRISE`, `MUSIC_VIDEO_AUDIO`.
   - **Compatible** (`VariantNote`): `REMASTER(year?)`, `ORIGINAL_MIX`, `ALBUM_VERSION` (both mean "the usual one").
   - **Packaging** (`ContextNote`): soundtrack attribution ("From …"), "Official Audio/Lyric Video", bonus-track notes, edition notes. Tie-break only.
   - **Explicitness markers** ("Explicit", "Clean") → `Explicitness`.
5. Identity key = base words (alphanumeric, numerals kept — "Part 2" ≠ "Part 1").
6. Artist credits: prefer structured provider arrays; when only a string exists, split conservatively and **also** keep the full normalized string (protects band names containing "&"/"and"/"x").

### 9.3 Evidence → tier (rule-based; score only orders within a tier)
| Evidence | Effect |
|---|---|
| ISRC equal | `EXACT` unless veto below (explicitness conflict, or Δduration > 10 s → downgrade to `STRONG` + data-quality flag) |
| MusicBrainz recording id equal | `EXACT` (same veto rules) |
| ISRCs both present and different | cap at `PROBABLE` (same audio can carry multiple ISRCs; never auto-substitute) |
| Identity key differs | **reject** |
| Identity-changing tag sets differ (symmetric) | **reject** ("Song (Live)" ≠ "Song", and vice versa) |
| Explicitness both known and different | **reject** for substitution/grouping (explicit ≠ clean) |
| No shared artist (both known) | **reject** (covers) |
| Δduration ≤ 2 s | supports `EXACT` |
| Δduration ≤ 5 s (or ≤ 2% of length) | supports `STRONG` |
| Δduration > 10 s (both known) | **reject** (album vs single edit, extended mixes) |
| Duration unknown on either side | cap at `PROBABLE` |
| Album key equal | +score; required for `EXACT` when multiple same-title releases exist in the candidate set |
| Multiple candidates tie after rules | `AMBIGUOUS` (treated as no match) |
| `REMASTER` on one side only | cap at `STRONG` unless identifiers equal |

`EXACT` without identifiers requires all of: same identity key, same identity-changing tags, shared artist, compatible explicitness, Δ ≤ 2 s, and album equal or unknown with no competing same-title release.

### 9.4 Explainability
```kotlin
data class MatchResult(val tier: MatchTier, val confidence: Float, val evidence: List<Evidence>)
// Evidence examples: IsrcEqual, TitleIdentityEqual, VersionTagsDiffer(live vs none), DurationDelta(1.4s),
// ArtistOverlap(["Atif Aslam"]), ExplicitnessConflict, AlbumEqual, AmbiguousCandidates(n=3)
```
Debug builds show the evidence in Song info; decisions persist in `track_equivalence` with evidence JSON.

## 10. Fallback rules (identity is sacred)
1. Fallback changes the **source**, never the **recording**. Only EXACT qualifies (D-17).
2. Every fallback is **recorded, not announced** (D-36, replaces "Now Playing shows the actual source"). The serving source is kept in the session (`NowPlayingItem.servedBy`, `servedByDisplayName`, `resolutionPath`), in history (`online_history.served_by`) and in the logs; debug builds can print it (`now-playing-source`). Normal Now Playing never names a source.
3. Fallback never crosses **explicitness** or **version** boundaries, and never chooses a lower tier just to keep playing — it skips with an explanation instead.
4. Mid-track source switching is not performed. A better copy found later applies to the **next** play.
5. User can mark a pairing "Not the same song" → `track_equivalence` decided_by USER, tier REJECTED, respected forever. It's never re-linked: not by the matcher, not by grouping, not by a later EXACT decision, in memory or on disk. Implemented as infrastructure (`EquivalenceStore.reject`; debug command `not-same`); there's no listener-facing control yet.
6. **Fallback never crosses environments** (D-36). A song chosen ONLINE plays ONLINE; a song chosen from the library plays from the library. There's no silent ONLINE → LOCAL or LOCAL → ONLINE, even for a known EXACT pair. Cross-environment playback would need an explicit listener action, which doesn't exist yet.

## 11. Recommendations & autoplay integration
`AutoplayEngine` (queue-and-autoplay.md) asks the registry for `RecommendationFacet`s of enabled sources; results are normalized `Track`s, de-duplicated via `RecordingKey`/matcher, filtered by availability/capabilities. Library signals (playlists, history, likes) remain source-neutral.

### 11.1 Online (D-34, implemented)
- `SourceDescriptor.environment` (`MusicEnvironment.LOCAL` / `ONLINE`, defaulting from `Basis`) places every source in one of Podium's two worlds. Features branch on environment and capabilities — never on provider identity.
- `DiscoveryFacet` (shelves with a first page, `shelf(id, offset, limit)`, `genres()`, `genre(name, offset, limit)`), `RecommendationFacet` (`related(seeds, limit, exclude)`, `artistRadio`, `relatedArtists`), `CatalogFacet.playlist(id)`, paged `SearchQuery(offset, kinds)` and `SearchResults.playlists`, `PlaylistSummary`/`PlaylistDetail`, `PlaylistId` in `core:model`.
- `player:api`: `RecommendationEngine` (provider-neutral; see §11.2 for how sources are asked), `AutoplayEngine` (when and what to append), `AutoplaySettings`, `RecommendationStrategy`. `QueueOrigin.RADIO`.
- First online source: `sources:audius` (`AudiusMusicSource`, pure Kotlin, HttpURLConnection + kotlinx.serialization); adapter-private data (genre, artist id) rides in `SourceRef.providerData`.

### 11.2 Online is multi-source (D-35, implemented)
ONLINE is every enabled online source at once; the listener sees music, never sources. Adding a source means implementing the facets in its own `sources:<provider>` module and registering it in `AppGraph`. No screen changes.

| Piece | Where | What it does |
|---|---|---|
| **Registry** (the only list of sources) | `SourceRegistry` | Registration, enabled state, priority, capabilities, environment (`ordered(environment)`, `registered(environment)`), health. Re-enabling a source clears its breaker. Nothing else keeps a list of sources |
| **Choices** | `SourceSettings` + `SourcePreferencesStore` (app: `SharedPrefsSourcePreferences`) | The one way enablement and priority change. Stores only explicit choices, so a source added later keeps its default. Applied after registration; survives restarts and process death |
| **Identity** | `TrackId` / `ArtistId` / `AlbumId` / `PlaylistId` = `<sourceId>\|<key>`; `ScopedKey` for shelves | The same provider key on two sources never collides. Navigation, likes, history, playlists and the queue all carry these ids |
| **Fan-out** | `aggregate/SourceFanOut` | Asks sources concurrently, each with a timeout (8 s). Calls run detached from the caller: a source stuck in blocking I/O is abandoned, not awaited. Cancelling the collector cancels them. Skips sources whose breaker is open, or that are rate-limited or rejected (asks anyway if every breaker is open). Records health: an answer or a miss is healthy; network/server failures and timeouts count; offline counts against no source; "not authorised" is `AUTH_FAILURE` only for a source that signs in |
| **Grouping** ("one song, many sources") | `aggregate/TrackGrouper`, `TrackGroup` | One row per recording: **EXACT only** (the matcher's rules for versions, explicitness, covers and length), one copy per source, and ambiguity keeps rows apart. Order is deterministic: results interleaved by rank, sources by priority. The row shows the most preferred source's playable copy; every copy is kept (`alternates`) |
| **Catalogue** | `aggregate/MultiSourceCatalog` (per environment) | **Search** is progressive: it emits as sources answer, and the final view is complete. It fails only when no source could answer. **Shelves** with the same name merge; the shelf id names every member source. **Genres** appear once; a genre asks only the sources that have it. **Paging**: each source continues from its own place, and a later copy of a shown song joins its row. **Artists, albums and playlists** go to their own source by id, never "the current one"; a disabled source's item says so |
| **Equivalence** | `resolve/Equivalence.kt` (interface, `InMemoryEquivalenceStore`); `core:database` `DatabaseEquivalenceStore` (D-36) | EXACT decisions, with the copies themselves, plus the listener's rejections. In memory for synchronous reads; written through in order to `track_equivalence`; loaded at startup. Survives restarts, sources being off or unreachable, and priority changes. Nothing below EXACT is stored. Pairs, not groups, because EXACT isn't transitive |
| **Resolution** | `StreamResolver` | Same pipeline. In the EXACT-fallback step, a copy already known to be EXACT on a source is used directly; otherwise that source is searched and the matcher decides. A miss moves on and never trips a breaker; failures count. No STRONG/POSSIBLE fallback (D-17); never mid-track (D-18) |
| **Recommendations / autoplay** | `SourceRecommendationEngine`, `AutoplayEngine` | Each recommending source of the seeds' environment is asked about the seeds it holds (its own copies or known EXACT twins). The current song's source leads; suggestions interleave. Autoplay takes any source of the current song's environment, never another (D-34), and drops a candidate whose twin is queued or was just played |
| **Online repository** | `AppOnlineRepository` | `status` (what ONLINE can do across sources; null when none is on), `canStartRadio(track)`, `canRelate(artist)`, search as a `Flow`. Liked songs and history show each recording once; every record keeps its own copy and source |
| **History** | `online_history.served_by` (schema v3) | A listen records the song chosen (its own source and provider id) and, when another source's EXACT copy played it, that source |
| **Settings** | Settings ▸ Online sources (`OnlineSourcesScreen`, `OnlineSourceSettings`) | Each registered online source: its name, On/Off, and a note when it needs the listener. With more than one, hold Center to move it up or down. Shown only when the build has online sources |

Search and playback priority are related but distinct. Search collects useful results from every source; priority only orders them and picks the copy shown — **a preference, never a filter**. Playback picks the best eligible copy: the song's own source, then EXACT copies elsewhere in the same environment, in priority order.

### 11.3 Adding a source (D-36)
A new source plugs in without touching any screen:
1. **A module** `sources:<provider>` with a `MusicSource`. It needs a `SourceDescriptor` (id, display name, `Basis`; the environment defaults from `Basis`, and online is anything but `LOCAL_DEVICE`) and declared `SourceCapabilities`.
2. **Only the facets the source has:** `CatalogFacet` (search, track, artist, album, playlist), and optionally `DiscoveryFacet`, `RecommendationFacet`, `ArtworkFacet`, `PlaybackFacet` (a permitted route only), `AuthFacet`. Provider ids live in `SourceRef.providerKey` and `providerData`. Outcomes are reported honestly: `Outcome.Failure(NotFound)` or `FacetResolution.Miss` when the source doesn't have something, a real error when it failed.
3. **Registration** in `AppGraph` (or a build-variant `BuildSources.kt`; keep `UNOFFICIAL_API` sources out of release builds, D-19), before `sourceSettings.apply()`.

Everything else follows from the registry. The new source then:
- appears in Settings ▸ Online sources;
- is searched, browsed and recommended alongside the others;
- has its copies grouped with theirs at EXACT;
- falls back within its environment;
- shows up in history, likes and playlists through its ids.

No `if (provider)` anywhere outside its own module.

### 11.4 Configured sources and OpenSubsonic (Historical: D-37, retired under D-48)
> **Historical note:** The multi-source configured source setup below (D-37) was built for OpenSubsonic and Audius. Under **D-48**, YouTube Music is the sole online provider. This section is preserved as architectural reference for dynamic source configuration and credential storage patterns.

Some sources were *added by the listener*, not built in: one per music server, with its own sign-in. They're ordinary sources once registered (§11.3). Only how they come and go is new.

| Piece | Where | What it does |
|---|---|---|
| **Setup form** | `SetupForm`, `SetupField` (text / URL / secret) | A kind of source describes what it needs. Settings renders it without knowing the kind (`SourceFormScreen`). Secrets are masked, use a password keyboard, aren't saved to instance state, and are cleared when the form closes |
| **Factory** | `SourceFactory` (registered in `AppGraph`) | `connect(values)` checks the answers with the source itself, with no side effects, and answers `ConnectResult.Connected(name, settings, secrets)` or `Refused(SetupProblem)`. `create(profile, credentials)` builds the source |
| **Profiles** | `SourceProfile`, `SourceProfileStore` (app: `SharedPrefsSourceProfiles`, prefs file `source-profiles`) | Id (`<kind>-<8 hex>`), kind, display name, non-secret settings (address, user name). Never in the database, never a secret |
| **Secrets** | `CredentialStore` (app: `KeystoreCredentialStore`, prefs file `credentials`) | Per source, AES-256-GCM, with the key held in the Android Keystore; ciphertext only. A value that can't be opened means "sign in again" |
| **Lifecycle** | `ConfiguredSources` | `restore()` at startup before `SourceSettings.apply()`. `add()`: connect → secrets → profile → register. `remove()`: unregister → delete secrets → drop profile → forget the source's on/off and priority |
| **Sign-in** | `AuthFacet.signInFields` / `signIn` / `signOut`, `AuthState` | Signed out or refused: every gated capability is `REQUIRES_SIGN_IN` with a Sign in action; the source stays configured. Settings ▸ Online sources: "Sign in" on the row; hold Center for Move up/down, Sign out/in, Remove |
| **Network policy** | `NetworkPolicy` (`check(address)`, `permits(url)`) | `https` anywhere. `http` only to the listener's own network (loopback, RFC 1918, link-local, ULA, `.local` / `.lan` / `.home.arpa`) and after consent. Enforced at setup, on every request and redirect hop of the server client, and on every stream URL the player opens, whichever source it comes from |

**OpenSubsonic** (`sources:subsonic`; Navidrome, Gonic, Airsonic-Advanced, Ampache, LMS…):
- **API** (`SubsonicApi`): REST v1.16.1, JSON. Every URL carries `u`, a fresh salt `s` (8 random bytes) and `t = md5(password + s)`, never `p`.
- **Transport** (`UrlConnectionTransport`): `HttpURLConnection` with an 8 s connect and 10 s read timeout. It's cancellable (giving up closes the socket) and follows redirects itself under the policy. URLs and exception messages never leave it; errors are reduced to class names.
- **Facets:**
  - catalogue: `search3`, `getSong`, `getAlbum`, `getArtist` + `getTopSongs`, `getPlaylist`;
  - discovery: "Recently added", "Random picks", "Most played", "Server playlists", genres;
  - recommendations: `getSimilarSongs`, `getSimilarSongs2`, `getArtistInfo2`;
  - artwork: `getCoverArt`, fetched inside the module and handed over as bytes for `podium-art://` refs;
  - playback: one route, DIRECT. `getSong` first, then a `stream` URL with fresh authentication, optionally `maxBitRate`.
- **Capabilities:**

  | State | Capabilities |
  |---|---|
  | Available when signed in | SEARCH, BROWSE, DIRECT_STREAM, ARTWORK |
  | DEGRADED | RECOMMENDATIONS (depends on the server's metadata agents) |
  | UNAVAILABLE | LIKES (kept on the device), PLAYLISTS (playable, not editable), DOWNLOADS (later) |

- **Miss vs failure:**

  | What the server answers | Treated as |
  |---|---|
  | Error 70, a missing element, HTTP 404 | Miss |
  | 40/41/44/50, HTTP 401/403 | Refused credentials: `AUTH_FAILURE`, the source turns `Rejected` |
  | 42/43 | Token auth unsupported (also `AuthRequired`) |
  | HTTP 429 | Rate-limited |
  | Malformed JSON, 5xx, timeouts, network errors | Failures |
  | 20/30 | Protocol version mismatch (`PolicyDisabled`) |
- **Identity:** `opensubsonic-xxxxxxxx|<song id>`. The album key `al.<id>` is kept apart from song ids. Copies on two servers group at EXACT (ISRC, title identity, artist, length) like any others; a "Live" cut never does.
- **Names:** the display name is the server's `type` plus its host, shown only in Settings ▸ Online sources. Browsing never names a server (D-35).

## 12. UI independence — what the UI may ask
| UI need | Ask | Never |
|---|---|---|
| Show Download? | `track.capabilities.canDownload` | `source is …` |
| Show "Like on server" | `canLikeRemotely` | provider name checks |
| Now Playing controls | `PlaybackSnapshot.controls` (seek/shuffle/repeat availability from the active engine) | engine type checks |
| Attribution | `descriptor.attribution` rendered by a generic `AttributionBadge` | hard-coded logos in features |
| Source label | `descriptor.displayName`, `basis` | ids |
| Unavailable explanation | `availability` + `CapabilityState.note` | provider error strings |

## 13. Module layout (delta to ADR-012, updated by D-48)
`sources:api` gains facets, `StreamResolver`, `TrackMatcher`, `TrackNormalizer`, `SourceHealthMonitor`, `SourceRegistry` (pure Kotlin, JVM-tested). Provider modules: `sources:local` (offline MediaStore), `sources:youtubemusic` (online, D-48 direct stream). Retired provider modules: `sources:subsonic`, `sources:audius`. `player:api` hosts `PlaybackRouter` and engine interfaces; `player:service` hosts `DirectStreamEngine` (Media3); remote engines live with their provider module when applicable.

## 14. Testing (minimum, all JVM unless noted)
| Area | Tests |
|---|---|
| Registration | factory registration, priority ordering, enable/disable, removal GC, policy-disabled bases excluded |
| Capability detection | declared ∩ probe ∩ auth ∩ policy combinations; recomputation on auth change |
| Normalization | featured-artist extraction, segment classification, numerals, band-name protection, scripts |
| Matching corpus | same song from two sources (EXACT); same title different recording (reject); remix vs original; live vs studio; explicit vs clean; album vs single/radio edit; remaster vs original (STRONG cap); re-recording vs original; sped-up/slowed; music-video audio vs album audio; covers; part 1 vs part 2; ISRC equal with long Δ (downgrade); ambiguous collisions |
| Duplicate detection | grouping by `RecordingKey`; user "not the same" override |
| Resolution | pin reuse; owned-copy preference; own-source path; expired URL (403 → one re-resolve); concurrent dedupe; prefetch pinning |
| Failure/fallback | miss doesn't trip breaker; breaker open/half-open/close; fallback only at allowed tier; surfaced path recorded |
| Remote playback | see PLAYBACK_TARGETS.md §8 |
| Queue integration | items keep `TrackId`; resolution per play; pinned choice survives navigation; engine handoff |
| Quality reporting | advertised vs resolved vs measured reconciliation; "never lossless unless measured lossless codec" |
