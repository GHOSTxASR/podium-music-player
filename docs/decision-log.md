# Decision Log

Every significant decision, newest at the bottom. Major ones have an ADR in `adr/`; smaller ones are recorded inline in the required format. Status values: **Accepted**, **Pending user**, **Superseded by …**.

## Index of ADRs

| ID | Decision | Status |
|---|---|---|
| [ADR-001](adr/ADR-001-platform-and-ui-framework.md) | Native Android, Kotlin, Jetpack Compose, Media3 | Accepted — **pending user confirmation** |
| [ADR-002](adr/ADR-002-music-source-architecture.md) | Capability-based `MusicSource`; Library (synced) vs Catalog (live) sources; no YouTube adapter | Accepted — **source strategy pending user** |
| [ADR-003](adr/ADR-003-audio-engine.md) | Media3 `MediaLibraryService` + `PlaybackController` facade, explicit `onConnectAsync` | Accepted |
| [ADR-004](adr/ADR-004-database.md) | Room 3 + bundled SQLite (FTS5) + Proto DataStore; pre-migration backups | Accepted |
| [ADR-005](adr/ADR-005-download-architecture.md) | Own DownloadManager, verified files, UIDT (34+) / WorkManager (29–33) | Accepted |
| [ADR-006](adr/ADR-006-queue-architecture.md) | `QueueManager` single writer mirrored into Media3; shuffle as queue transform | Accepted |
| [ADR-007](adr/ADR-007-liquid-glass-implementation.md) | Semantic glass API over Kyant Backdrop; one capture; Full/Blur/Solid tiers; no Material3 | Accepted |
| [ADR-008](adr/ADR-008-offline-architecture.md) | Three storage classes; synced library; outbox for offline writes | Accepted |
| [ADR-009](adr/ADR-009-dependency-injection.md) | Manual composition root (`AppGraph`) | Accepted |
| [ADR-010](adr/ADR-010-typography-and-iconography.md) | Instrument Sans + per-string Inter fallback; Material Symbols Rounded subset | Accepted |
| [ADR-011](adr/ADR-011-navigation-and-input-routing.md) | Navigation 3 app-owned stack; `InputRouter` → `InputTarget` with `WheelContext` | Accepted |
| [ADR-012](adr/ADR-012-modules-and-networking.md) | Module map, pure-JVM domain modules, OkHttp-only networking | Accepted |

## Inline decisions

### D-01 · SDK levels
- **Decision:** minSdk 29, targetSdk 36, compileSdk 37.
- **Context:** Play requires target 36 (since 2026-08-31). API 37 is current. Glass tiers depend on 31/33.
- **Options:** minSdk 26 / 29 / 31.
- **Chosen:** 29. **Why:** scoped-storage-only (one storage model), `Typeface.CustomFallbackBuilder`, system dark theme, reliable `MediaStore` columns; drops only Android 8/9. **Tradeoffs:** a few % of active devices excluded; API 29–30 get the Solid glass tier. **Future:** target 37 after verifying Android 17 behaviour changes in Phase 10.

### D-02 · No Material3 dependency
- **Decision:** depend on Compose foundation/ui only. **Context:** Material's ripple/shape/color leak into custom systems. **Options:** M3 themed heavily / M3 for a few widgets / none. **Chosen:** none. **Why:** enforceable visual purity; our component set is small. **Tradeoffs:** we build text fields, sheets, menus on foundation (`BasicTextField`, `Popup`, custom sheet). **Future:** can add `material3-adaptive` (layout-only, no visuals) for window size classes if useful.

### D-03 · Library sources are synchronised, not paged live
- See ADR-002/ADR-008. **Why:** wheel scrolling must never wait on the network; offline browsing and FTS search come free.

### D-04 · Transition model: iPod "strip" slide
- **Decision:** push/pop moves outgoing and incoming screens together as one horizontal strip (full width), driven by a spring; title cross-fades.
- **Options:** iOS parallax (−30% + dim), Material shared-axis, iPod strip. **Chosen:** iPod strip. **Why:** reinforces the single horizontal hierarchy (spatial model of the iPod) and reads as hardware-precise. **Tradeoffs:** heavier motion on large screens → tablets use pane transitions instead (scenes). **Reduced motion:** 150 ms cross-fade. Exception: opening Now Playing from the mini player uses a container transform from the capsule (the touched element), see `animation-system.md`.

### D-05 · Now Playing opens automatically on play
- **Decision:** selecting a song pushes Now Playing (iPod behaviour); setting "Show Now Playing when a song starts" (default On). **Why:** the brief's journey turns the wheel into volume right after playing. **Tradeoffs:** some users prefer staying in lists → setting.

### D-06 · Long-press mappings
- Center: context menu for the focused item · Menu: go to Home (iPod) · ⏮/⏭: scan rewind/fast-forward while held (iPod) · ⏯: Sleep sheet. **Why:** authentic, discoverable via onboarding hint, each has a non-gesture alternative.

### D-07 · Haptics only through `View.performHapticFeedback`
- **Why:** automatically honours the system touch-feedback setting; constants map to documented meanings. **Tradeoffs:** no custom waveforms. In-app "Haptics" toggle in Appearance.

### D-08 · Clicker sound default Off
- **Decision:** optional iPod-style click (Settings ▸ Playback ▸ Clicker: Off / On). Played with `USAGE_ASSISTANCE_SONIFICATION`, suppressed in silent/vibrate. **Why:** HIG — sounds must respect silence; haptics already carry the feedback.

### D-09 · Cleartext HTTP policy
- **Decision:** platform cleartext permitted; Podium's `NetworkPolicy` allows `http://` **only** for private/LAN hosts (RFC 1918, link-local, `*.local`, `*.lan`, `*.home.arpa`) after an explicit per-server warning; everything else requires HTTPS. **Why:** self-hosted Navidrome on LAN commonly lacks TLS; network-security-config can't express IP ranges. **Tradeoffs:** weaker defence in depth → compensated by app-level enforcement + test.

### D-10 · Preferences in Proto DataStore, not Room
- **Why:** typed, atomic, observable; no relational need. Recorded because the brief lists UserPreferences among DB entities.

### D-11 · Online lyrics require one-time consent
- **Decision:** first time Lyrics needs LRCLIB, ask: "Find lyrics online? Podium sends the song title, artist, album and duration to LRCLIB." **Why:** privacy-minimising default without crippling the feature.

### D-12 · No analytics, no crash SDK
- **Decision:** none in v1. Local, user-shareable diagnostic log export instead. **Why:** privacy principle; no backend.

### D-13 · Podium's code license
- **Status:** **Pending user.** Until decided: all rights reserved, and no GPL code copied in.

### D-14 · Trademarks in UI
- **Decision:** never use "iPod", "Click Wheel", "Cover Flow" in UI/marketing. Names: "the Wheel", "Album Flow". App icon must not depict an iPod. **Why:** avoid trade-dress/trademark issues.

### D-15 · "Shuffle Songs" uses Podium shuffle
- Home ▸ Shuffle Songs builds a context of all playable library songs, shuffled via the queue transform (ADR-006), chunked into the player.

### D-16 · Volume on the wheel controls system media volume
- **Decision:** Now Playing rotation adjusts `STREAM_MUSIC` index via `AudioManager.adjustStreamVolume(..., flags = 0)` (Podium shows its own volume bar; system panel suppressed). Detents per step adapt to the device's step count so a full volume sweep ≈ 1.25 rotations. If `isVolumeFixed` (some devices, bit-perfect USB), the wheel shows "Volume is controlled by the connected device" and does nothing. **Why:** iPod adjusted hardware volume; software gain would reduce resolution and diverge from system volume.
