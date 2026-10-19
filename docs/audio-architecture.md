# Audio Architecture

**Date:** 2026-10-02 · ADR-003 (engine), ADR-006 (queue) · State model: `playback-state-machine.md`.

## 1. Components

```
UI ──► PlaybackController (MediaController) ══► PlaybackService : MediaLibraryService
                                                   ├─ MediaLibrarySession(callback = SessionPolicy)
                                                   ├─ ExoPlayer
                                                   │   ├─ MediaSourceFactory(DataSource chain ↓)
                                                   │   ├─ RenderersFactory(float output for > 16-bit; ALAC decoder after the platform's, D-87)
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
1. **Reported** — `PlayableMedia.advertised` from the source (see `architecture/PLAYBACK_TARGETS.md` §4 for the four quality facts) (Subsonic song fields, file metadata).
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
| Crossfade | **Built (D-77).** Settings ▸ Crossfade: off, 2, 4, 6, 8 or 12 s. The engine's one player keeps the queue and the session; a silent second player (`Crossfader`) carries only the outgoing song's last seconds: levelled with the first (by playing a little slower or faster while silent), swapped in unheard, faded out on an equal-power curve while the next song fades in from its start | Never for an album's next track (gapless), repeat-one, songs under 3× the overlap, or when the sleep timer ends with the song; any pause, skip or seek ends it at once |
| Equalizer | **Built (D-76).** Settings ▸ Equaliser: ten peaking filters an octave apart (31 Hz to 16 kHz, ±12 dB), presets or custom, as an `AudioProcessor` (`EqualizerProcessor`) in the player's own audio path, the same on every phone | Off or flat it passes audio through sample for sample; a boost lowers everything first by the curve's peak, so it never clips; changes are heard at once |
| Sound effect | **Built (D-89).** Settings ▸ Equaliser ▸ Effect: None, Car stereo, 3D hall or 8D, one at a time, with the equaliser on or off. `SoundEffectProcessor`, after the equaliser in each player's audio path. Car: low shelf +7 dB at 85 Hz, +2 dB at 2.5 kHz, high shelf −4 dB at 9 kHz, a narrower image, five early reflections 2.3–9.8 ms. 3D hall: a stereo reverb (Freeverb's public-domain tuning, large room, 25 ms pre-delay) around a slightly wider dry sound. 8D: the music circles the head every 8 s (equal-power level, up to 0.45 ms between the ears, the head's shadow on the far ear), with a small room | None passes audio through bit for bit; a change crossfades over 60 ms; a soft limit above 0.8 of full scale keeps it from clipping; stereo 16-bit and float (mono passes untouched) |
| Audio meter | **Built (D-82, D-83).** `AudioMeter`, after the equaliser in the main player's audio path: mono, RMS per 10 ms block whole, below 120 Hz and above 2.5 kHz, each filed under its song position from the sink's `StreamMetadata.positionOffsetUs`, for the lyrics' swell and the orb (LYRICS_ARCHITECTURE.md §13, §14) | Pass-through: changes no sample; no permission; no second decode; measures only while the lyrics screen listens; not on crossfade's second player; another app's playback is never measured |
| Skip silence | `SilenceSkippingAudioProcessor` | P2 |
| Speed | Not exposed (music app) | |

## 8.1 ALAC (D-87)
Media3 reads ALAC from MP4, and most phones decode it with their own MediaCodec decoder. Phones
without one (Android's own software codecs have none) use Podium's: `AlacAudioRenderer`, a
`DecoderAudioRenderer` over `AlacFrameDecoder` (a Kotlin port of Apple's Apache-2.0 reference
decoder). It's added after the platform's renderers, so a phone with its own decoder keeps using
it. It handles 16, 20 (written as 24), 24 and 32 bit, and 1–8 channels in Android's order, and is
bit-exact against FFmpeg on the test files. It doesn't read CAF files (no Media3 extractor).

## 8.2 Live radio (D-90)
A live station (`MediaKind.LIVE`) is a queue item whose media item is marked live HLS
(`application/x-mpegURL`, with a live configuration) before it loads; `media3-exoplayer-hls` is on
the classpath. The resolver hands over the stream's audio-only rendition, so no video is fetched.
Its playlist reloads go through the same `ResolvingDataSource` and reuse the pinned selection
until it expires. Crossfade never applies (the player's duration is only the rewind window).

## 9. Sleep timer
**Built (D-75).** Options: 15 / 30 / 45 / 60 / 90 min, end of song, end of album. A countdown fades the player's own volume (never the phone's) over its last 10 s, then pauses; the volume stays down until the music plays again, so the pause lands in silence. The end of a song or album is the player's own pause at the end of that item. Reachable via Now Playing ▸ More ▸ Sleep timer and Settings ▸ Sleep timer; Now Playing says when the music stops while it's set. (Long-press ⏯ switches the device off since D-26, so it no longer opens the timer.) Music another app plays is paused when the countdown ends, without a fade.

## 10. Testing hooks
- `player:api` reducer + `QueueManager` are pure → JVM tests.
- `player:service` uses `media3-test-utils` (`TestExoPlayerBuilder`, `FakeClock`, `TestPlayerRunHelper`) under Robolectric for transitions, gapless boundaries, and error policies.
- Device tests: notification controls on API 36 & 37 (R-07), Bluetooth/headset (manual matrix in `testing-strategy.md`).
