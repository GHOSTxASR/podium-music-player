# ADR-003 — Audio engine and playback service

**Status:** Accepted · **Date:** 2026-10-02

## Context
Requirements: background playback, notification, lock screen, Bluetooth/headset, audio focus, noisy-pause, gapless, seek, repeat/shuffle, lossless up to 24/192, lazy stream URL resolution, honest format reporting, Android 16/17 compatibility.

## Options considered
- **Media3 ExoPlayer + `MediaLibraryService`** — mature, first-party, handles focus/noisy/notification/AVRCP; gapless; FLAC/ALAC/Opus extractors; `ResolvingDataSource`; analytics hooks for format/output reporting.
- Platform `MediaPlayer` — no gapless control, weak format introspection. Rejected.
- Custom decoder pipeline — explicitly forbidden by the brief. Rejected.
- Third-party SDKs (TIDAL player etc.) — source-specific; usable later as delegates only.

## Decision
- **Media3 1.11.x** (`exoplayer`, `session`, `datasource-okhttp`). Version pinned; upgrade only with the playback regression suite green on API 36 and 37.
- `PlaybackService : MediaLibraryService` (library variant so AVRCP browsing / Android Auto can be added without rework), `foregroundServiceType="mediaPlayback"`.
- **Player lives in the service, on the main (application) looper** — required by Media3 1.11's stricter session threading.
- The UI never touches ExoPlayer. It talks to `PlaybackController` (interface in `player:api`), implemented by `MediaControllerPlaybackController`, which wraps a Media3 `MediaController` connected to the service. Same path the system UI uses → one source of truth.
- Session callback overrides **`onConnectAsync`** with explicit command grants: full for Podium itself, SystemUI/media notification, Bluetooth/AVRCP, Android Auto/Wear (allow-list by package + signature where possible); read-only for unknown controllers.
- MediaItems carry `podium://track/<TrackId>` URIs; a `ResolvingDataSource` turns them into real URIs at load time (cached, with expiry and retry).
- Audio attributes: `USAGE_MEDIA`, `CONTENT_TYPE_MUSIC`, `handleAudioFocus = true`, `handleAudioBecomingNoisy = true`.
- High-resolution: `DefaultAudioSink` with float output enabled for > 16-bit sources; on API 34+ USB outputs, offer **bit-perfect mixer attributes** (`MIXER_BEHAVIOR_BIT_PERFECT`) as an opt-in setting.

## Why
Media3 is the only option that satisfies all integration requirements with acceptable risk, and its session is the same channel Bluetooth/notification controls use, which removes whole classes of "UI and lock screen disagree" bugs.

## Tradeoffs
- Media3 behavioural changes between minors (1.11 session defaults) → pinned versions + regression tests.
- Controller IPC adds a hop; negligible (in-process binder).

## Future implications
- Android Auto, Wear, and Cast fit the same `MediaLibraryService`.
- Crossfade requires a second player or audio processor; designed as P2 in `audio-architecture.md`.
