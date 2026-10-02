# Decision Log

Every significant decision, newest at the bottom. Major ones have an ADR in `adr/`; smaller ones are recorded inline in the required format. Status values: **Accepted**, **Pending user**, **Superseded by …**.

## Index of ADRs

| ID | Decision | Status |
|---|---|---|
| [ADR-001](adr/ADR-001-platform-and-ui-framework.md) | Native Android, Kotlin, Jetpack Compose, Media3 | Accepted — **pending user confirmation** |
| [ADR-002](adr/ADR-002-music-source-architecture.md) | Library (synced) vs Catalog (live) sources; source-qualified identity | Accepted — **superseded in part by ADR-013** |
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
| [ADR-013](adr/ADR-013-provider-source-architecture.md) | Capability-faceted sources; `PlaybackTarget` = DirectStream / RemoteProvider / Embedded; identity-preserving fallback; `Basis`; stream-unlock boundary | Accepted — **optional providers pending user** |
| [ADR-014](adr/ADR-014-bitchord-inspired-independent-implementation.md) | Learn from BitChord (GPL-3.0) as reference; reimplement from Podium specs; no GPL deps while D-13 open | Accepted |

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

### D-17 · Automatic cross-source fallback only at matcher tier EXACT
- **Decision (revised 2026-10-02, implementation directive):** automatic fallback to another source requires `EXACT` equivalence — nothing else, no setting. `STRONG`, `POSSIBLE` and `NO_MATCH` never substitute automatically (tiers renamed from PROBABLE/AMBIGUOUS during S1; ambiguity is evidence that caps a result at `POSSIBLE`). Always surfaced in Now Playing/Signal Path. **Context:** directive: never silently substitute a different recording. **Options:** score threshold / rule-based tiers / no fallback. **Chosen:** rule-based tiers. **Why:** explainable, testable, conservative. **Tradeoffs:** some legitimate equivalents skipped. **Future:** user "Not the same song" overrides persist in `track_equivalence`.

### D-18 · No mid-track source switching
- **Decision:** a queue item's resolved target is pinned for that play; better copies found later apply to the next play. **Context:** BitChord swaps streams mid-track on a duration-only check. **Why:** identity and listening continuity; avoids decoder/cache discontinuities. **Tradeoffs:** a slow source's better copy waits one play.

### D-19 · `Basis` drives build policy
- **Decision:** every source declares `LOCAL_DEVICE` / `USER_SERVER` / `OFFICIAL_API` / `UNOFFICIAL_API`; Play-distributed builds link no `UNOFFICIAL_API` factory; UI labels unofficial sources ("Unofficial — may stop working"). **Why:** honest provenance; keeps Play distribution possible.

### D-20 · YouTube Music positions
- **Status: Pending user.** Y1 (unofficial catalogue + matched playback via authorized sources) and Y2 (official embed) are buildable; Y3 (direct YouTube audio via stream unlock) is not built (ADR-013 boundary; brief §65). Y0 (none) is valid.

### D-21 · Spotify positions
- **Status: Pending user.** S-a (read-only playlist/library import, matched to the user's sources) and S-b (App Remote remote session: provider-owned queue, no mixing, hard cuts, attribution) are buildable for personal use with a bring-your-own client id (dev mode: 5 users, Premium owner). Direct Spotify audio: unavailable. Policy tension (III.5, III.11) documented in the matrix.

### D-22 · First real remote target = OpenSubsonic jukebox
- **Decision:** validate `RemoteProvider` with `jukeboxControl` (official API of the user's own server) before any third-party remote SDK. **Why:** proves the abstraction (queue ownership, state mirroring, session ownership) without policy risk. **Tradeoffs:** server support varies; a fake controller covers tests regardless.

### D-23 · Matcher lexicon basis
- **Decision:** version/variant/packaging lexicon derived from MusicBrainz style guidelines (recording disambiguation, release secondary types) and Podium's own corpus; adds identifiers (ISRC/MBID), explicitness, re-recordings, sped-up/slowed, music-video audio. **Why:** independent of BitChord (ADR-014) and grounded in an open, documented taxonomy.

### D-24 · Quality is four facts, label uses measured only
- **Decision:** catalogue-advertised, resolved-advertised, measured, output are kept separate (`PLAYBACK_TARGETS.md` §4); Now Playing label uses measured values only; remote targets show no label unless the provider's official API states quality.

### D-25 · The focus lens sits beneath the row text
- **Decision:** the focus lens is a stained glass capsule (tint + rim + sheen, no refraction) drawn *beneath* the focused row's content; on the Solid tier it is a solid highlight bar with white text. **Why:** refraction over the text being read hurt legibility; beneath, the text stays crisp and the lens still reads as glass. **Tradeoffs:** less "liquid" than a refracting lens.

### D-26 · The device shell: a virtual screen, a power button, finishes
- **Context:** user direction after the first on-device run: the interface should live in a smaller virtual screen above the Wheel, as large as possible; a small power button turns the device on and off with a boot screen and sound; the body and Wheel come in finishes — steel gray, burgundy, glacier blue, silver, the original glass, or a custom hex with a grain adjuster.
- **Decision:** the window is the device body. A recessed `VirtualScreen` (black bezel, own canvas, cover-glass reflection) holds the whole interface — header, lists, Now Playing, mini player, menus — and takes all height the Wheel doesn't. A `PowerButton` beside the Wheel switches between Off (black screen, playback paused), Booting (`BootScreen` + chime) and On; a fresh process boots, activity recreation doesn't. Finishes are `DeviceAppearance` (preset, custom ARGB, grain) persisted in SharedPreferences and rendered by `DeviceBody`, the solid `PodWheel` style and `PowerButton`; previews are live. Specs in design-system.md §1.1 and §5.8.
- **Why:** Podium reads as an object, not a skin over a phone app; finishes are personal without touching legibility (the screen's colours never change with the finish).
- **Tradeoffs:** less content area than full-bleed (≈ 380 × 520 dp on a 411 × 911 dp phone); two backdrop captures on the Glass finish (body for the Wheel, screen for menus and the mini player) — one structure for every finish so previews never rebuild the screen. The chime is a UI sound (sonification usage), so it obeys silent mode and never plays over music.
- **Rules kept:** glass only on controls (Wheel, power button, mini player, menus, lens); the screen is never glass; no Material components; the chime is original (generated with ffmpeg, no third-party sound).

### D-27 · Implementation-time adjustments (S1–S3, slice)
- `CapabilityStatus` gained `REQUIRES_PERMISSION` (with `CapabilityAction.RequestPermission`) and uses `DISABLED` for user-disabled sources.
- Room is deferred: `LocalMusicSource` reads MediaStore into memory and observes changes; the library cache lands with S4.
- `:core:designsystem` depends on `:core:interaction` (the Wheel needs the gesture tracker and router), the reverse of ADR-012's sketch.
- The engine rejects non-`DirectStream` targets with a typed error until S5 (remote/embedded).
- Navigation: Navigation 3 `NavDisplay` with the back stack owned as a saveable `SnapshotStateList` (no `rememberNavBackStack`/`NavKey`; `lifecycle-viewmodel-navigation3` avoided while alpha).
- Wheel velocity is measured over the span of recent detents (a lone detent has no rate). Found on device, where every slow click was being doubled.
- The session player reports the queue's shuffle state to controllers itself (ExoPlayer's own shuffle never changes, so the session never re-read it). Found on device.

