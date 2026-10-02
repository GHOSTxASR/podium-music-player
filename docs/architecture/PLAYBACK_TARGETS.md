# Playback Targets

**Status:** Accepted (ADR-013) · **Date:** 2026-10-02 · Extends `audio-architecture.md`, `playback-state-machine.md`, ADR-003, ADR-006.

## 1. The distinction that matters: who owns the audio?

| Route | Who decodes and outputs audio | Podium controls via | Example |
|---|---|---|---|
| **Direct stream** | **Podium** (Media3/ExoPlayer) | its own player | Local files, OpenSubsonic, Audius, verified downloads |
| **Remote provider** | **Another app, device, or server** | that provider's SDK/protocol (commands + state) | OpenSubsonic jukebox (server's speakers), Cast receivers (future), a provider's own app via its official remote SDK |
| **Embedded official player** | The provider's official embeddable player, inside Podium, visible | the embed's official API | An official video embed that must stay visible and foreground |

A direct stream is a URL Podium may open. A remote target is **a session Podium may command**. They are different types and are never coerced into each other. In particular, a provider whose official platform only allows remote control is modelled as `RemoteProvider` — Podium never pretends that audio is Podium-owned.

## 2. Types (`player:api` / `sources:api`)

```kotlin
enum class PlaybackRoute { DIRECT, REMOTE, EMBEDDED, NONE }

sealed interface PlaybackTarget {
    val trackId: TrackId

    data class DirectStream(
        override val trackId: TrackId,
        val media: PlayableMedia,
    ) : PlaybackTarget

    data class RemoteProvider(
        override val trackId: TrackId,
        val controllerId: RemoteControllerId,     // which RemoteProviderController handles it
        val providerItemRef: String,              // opaque to Podium core (e.g., a provider URI)
        val policy: RemotePolicy,
    ) : PlaybackTarget

    data class Embedded(
        override val trackId: TrackId,
        val embedRef: String,                     // opaque to Podium core
        val constraints: EmbedConstraints,
    ) : PlaybackTarget
}
```

## 3. `PlayableMedia` (direct streams only)

```kotlin
data class PlayableMedia(
    val uri: String,                              // https://, content://, file://
    val headers: Map<String, String> = emptyMap(),// auth headers if the source requires them (never logged)
    val mimeType: String?,                        // container MIME as reported (audio/flac, audio/mp4, audio/mpeg, audio/ogg)
    val advertised: AudioQuality?,                // what the SOURCE says this specific stream is
    val durationMs: Long?,                        // if the source states it; checked against the decoder
    val sizeBytes: Long?,
    val supportsRange: Boolean?,
    val expiresAt: Instant?,                      // null = no known expiry
    val sourceId: SourceId,
    val cacheKey: String,                         // "<trackId>|<tierKey>" — stable across URL refreshes
    val path: ResolutionPath,                     // OWN_SOURCE | LOCAL | DOWNLOAD | EQUIVALENT_OWNED | EQUIVALENT_SOURCE
)

data class AudioQuality(
    val codec: Codec?,                            // FLAC, ALAC, PCM, AAC, OPUS, MP3, VORBIS, …; null = not stated
    val container: String?,
    val bitrateKbps: Int?, val isVariableBitrate: Boolean?,
    val sampleRateHz: Int?, val bitDepth: Int?,   // bit depth only meaningful for lossless/PCM
    val channels: Int?,
)
```
No DRM fields: Podium's direct engine plays unprotected media only. Protected content, if ever supported, comes through a provider's official SDK as a remote/delegate target.

## 4. Quality: four distinct facts, never merged silently

| Fact | Where it comes from | Shown as |
|---|---|---|
| **Catalogue-advertised** | `Track.advertisedQualities` (what the source *offers*) | Album footers/quality tier picker hints ("Available up to 24-bit/96 kHz") |
| **Resolved-advertised** | `PlayableMedia.advertised` (what the source says *this* stream is) | Signal Path "Reported by source" row |
| **Measured** | Decoder `Format` + `AudioTrackConfig` once playback starts | Now Playing label + Signal Path "Decoded" row |
| **Output** | Routed device, mixer rate, bit-perfect state | Signal Path "Output" rows |

Rules:
1. The Now Playing label uses **measured** values only. Before measurement: no label (≈ 100–300 ms). If the decoder never reports a field, that field is omitted.
2. "Lossless" only if the **measured** codec is lossless (FLAC/ALAC/PCM). If the source advertised FLAC but the decoder sees AAC: label "AAC 256 kbps", Signal Path notes "Converted by the source (reported FLAC)". This is the "if the actual stream is AAC 256, do not display FLAC" rule, enforced in one formatter with tests.
3. Unknown stays unknown: "MP3" (no bitrate) is a valid, honest label. Never infer bit depth from codec, or bitrate from the tier name.
4. Remote targets: quality comes only from what the provider's official API states (often nothing) → label omitted; Signal Path says "Quality managed by <provider display name>".

## 5. Engines and routing

```kotlin
interface PlaybackEngine {
    val kind: PlaybackRoute
    val state: StateFlow<EngineState>               // mapped into PlaybackSnapshot (playback-state-machine.md)
    val controls: StateFlow<ControlSet>              // play/pause/seek/next/prev/shuffle/repeat/volume availability
    suspend fun prepare(target: PlaybackTarget, startPositionMs: Long, playWhenReady: Boolean)
    fun play(); fun pause(); fun seekTo(ms: Long); fun stop()
    fun positionMs(): Long
}
```
- `DirectStreamEngine` — wraps the service's ExoPlayer (ADR-003). Supports gapless within itself.
- `RemoteEngine` — one per `RemoteProviderController`; translates commands and mirrors state.
- `EmbeddedEngine` — hosts an official embed in a visible surface; foreground only.
- `PlaybackRouter` (in the service) selects the engine for the current queue item's resolved target, performs **handoffs**, and publishes a single `PlaybackSnapshot` with `owner` and `controls`.

### 5.1 Handoff rules
| From → To | Behaviour |
|---|---|
| Direct → Direct | Media3 playlist transition (gapless where formats allow) |
| Direct → Remote / Remote → Direct | **Hard cut**: stop/pause the outgoing engine at the item end, then start the incoming. No crossfade, no overlap. Latency surfaced as Loading. |
| Any → Embedded | Only when the embed surface is visible; otherwise the item is `Unavailable(requires visible player)` and skipped with explanation |
| Remote → Remote (same controller) | Delegate to the provider's own sequencing if `queueOwnership == PROVIDER` |

### 5.2 Media session & notification ownership
- **Direct** items: Podium's `MediaLibraryService` session is active; Podium owns the notification, lock screen, Bluetooth controls.
- **Remote, `systemControlsOwner = PROVIDER`** (e.g., another app on this device already publishes its own media session): Podium **releases** its session's foreground/notification while that target is active, to avoid duplicate or conflicting system controls; Podium's UI remains a control surface.
- **Remote, `systemControlsOwner = PODIUM`** (e.g., server jukebox, cast receivers): Podium keeps its session and maps commands to the remote engine (a `SimpleBasePlayer` facade over the engine, so system controllers see one consistent player).

## 6. Remote provider model

```kotlin
data class RemotePolicy(
    val queueOwnership: QueueOwnership,          // PODIUM (Podium sequences item by item) | PROVIDER (provider sequences a context)
    val mixesWithOtherSources: Boolean,          // may a Podium queue interleave this provider's items with others?
    val allowsTransitions: Boolean,              // crossfade/overlap permitted? (hard cut if false)
    val systemControlsOwner: ControlsOwner,      // PODIUM | PROVIDER
    val requiresProviderApp: String?,            // package name if a local provider app must be installed
    val requiresSubscription: Boolean,           // e.g., on-demand needs a paid tier (checked via capability)
    val stateReporting: StateReporting,          // PUSH (subscription) | POLL(intervalMs)
    val attribution: Attribution?,               // marks/link-back the provider requires on screen
)

interface RemoteProviderController {
    val id: RemoteControllerId
    val connection: StateFlow<RemoteConnection>  // DISCONNECTED | CONNECTING | CONNECTED | FAILED(reason)
    suspend fun connect(): Outcome<Unit>
    suspend fun playItem(ref: String, startMs: Long): Outcome<Unit>
    suspend fun playContext(ref: String, startIndex: Int): Outcome<Unit>   // when queueOwnership == PROVIDER
    suspend fun pause(): Outcome<Unit>; suspend fun resume(): Outcome<Unit>
    suspend fun seekTo(ms: Long): Outcome<Unit>
    suspend fun skipNext(): Outcome<Unit>; suspend fun skipPrevious(): Outcome<Unit>
    val remoteState: StateFlow<RemoteState>      // item ref, isPlaying, positionMs + timestamp, duration, controls
}
```

### 6.1 Queue ownership
- `PODIUM`: Podium's `QueueManager` stays authoritative; the router plays one item at a time on the remote engine and advances on its "ended" signal. Mixed queues allowed if `mixesWithOtherSources`.
- `PROVIDER`: entering this target starts a **remote session**: Podium hands the provider a whole context (album/playlist), suspends its own queue (preserved, restorable), and Up Next shows the provider's state read-only (with "Return to Podium queue"). Podium does not interleave other sources' items with it and does not overlap audio.

### 6.2 State mirroring
Position = last reported position + elapsed since report timestamp (when playing), corrected on each report; drift > 2 s snaps. Remote disconnect → status `Error(SourceUnavailable, AwaitNetwork)`; provider app missing → `Unavailable(REQUIRES_PROVIDER_APP)` with an "Open store page" action (no auto-install).

### 6.3 Candidate remote targets
| Provider | Route basis | Policy sketch | Status |
|---|---|---|---|
| OpenSubsonic **jukebox** (`jukeboxControl`) | Official API of the user's server | queue PODIUM-mirrored to the server's jukebox list; controls PODIUM; mixes = false (server plays only its own library) | Good first real implementation (validates the abstraction legitimately) |
| Cast receivers | Media3 `CastPlayer` (Google Cast SDK, proprietary) | queue PODIUM; controls PODIUM | Future; adds a proprietary dependency → user decision |
| A streaming provider's own app via its official remote SDK | Provider SDK | queue PROVIDER; controls PROVIDER; mixes per provider policy; attribution required | Only per `SOURCE_CAPABILITY_MATRIX.md` and an explicit user decision |

## 7. Embedded official players
```kotlin
data class EmbedConstraints(
    val requiresVisibleSurface: Boolean, val minWidthDp: Int, val minHeightDp: Int,
    val foregroundOnly: Boolean,            // no background playback
    val audioOnlyAllowed: Boolean,          // false when the provider forbids separating audio from video
)
```
Embedded targets play only while their surface is on screen in the foreground; leaving the screen pauses them (it does not hand them to the background service). The wheel still controls them while visible. They never enter downloads or the stream cache.

## 8. Integration with the playback state machine
`PlaybackSnapshot` gains:
```kotlin
val owner: PlaybackOwner          // Podium | Remote(displayName, attribution) | Embedded(displayName)
val controls: ControlSet          // what the active engine supports right now
```
Status mapping is per engine (Direct: Media3 table in `playback-state-machine.md` §5; Remote: provider state → Idle/Loading/Buffering/Playing/Paused/Ended/Error; Embedded: embed player states). The UI renders `owner` generically (a "Playing on …" chip and attribution slot) and disables controls not in `controls` — no engine-type checks.

Wheel volume: Direct → system `STREAM_MUSIC` (D-16). Remote on this device → same system volume. Remote elsewhere (jukebox/cast) → the controller's volume command if `controls.volume`, else "Volume is controlled on <device>".

## 9. Tests
- Router: correct engine per target; hard-cut handoffs Direct↔Remote; no overlap (engine A stopped before B starts); gapless only within Direct.
- Remote PODIUM ownership: advance on remote "ended"; disconnect mid-item → AwaitNetwork; reconnect resumes at mirrored position.
- Remote PROVIDER ownership: Podium queue suspended and restorable; Up Next read-only; no interleaving of other sources.
- Session ownership: Podium notification released for PROVIDER-controls targets, restored afterwards.
- Embedded: plays only while visible; leaving pauses; never cached/downloaded.
- Quality: label from measured only; advertised-vs-measured mismatch note; remote → no label.
- Fakes: `FakeRemoteProviderController` (scriptable latency, drops, state pushes), `FakeEngine`.
