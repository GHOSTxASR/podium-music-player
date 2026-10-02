# Playback State Machine

**Date:** 2026-10-02 · Lives in `player:api` (pure Kotlin) as a reducer; `player:service` feeds it Media3 and resolver events. Every transition is logged under `playback.state` (ids only in release).

## 1. Why a statechart, not booleans
Booleans like `isPlaying`, `isLoading`, `isBuffering`, `isPaused`, `isError` admit impossible combinations (playing *and* error). But some of the brief's states are genuinely **orthogonal**: you can seek while paused; a download can progress while playing; you can be offline while playing a downloaded file. So Podium models one **exclusive** status plus small **orthogonal regions**.

```kotlin
data class PlaybackSnapshot(
    val status: PlaybackStatus,          // exclusive
    val item: NowPlayingItem?,           // track + queue uid + format + artwork key
    val intent: PlayIntent,              // PLAY | PAUSE — what the user asked for (drives the ⏯ glyph)
    val seek: SeekRegion,                // orthogonal
    val connectivity: Connectivity,      // orthogonal (from ConnectivityMonitor)
    val itemDownload: DownloadBadge?,    // orthogonal (from DownloadManager for current track)
    val modes: Modes,                    // repeat, shuffle, autoplay
    val format: AudioFormatInfo?,        // see audio-architecture.md
)

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus                                   // nothing loaded
    data class Loading(val phase: LoadPhase) : PlaybackStatus           // RESOLVING_STREAM | PREPARING
    data class Buffering(val sinceMs: Long) : PlaybackStatus            // stalled mid-play; intent preserved
    data object Playing : PlaybackStatus
    data class Paused(val reason: PauseReason) : PlaybackStatus         // USER, FOCUS_LOSS_TRANSIENT, BECAME_NOISY, SLEEP_TIMER, REMOTE, RESTORED
    data object Ended : PlaybackStatus                                  // queue exhausted, nothing to autoplay
    data class Error(val error: PodiumError, val recovery: Recovery) : PlaybackStatus
}
sealed interface SeekRegion { data object None; data class Seeking(val targetMs: Long); data class Scanning(val dir: ScanDirection) }
```

Mapping the brief's list: **Idle, Loading, Buffering, Playing, Paused, Ended, Error** are `status` values; **Seeking** is the `seek` region; **Offline** is the `connectivity` region (and, when it blocks the current item, `Error(Offline, AwaitNetwork)`); **Downloading** is the `itemDownload` region.

## 2. Events
`Load(item)`, `StreamResolved`, `StreamFailed(e)`, `PlayerReady`, `PlayerBuffering`, `PlayerPlaying`, `PlayerPaused(reason)`, `PlayerEnded`, `PlayerError(e)`, `UserPlay`, `UserPause`, `UserSeek(ms)`, `SeekCompleted`, `ScanStart(dir)`, `ScanEnd`, `NetworkLost`, `NetworkRestored`, `QueueEmptied`, `Stop`.

## 3. Transitions (exclusive status)

| From | Event | To | Side effects |
|---|---|---|---|
| Idle | Load | Loading(RESOLVING_STREAM) | resolver starts |
| Loading(RESOLVING) | StreamResolved | Loading(PREPARING) | |
| Loading(RESOLVING) | StreamFailed(Offline) | Error(Offline, AwaitNetwork) or skip* | |
| Loading(RESOLVING) | StreamFailed(other) | Error(e, SkipAfter(2 s)) | toast-level notice, history not counted |
| Loading(PREPARING) | PlayerReady ∧ intent=PLAY | Playing | |
| Loading(PREPARING) | PlayerReady ∧ intent=PAUSE | Paused(RESTORED/USER) | |
| Playing | PlayerBuffering | Buffering(now) | spinner only if > 500 ms |
| Buffering | PlayerPlaying | Playing | |
| Buffering | NetworkLost (stream not cached ahead) | Error(Offline, AwaitNetwork) | keeps position |
| Playing | UserPause | Paused(USER) | |
| Playing | PlayerPaused(FOCUS_LOSS_TRANSIENT / BECAME_NOISY / REMOTE / SLEEP_TIMER) | Paused(reason) | |
| Paused(FOCUS_LOSS_TRANSIENT) | focus regained (Media3 resumes) | Playing | only transient losses auto-resume |
| Paused(*) | UserPlay | Playing (via Buffering if needed) | |
| Playing/Paused | Load(next) (skip/auto-advance) | Loading(RESOLVING) | gapless: when next item is pre-resolved & prepared, goes straight to Playing |
| Playing | PlayerEnded ∧ no next ∧ autoplay yields items | Loading | AutoplayEngine appends first |
| Playing | PlayerEnded ∧ nothing next | Ended | |
| Ended | UserPlay | Loading (restart queue from first item) | |
| any | PlayerError(e) | Error(e, policy(e)) | |
| Error(_, AwaitNetwork) | NetworkRestored | Loading(RESOLVING) | same item, same position |
| Error(_, SkipAfter) | timer | Loading(next) | if 3 consecutive items fail → Error(e, Halt) |
| Error(_, Halt) | UserPlay / user picks item | Loading | |
| any | Stop / QueueEmptied | Idle | |

\* If `skip_unavailable_offline` is on and a later queue item is playable offline, skip to it instead of waiting.

**Recovery policies:** `Retry(n, backoff)` for transient network/5xx (max 2 retries, 1 s/3 s, re-resolving the URL because expired URLs present as 403); `SkipAfter(d)` for StreamUnavailable/UnsupportedFormat/NotFound/Corrupt; `AwaitNetwork` for Offline; `Reauth` for AuthRequired (shows a banner linking to source settings); `Halt` after 3 consecutive failures to avoid skip storms.

## 4. Seek region
- `UserSeek(ms)` → `Seeking(ms)`; status unchanged (seeking while paused stays Paused). `SeekCompleted` → `None`.
- Wheel scrub mode issues seeks at most every 120 ms (coalesced to latest target) to avoid decoder thrash; UI shows the target position immediately.
- `ScanStart` (hold ⏭/⏮) → `Scanning`: playback continues with repeated seeks of +/−(2 s → 4 s → 8 s every 1 s held) at 250 ms cadence; `ScanEnd` → `None`. Scanning past the end advances to the next item (iPod behaviour); past the start stops at 0.

## 5. Mapping from Media3
| Media3 | Podium status |
|---|---|
| `STATE_IDLE` & no items | Idle |
| `STATE_IDLE` & items & `playerError != null` | Error |
| `STATE_BUFFERING` before first ready of item | Loading(PREPARING) |
| `STATE_BUFFERING` after first ready of item | Buffering |
| `STATE_READY` & `isPlaying` | Playing |
| `STATE_READY` & `!isPlaying` & `playbackSuppressionReason != NONE` | Paused(FOCUS_LOSS_TRANSIENT) |
| `STATE_READY` & `!playWhenReady` | Paused(reason from last `onPlayWhenReadyChanged` reason: USER_REQUEST/AUDIO_FOCUS_LOSS/AUDIO_BECOMING_NOISY/REMOTE) |
| `STATE_ENDED` | Ended (after autoplay check) |
`Loading(RESOLVING_STREAM)` has no Media3 equivalent; the resolver reports it (open of `podium://` URI pending).

## 6. UI derivation rules
- **⏯ glyph** follows `intent`, not status: pressing play during Buffering shows ⏸ immediately (no "flash back").
- **Spinner**: only `Loading` > 300 ms or `Buffering` > 500 ms. Never during gapless transitions.
- **Error**: inline on Now Playing ("This track isn't available right now. Skipping…" with countdown and Cancel), condensed in the mini player. Copy table in `design-system.md` §11.
- **Offline region**: subtle offline glyph in title bar; Now Playing shows "Playing from downloads" when relevant.

## 7. Edge cases (tested)
| Case | Expected |
|---|---|
| Empty queue + play | No-op; Home shows "Choose something to play" hint |
| One-track queue, repeat OFF, ends | Autoplay if enabled, else Ended |
| Same track repeated in queue | Distinct uids; history counts each |
| Rapid ⏭ ×10 in 1 s | Only the final item resolves; intermediate resolutions cancelled; no history rows |
| Rapid center presses on a track | First press plays; subsequent presses within 400 ms ignored (debounce per target) |
| Headphones unplugged | Paused(BECAME_NOISY); no auto-resume on replug |
| Bluetooth disconnect mid-play | Same as noisy |
| Phone call | Paused(FOCUS_LOSS_TRANSIENT) → resumes after call if it was playing |
| Another music app starts | Paused(USER/AUDIO_FOCUS_LOSS) permanent; no auto-resume |
| App killed during playback | Service killed → on next launch queue restored paused at last saved position (≤ 10 s drift) |
| Process death while paused in background | Same restoration; media notification resumption works via `onPlaybackResumption` |
| Stream URL expired while paused for hours | Next play: 403 → re-resolve → continue at position |
| Track deleted from server | StreamFailed(NotFound) → SkipAfter; row flagged unavailable |
| Unsupported codec on device | UnsupportedFormat → SkipAfter; quality sheet explains |
| Offline at startup | Restored item playable if downloaded/local; else Error(Offline, AwaitNetwork) only when user presses play |
