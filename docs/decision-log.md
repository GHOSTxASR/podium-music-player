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
- **Decision (revised 2026-10-02, implementation directive):** automatic fallback to another source requires `EXACT` equivalence — nothing else, no setting. `STRONG`, `POSSIBLE` and `NO_MATCH` never substitute automatically (tiers renamed from PROBABLE/AMBIGUOUS during S1; ambiguity is evidence that caps a result at `POSSIBLE`). Always surfaced in Now Playing/Signal Path *(superseded by D-36: recorded, not shown on normal Now Playing)*. **Context:** directive: never silently substitute a different recording. **Options:** score threshold / rule-based tiers / no fallback. **Chosen:** rule-based tiers. **Why:** explainable, testable, conservative. **Tradeoffs:** some legitimate equivalents skipped. **Future:** user "Not the same song" overrides persist in `track_equivalence`.

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

### D-32 · Boot self-test, the Wheel's click, and music folders
- **Context:** user direction (2026-10-03): a boot that feels like old hardware running new software — a macOS-style loading screen, old-computer self-test text, the Mac chime, haptics in step with the boot text; a sound setting alongside haptics; and a way to choose the folders Podium reads music from, because the phone's media library also holds messengers' audio (WhatsApp voice notes and the like).
- **Decision:**
  - Boot: a typed power-on self-test with real measurements (`BootReport`: cores, RAM counted up, display pixels, current audio output, library song count), each line landing on a haptic tick; then the mark, the chord and a stalling progress bar (design-system.md "Boot"). Skippable with any Wheel press.
  - The chime is a new original startup chord, not Apple's: the Mac startup chime is Apple's registered sound mark, so Podium has its own in the same spirit (synthesized with ffmpeg).
  - Feedback settings: Haptics (default on) and Click sound (default off, per D-08), applied app-wide through `LocalFeedback`; `PodiumHaptics` plays the click with each detent/step/press.
  - Music folders: a provider-neutral `FolderFacet` (in `sources:api`) for libraries read from storage; `FolderSelection` (included/excluded folder prefixes, the closest choice wins). The local source reads `RELATIVE_PATH`, lists every folder holding music, and only syncs songs from chosen folders. The default skips `Android/media/`, `WhatsApp/`, `Telegram/` and `Recordings/`. Settings ▸ Music folders is a wheel-driven tree on the paper ("All folders" at the top, "All of …" inside a folder, subfolders open on Center, long-press chooses a folder whole); `RegistryMusicFolders` finds the facet by capability, never by source.
- **Tradeoffs:** the boot is ~4.5 s each power-on (skippable); excluded songs leave the library through the normal sync (marked removed, kept if the queue refers to them); a song in an excluded folder can't be added back individually, only by its folder.

### D-33 · Indicator lights on the body
- **Context:** user direction (2026-10-03): two indicators at the bottom like old Nintendo handhelds — a battery light coloured by charge, and a small blinking light that simulates disk activity when folders open and close.
- **Decision:** `StatusLeds` on the body at the bottom corner opposite the power button (a `DeviceLayout` slot): **Battery** — green above 60 %, lime to 30 %, amber to 15 %, red below, breathing while charging, blinking at 10 % and under (from the sticky battery broadcast; no permission). **Disk** — amber, two quick flickers per burst of I/O. Anything that reads or writes pulses `DiskActivity` (core:common): pushing and popping on the paper, a library sync, artwork loads, and network requests. Each diode sits in a recessed bezel with a lens highlight, tiny sentence-case legends below. On the body, not the screen, so it follows the finish and never touches the display (D-26).
- **Tradeoffs:** purely decorative signals; the disk light is lossy by design (a burst of work is one flicker, never a backlog).

### D-34 · ONLINE: a separate music environment, starting with Audius
- **Context:** user direction (2026-10-03, "Online mode + online music experience"): Podium becomes two environments — LOCAL (music on the device) and ONLINE (music streamed from connected online sources) — kept conceptually and persistently separate, ONLINE as a top-level destination on the existing paper, with discovery, search, liked songs, playlists, radio, history, autoplay and an online Now Playing, built on the existing source abstraction with Audius (official API, direct streams) as the first source. BitChord is a product reference only (no code, no GPL, no YouTube extraction).
- **Decision:**
  - Contracts (O1): `MusicEnvironment` on `SourceDescriptor`; `DiscoveryFacet`; `RecommendationFacet` (related with exclusions, artist radio, related artists); paged search with kinds and playlists; `CatalogFacet.playlist`; `PlaylistId`; `RecommendationEngine`/`AutoplayEngine`/`AutoplaySettings` in `player:api`; `QueueOrigin.RADIO`. See MUSIC_SOURCE_ARCHITECTURE.md §11.1.
  - Audius (O2): `sources:audius`, anonymous read access with `app_name` (no key, no secrets), verified against the live API on 2026-10-03 (SOURCE_CAPABILITY_MATRIX.md §2.3). Search, shelves (trending week/month, underground, popular playlists), genres, artists (popular songs, albums, playlists, related artists), playlists/albums, genre-based recommendations with exclusions, artist radio, MP3 direct streams via the API's redirecting stream endpoint, sized artwork. Gated tracks are unavailable and never resolved. Only the codec is claimed (MP3); the decoder measures the rest — never "lossless". Signing in needs an Audius API key, which Podium doesn't have: AUTHENTICATION, LIKES and PLAYLISTS (server-side) report unavailable.
  - Database (O3): schema v2 adds ONLINE's own likes, playlists and history (data-model.md "Schema v2"), per account ("" = on this device). Liking a local song never touches ONLINE's liked songs and vice versa (`EnvironmentFavorites` routes Now Playing's heart by the song's environment; Music ▸ Favorites reads local favorites only).
  - Navigation & UI (O4): Home ▸ Online; ONLINE's tree on the paper (navigation-map.md), capability-gated rows, previews from the source's artwork; search inside the paper (the field is the list's first row); track menus with only supported actions (Play next, Add to Up Next, Like, Add to playlist, Start radio, the artist, Share); offline/errors explained ("You're offline… Music on this phone still plays").
  - Playback (O5): the same pipeline (catalog → QueueManager → StreamResolver → Media3); Now Playing says "Online" quietly in its status line; ONLINE history is recorded from playback (time actually heard, ≥ 5 s) and never touches local data.
  - Autoplay (O6) and radio (O7) as in queue-and-autoplay.md §5; Settings ▸ Autoplay, Online recommendations, Avoid repeats.
  - Debug: `online-offline` / `online-online` rehearse losing the network for ONLINE without touching the phone's settings.
- **Tradeoffs / limits:** no Audius sign-in yet (likes and playlists live on the device; server sync later via the account model); Audius albums referenced from a track (album backlink) aren't navigable (numeric id only); radio-from-song uses the seeds' genre (the API has no per-track "related"); history records when the song changes (the last song before the process dies isn't recorded); search opens the soft keyboard over the Wheel until dismissed.


### D-35 · ONLINE is multi-source; sources disappear behind the music
- **Context:** user direction (2026-10-06, "O10 multi-source Online foundation"): *the user thinks about music, Podium thinks about sources*. Online behaved as if there were one provider — `AppOnlineRepository` sent every catalogue call to "the first enabled ONLINE source" and `OnlineRepository.source` was a single `OnlineSource?` — so a second online source would have hidden the first (or vice versa) and source-local ids (artists, playlists) could be routed to the wrong source. This phase builds the generic foundation; no new provider (YouTube stays a future source boundary, D-20 still pending).
- **Options:** (1) keep one active online source chosen by priority — no aggregation, no "one song, many sources"; (2) concatenate every source's results — duplicates, provider-shaped lists; (3) **aggregate concurrently and group equivalent copies with the matcher**. Chose (3).
- **Decision:**
  - **One registry, one way to change it.** `SourceRegistry` stays the only source list (now `ordered(environment)`, `registered(environment)`; re-enabling clears the breaker). `SourceSettings` + `SourcePreferencesStore` persist the listener's explicit on/off choices and priority (app: `SharedPrefsSourcePreferences`, committed synchronously). Settings ▸ Online sources renders whatever online sources the build registers.
  - **Aggregation** (`sources:api/aggregate`, pure Kotlin): `SourceFanOut` asks sources concurrently with an 8 s per-source timeout, detached from the caller (a source stuck in blocking I/O is abandoned, cancellation propagates), skips open-breaker/rate-limited/rejected sources (all-open → asks anyway), and records health (answer/miss healthy; failures and timeouts count; offline counts against no one; "not authorised" is `AUTH_FAILURE` only for sources that sign in). `MultiSourceCatalog` does progressive search, merged shelves (same name → one shelf; ids name every member), genres, per-source paging sessions, and routes artists/albums/playlists to their own source.
  - **One song, many sources = EXACT only.** `TrackGrouper` merges copies only when the matcher says EXACT, at most one copy per source, and never when ambiguous; the row shows the most preferred source's playable copy and keeps every copy. This is stricter than MUSIC_SOURCE_ARCHITECTURE §9.1's original STRONG grouping, so every member of a row is a safe fallback for every other (D-17 unchanged). Groups are remembered in the `EquivalenceStore` (now holding the copies), and `StreamResolver` uses a known EXACT copy before searching.
  - **Identity:** unchanged representation `<sourceId>|<key>` (`PlaylistId.sourceId` added; `ScopedKey` for shelves). Likes, playlists, history and queue items keep their own copy's source; liked songs and history *display* each recording once.
  - **History:** schema v3 adds `online_history.served_by` (nullable): the listen stays the song chosen; `served_by` notes another source's EXACT copy when one played it. No other schema change was needed (the queue stores source-qualified ids; preferred source and fallback are derived).
  - **Autoplay/radio:** "never mix sources" (D-34) becomes "never mix environments": every recommending source of the environment is asked about the seeds it holds (own copies or EXACT twins); candidates from any of them, de-duplicated at EXACT, including against twins of recently played songs. Artist radio stays on the artist's source.
  - **UI:** `OnlineRepository.status` (what ONLINE can do across sources), `canStartRadio(track)`, `canRelate(artist)`, `search(...)` as a `Flow`. Error copy never names a source ("Couldn't reach online music"); a disabled source's item says to turn it on in Settings.
  - **Test-only fake:** `FakeOnlineMusicSource` (sources:api test fixtures) proves aggregation, grouping, routing, fallback and health with deterministic success/miss/failure/timeout/rate-limit; no app build registers it.
- **Unchanged on purpose:** D-17 (EXACT-only automatic fallback, surfaced on Now Playing as "Playing from …"), D-18 (no mid-track switch), the resolver's fallback walk over *all* enabled sources (an ONLINE song may still be served by an EXACT local copy — open question for a later phase — resolved by D-36: fallback stays within the environment), Audius' adapter.
- **Tradeoffs / limits:** artists/albums/playlists from different sources aren't merged (no safe identity rule for them; two sources' "same" artist shows twice); equivalence is in memory (the `track_equivalence` table is still a spec item), so twins are rediscovered by search after a restart; a liked copy doesn't make its twin show as liked; shelf/genre/search paging continues per source from in-memory sessions (a list reopened after process death restarts mid-way best-effort); the per-source timeout bounds latency but a blocked source's thread keeps running until its own I/O timeout.

### D-36 · Source resolution stabilised: invisible provenance, environment-bound fallback, persistent equivalence
- **Context:** user direction (2026-10-06, "O10.5"): before a second production online source, settle what O10 left open. (1) Now Playing said "Playing from <source>" on a fallback, which contradicts "the user thinks about music". (2) `StreamResolver`'s fallback walked every enabled source, so an online pick could silently play a local file and a missing local file could silently stream online. (3) Equivalence lived in memory, and a "not the same song" didn't survive a restart. Two smaller gaps: the grouper and the liked/history de-duplication ignored rejections, and the resolver had no per-source timeout (a hanging source waited out the player's 15 s).
- **Decision:**
  - **Provenance is recorded, not announced.** Normal Now Playing never names a source; the "Playing from …" line is gone. `NowPlayingItem` keeps `servedBy` / `servedByDisplayName` / `resolutionPath` for history (`online_history.served_by`), logs and diagnostics. Debug builds add `now-playing-source`, which prints it. This supersedes the "always surfaced" clause of D-17; EXACT-only fallback and everything else in D-17 stand.
  - **Fallback stays in the song's environment.** Fallback (and owned copies, unless it's the same song) is limited to sources of the track's own source's environment. A song picked online stays online, and one picked from the library stays in the library, even when a known EXACT copy exists across the line. A source that's no longer registered has no environment, so its songs get no fallback. Cross-environment playback needs an explicit listener action, which doesn't exist yet.
  - **Persistent equivalence:** `DatabaseEquivalenceStore` (core:database) over schema v4's `track_equivalence`.
    - **What's stored:** pairs, in two kinds only: EXACT matches the matcher made (AUTO) and the listener's rejections (USER).
    - **How it runs:** reads come from memory (synchronously); changes are written through in order on one writer; everything is loaded at startup. The copies' metadata is cached in `track`.
    - **Rejections are final.** A later automatic decision never overwrites one, in memory or on disk, even before the stored decisions are loaded. `TrackGrouper` and `distinctRecordings` refuse rejected pairs. "Not the same song" is infrastructure only: `EquivalenceStore.reject`, plus the debug `not-same` command.
  - **Timeouts and failure classes.** Each source gets 6 s to resolve or search during fallback; a timeout is a failure and the next copy is tried. An exception from a source (e.g. a malformed answer) is a failure. A broken fallback search counts against the source's health; an empty one is a miss. Miss ≠ failure is unchanged.
  - **Autoplay:** two sources of unknown environment are no longer treated as the same environment.
- **Options considered for persistence:** (a) pair rows (chosen; matches the spec, every row is a real matcher decision, few rows per song with a handful of sources); (b) equivalence groups (fewer rows, but merging groups would claim EXACT for pairs never compared, because EXACT isn't transitive).
- **Tradeoffs / limits:** no listener-facing "Not the same song" control yet. The resolver's timeout bounds cooperative sources; a source stuck in blocking I/O is still bounded by the player's own 15 s wait. Equivalence rows aren't garbage-collected (they're tiny; a GC pass belongs with the `track` GC). Stored decisions are trusted after a matcher change: a stricter matcher doesn't re-judge old EXACT rows (re-validation could run at load if the lexicon ever loosens).


### D-37 · Configured sources and OpenSubsonic (O11)
- **Context:** user direction (2026-10-06, "O11"): the second production online source is the listener's own music server. Until now every source was built into the app and none signed in. OpenSubsonic (Navidrome, Gonic, Airsonic-Advanced, Ampache, LMS…) is the authorised, user-owned route recorded in `architecture/SOURCE_CAPABILITY_MATRIX.md` §2.2. It needs three things the app lacked: sources the listener *adds* (several, of one kind), sign-in with secrets kept safe, and unencrypted `http` for servers on the listener's own network without opening cleartext to the internet (D-09).
- **Decision:**
  - **Configured sources, provider-neutral** (`sources:api` `SourceSetup.kt`). A `SourceFactory` describes its own `SetupForm` (`SetupField`s: text, URL, secret). Its `connect()` checks the answers with the source (no side effects); `create()` builds the source for a stored `SourceProfile` (id, kind, name, non-secret settings). `ConfiguredSources` restores profiles at startup (before `SourceSettings.apply()`), adds (checks, keeps the secrets, keeps the profile, registers) and removes (unregisters, deletes the secrets, forgets the profile and its on/off and priority choices). Ids are `<kind>-<8 random hex>`, so two servers never share an id, and re-adding a server makes a new one. Settings renders any form without knowing the kind; `SetupProblem` is the only error vocabulary.
  - **Sign-in is a facet.** `AuthFacet` gains `signInFields`, `signIn(values)` and `signOut()`; `AuthState` is all the UI sees. Settings ▸ Online sources shows "Sign in" for a signed-out or refused source. Holding Center offers Move up/down, Sign out/Sign in, and Remove (configured sources only).
  - **Secrets live only in the `CredentialStore`.** The app's `KeystoreCredentialStore`: AES-256-GCM, with a 256-bit key generated in, and never leaving, the Android Keystore (alias `podium-credentials`). One sealed value per source (random IV, base64) sits in the private prefs file `credentials`, which only ever holds ciphertext. A value that can't be opened reads as "no credentials", and the source asks to sign in again. Profiles (address, user name, display name) live in `source-profiles`, never in the database, never with a secret. `ConnectResult.Connected` and the server credentials mask secrets in `toString()`.
  - **Network policy, at every request** (`NetworkPolicy`, D-09).
    - **Addresses:** `https` anywhere; `http` only to localhost, private or link-local IPv4/IPv6, or `.local` / `.lan` / `.home.arpa` names, and only after the listener confirms ("Connect anyway"). No user info, query or fragment.
    - **Why it's enforced in-app:** a network-security-config can't name address ranges, so the platform allows cleartext app-wide (`usesCleartextTraffic`). `NetworkPolicy.permits(url)` therefore holds every request to the rule, not just the address typed in:
      - the server client refuses any other URL;
      - it follows redirects itself, never from https down to http, never off the listener's network, at most 3 hops;
      - the player refuses a stream URL from *any* source that is `http` to a host off the listener's network (a per-item refusal that never trips a breaker).
  - **OpenSubsonic** (`sources:subsonic`, pure Kotlin, `HttpURLConnection` + kotlinx.serialization, no new dependency).
    - **Auth:** token auth on every request: user name, a fresh 8-byte `SecureRandom` salt, `md5(password + salt)`. The password itself is never sent.
    - **Facets:** catalogue (search3, song, album, artist, playlist, top songs), discovery (newest, random, frequent, server playlists, genres), recommendations (similar songs, artist radio, related artists), artwork through the source (`podium-art://` refs, so no tokenised URL leaves the module), and direct streams.
    - **Capabilities:** search, browse, stream and artwork need a signed-in source (otherwise REQUIRES_SIGN_IN with a Sign in action). Recommendations are DEGRADED because they depend on the server's metadata agents. Likes stay on the device; playlists can be played, not edited; downloads come later.
    - **Name and identity:** the display name is the server's type plus its host ("Navidrome (music.lan)"), shown only in Settings. `Basis.USER_SERVER`, environment ONLINE.
  - **Miss vs failure for a server:**
    - Protocol error 70, a missing element or HTTP 404 → miss (the server works, it doesn't have it).
    - 40/41/44 and 50 → refused credentials. 42/43 → token auth unsupported. Either way the source turns `Rejected` and asks to sign in.
    - A malformed answer, 5xx, timeouts and network errors → failures.
    - Before a stream URL is built, `getSong` asks the server about the song, so a vanished song is a miss and a dead server is a failure.
  - **Hardening found during O11 acceptance and audit:**
    - **Breaker escalation.** A source asked anyway while its breaker is open (every source down) no longer lengthens its backoff per call; only a failed probe does. On device, a few calls while the server was down had pushed the backoff to 10 min, hiding the recovered server from search.
    - **Stale stream URLs.** When a source stops serving (turned off, signed out, removed), queued items it served lose their pinned stream URL (which may carry its sign-in) and resolve afresh before they play. The playing item keeps its pin (D-18).
- **Options considered:** (a) store an OpenSubsonic API key instead of the password (needs the `apiKeyAuthentication` extension, which most servers lack; later, as an option); (b) password-equivalent `t`/`s` pairs stored instead of the password (a replayable credential, and servers expect a fresh salt per request); (c) **the password, sealed by the Keystore** (chosen). For cleartext: a network-security-config per host (can't express ranges; would break every LAN address) versus **app-wide cleartext plus in-app enforcement at every request** (chosen).
- **Tradeoffs / limits:**
  - Token auth needs the password at rest (sealed).
  - `.local` / `.lan` names are trusted by name: a hostile DNS could point one off the network, after the listener has already consented to cleartext.
  - Same-protocol redirects inside Media3's HTTP data source aren't policy-checked (a listener's own `http` server could redirect a stream to another `http` host).
  - No "Not encrypted" badge on the source yet, only the warning at setup.
  - No API-key or OAuth sign-in, no server-side stars or playlist editing, no scrobbling, lyrics, jukebox (S5) or library sync into the local database (D-34 keeps online songs out of the library).
  - Removing a server keeps its listens, likes and equivalence rows (orphaned under the old id; a re-added server gets a new id).
  - Self-signed certificates aren't supported.
  - Device acceptance and the security audit are recorded in `testing-strategy.md` §4.1 and `security.md` §8.

### D-38 · YouTube Music is ONLINE: unofficial catalogue, the listener's own session, playback in the official app
- **Context:** user direction (2026-10-06, "execution mission"): make Online a working YouTube Music experience — search, home, albums, artists, playlists, library, likes, history, radio, account — with legitimate playback only, while Offline stays intact; retire Audius/OpenSubsonic and the multi-source machinery carefully. This is the D-20 answer: Y1 (unofficial catalogue) approved, playback by delegation; Y3 stays forbidden (ADR-013).
- **Decision:**
  - **One online service.** `sources:youtubemusic` (pure JVM) is the only ONLINE source; `sources:audius`, `sources:subsonic`, configured sources and `MultiSourceCatalog` are removed; stored credentials of retired servers are deleted at startup (`RetiredSources`). The online UI stays provider-free.
  - **Catalogue** from the music web client's browsing endpoints (`search`, `browse`, `next`; never `player`), `Basis.UNOFFICIAL_API`, page config read at runtime, parsers that fail soft. Signed out it's DEGRADED ("Sign in for the full catalogue").
  - **Account (A2):** the service's own sign-in page in a locked-down WebView; only the session cookies are kept, sealed by the Keystore credential store; the database sees only a hashed account key. Expiry is detected and reported; sign-out wipes the account's online data.
  - **Playback (P2, P0):** the official YouTube Music app plays under the listener's account. Podium starts it through its media session (`playFromUri`) or by opening the song's link, then controls and mirrors it through Android's media-session interface (notification-listener access, no notification handling). `OwnerAwarePlaybackController` owns the LOCAL/REMOTE split: hard cuts, the local queue preserved, remote songs never in the local queue. "After choosing a song: Stay here / Show YouTube Music."
  - **Model:** `MediaKind` on tracks (schema v5, additive); `AccountLibraryFacet`, `AuthFacet.webSignIn`, `RemoteContext`, `RemotePlayback` in the provider-neutral APIs.
- **Options considered:** OAuth (no YouTube Music library scope); the official embedded player P1 (needs a visible video, no background); Podium-owned audio (forbidden); keeping Audius/OpenSubsonic alongside (user direction: one service).
- **Tradeoffs / limits:** unofficial basis; a web session gives Podium the account's YouTube Music session (necessary for the library, stated to the listener); embedded-WebView sign-in may be refused by Google; P2 depends on what the app's session exposes (U1–U6); device acceptance pending. Spec: `architecture/YOUTUBE_MUSIC_ARCHITECTURE.md`; evidence: `research/YOUTUBE_MUSIC_IMPLEMENTATION_NOTES.md`; device plan: `testing/YOUTUBE_MUSIC_DEVICE_ACCEPTANCE.md`.

### D-39 · Lyrics: LRCLIB by metadata, one line on the whole display
- **Context:** user direction (2026-10-06, "secondary polish mission"): Now Playing ▸ Lyrics, synced when possible, plain without fake timing, a full-screen canvas that alternates light and dark per line, large justified type that never clips, browsing with the Wheel. D-11 already required consent for online lyrics.
- **Decision:**
  - **Provider:** LRCLIB (open API, no key, synced LRC + plain + instrumental), behind a `LyricsProvider` interface in a new pure-Kotlin `core:lyrics` module. Asked by title, artist, album and length only — never by a music service's id — so local and online songs work the same and no provider identity leaks.
  - **Confidence before words:** Podium's own `LyricsMatcher` (title identity, version tags both ways, a shared artist, length within ±3 s) checks even LRCLIB's exact answer; otherwise "No lyrics found".
  - **Consent (D-11)** gates every request; cached answers still show without it. Found lyrics cached 30 days, misses 3 days, failures never; files in the app's cache directory under hashed keys.
  - **The screen:** a destination without header or mini player. One line at a time, set as large as it fits and justified edge to edge (`LyricLayout`, pure), shrinking rather than clipping. Even line index → Bone paper, odd → Carbon black (90 ms, instant with reduced motion). The Wheel reads back/ahead, following resumes after 5 s; Center jumps playback to the line read only when the player can seek.
- **Options considered:** Musixmatch/LyricFind (licensed, need a business agreement and key), Genius (no lyrics text in its API), the music service's own lyrics (unofficial, provider-specific). Spec: `architecture/LYRICS_ARCHITECTURE.md`.
- **Tradeoffs / limits:** community-contributed lyrics need a licensing review before any public release; no karaoke word timing; no in-app "clear saved lyrics" yet.

### D-40 · Edge-aware focus geometry; lists never clip their rows
- **Context:** user direction (2026-10-06, polish mission §2–§7): the first and last rows didn't get the right highlight, the highlight looked held to a fixed band, rows near the ends sat far from the focus, scrolling at the ends felt wrong, and text was cut at corners. Inspected in Robolectric renders of the device (1/2/3/5/10/50 rows, focus walked with the Wheel) before changing anything.
- **Root causes found:**
  1. **Clipped rows.** Paper rows ride the arc through `graphicsLayer.translationX`, up to ~50 dp left at the region's ends, but the `LazyColumn` was only as wide as the straight column, and a lazy list clips to its bounds: "Item 1" rendered as "tem 1". The lens (drawn outside the list) wasn't clipped, so the band and the text disagreed at the ends.
  2. **The marquee's clip.** `basicMarquee` clips to the text's box, which cut Carbon's glow on the focused title into a hard rectangle (and would shave glyphs reaching outside the line box), and stopped long titles mid-letter.
  3. **Keep-in-view exempted the ends.** `keepFocusedInView` skipped scrolling for index 0 and the last index, and used the focused row's size as the margin, so with rows of different heights the first/last row could stay partly under the title or the mini player.
  4. **The lens was clamped** to the readable region (`coerceIn`) instead of following its row — the "fixed vertical region".
  5. **Geometry tied to the display, not the content.** Short lists were centred (empty space above item 1), and the ends faded and blurred even where the list had nothing beyond them.
- **Decision:**
  - `FocusGeometry` (core:interaction, pure): scroll targets from the readable region, the rows and the scroll position. Paper: the focused row rides at the middle (the arc's apex) and the list's own scroll limits put the first and last rows flush with the edges. Plain menus: a neighbour in view each side, ends flush. Lists that fit start at the top.
  - The lens slides between rows in content space (fractional row position) and is clipped to the readable region; the scroll glides with the lens's spring so they move as one.
  - Ends fade only as far as the list continues beyond them.
  - The paper list's bounds reach left by the furthest bend plus 8 dp, rows are inset to the column, and the previous column's glimpse is drawn above the list so taps still reach it.
  - The scrolling title draws 8 dp beyond its box on every side (layout unchanged) and fades its ends.
  - Miniatures (the previous column's glimpse) centre the row that led here, since short lists no longer sit at mid-height.
- **Supersedes:** design-system §6.2 "focused row stays ≥ 1 row from the edges" and §5 "short lists sit centred on the apex".
- **Tradeoffs:** on the paper every detent in the middle of a long list scrolls the list (the lens holds still at the apex), like a drum; a short menu's rows step along the upper part of the arc rather than its apex; the navigation transition still targets the glimpse box's middle, so a top-aligned parent's focused row isn't exactly where the glimpse that replaces it shows it (the glimpse fades in after the move).

### D-41 · Customization: body glitter, display fonts, a Custom theme, your own background
- **Context:** user direction (2026-10-06, polish mission §8–§18): optional glitter in the body; a font, background (none, solid, picture), background opacity, text contrast and theme for the virtual display; settings in Podium's own menus; persistence; nothing allowed to break readability or the existing design.
- **Decision:** one value (`DeviceAppearance`) gains `glitter` (body) and `screen: VirtualDisplay` (display). `DisplayTheme` gains `CUSTOM` — the matte instrument (Carbon/Bone's system) on the listener's own background. Fonts are a `DisplayFont` → `TypographyPreset` abstraction that builds the whole type scale (Classic, Clean, Industrial, Mono, Pixel, Condensed; bundled OFL faces; whole-string fallback to Inter). Ink is chosen by contrast with what is drawn; mid-tones are nudged; over pictures a veil and a text halo keep text readable. Pictures come from the system photo picker with no storage permission and are kept as a URI with persisted read access; an unreadable picture falls back to the solid colour. Glitter is a seeded, cached flake texture tinted from the body, optionally with a slow glint at ~12 fps that pauses off-screen. Settings ▸ Appearance ▸ Device body / Virtual display. Spec: `architecture/PODIUM_CUSTOMIZATION.md`.
- **Constraints kept:** Carbon and Bone accept no background (D-29: album art is the only colour there); the body never takes the display font (D-26); no glass added anywhere; defaults reproduce the previous look exactly.
- **Options considered:** copying the picture into app storage (survives deletion, but duplicates personal photos and contradicts "a reference is enough"); fixed secondary ink mixes (fail on mid-tones — found by the sRGB sweep); a separate Archivo Narrow file for Condensed (Instrument Sans's own `wdth` axis does it with no new file); per-frame particle glitter (rejected: battery and GPU).
- **Tradeoffs / limits:** a picture's readability is judged by its average luminance plus a fixed veil, so extreme pictures at high opacity can still be busy; no picture cropping; no glitter on the Glass finish.

### D-42 · Lyrics arrive word by word
- **Context:** user direction (2026-10-06): synced lyrics should appear progressively, word by word, not a whole line at once.
- **Decision:** `LyricsLine` gains `endMs` and `words`. Real word times are used when the lyric has them (enhanced-LRC word stamps, or words in LRCLIB's `lyricsfile`, which also supplies line end times). Otherwise `WordTiming` paces the words inside the line's real window by syllables, capped at 380 ms per syllable; the first word is always exactly on the line's time. Words fade in at their places in the fixed justified layout; reading, pausing on a line and plain lyrics show whole lines; the details overlay says when the pace is estimated. Spec: `architecture/LYRICS_ARCHITECTURE.md` §4.1, §5.1, §7.
- **Options considered:** a karaoke sweep across a whole visible line (the user asked for words to appear, and D-39 rules out sweeps); revealing only provider-timed words (almost no LRCLIB records have them — none in 60 sampled — so lyrics would stay whole-line nearly always).
- **Tradeoffs:** the estimate can lead or lag a sung word by a few hundred milliseconds within a line; lines are never late.

### D-43 · One loading screen wherever content loads
- **Context:** user direction (2026-10-06): add one loading screen for when content is loading. Until then a screen waiting on the network or the library was simply blank (or showed an empty list), which read as "nothing here".
- **Decision:** `LoadingScreen` (core:designsystem): Podium's pinwheel, twelve spokes around a hub with the lit spoke stepping round and a fading tail, in the display's own ink, above one word ("Loading", "Reading your music"). It is centred in the readable region and shows only after 150 ms, so a fast answer never flashes it. A list that is filling in shows `LoadingRow` instead ("Searching"). Lyrics use the same pinwheel in the lyric paper's ink ("Finding lyrics"). Used by online Home, shelves, genres, album/artist/playlist pages, online library lists and history, and the local library. Screen readers hear the word and an indeterminate progress state.
- **Options considered:** placeholder rows (design-system §6.11's earlier `LoadingState`; they implied a row count we don't know and looked like content); a smooth spinner (redraws every frame; the stepping pinwheel redraws about 12 times a second and suspends when nothing is drawn).
- **Supersedes:** design-system §6.11 `LoadingState` (300 ms, placeholder rows, title-bar arc).
- **Tradeoffs:** with reduced motion the pinwheel stands still, so only the word says it's working.

### D-44 · Glitter that catches the light as the phone tilts
- **Context:** user direction (2026-10-06): the glitter should use the phone's accelerometer and other sensors to glow and fade as if glazed in real life — a slight glow held straight, the glow shifting left when the phone tilts right — with settings for the glow and the glitter amount. This replaces D-41's timed "subtle glint".
- **Decision:** a soft light over the body moves opposite to the tilt (roll absolute; pitch relative to the usual holding angle, adapting over ~4 s), from the gravity sensor (smoothed accelerometer as fallback), remapped to screen rotation. Lit flakes are split into three facing groups that brighten and fade as the tilt turns them to the light; the best-facing group flares additively under it. Settings: Glitter glow (level), Follow tilt (On/Off), with Glitter amount as before. The sensor listens only while resumed and needed; redraws happen only when the tilt changes. Spec: `architecture/PODIUM_CUSTOMIZATION.md` §6.
- **Options considered:** rotation-vector sensor (more power, needs a gyroscope; gravity is enough for a light direction); per-flake simulation (too costly); a continuous frame loop (would keep the GPU awake while still).
- **Tradeoffs:** three facing groups are a stylisation of real flakes; the effect can't be judged in Robolectric renders beyond direction and brightness — device step 2.3 judges the feel.

### D-45 · The Wheel becomes a keyboard
- **Context:** user direction (2026-10-06): when typing is needed, the device should have its own keyboard. The Wheel opens up and transforms into a virtual keyboard; a close key transforms it back, with an animation that fits the device; a setting chooses this keyboard or the phone's.
- **Decision:** `PodiumTextField` (core:designsystem) is the one text field. With the Podium keyboard (Settings ▸ Keyboard: Podium, the default), focusing a field never summons the phone's keyboard. Instead the shell's `KeyboardHost` gets a session, and `WheelKeyboard`, which sits where the Wheel is, morphs: the Wheel unwinds (turns up to 150°, shrinks, fades), the piece of the body that holds it opens from a circle into a wide rounded panel in the ring's own material (glass for Glass), and the keys rise from the middle outwards. The close key, Back, or leaving the screen runs it backwards. The power button and lights step aside while it's open. Keys: four rows (letters; numbers and symbols; a hex page for colour codes), Shift once or twice for caps lock, Delete repeating while held, Space, the field's action word (Search, Done) and close. Each key gives the Wheel's detent haptic. Keyboard style is chosen with Settings ▸ Keyboard (Podium/Phone), so the phone's keyboard stays available for other languages, voice input and autocorrect. Where the Wheel's column is narrower than 300 dp (landscape) or in a miniature, fields fall back to the phone's keyboard. Spec: `interaction-model.md` §2.1.
- **Options considered:** an `InputMethodService` (a system keyboard: needs the listener to enable it system-wide, and would be offered to other apps); drawing the keyboard over the display (hides the results being searched and breaks D-26's screen/Wheel split); typing with the Wheel by turning through letters (the classic way, but slow for search; can be added as a Wheel mode later).
- **Tradeoffs / limits:** English QWERTY only, no autocorrect, no suggestions, no voice; the caret stays at the end (no cursor moving or selection); TalkBack users can still type with keys or set the text directly. A hardware keyboard types through the field as usual only with the Phone setting.

### D-46 · A long focused title rests, then eases into its scroll
- **Context:** user report (2026-10-06): at the top of a list the text "is centred for a second, then suddenly aligns to the side". Rendering the whole app frame by frame (the new `AppShellTest` harness) found it: Music's first row was the access prompt "Allow access to music on this phone", too long for the paper column. Focused, its clipped text sat in the middle of the lens; after 0.9 s `basicMarquee` started moving it left at full speed. The same happened to any long focused title.
- **Decision:** the scrolling title is Podium's own, not `basicMarquee`. It rests 1.4 s at its start, then eases in and out (sine-like; ~28 dp/s on average) as the next copy arrives where it began, rests 2 s, and repeats. Titles that fit are drawn untouched (the old marquee also faded the last letters of a title that fit). Calls to action are written to fit: the access row now reads "Allow music access". Album and artist lists, and Songs, no longer flash a centred "No albums yet" before the library's first sync (or in the beat before the database re-reads a sync), which also read as text jumping from the middle to the side.
- **Supersedes:** design-system §3.3 "scrolls after 0.9 s".
- **Tests:** `ScrollingTitleTest` (rests ≥ 1.3 s, gathers speed, never jumps; a title that fits never moves), `AppShellTest` (boot → Home → Music → Albums → back; the Music list holds still), `DatabaseLibraryRepositoryTest` (albums and artists never say none before the first sync).

### D-47 · Music access is asked for, not hidden
- **Context:** user report (2026-10-06): "no permission screen is opening up for storage". Podium declared `READ_MEDIA_AUDIO` but asked only when the listener found the row at the top of Music; and if Android had stopped showing the question (two refusals, or "don't ask again"), pressing that row did nothing at all.
- **Decision:** Podium asks once by itself, as soon as it first switches on (after the startup screen). Without access, Home leads with "Allow music access" as Music does. A refusal Android won't ask about again is remembered (`PermissionAsks`); then the row opens Podium's page in the system settings. If a press gets an instant refusal with no question shown (Android had already stopped asking before this version), the settings page opens at once. Empty library lists say which is true: access is missing ("Allow music access from Home…") or there's no music in the folders read.
- **Options considered:** a Podium explanation screen before Android's question (Android's own wording already says "music and audio"; one fewer step); asking every launch (nagging; Android itself stops after two refusals).

