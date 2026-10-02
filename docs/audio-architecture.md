# Audio Architecture

**Date:** 2026-10-02 · ADR-003 (engine), ADR-006 (queue) · State model: `playback-state-machine.md`.

## 1. Components

```
UI ──► PlaybackController (MediaController) ══► PlaybackService : MediaLibraryService
                                                   ├─ MediaLibrarySession(callback = SessionPolicy)
                                                   ├─ ExoPlayer
                                                   │   ├─ MediaSourceFactory(DataSource chain ↓)
                                                   │   ├─ RenderersFactory(float output for > 16-bit)
                                                   │   └─ AnalyticsListener → QualityInspector
                                                   ├─ QueueManager ── QueuePersister
                                                   ├─ AutoplayEngine
                                                   ├─ OutputMonitor (AudioManager, AudioDeviceCallback)
                                                   ├─ VolumeController (STREAM_MUSIC)
                                                   └─ SleepTimer
```

### DataSource chain (per item)
```
podium://track/<id>
  └─ ResolvingDataSource(StreamResolver)
       ├─ Downloaded?  → FileDataSource (verified file)          provenance: DOWNLOAD
       ├─ Local?       → ContentDataSource (MediaStore URI)      provenance: LOCAL
       └─ Remote       → CacheDataSource(SimpleCache "stream-cache", key = "<trackId>|<tier>")
                             └─ OkHttpDataSource(shared client, source auth headers)
```
- **Cache key is the track + quality tier, not the URL** (remote URLs embed expiring tokens).
- Stream cache: LRU, default 512 MB (Settings ▸ Storage), separate from downloads (ADR-005).
- `StreamResolver` memoises resolved URLs until `expiresAt − 60 s`; on HTTP 401/403 it invalidates and re-resolves once.
- **Pre-resolution for gapless:** when the current item passes 50% (or 20 s remaining), resolve the next item and let ExoPlayer's playlist preloading prepare it. Media3 handles gapless for formats with encoder delay/padding metadata (MP3 LAME/Xing, AAC iTunSMPB, FLAC/Opus inherently).

## 2. Session policy (`onConnectAsync`)
| Controller | Detection | Grant |
|---|---|---|
| Podium UI | own package + same UID | all player + custom queue commands |
| System UI / media notification / lock screen | `MediaSession.ControllerInfo.isTrusted` / system package | transport, seek, repeat/shuffle (routed), like custom action |
| Bluetooth AVRCP, wired headset | platform media button receiver / trusted | transport, seek |
| Android Auto, Wear OS | known packages + signature check | transport + library browsing (P1) |
| Unknown | — | read-only (Media3 1.11 default), logged |

Custom session commands: `podium.queue.*` (play next, add, move, remove, skip-to, clear upcoming, set autoplay), `podium.like.toggle`. Notification custom layout: ⏮ ⏯ ⏭ + Like (heart, reflects state).

## 3. Lifecycle & background
- Foreground service type `mediaPlayback`; Media3 manages foreground state from player state.
- `onTaskRemoved`: if not playing → `stopSelf()` after persisting; if playing → keep playing (user expectation).
- Notification permission is **not** required for the media notification (exempt). It is requested only when the user starts a download.
- Battery: no wakelock beyond Media3's `WAKE_MODE_NETWORK` for remote streams / `WAKE_MODE_LOCAL` for files.

## 4. Audio focus, noisy, interruptions
- `setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC, handleAudioFocus = true)`; `setHandleAudioBecomingNoisy(true)`.
- Transient loss (call, navigation prompt): Media3 pauses/ducks and resumes automatically; Podium status `Paused(FOCUS_LOSS_TRANSIENT)` during.
- Permanent loss (another music app): pause, never auto-resume (HIG + Android guidance).
- Headphones/Bluetooth disconnect: pause immediately; never auto-resume on reconnect.

## 5. Quality: what Podium knows and shows

### 5.1 Two sources of truth, reconciled
1. **Reported** — `ResolvedStream.format` from the source (Subsonic song fields, file metadata).
2. **Measured** — ExoPlayer `Format` of the selected audio track (`sampleMimeType`, `codecs`, `averageBitrate`/`peakBitrate`, `sampleRate`, `channelCount`, `pcmEncoding` → bit depth for FLAC/PCM) plus `AnalyticsListener.onAudioTrackInitialized(AudioTrackConfig)` (encoding, sample rate, offload, tunneling).

Measured wins when present. If reported and measured disagree (e.g., server says FLAC but the stream is MP3 because a transcoding rule kicked in), Podium shows **measured** and notes "Converted by server" in the Signal Path sheet. **The word "Lossless" appears only when the measured codec is lossless** (FLAC, ALAC, WAV/PCM). "Hi-Res" appears only when measured bit depth ≥ 24 **and** sample rate ≥ 48 kHz.

### 5.2 Output path (honest end-to-end)
`OutputMonitor` reports:
- Routed device type (`AudioDeviceInfo`): speaker, wired, USB, Bluetooth A2DP/LE, HDMI, cast.
- System mixer rate: `AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)` (typically 48 kHz).
- Bit-perfect: on API 34+ with a USB device supporting `MIXER_BEHAVIOR_BIT_PERFECT` and the user's opt-in → Podium sets preferred mixer attributes to match the stream; status "Bit-perfect".
- Bluetooth codec: **not exposed to apps** → shown as "Bluetooth (codec chosen by your phone)". Never guessed.

### 5.3 Signal Path sheet (Now Playing ▸ quality label)
A label/value list grouped in three sections; structure carries the meaning, so values are never dot-joined:
```
Source
  From          Home server
  Format        FLAC
  Resolution    24-bit, 96 kHz
  Bitrate       2,304 kbps
  Verified      Reported by the server and confirmed by the decoder
Decoding
  Decoder       Built-in FLAC decoder, 32-bit float output
Output
  Device        USB DAC
  Path          Bit-perfect at 96 kHz
```
or, for a lossy stream over Bluetooth:
```
Source
  From          Audius
  Format        MP3
  Bitrate       320 kbps
Output
  Device        Bluetooth headphones
  Path          Resampled to 48 kHz by Android
  Codec         Chosen by your phone (Android doesn't share it with apps)
```
Copy uses sentence case and no all-caps.

### 5.4 The label on Now Playing
Short, natural-case, one line: `FLAC 24-bit/96 kHz`, `ALAC 16-bit/44.1 kHz`, `AAC 256 kbps`, `Opus 160 kbps`, `MP3 320 kbps`. If unknown: no label (never a guess). Tapping opens the sheet.

## 6. High-resolution playback
- `DefaultRenderersFactory.setEnableAudioFloatOutput(true)` when the item's measured bit depth > 16, so 24-bit sources aren't truncated before the mixer.
- Sample rate is never converted by Podium; Android's mixer resamples unless bit-perfect is active (and Podium says so).
- Offload (`AudioOffloadPreferences`) enabled for long lossy content when the screen is off (battery), disabled when bit-perfect is active or ReplayGain/crossfade require PCM processing.

## 7. Volume
- Wheel rotation in Now Playing → `VolumeController.step(+/−)` → `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE/LOWER, 0)` (no system panel). Detents per step = `ceil(30 / steps)` so a full sweep ≈ 1.25 rotations regardless of 15- or 150-step devices.
- Observes `VOLUME_CHANGED_ACTION` to keep the on-screen bar in sync with hardware keys.
- Absolute-volume Bluetooth devices: same API; Android forwards.
- Fixed-volume outputs (`isVolumeFixed`, bit-perfect USB): wheel shows "Volume is controlled by the connected device".

## 8. Optional processing (P1–P2)
| Feature | Approach | Notes |
|---|---|---|
| ReplayGain | `AudioProcessor` applying track/album gain from tags/Subsonic `replayGain`; pre-amp; clip prevention | Disabled when bit-perfect |
| Crossfade | Second ExoPlayer instance with volume ramps, or Media3 composition when available | P2; disabled for gapless albums automatically (same-album consecutive tracks) |
| Equalizer | Launch system `ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` with session id | No custom EQ in v1 |
| Skip silence | `SilenceSkippingAudioProcessor` | P2 |
| Speed | Not exposed (music app) | |

## 9. Sleep timer
Options: 15 / 30 / 45 / 60 / 90 min, end of track, end of album. Fades volume over the last 10 s (player volume, not system), then pauses with `Paused(SLEEP_TIMER)`. Reachable via long-press ⏯ (D-06) and Now Playing ▸ More.

## 10. Testing hooks
- `player:api` reducer + `QueueManager` are pure → JVM tests.
- `player:service` uses `media3-test-utils` (`TestExoPlayerBuilder`, `FakeClock`, `TestPlayerRunHelper`) under Robolectric for transitions, gapless boundaries, and error policies.
- Device tests: notification controls on API 36 & 37 (R-07), Bluetooth/headset (manual matrix in `testing-strategy.md`).
