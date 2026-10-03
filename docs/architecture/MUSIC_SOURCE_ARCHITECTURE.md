# Music Source Architecture

**Status:** Accepted (ADR-013, ADR-014) · **Date:** 2026-10-02 · **Normative:** this document supersedes `architecture.md` §4.2 and the source parts of ADR-002.
Related: [`SOURCE_CAPABILITY_MATRIX.md`](SOURCE_CAPABILITY_MATRIX.md) · [`PLAYBACK_TARGETS.md`](PLAYBACK_TARGETS.md) · [`../research/BITCHORD_ARCHITECTURE_REVIEW.md`](../research/BITCHORD_ARCHITECTURE_REVIEW.md)

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
sources:api   SourceRegistry ──► MusicSource (facets) ◄── implemented by sources:local / subsonic / audius / …
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
@JvmInline value class SourceId(val value: String)    // stable per connected instance: "local", "subsonic:9f2c…", "audius"

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
@JvmInline value class TrackId(val value: String)        // "<sourceId>:<opaque provider track key>"

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

## 9. Track normalization & matching (`TrackMatcher`)

Independent Podium design (ADR-014). Lexicon derived from MusicBrainz's published style guidelines (recording/release disambiguation, secondary release types) and Podium's own test corpus.

### 9.1 Uses and policies
| Use | Minimum tier | Behaviour |
|---|---|---|
| Automatic playback fallback (§8.1 step 3/5) | `EXACT` | Silent switch **of source** only, always surfaced as "Playing from Home server" |
| Same, with setting "Allow close matches" | `STRONG` | Surfaced; first time per source pair asks for confirmation |
| Cross-source duplicate grouping in lists/search | `STRONG` | One row "Available from 2 sources" |
| Playlist import / migration | `STRONG` auto; `PROBABLE` to review list | User confirms probable ones |
| Autoplay de-duplication | `STRONG` | Avoid re-suggesting the same recording from another source |
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
1. Fallback changes the **source**, never the **recording**. Only equivalence tiers allowed by §9.1 qualify.
2. Every fallback is **visible**: Now Playing shows the actual source; Signal Path says why ("Audius didn't respond, playing your copy from Home server").
3. Fallback never crosses **explicitness** or **version** boundaries, and never chooses a lower tier just to keep playing — it skips with an explanation instead.
4. Mid-track source switching is not performed. A better copy found later applies to the **next** play.
5. User can mark a pairing "Not the same song" → `track_equivalence` decided_by USER, tier REJECTED, respected forever.

## 11. Recommendations & autoplay integration
`AutoplayEngine` (queue-and-autoplay.md) asks the registry for `RecommendationFacet`s of enabled sources; results are normalized `Track`s, de-duplicated via `RecordingKey`/matcher, filtered by availability/capabilities. Library signals (playlists, history, likes) remain source-neutral.

### 11.1 Online (D-34, implemented)
- `SourceDescriptor.environment` (`MusicEnvironment.LOCAL` / `ONLINE`, defaulting from `Basis`) places every source in one of Podium's two worlds. Features branch on environment and capabilities — never on provider identity.
- `DiscoveryFacet` (shelves with a first page, `shelf(id, offset, limit)`, `genres()`, `genre(name, offset, limit)`), `RecommendationFacet` (`related(seeds, limit, exclude)`, `artistRadio`, `relatedArtists`), `CatalogFacet.playlist(id)`, paged `SearchQuery(offset, kinds)` and `SearchResults.playlists`, `PlaylistSummary`/`PlaylistDetail`, `PlaylistId` in `core:model`.
- `player:api`: `RecommendationEngine` (provider-neutral; `SourceRecommendationEngine` asks only the seeds' own source), `AutoplayEngine` (when and what to append), `AutoplaySettings`, `RecommendationStrategy`. `QueueOrigin.RADIO`.
- First online source: `sources:audius` (`AudiusMusicSource`, pure Kotlin, HttpURLConnection + kotlinx.serialization); adapter-private data (genre, artist id) rides in `SourceRef.providerData`.

## 12. UI independence — what the UI may ask
| UI need | Ask | Never |
|---|---|---|
| Show Download? | `track.capabilities.canDownload` | `source is …` |
| Show "Like on server" | `canLikeRemotely` | provider name checks |
| Now Playing controls | `PlaybackSnapshot.controls` (seek/shuffle/repeat availability from the active engine) | engine type checks |
| Attribution | `descriptor.attribution` rendered by a generic `AttributionBadge` | hard-coded logos in features |
| Source label | `descriptor.displayName`, `basis` | ids |
| Unavailable explanation | `availability` + `CapabilityState.note` | provider error strings |

## 13. Module layout (delta to ADR-012)
`sources:api` gains facets, `StreamResolver`, `TrackMatcher`, `TrackNormalizer`, `SourceHealthMonitor`, `SourceRegistry` (pure Kotlin, JVM-tested). Provider modules: `sources:local`, `sources:subsonic`, `sources:audius`, and — only if approved (§ matrix) — `sources:youtube-catalog`, `sources:spotify`. `player:api` gains `PlaybackRouter` and engine interfaces; `player:service` hosts `DirectStreamEngine`; remote engines live with their provider module (they implement `RemoteProviderController`).

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
