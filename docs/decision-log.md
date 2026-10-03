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
- Wheel made calmer after on-device feedback (fast spins skipped the highlight past songs): 18° detents (was 15°), acceleration only in lists of ≥ 50 items as the spec intended, and only above ≈ 1 rev/s (×2 at 22 det/s, ×4 at 36; ×8 removed). Volume keeps ≈ 1.25 turns for a full sweep.
- The session player reports the queue's shuffle state to controllers itself (ExoPlayer's own shuffle never changes, so the session never re-read it). Found on device.

### D-28 · Now Playing, artwork and the strict display boundary
- **Context:** user brief (2026-10-03): a signature Now Playing inside the virtual screen, artwork as the star, Liquid Glass only for controls, a hard display boundary, wheel integration without breaking the established contract. Research: `docs/research/NOW_PLAYING_VISUAL_REFERENCES.md`.
- **Decision:**
  - The display is a hard boundary: `VirtualScreen` clips everything; `DeviceLayout` places screen, Wheel and power button (portrait and landscape) with a fixed screen proportion; screens know only `LocalScreenInsets`. A Robolectric test renders Now Playing at five device sizes and fails if any node on the display leaves it.
  - Now Playing per design-system.md §6.7 (stacked / compact / landscape compositions computed from the screen, aspect-true artwork with breathing and direction-aware transitions, brightness-keyed environment, bare transport, one glass action capsule).
  - Wheel on Now Playing keeps the iPod contract (volume default; Center cycles volume → scrub → actions; hold Center = More; Menu returns to volume). Scrubbing is velocity-aware. Hold ⏯ = off, as on the original.
  - Up Next: headers are non-focusable rows (the lens only covers songs), the current song has an active state, and songs move with the Wheel through `QueueManager.move` (no touch drag yet).
  - Favorites are real: `FavoritesRepository`, persisted by source-qualified `TrackId` in SharedPreferences until the library database (S4) migrates them.
  - Mini player gains Next; Now Playing rises from it and sinks back (Nav3 per-entry transitions); screen sleep dims, holds, then goes black inside the display only.
  - Artwork loading: a synchronous cache peek (no placeholder flash for cached art), a smaller cached copy as placeholder while the large one decodes, crossfade otherwise.
  - Icons: Favorite and Power join the Material Symbols subset (34 glyphs); filled symbols use a static instance with the variable font's coincident hole/fill contours removed (they rendered a hairline seam on device).
- **Tradeoffs:** the stacked layout gives the artwork ≈ 230 dp on a 411 × 911 dp phone (the Wheel takes the rest); a second backdrop capture inside Now Playing (artwork environment) on the Full/Blur tiers; a debug-only adb command drives the test tones so device tests never touch the listener's library.

### D-29 · Carbon and Bone, the paper, and library browsing
- **Context:** user direction (2026-10-03): a switchable two-tone industrial theme (Teenage Engineering-style matte hardware, monochrome instrument display, album art as the only colour, no gloss, no blue highlight); a curved menu tied to the Wheel's geometry with a selection indicator; a two-zone list + preview; the "infinite paper" horizontal model; Cover Flow; and the next phase (Albums, Artists, touch reordering, wheel timings). Follow-up from the device: the arc must mirror the Wheel's right side (hollow facing left); the previous column should peek in on the left and the next on the right; the composition must apply to every list; matte themes need boxy controls and a mechanical wheel; previews flashed during navigation; the Theme picker couldn't be turned and jumped back. Research: `docs/research/INDUSTRIAL_DIRECTION_REFERENCES.md`.
- **Decision:**
  - Display themes Glass / Carbon / Bone (`DisplayTheme`, Settings ▸ Theme, live preview, persisted). Carbon and Bone: `CarbonColors` / `BoneColors` (monochrome, `DisplayStyle.INDUSTRIAL`), no glass anywhere on the display (Solid tier, no shadows), no atmosphere tint, matte black hardware (`ShellPalette.matte`) whatever the finish; grain still applies.
  - Selection on industrial displays is lit, not highlighted: brighter text, a step heavier, a faint glow on Carbon, a barely-there band, and a small indicator — never a pill or a colour.
  - The paper (`Paper`, `FocusList` default): every list is a straight column between a left strip where the previous column peeks in (`LocalPaperPeek`, provided by the shell from the back stack) and a ")" arc mirroring the Wheel's right side, with the indicator riding on it and the focused item's next column previewed beyond it (`MenuPreview`). Overlay menus opt out. The leaving screen's peek and preview fade out at once and the arriving screen's fade in after it settles (`LocalPaperDecor`, tied to Navigation 3's `LocalNavAnimatedContentScope`).
  - Navigation stays horizontal on Carbon/Bone (Now Playing slides in like any column); Glass keeps the rise from the mini player. The header title slides with the paper.
  - Boxy controls on industrial displays: squared mini player and menus, near-square artwork without shadows, a flat progress rule with a square playhead, outlined rectangular transport keys, and a segmented action strip with an LED-style bar under the focused cell.
  - Mechanical wheel on industrial displays: a knurled band of radial ticks (a long tick per detent) that turns one detent step per click, a machined centre button, flat matte surfaces.
  - Library browsing on the in-memory library (database next, by user choice): `LibraryIndex` derives albums and artists from the merged songs; Music ▸ Cover Flow, Albums, Artists, Songs, Favorites; album and artist pages; typed destinations (`Dest`) so album/artist pages survive process death.
  - Up Next: drag handle to reorder (insertion line, committed through `QueueManager.move` on release; TalkBack "Move up/down"). Wheel timeouts: scrub 4 s, actions 6 s.
  - Fixes: `GlassHost` keeps one tree for every tier (switching Glass ↔ Carbon/Bone rebuilt the screens and reset the Theme picker's focus); settings pickers commit in place (Menu goes back).
- **Tradeoffs:** the paper takes ~26 % of the width for the peek and preview, so long titles ellipsize sooner; peeks show labels (not live screens) of the previous column; artist imagery uses album art until a source provides artist pictures. (All three revisited in D-30.)

### D-30 · The curved list, live previous column, and moving along the paper
- **Context:** user direction with a sketch (2026-10-03): lists should curve along the arc and scroll along it, blurring out at the ends; the previous and next screens should sit as glimpses at the middle of the display, either side of the list, and navigation should animate between them (the next screen rising into focus, the current one sinking down to the left into the glimpse). Also: long titles cut off sooner with the paper; artists should not borrow album art; the previous-column peek should be the screen itself, not its labels. Follow-up on device: the previous glimpse should look like the next one, blurred, not a tiny copy.
- **Decision:**
  - `PaperGeometry` is the single description of the paper (arc, column, boxes, scales), shared by `FocusList` and the navigation transitions, so a screen moving away lands exactly in its box.
  - Rows ride the arc (shifted by the curve at their height) and fade, shrink slightly and soften toward the ends; the focused row stays crisp; rows fade out before the title and the mini player.
  - Both glimpses are the same square at mid-height with the same distance treatment, each running off its display edge. The previous one is that screen, live (a `LocalMiniature` composition: a throwaway input router and overlay host, no previews of its own, settings pickers don't preview finishes), zoomed in on its list with the item that led here focused; tapping it goes back.
  - Navigation moves along the paper in every theme (600 ms; crossfade with reduced motion); Glass keeps Now Playing's rise from the mini player.
  - Artists show pictures only from sources that have them (`LibraryRepository.artistArtwork`, from `LibrarySource.artists()`); otherwise a round portrait monogram. Artist rows, pages and previews are round.
  - The focused row's title scrolls slowly when it doesn't fit (design-system §3 amended); other rows ellipsize.
- **Tradeoffs:** a live previous column composes a second screen while a column is shown (cheap for lists; artwork comes from the cache); the glimpse zooms in on the middle of the previous list, so in a long list scrolled far from its middle it may show neighbours rather than the chosen row; the blur on row ends needs API 31+ (older devices get fade and scale only).

### D-31 · The library database
- **Context:** the library was rebuilt in memory from each source on every launch (slow to appear, slow to group at scale: artist grouping was quadratic), favorites lived in a SharedPreferences file, and the queue was lost on every restart though the product spec promises Podium reopens with it. The user chose a dedicated database pass (2026-10-03).
- **Decision:**
  - `core:database` on Room 3 + bundled SQLite (ADR-004), schema v1: library cache (`track`, `album`, `artist`, `source_account`), favorites (`liked_track`), the saved queue (`queue_state`, `queue_item`) and an FTS5 search index. See data-model.md §1a.
  - `LibraryStore.sync` replaces one source's library with what it reports: rows are rewritten only when their content hash changed, songs no longer reported are marked removed (not deleted), albums and artists are derived from the songs. The app's `DatabaseLibraryRepository` keeps every usable library source synced in the background and serves screens from the database, so the library is there at launch; until each usable source has synced once an empty store reads as "loading". Reads are scoped to sources whose library is usable now (a revoked permission hides that source's songs) and to the debug scope.
  - `LibraryRepository` gained album and artist streams (`albums()`, `album(id)`, `artists()`, `artist(id)`); screens no longer group songs themselves. The in-memory `LibraryIndex` remains the default for repositories without a database and follows the same `LibraryGrouping` rules (now in `core:model`, with a collation-free sort key that drops leading articles and accents).
  - Favorites moved to `liked_track` (`DatabaseFavorites`); the old SharedPreferences set is imported once and the file deleted.
  - The playback service saves the queue (`QueueStore` in `player:api`, `DatabaseQueueStore`) when its contents, order, current song or modes change — not on resolutions — and the position on pause, every 10 s while playing and on shutdown. On start it restores the queue paused at that position without preparing the player (nothing loads until play); a command that arrives first wins. Restored slots get fresh ids; nothing is pinned.
  - `TrackCatalog` looks in the database before asking a source, so play commands by id work after a restart before any source has synced.
- **Tradeoffs:** the database adds ~1 MB per ABI (bundled SQLite) and a sync's write cost on first launch (steady-state syncs write only changes); album/artist rows are rebuilt per sync of their source; search exists in the data layer but has no screen yet; history, playlists and GC are later passes.

