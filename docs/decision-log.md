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
| [ADR-013](adr/ADR-013-provider-source-architecture.md) | Capability-faceted sources; `PlaybackTarget` = DirectStream; identity-preserving fallback; `Basis` | Superseded in part by D-48 (stream-unlock boundary revoked; YouTube Music plays via DirectStream) |
| [ADR-014](adr/ADR-014-bitchord-inspired-independent-implementation.md) | BitChord as architectural and implementation reference | Superseded by D-48 (clean-room restrictions revoked) |

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
- **Status: Superseded by D-48.** Originally framed options Y0–Y3 with direct audio (Y3) excluded under ADR-013. Superseded by D-48, which adopts YouTube Music as the sole online provider with direct in-app streaming in Media3 for Podium's sideloaded personal player.

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
- **Status: Superseded in part by D-48.** The playback delegation model (P2) to the official app and the prohibition against in-app playback (Y3) are superseded by D-48. D-48 establishes direct in-app streaming via Media3 comparable to BitChord for the sideloaded personal player. The single online provider scope, catalogue, account web session, and library facets established here remain active.
- **Context:** user direction (2026-10-06, "execution mission"): make Online a working YouTube Music experience — search, home, albums, artists, playlists, library, likes, history, radio, account — while Offline stays intact; retire Audius/OpenSubsonic and the multi-source machinery carefully.
- **Decision:**
  - **One online service.** `sources:youtubemusic` (pure JVM) is the only ONLINE source; `sources:audius`, `sources:subsonic`, configured sources and `MultiSourceCatalog` are removed; stored credentials of retired servers are deleted at startup (`RetiredSources`). The online UI stays provider-free.
  - **Catalogue** from the music web client's browsing endpoints (`search`, `browse`, `next`), `Basis.UNOFFICIAL_API`, page config read at runtime, parsers that fail soft.
  - **Account (A2):** the service's own sign-in page in a locked-down WebView; only the session cookies are kept, sealed by the Keystore credential store; the database sees only a hashed account key. Expiry is detected and reported; sign-out wipes the account's online data.
  - **Playback (Historical — superseded by D-48):** D-38 originally delegated playback to the official YouTube Music app (P2). Superseded by D-48, which specifies direct in-app playback via Media3 (`DirectStream`).
  - **Model:** `MediaKind` on tracks (schema v5, additive); `AccountLibraryFacet`, `AuthFacet.webSignIn`, `RemoteContext`, `RemotePlayback` in the provider-neutral APIs.

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

### D-48 · Authoritative YouTube Music Direction: Sole Online Provider with Direct Playback
- **Context:** user direction (2026-10-06, "Repository Documentation Reset — Podium YouTube Music Direction"): Podium is a sideloaded personal Android music player. Older architectural decisions avoiding BitChord's implementation approach (the "clean-room" hygiene rules of ADR-014, the "stream-unlock boundary" of ADR-013, and the ban on direct YouTube audio / Y3) are no longer the product direction.
- **Decision:**
  1. **Offline/local playback is stable:** existing offline/local music playback (MediaStore, Room, Media3 `PlaybackService`, `QueueManager`, iPod interaction model, and Liquid Glass / Carbon / Bone UI) is stable and must not be rewritten as part of online changes.
  2. **YouTube Music is the sole online provider:** online music is redesigned exclusively around YouTube Music to provide an experience comparable to BitChord:
     - Full YouTube Music catalogue and search (songs, videos, albums, artists, playlists)
     - Albums, artists, playlists
     - User's library (albums, artists, playlists)
     - Liked music (two-way sync with the account)
     - Listening history (account history where technically supported + local history)
     - User authentication/session (web session sealed at rest)
     - **Direct in-app playback via Media3**
     - **Background playback where technically supported**
  3. **BitChord as architectural & implementation reference:** BitChord may be studied directly as an architectural and implementation reference.
  4. **Practical, viable architecture over clean-room:** Podium uses the most practical technically viable architecture (drawing on proven extraction techniques, cipher deobfuscation, and token minting) rather than enforcing obsolete clean-room or stream-unlock restrictions.
  5. **Licensing:** Factual licensing notices are preserved; third-party licenses (e.g. GPL-3.0 for BitChord, NewPipeExtractor, InnerTubeX) are acknowledged factually.
- **Supersedes:** D-20, D-38 playback delegation (P2), ADR-013 stream-unlock boundary, ADR-014 clean-room restrictions.

### D-49 · The paper list follows its lens; the previous column dissolves; the background picture decodes
- **Context:** user report (2026-10-06): the top of a list "suddenly pops left" as a menu opens; on a long list the indicator "freaks out trying to follow the highlight" when the Wheel turns a bit fast; a custom display background always says "Unavailable"; the previous column's glimpse is too sharp at its right edge and corners.
- **Findings** (device recordings read frame by frame, a diagnostic log of the row geometry):
  - Opening Songs, the first row was drawn unbent for the whole 600 ms move along the paper and jumped about 40 dp left — its offset along the arc — when the move ended. Its layer's translation was computed correctly from the first frames but wasn't reflected on screen until then; the rows below were.
  - The lens slid on its spring while the list scrolled by an animation of its own, restarted from rest at every detent and replaced by an instant jump for any move over 1.5 rows (every accelerated detent). The two diverged and the indicator bounced around the middle.
  - `BitmapFactory` returns null when it only reads a picture's size; the decoder took that null for a failure, so every picture was "Unavailable".
  - The glimpse was clipped to a rounded rectangle.
- **Decision:**
  - A row's bend along the arc is applied as a placement offset; fade, scale and softening stay in the layer.
  - On the paper the list follows the lens: the lens's own animation frames scroll the list (`FocusListState.centreLensNow`, before layout), so mid-list the lens and its indicator hold still at the middle while the rows move, and only an end of the list lets the lens travel. Moves of more than three rows still jump, lens and list together. Plain lists keep their own glide.
  - The size probe's null is expected. The photo's EXIF orientation is applied, so a portrait photo stands upright.
  - The previous column's tile feathers out with an eased mask (22 dp at the right, about 18 dp at the top and bottom, at the left only when the tile sits wholly on the display), with no hard clip.
- **Tests:** `PaperLensFollowTest` (wherever the lens is between two rows, the list puts it at the middle; the ends clamp; a jump far along the list; a two-rows-a-frame spin scrolls one way, keeps the focused row in view and settles at the middle), `DisplayImagesTest` (decoded and downsampled, a sideways photo stands upright, a missing picture is reported); `FocusListGeometryTest` passes unchanged.
- **Device:** Nothing Phone (3a), test-tone library: Music ▸ Songs three times with no jump; fast spins on Songs with the indicator steady; the glimpse's edges soft. Acceleration (lists of 50 or more) is covered by tests only.

### D-50 · Lyrics that follow the music, whatever the provider wrote; faces that look different
- **Context:** user direction (2026-10-07): "sometimes the lyrics doesn't move … format the lyrics automatically so that it works well"; "add more fonts … italics, Times New Roman … gothic fonts for the lyrics screen … cursive fonts too".
- **Decision (lyrics):** `LyricsFormatter` shapes every answer for the screen (LYRICS_ARCHITECTURE.md §5.2): usable synced lyrics kept as they are; shared stamps spread, overrunning stamps fitted, labels made gaps, over-long lines split at phrases; stamps that say nothing, and plain lyrics, paced across the song by line length with a lead-in and outro — marked `estimated`, said so in the details overlay, the Wheel still reading ahead. LRCLIB lookups prefer the same song with times over an exact match without them; the lyrics cache key is versioned (`v2`).
- **Supersedes:** D-39's "plain without fake timing", and the screen's "plain lyrics never pretend to be synced": they now follow the music with an estimate that says so. Without the song's length, plain lyrics stay plain.
- **Decision (fonts):** eleven more display faces — Italic (Instrument Sans Italic), Times and Times italic (Tinos, Times New Roman's metric twin; Times New Roman itself can't be bundled), Elegant and Elegant italic (Playfair Display), Typewriter (Courier Prime), Rounded (Nunito), Handwritten (Caveat), Script (Dancing Script), Bubbly (Pacifico), Gothic (Grenze Gotisch) — and a separate **Lyrics font** with those plus faces that only read at a lyric's size: Typewriter italic, Calligraphy (Great Vibes), French script (Parisienne), Loopy (Sacramento), Fraktur (UnifrakturMaguntia), Pirata (Pirata One), Jacquard (Jacquard 24). All SIL OFL 1.1, bundled unmodified, downloaded once from the official Google Fonts repository with the listener's approval (third_party/FONTS.md); coverage tables from their cmaps; whole-string fallback to Inter as before.
- **Tradeoffs:** about 5 MB more APK; paced timing drifts from the singing (it's an estimate, and labelled one).
- **Tests:** `LyricsFormatterTest` (kept, paced, stuck stamps, shared stamps, overrun, long lines, labels), `LrclibProviderTest` (an exact plain match gives way to a timed upload; falls back to it), `VirtualDisplayTest` (every new face sets its own strings, falls back whole; the lyrics font follows the display or its own face).

### D-51 · The colour picker shows the whole palette; saturation and brightness bars
- **Context:** user direction (2026-10-07): the background colour picker "should circulate in the whole colour palette and also show that to the user. and give a saturation slider". The old picker turned only the hue of the current colour in OKLCH, keeping its chroma — a greyish colour (the default slate) barely changed, and nothing showed where it was.
- **Decision:** both colour editors (device finish and display background) show three bars — Hue (the full spectrum, a marker at the colour's hue, wrapping all the way round), Saturation (grey to full colour at that hue) and Brightness (black to full) — in HSV, so every colour is reachable. The Wheel turns the marked bar (hue 4° a detent, the others 2 %); skip forward and back choose the bar; touch sets a bar directly; hex entry stays; Center keeps the colour. Each bar is a slider to accessibility services.
- **Tests:** `ColorPickerTest` (round trips, a full turn of the hue passes every sixth of the palette even from a greyish colour, ends stop).

### D-52 · The status bar says where the music is heard
- **Context:** user direction (2026-10-07): "in the status bar, where the play pause icon is … add an icon if mute, bluetooth, or phone speaker".
- **Decision:** the virtual display's header shows an output glyph left of the play state: muted (media volume muted or at zero) outranks the route; otherwise Bluetooth, headphones (wired or USB) or the phone speaker; nothing for other outputs (HDMI, casting). `AudioOutputMonitor` asks the system which device media plays to (Android 13+; before that, what's connected), and updates on device changes, volume and mute broadcasts, and every 4 s while Podium is in front (a route switched in the system's output picker sends no event). Headphones come from the symbol font; the Bluetooth rune, the phone and the muted speaker — not in the bundled symbol subset — are drawn with the same stroke, rather than downloading the full symbol font.
- **Tests:** `AudioOutputTest` (device types to routes). Device: Nothing Phone (3a) shows "Muted" with media volume at zero.

### D-53 · Online playback that waits for real work, survives a stale URL, and finds albums and artist radio
- **Context:** user direction (2026-10-07): fix the timeouts, the expired-URL retry, the weak spots of the in-app YouTube Music playback (D-48), search showing only songs, and radio mostly not working.
- **Findings:** each source had 6 s to resolve a song, and a timeout counted as an infrastructure failure — three in a row benched the online source (search too) for 30 s, 2 min, then 10 min; the blocked extraction carried on unseen and its result was thrown away. A stream URL refused mid-play (HTTP 403 after a long pause) was classed "sign in again" and benched the source until the listener signed in again; the same-source refresh existed but nothing called it. Search listed 20 songs before any album, and the mixed answer holds only ~3 albums. The artist page's Mix button moved to a song-and-playlist endpoint in 2026, so artist radio found no playlist.
- **Decision:**
  - `PlaybackFacet.resolveTimeoutMillis` lets a source say it needs longer (YouTube Music: 20 s; the player's own wait 25 s). The YouTube resolver runs each extraction in its own scope — a caller that stops waiting doesn't cancel it — shares one extraction per song, runs at most two at once, and keeps results five minutes (never past the URL's expiry).
  - A stream refused with 401/403/404/410 is asked for afresh from the same source, once, and playback resumes where it was (`PlaybackFacet.forgetStream` drops the refused URL first). A refusal is never "sign in again" and never benches the source: a second refusal is that song (`NotPlayable`, per item) and the queue moves on.
  - The extractor's localization is (language, country) the right way round, with the content country set; bitrates are kbps whichever figure the extractor has.
  - Nothing is handed to the official app any more: the source no longer offers remote playback or remote contexts, Settings ▸ Online service drops the app, media-controls and "after choosing a song" rows when the service's songs play in Podium.
  - Search shows five songs, "More songs" (the rest, then further pages), then albums, artists, playlists, videos. Albums come from the album-only search — or, when the query is an artist's name, from that artist's own page (the album-only search answers with tributes and covers).
  - Artist radio reads the Mix button's song-and-playlist endpoint (and its parameters), and falls back to a radio from the artist's best-known song.
  - The live-network resolver test runs only with `PODIUM_LIVE_NETWORK=1`.
- **Not changed:** how the extractor obtains streams (outside what Podium's own code decides).
- **Tests:** `StreamRefreshTest` (a refused URL is refreshed from the same source and the song carries on; a second refusal is per item, never a sign-in, the source stays usable — served by a local HTTP server in the test), `YouTubeMusicStreamResolverTest` (shared extraction, a caller's timeout doesn't lose it, a refused stream is forgotten, kept results age out, kbps), `YouTubeMusicSourceTest` (an artist's search lists their own albums and singles; no remote playback), `YouTubeMusicParserTest` (the 2026 Mix button).
- **Device:** Nothing Phone (3a): "the weeknd" lists five songs, then The Highlights, Dawn FM, After Hours, Starboy, Beauty Behind The Madness, Kiss Land…, then artists and playlists. Streaming itself wasn't exercised on the device in this pass.

### D-54 · The physical Podium: pinch it out into its own space
- **Context:** user direction (2026-10-07, Phase 2): a two-finger pinch turns the flat app into an object in a dark, sparsely starred space, front first; nothing about playback changes.
- **Decision:** `PodiumSpace` (app/space) wraps the whole device in one `graphicsLayer` (scale, a slight perspective turn, a drawn slab edge and shadow); `PodiumSpaceState` owns `SpacePhase` (NORMAL → ENTERING → PHYSICAL ⇄ STICKER_EDITING → RETURNING) with `depth`, `zoom` and `edit` animatables, never persisted. The pinch is recognised in the Initial pass at the top and consumed once it's clearly a pinch; the Wheel lets go of a finger whose change an ancestor consumed. Spread, Back, Return to Podium or a tap on the close Podium return. Settings ▸ Podium body opens it without the gesture. See architecture/PHYSICAL_SPACE.md.
- **Tests:** `PodiumSpaceTest` (a pinch enters, one finger never does, a pinch over the Wheel doesn't turn it, a small pinch springs back, Return).

### D-55 · Stickers: cut out on the phone, stuck anywhere on the Podium
- **Decision:** a sticker is made from a picture chosen in the system photo picker. Google's MediaPipe MagicTouch point-to-mask model (Apache-2.0, third_party/MODELS.md) runs on the bare LiteRT 2.3.0 runtime: no MediaPipe Tasks or ML Kit (both bring telemetry), nothing leaves the phone. The picture's centre is chosen first; a tap chooses another subject; Add and Erase brushes (round on the picture, whatever its shape) refine the edge with undo and redo. The border (off, black, white; fine to bold) follows the cut-out's own outline (a chamfer distance-transform dilation) with a faint shadow, previewed on neutral grey. `StickerStore` keeps `<id>.png` (border baked in, ≤ 640 px) and `<id>.cut.png`, and `stickers.json` (stickers and placements: centre as fractions of the object, scale of its width, rotation, z), written atomically off the main thread; a damaged index starts empty. `StickerLayer` draws placements in the object's coordinates and never takes touches; arranging (STICKER_EDITING) chooses, moves (one finger), resizes and turns (two fingers), raises and takes off.
- **Tests:** `StickerStoreTest` (kept with border and thickness; moved, turned, resized after a restart; bounds; several stickers and z order; take off vs delete; damaged index; decode size and cache), `StickerArtTest` (the cut, nothing kept, the border follows the outline in colour and thickness, dilation is round, the brush, undo/redo, hit testing turned stickers).

### D-56 · Outside the Podium: one settings page, large by default; Turn off Podium
- **Context:** user direction (2026-10-07): the outside page holds only what isn't the Podium's own — stickers (as a gallery, with adding more), Help, and turning the app off; the Podium's look stays in its own Settings, reached from there ("Podium body").
- **Decision:** every visit opens with the page large (60 % of the width, full height) and the Podium small beside it; a switch at the bottom, Podium | Settings, brings either close (`SpaceFocus`, `zoom`): the Podium large and the page small in its corner, or back. Personalize is gone from the outside page. **Turn off Podium** pauses the music at once, darkens the window with "Goodbye", then writes the stickers, releases the controller, removes the task, stops the playback service and ends the process 1.2 s later (the service saves its position as it goes). Labels say Podium, never another product's name.
- **Tests:** `PodiumSpaceTest` (opens large every time, the switch both ways, a tap on the small Podium brings it close and a tap when close returns, the gallery lists every sticker, Turn off asks the app to close).

### D-57 · The tour: a model of the Podium, not the Podium
- **Context:** user direction (2026-10-07): the first-launch tour plays like a short film — the camera closes in on the part in question and shows how — in checkpoints, on a model rather than the real device.
- **Decision:** `GuideTour` covers the window: a drawn model in the listener's finish on a dark stage; per checkpoint (Turn the Wheel, Press the center, Menu, Play and pause, Step outside, Make it yours) the camera moves slowly to its shot with a spotlight, a ghost finger loops the gesture, and the words sit beneath. The model can be tried by hand — a turn of 150°, a press on the right part, two fingers together, a tap on a sticker — which passes the checkpoint and moves on after a beat; Next, Back and Skip are always there. Finishing or skipping is remembered (`GuideStore`); Help replays it. Reduced motion holds one telling frame per checkpoint.
- **Tests:** `GuideTest` (offered once, checkpoints in order and passed only while showing, Back stops at the first, replay), `GuideTourTest` (each shot renders; turning the model's Wheel, pressing its center, pinching it pass).

### D-58 · Now Playing's cover leans to the right
- **Context:** user direction (2026-10-07), with a sketch: the cover a little turned, its near edge at the left.
- **Decision:** the artwork stage turns 26° about its vertical axis, hinged at its left edge, seen from close (camera distance 6), in every theme; the breathing scale stays centred. Covers still slide out left and in from the right.

### D-59 · The previous column's glimpse has no backing
- **Context:** user direction (2026-10-07): the glimpse of the previous screen showed an opaque tile over a background picture.
- **Decision:** the glimpse draws no background of its own: the display (colour or picture) shows through.
- **Tried and withdrawn:** a motion blur on columns moving along the paper. On the device it lingered on the menus for a second or two after the move; the listener asked for it gone (D-60 instead).
- **Lyrics drift (open):** lyrics that ran ahead after leaving the app coincided on the device with a stream refused mid-song (HTTP 403) and refreshed (D-53). The lyrics screen now follows afresh whenever it's shown again and when the player's status changes, and logs (tag `PodiumLyrics`) any time the position moves more than 1.2 s off the clock; the engine logs where it resumes after a refresh. Root cause not confirmed yet.

### D-60 · One smooth curve for moves along the paper
- **Context:** user direction (2026-10-07): instead of the blur, "a smoother animation curve, like Apple's or ColorOS's".
- **Finding:** each move along the paper ran in two eased halves (keyframes with FastOutSlowIn per segment), so it slowed almost to a stop at the midpoint of its arc and picked up again.
- **Decision:** `PodiumMotion.Smooth` = cubic-bezier(0.3, 0, 0.1, 1): sets off gently, keeps going, settles with a long soft finish. A column's whole move — its arc (a quadratic curve through the old midpoint, sampled at 30 eased times), its scale and its fade — follows that one curve, over 560 ms. Reduced motion still cross-fades.

### D-61 · A column lands where its glimpse shows it
- **Context:** user direction (2026-10-07, with a screen recording): the previews on the left and right sat lower than where a column's move ended, so they seemed to pop into place after it.
- **Finding:** the left glimpse shows the previous column with the item that led on centred in its box; the move shrank the live column with its own scroll, so a lit row near the top (Home's Music) ended well above where the glimpse then showed it. On the right, the whole column was fitted to the box's height, its rows high in it, while the tile appears centred.
- **Decision:** every paper list records where its lit row sits (`PaperLenses`, by the column's key; never from a miniature). A move into or out of the left box offsets the column by (box middle − lit row) × peek scale; into or out of the right box by (column middle − lit row) × mini scale — the lit row lands on the box's middle, exactly where the preview takes over. A column not seen before is taken to light its first row. The column leaving is whichever of the two last in front isn't in front now, as the navigation sets a move up just after the change.
- **Device:** Nothing Phone (3a): Home ▸ Music, the Music row lands where the glimpse shows it; back, Cover Flow lands on the right box's middle.

### D-62 · Glimpses arrive soft, then sharpen
- **Context:** user direction (2026-10-07): going back, the right preview replaced the column sinking into it abruptly; "a soft blur" instead.
- **Decision:** both glimpses come in over the move's last 280 ms and beyond (fade 380 ms on `PodiumMotion.Smooth`), blurred 8 dp at first and sharp as they settle (`Modifier.softArrival`, core:designsystem, Android 12+), overlapping the column leaving for their box. The blur follows the glimpse's own enter state, so at rest it's exactly none — unlike the withdrawn motion blur (D-59).

### D-63 · A column shrinks to exactly the glimpse's size
- **Context:** user direction (2026-10-07, with a screenshot): the column leaving for the left box was still larger than the glimpse there, so it seemed to shrink suddenly at the end.
- **Finding:** the glimpse is the previous screen at peek scale and then a step away (0.94, about the box's middle); the move aimed at peek scale alone, and the glimpse began arriving halfway through, while the column was still near 0.6.
- **Decision:** `PaperGeometry.glimpseScale` and `glimpseOriginX/Y` describe the glimpse exactly as drawn (`Paper.DistantScale` is shared with `distant()`); moves into and out of the left box aim at them, lit row included (D-61). The glimpse arrives from 62 % of the move (300 ms), the column fades from 70 %.

### D-64 · The column leaving melts into its box
- **Context:** user direction (2026-10-07): "for the swap too, a smooth blur transition at the end".
- **Decision:** `Modifier.softDeparture` (core:designsystem, Android 12+) blurs a column as it leaves, growing to 7 dp (in its own size) over the move's last 45 %, while the glimpse or tile in that box arrives soft and sharpens (D-62). Driven by the column's own exit state; a column arriving is never blurred, so nothing stays soft after a move.
- **Device:** Nothing Phone (3a): Home ▸ Music and back — the sizes meet, the hand-over melts both ways, both menus sharp at rest.


### D-65 · One motion language, and nothing waits for it
- **Context:** user direction (2026-10-08, "motion design & animation audit"): the UI looks right, but motion must feel instantly responsive and physical — a small engineered device, not an app with animations; smooth, never slow; nothing may wait for an animation; gestures must follow the fingers. Audit: [research/2026-10-08-motion-audit.md](research/2026-10-08-motion-audit.md).
- **Findings (Nothing Phone (3a), 120 Hz):** the paper move (D-60's curve, 560 ms) had moved 4 % after 50 ms — the column sat still for five frames after the press, then glided; the cover's skip likewise (FastOutSlowIn, 460 ms). After releasing Center the first frame of a move came 111 ms later (79 ms going back): two long main-thread frames composing the next column. Idle on Home the app redrew ~94 times a second at 19 ms of GPU each: the charging light breathed at the display's rate and every frame re-rasterised the body (gradients, grain and six full-window glitter passes). The Wheel recomposed on every press and every detent; the lens used the same soft spring during spins and swam; the pinch jumped from flat to ~0.37 deep the moment it was recognised and its release dropped the fingers' speed; sticker moves went through a flow and a recomposition; a slow pinch on a row or on the Wheel could fire the row's or the Wheel's hold first (the phone holds at ~300 ms).
- **Decision:** a motion language of seven kinds — TACTILE, MECHANICAL, FOCUS, SPATIAL, CONTENT, DRAMATIC, PERSONAL — each with its own tokens in `PodiumMotion` (animation-system.md §2), and:
  - SPATIAL = cubic-bezier(0.2, 0.7, 0.2, 1) over 420 ms for the paper move, its title and the cover's skip (360 ms): it leaves on the frame of the press and keeps D-60's long soft finish. Glimpses, the departure blur and the leaving column's fade re-timed to where the column now is.
  - TACTILE presses everywhere from one modifier (`pressFeedback` / `KeyTravel`): down on the frame the finger lands, up with a whisper of spring; read in the draw phase, never recomposing.
  - The Wheel's knurled band turns 1:1 with the finger and clicks into the nearest detent on lift; the lens locks on with `focusFast` when detents come < 90 ms apart; only the two rows whose light changes recompose.
  - Now Playing: the cover settles into its place and lean (DRAMATIC) as the screen arrives (`LocalColumnTransition`). Context menus arrive with a 0.96 → 1 pop, interactive at once, and leave at once.
  - The space: the pinch follows from where the object is, both ways; the spread follows too; release carries the fingers' velocity (a quick short flick goes in; fingers that stopped carry none); the settings page swings in under the object's camera; the stars cache their shapes, gain a slight parallax and stand still under reduced motion.
  - Stickers are moved through a live placement read in the draw phase and stored once on lift; a held sticker lifts a hair.
  - Holds (row long-press, Wheel holds) don't fire while two fingers are down (`LocalSeveralFingers`).
  - Performance: the body is drawn into its own cached layer; the charging light steps ~20 times a second and only while it breathes; glitter tilt, Cover Flow, the keyboard's keys and Now Playing's action lens read their animated values in the draw phase. Reduced motion is followed live.
- **Supersedes:** D-60's curve and duration (its one-curve, never-pausing arc stays); the 560 ms move.
- **Measured (same scripts, before → after; the after build also non-debuggable, D-67):** release → first motion 111 → 56 ms forward, 79 → 33 ms back; navigation frames p50/p90/p99 22/36/150 → 19/30/73 ms; spins 17/38/53 → 15/34/42 ms; idle on Home 94 → 39 frames a second at 19 → 9 ms GPU. Details in performance.md §6.
- **Tests:** `PodiumSpaceTest` (no jump when the pinch is recognised and reversing reverses; a quick short pinch goes in; fingers that stopped carry no speed; a spread follows back; no hold under two fingers), `StickerEditorTest` (drawn at once, stored on lift), `WheelGestureTrackerTest` (travelled angle across 12 o'clock both ways). Device: `MotionProbe` (androidTest, real two-finger touches on the phone; testing/UI_DEVICE_ACCEPTANCE.md §7).

### D-66 · The right box hands over frame to frame
- **Context:** user report (2026-10-08): "the left and the right previews aren't the same size and in the same position as the left slide and right slide animations when they are at the very end, causing them to snap in place".
- **Findings** (device recordings, frame by frame): the left box matched (D-61/D-63: the column ends exactly at the glimpse's size and place, its lit row where the glimpse shows it). The right box didn't: a column sinking into it ended at the box's full height, its left edge on the box's, its lit row on the box's middle — hanging below and beside the preview tile, which is drawn a step away (0.94) and centred. The tile shows the next column's artwork, not the column, so the lit row can't match anything in it.
- **Decision:** a column growing out of the right box or sinking into it starts or ends in exactly the tile's frame (`PaperGeometry.tileScale`, `tileLeft`: the tile's height, centred on the box's middle, left edges together — past the display's edge both run off together) and melts into the artwork there. Supersedes D-61's right-box half (the lit row on the box's middle); the left box keeps it.
- **Device:** Nothing Phone (3a), Music ▸ back to Home: the column shrinks into the tile's frame and the cover fades in over it in the same place and size.

### D-67 · The phone runs a build that isn't debuggable
- **Context:** user direction (2026-10-08): "why is cold boot and warm boot a thing. it should feel smooth every time no matter when I open the app".
- **Finding:** the daily build (`debug`, app.podium.debug) was debuggable. Android never compiles a debuggable app ahead of time, and its JIT-compiled code dies with its process, so every fresh open ran cold until it warmed up. Same code, same phone: debuggable and warm, navigation p90/p99 44/133 ms; not debuggable and compiled, 28/61 ms — and the same on the first run as the tenth.
- **Decision:** the `debug` build type is not debuggable by default (`-Ppodium.debuggable=true` for a debugger). Same package and signature — the listener's data stays; debug commands and the test tones unchanged. Compose's own baseline profiles reach ART through profileinstaller; the phone compiles the app when idle, or at once with `adb shell cmd package compile -m speed-profile -f app.podium.debug` after an install (deployment.md §3). `BuildConfig.DEBUG` follows the flag, so the playback engine's debug-only mirror assertion no longer runs on the phone. Lint's `InvalidFragmentVersionForActivityResult` (now run on the build) is disabled: both activities are `ComponentActivity`; the old fragment library only arrives transitively.
- **Open:** a project-generated baseline profile (Macrobenchmark journeys, performance.md §3) would make the first run after an install as fast as a compiled one.

### D-68 · Double ⏯ opens Now Playing
- **Context:** user direction (2026-10-08): double-pressing play/pause on the Wheel should open Now Playing from wherever the listener is.
- **Decision:**
  - Two ⏯ presses within 300 ms (Android's double-tap timeout; `DoublePress` in `core:interaction`) open Now Playing from any screen. An open menu is closed first. A third quick press starts afresh.
  - Every press still toggles the moment it lands, so ⏯ never waits to find out whether a second press is coming. The double press's two toggles cancel out, and the music ends as it was.
  - Nothing opens when nothing is loaded. On Now Playing itself, the two presses just toggle.
- **Options:** (a) hold every ⏯ for the double-press window before acting, as headphone remotes do: rejected, because a response that waits is wrong for Podium (D-65); (b) let the double press keep the first toggle: rejected, because opening Now Playing shouldn't change the music; (c) **toggle at once and let the second toggle undo the first** (chosen).
- **Tradeoff:** from playing, a double press leaves a gap as long as the two presses are apart, under 300 ms. From paused, the same length of music sounds before it pauses again.

### D-69 · Open source, signed releases, no APKs in git
- **Context:** user direction (2026-10-08): publish Podium on GitHub after a security review, with the newest APK as the latest release and the website on GitHub Pages; remove the old APKs and the personal email from history.
- **Decision:**
  - Podium is licensed **GPL-3.0** (`LICENSE`, the canonical text). It has to be: `sources:youtubemusic` links NewPipeExtractor (GPL-3.0), so any APK handed out carries GPL obligations, and an unlicensed repository would contradict them.
  - What people download is the **release** build (`app.podium`, minified, debug commands stubbed, no test tones), signed with a release key kept outside git (deployment.md §5). The debug build, whose debug commands any app on the phone can send, is never published.
  - APKs are **never committed**: they're GitHub Release assets named `podium.apk` (deployment.md §7a). History was rewritten to drop the six committed debug APKs and to replace the personal commit email with the GitHub no-reply address; a bundle of the old history was kept outside the repository first.
  - R8 keeps NewPipeExtractor and Rhino whole (they're reflective), LiteRT's JNI classes, and every Room database with its generated `_Impl` (Room finds it by name; without the rule the release build crashed at start in WorkManager's `WorkDatabase`). Checked on the Nothing Phone (3a): the release build starts, runs the tour, Home and Online, and loads online artwork, with no crash.
  - The website (`site/`) deploys to GitHub Pages through `.github/workflows/pages.yml`; promotional video renders (`artifacts/ad/`, one with a commercial track) are ignored.
- **Tradeoff:** rewriting history changes every commit id, so other clones must re-clone or reset to the new `origin`.

### D-70 · Vercel hosts the website; GitHub serves the source and APKs
- **Context:** user direction (2026-10-08): the public website should move from GitHub Pages to
  Vercel, while the app download and source view remain on GitHub.
- **Decision:** `vercel.json` serves the static `site/` directory with no build step. Vercel's
  GitHub integration deploys `main` to production and pull requests as previews; the GitHub Pages
  workflow is removed. The Vercel production deployment is
  `https://podium-music-player.vercel.app/`; the existing GitHub Pages deployment is unpublished
  once it has been verified. The landing page's Download button stays on the stable GitHub Release
  asset URL (`releases/latest/download/podium.apk`), and View on GitHub stays on the repository.
  APKs are never put in Vercel deployment output.
- **Tradeoff:** the Vercel subdomain is convenient but project-owned. A custom domain can replace
  it later, provided the root README link is updated in the same change.
- **Supersedes:** D-69's GitHub Pages hosting bullet only. Its release-signing and GitHub Release
  decisions remain in force.

### D-71 · The boot shows the wordmark, and the chord grows
- **Context:** user direction (2026-10-08): on the boot screen, replace the drawn ring with its blue
  centre with the website's bold PODIUM wordmark, white or slightly silver with static glitter all
  over; make the progress bar a little broader; give the startup chord more bass and depth and a
  slight electric guitar chord, so it feels grand.
- **Decision:**
  - The boot's second stage draws "PODIUM" in Archivo at weight 900 and width 125, the website's
    wordmark, about three quarters of the screen wide and never scaled by the font-size setting. It
    is silver (white at the top of the letters, a soft silver at their feet) with fine flakes set
    into the letters, bright and dark, from the body glitter's seeded field. They hold still.
  - It is a logotype, so it is the one place besides the Wheel's `MENU` drawn in capitals.
  - The ring, its blue centre and the small "Podium" title under them are gone. The boot screen has
    no blue now.
  - The progress bar is 58% of the screen wide (at most 220 dp) and 7 dp tall, up from 132 by 6 dp.
  - The chord is rebuilt by `tools/sounds/boot_chord.py`, still an original synthesized sound with
    no samples. It keeps the strummed C major 9 and adds a soft low impact, a sub-octave C, gently
    saturated low strings (so a phone speaker that can't play the sub still sounds full), and a
    Cadd9 strum on lightly overdriven plucked strings just behind the chord. It is 5 s long and
    about 5 dB louder on average than before, and peaks at -1 dBFS.
  - The Archivo face is a static instance subset to printable ASCII (`third_party/FONTS.md`), about
    29 KB.
- **Tradeoff:** the chord is longer and louder than before. It still plays as a UI sound, so the
  system-sounds volume, silent mode and Settings ▸ Startup sound all still govern it.

### D-72 · Persistent YouTube Music session and cold-start startup fix
- **Context:** Following the initial YouTube Music sign-in integration, signed-in users experienced
  a startup crash upon app launch or force-stop recreation (`NullPointerException` on `likesFetchedAt`),
  along with potential startup ANRs from blocking WebView initialization.
- **Decision:**
  - In `OnlineMusicRepository`, declare mutable cache fields (`shelvesCache`, `likesFetchedAt`,
    `likeWrites`) before constructor logic, and place the `init` block containing reactive account
    collection at the bottom of the class body.
  - Offload repository library refreshes to `Dispatchers.IO` so network and disk queries never stall
    `Dispatchers.Main.immediate`.
  - In `AppGraph`, replace synchronous blocking `by lazy` resolution of `WebSettings.getDefaultUserAgent`
    with `@Volatile var webUserAgent` carrying a standard fallback user-agent string, warmed
    asynchronously on `Dispatchers.IO` to eliminate main looper deadlocks with Chromium initialization.
  - Persist valid YouTube Music session credentials securely using Android Keystore cipher storage,
    restoring active sessions seamlessly across process termination without re-prompting the user.
- **Tradeoff:** Cached library shelves and likes metadata reset cleanly across explicit account
  transitions while remaining reliably available during cold startup.

### D-73 · Sign-in never loads forever
- **Context:** user report (2026-10-12): a listener couldn't sign in because the screen kept
  loading. After D-72 the code still had ways to stall: a failed account check rebuilt the web
  view, which signed in again by itself and failed again in a loop; a check cancelled by closing
  or recreating the screen left the account "signing in" for good, and Settings and Online then
  refused to start another; a recreated screen restored "checking" without a check running; and
  the automatic look for the session began on the first page and ended a minute later for good.
- **Decision:**
  - `WebSignInChecks` decides when to check, apart from the web view, and lives in a `ViewModel`.
    It looks for the session only once the page is on the service's own site, at each navigation
    and on a one-minute poll that starts again with the next navigation there.
  - A session found is checked once. A failed check stops the automatic checks: the reason shows
    with Try again (when checking the same session again could help) and Start over, and only
    the listener starts the next one. The web view is kept under the message, never rebuilt by a
    failure.
  - Start over wipes the web state, waits for the cookies to be gone, then loads a new page.
  - The screen isn't recreated by turning, folding or resizing the phone, so neither the page nor
    a check under way is lost; if it is recreated anyway, the check carries on in the `ViewModel`.
  - The source puts the account back as it was after any sign-in that doesn't finish: refused,
    cancelled, or unable to seal the session in the Keystore (now a refusal rather than a crash).
    A failed sign-in while signed in keeps that account; from Expired it stays Expired.
  - A page the sign-in stops, a load or certificate error, and a crashed page renderer are told to
    the listener with Start over, instead of a page that silently stops.
  - When the music page redirects a request without cookies (in some regions, to a consent page),
    its settings are read again with the session, instead of falling back to a built-in client
    version that ages.
- **No overall time limit on the account check.** Each request already gives up on its own (8 s
  to connect, 12 s between reads), so the check ends by itself, about 80 s at worst on a connection
  that stalls, and a fixed limit short enough to feel quick would cut off slow connections that
  would have finished. Instead, a check still running after 15 s says "This can take a while on a
  slow connection. Keep waiting, or tap Cancel to stop."
- **Tradeoff:** after a failure the listener taps once more to retry; a loop that retried by
  itself could hammer the service (rate limits) and never let the message be read.

### D-74 · Searching plays the song, then songs like it; videos say so; Go to album and artist
- **Context:** user requests (2026-10-12): a song played from search was followed by the other
  search results instead of similar songs; Now Playing should say when the sound comes from a
  video; searching for an album should bring up albums of songs first; Now Playing's menu should
  go to the album and the artist.
- **Decision:**
  - A song (or video) chosen in search plays on its own. Up Next is then short, so autoplay asks
    the song's own source for songs like it straight away (D-34) and they follow. Nothing else
    the search found joins the queue. With Autoplay off, the song plays alone.
  - `NowPlayingItem.kind` carries the item's `MediaKind` (a media item extra). Now Playing adds
    "Audio from the music video" or "Audio from a video" under the title. That says what the
    recording is, not who serves it (D-36).
  - Album search results: an album titled exactly as searched first, then full albums, EPs,
    singles and anything unlabelled, otherwise the catalogue's order (`AlbumRanking`). ~~When the
    top album is titled exactly as searched, the Albums section comes before Songs.~~ Withdrawn
    by D-80: Songs always come first.
  - Now Playing ▸ More has Go to album and Go to artist (a list to choose from when there are
    several), shown only when the song has those pages. Each opens in the song's own environment
    (D-34): the library's page for a local song, the online page for an online one. Online song
    menus gain Go to album too.
- **Not verified:** the live search answers. What "video albums" were could only be inferred,
  because Podium never probes the service from the build container.

### D-75 · Sleep timer
- **Context:** user request (2026-10-12); specified in audio-architecture.md §9.
- **Decision:** `SleepTimer` (player:api) is shared by the screens and the playback engine. It
  offers 15, 30, 45, 60 or 90 minutes, the end of the song, or the end of the album.
  - A countdown fades the player's own volume over its last 10 s, then pauses. The volume stays
    down until the music plays again.
  - The end of a song or album uses the player's own pause at the end of that item (set only for
    the album's last song).
  - It's reachable from Now Playing ▸ More ▸ Sleep timer and Settings ▸ Sleep timer. Now Playing
    shows "Music stops in 23 min" while it's set.
  - It isn't kept across restarts: a timer belongs to a listening session.
- **Tradeoff:** music another app plays is paused when the countdown ends, but can't be faded.

### D-76 · A built-in equaliser
- **Context:** user request (2026-10-12): a configurable equaliser. audio-architecture.md had
  planned only to open the phone's own panel.
- **Decision:** Podium's own `AudioProcessor` in each player's audio path, so it sounds the same
  on every phone and for local and online songs alike.
  - Ten peaking filters an octave apart (31 Hz to 16 kHz), ±12 dB in 1 dB steps.
  - Thirteen presets, and Custom when a band is moved by hand.
  - Settings ▸ Equaliser lists the bands; each opens to be set with the Wheel, heard as it turns.
  - Off or flat, the audio passes through sample for sample. Boosts lower the whole signal first
    by the curve's peak, so they never clip.
- **Tradeoff:** the decoded audio is changed, so with the equaliser on the sound is no longer
  bit-for-bit what the file holds. The quality label still reports the source, and is never
  "Lossless" on its account.

### D-77 · Crossfade
- **Context:** user request (2026-10-12). Media3 1.11 has no crossfade of its own.
- **Decision:** Settings ▸ Crossfade offers off, 2, 4, 6, 8 or 12 seconds (off by default).
  - The engine's one player keeps the queue, the session and the notification, so local playback
    and everything that controls it work as before.
  - A second, silent player (`Crossfader`) takes over only the outgoing song's last seconds. It
    starts the same song just ahead and is levelled with the first by playing a little slower or
    faster while silent. The same audio is then swapped across unheard.
  - The first player moves to the next song, which fades in from its start while the old one
    fades out, on equal-power curves.
  - Never for an album's next track (gapless, as mastered), repeat-one, songs shorter than three
    times the overlap, or a song the sleep timer ends with.
  - Any pause, skip, seek or error ends a crossfade at once: the second player stops, and the
    song playing is at full volume.
- **Verified:** on Media3's test players with generated tones (the crossfade happens, the queue
  moves with it, the second player stops, gapless album tracks are left alone). **Not** heard on a
  phone: the levelling's accuracy on real hardware is unmeasured.

### D-78 · Every new song slides its cover in
- **Context:** user direction (2026-10-12), with a sketch: the playing cover leaves the frame as
  the next comes in from the right, whenever a new song starts; Previous runs the other way.
- **Decision:**
  - The slide is keyed by the queue entry, not the artwork, so songs sharing a cover (an album)
    slide too. This replaces "no transition when the next song shares the cover"
    (NOW_PLAYING_VISUAL_REFERENCES).
  - Direction: back (in from the left) only when the new song is the one just before the last in
    the same queue (`songChangeIsBack`). Otherwise it's a new song, in from the right: the song
    ending, Next, a song chosen from a list or a search, or repeat wrapping round. Before this, a
    new queue started from a search could slide in from the left.
  - Each cover leans on its own, and the slide moves it flat across the display. Before, the lean
    turned the whole stage, so a cover moving right receded into the distance: the incoming one
    appeared small mid-screen, and on Previous the outgoing one parked mid-screen, then vanished.
  - The covers of the songs either side are loaded ahead at the size Now Playing shows them, so an
    online cover slides in already showing instead of popping in after.
- **Verified:** filmed frame by frame on Robolectric (`NowPlayingSlideTest`): Next, Previous, same cover.

### D-79 · Go to album and artist for every online song; each artist named in the menu
- **Context:** user report (2026-10-12, on the phone): Now Playing's menu had no Go to album or
  artist for an online song. Some of the service's lists name a song's album and artists without
  linking their pages (shelf tiles, liked-songs rows), so the song Podium held had nothing to go
  to. The user also asked for every artist to be named in the menu itself.
- **Decision:**
  - `SongPages` (app) gives Now Playing the playing song with its pages. When the song has no album
    page, or an artist without a page, it looks the song up once on its own source (its watch-next
    details link both), fills in what was missing and remembers it in the catalog.
  - What the song already links is never replaced. Credits that match none of the source's (one
    line naming two artists) give way to the source's own, one per artist.
  - Now Playing ▸ More lists Go to album, then "Go to <artist>" for each artist with a page, in the
    menu itself. This replaces D-74's single Go to artist entry, which opened a list when there
    were several. Online song menus name every artist the same way.
  - Provider-neutral: the lookup asks the song's own source through the registry.
- **Not verified:** which of the service's lists leave the pages out. The fix covers any of them.

### D-80 · Search lists songs first; Go to album and artist made dependable for online songs
- **Context:** user report (2026-10-12, on the phone, the D-79 build):
  - Searching showed albums above songs. The user wants songs first, then albums.
  - Now Playing ▸ More still had no Go to entries for online songs ("Blinding Lights", "STAY",
    both liked). Offline songs were fine.
- **Decision (search):** Songs always lead, then Albums, Artists, Playlists, Videos. D-74's rule
  moving Albums above Songs when the top album's title matched the query is withdrawn. The album
  ranking inside the Albums section stays.
- **Cause (Go to), as far as the code shows:** the phone's answers can't be seen from the build
  container, so every way the pages could go missing was closed:
  - `SongPages` marked a song "looked up" before the lookup ran. A lookup cut short (the song
    changed, Now Playing closed, a second copy of the screen asked at the same moment) or one that
    failed left the song without pages for the rest of the session.
  - Lists stored in the database (liked songs, playlists) put songs back in the catalog whenever
    they reload. A song filled in by a lookup was overwritten by its stored copy, and never looked
    up again.
  - The parser took a link as an album or artist page only when it stated its page type.
  - A song's own details were looked for only in each watch-next row's leading version, not the
    paired one (a song's audio and its music video share a row).
- **Decision (Go to):**
  - `SongPages` runs each lookup in the app's scope: it finishes when the screen that asked has
    gone, and callers at the same moment share it. What it found is kept in `SongPages`, so a list
    storing the song again doesn't undo it. A lookup that failed (no network, say) is tried again
    the next time.
  - When the song's details still leave a page out, it's found by name on the same source: the
    artist named exactly so, the album with exactly that title by the song's artist. One line
    naming several artists ("A & B") becomes one credit each, only when every one is found.
    Nothing close is taken. The name search stays within the song's own source and environment
    (D-34).
  - The YouTube Music parser reads links that don't state a page type by their ids (albums
    `MPRE…`, artists `UC…`). It also reads the row's own menu (Go to album, Go to artist) when the
    line names the album or artist without linking it. The menu's single artist is never put on
    a line naming several. A song's details are found in either version of a paired row.
  - Opening Now Playing ▸ More without Go to entries asks again, so the next opening has them
    when they've arrived.
- **Verified:** unit tests for each failure path (`SongPagesTest`, `YouTubeMusicParserTest`). A
  Robolectric test opens the menu before and after the pages arrive (`NowPlayingGoToTest`).
- **Not verified:** the phone's own answers. If the entries are still missing, the next step is a
  captured answer from the device, which the build container can't make.

### D-81 · Continuous line: an optional lyrics theme where one line writes the words
- **Context:** user request (2026-10-12) for a second lyrics theme. A matte black screen; a fine
  ivory line that actually writes each lyric letter by letter, flows from phrase to phrase and
  draws musical line art in instrumental passages; restrained champagne gold; seeking and
  rewinding must restore the same artwork; light enough for ordinary phones.
- **Decision:**
  - `VirtualDisplay.lyricsTheme` (`LyricsTheme.CLASSIC`, the default, or `CONTINUOUS_LINE`),
    persisted as `lyrics_theme`, chosen in Settings ▸ Appearance ▸ Virtual display ▸ Lyrics theme
    and provided by `PodiumTheme` like the lyrics font. Classic is unchanged. The label is
    "Continuous line" in sentence case (copy rule).
  - **Real stroke lettering**, not a font revealed under a moving dot. Podium's own single-stroke
    hand (`LineHandGlyphs.kt`), hand-authored as small vector paths: a–z, A–Z, 0–9, common
    punctuation, accents composed from canonical decomposition. Lowercase letters join with one
    generated connector; the pen lifts only where a hand lifts. Characters outside the hand are
    traced from the system font's outline by the same line (honestly an outline, not one stroke).
  - **The drawing is a function of the playback position.** Position → cursor (section and
    fraction of its pen-time) → pen and camera, from a composition prepared once per song and
    display shape. Timing comes only from the lyrics: provider word times when present, otherwise
    the line's start with the words paced by `WordTiming` and marked estimated. `WordTiming.end`
    added for the end of a line's words.
  - Seeks, rewinds and reading with the Wheel make the pen retrace its own route to the new place
    (280–900 ms), then land exactly on the position. The same moment always draws the same frame.
  - **Instrumental passages** chain nine hand-drawn motifs chosen with a seed from the track's key
    and the passage's index, so the same song always draws the same art. Beat dots follow the pen
    and are not presented as detected beats.
  - **Performance:** prepared geometry as flat arrays drawn with `drawLines`; section routes rebuilt
    on demand, eight kept; nothing laid out, built or allocated while mapping a frame; the glow is
    one cached radial gradient. No blur, `RenderEffect` or `RuntimeShader`, so the glass rule is
    kept.
  - **Colour:** the theme is content drawn inside the display, like Classic's alternating paper. It
    sets no display, body or Wheel colour (D-26). Its champagne gold is a deliberate, opt-in
    exception to D-29's "album art is the only colour" on Carbon and Bone. It is confined to the
    pen's point and glow, the wet edge of the ink and brief accents, and is never on text
    containers or controls.
  - Reduced motion: no glow, no gold accents, no retracing; the camera moves in cuts.
- **Verified:** unit and Robolectric tests (LYRICS_ARCHITECTURE.md §10, §12). Screenshots of
  the real screen at many positions. Pixel-identical frames after a rewind and after repeated seeks.
  Zero allocation while mapping 600 frames. Bounded draw calls (at most 82 a frame) for 12- to
  160-line songs. Theme switching issues no player command.
- **Not verified:** anything on the phone, including frame times on the Nothing Phone (3a)
  (UI_DEVICE_ACCEPTANCE.md §3a).

### D-82 · Lyrics that move with the music: an even pen, a glowing line, a swell with the bass
- **Context:** user report (2026-10-15, on the phone, the D-81 build): the Continuous line looks
  good but stutters slightly. The user wants the line a bit thicker with a very subtle glow, like a
  highlighter or a neon sign. All lyrics should react very slightly to the music, like a
  subwoofer, smoothly, never with a sudden jerk.
- **Cause of the stutter (measured on the phone):** the drawing followed the player's position
  raw. Per frame that position moved unevenly: read at a different moment after each refresh, in
  whole milliseconds, and corrected every few seconds. Worst steps were 7–270 ms off a frame, and
  1–144 frames per 5 s were off by more than a quarter of a frame. Inside a phrase the pen's speed
  also switched abruptly at every word.
- **Decision (time):** `LyricClock` drives the drawing from the display's frame times, leaning
  onto the reported position (time constant 400 ms, at most 8 % faster or slower than real time)
  and snapping on a seek, pause or stall. The cursor takes the fractional position. Inside a phrase
  the pace is a monotone cubic through the word keyframes: every word is still reached exactly on
  time, but the speed changes smoothly. On the phone: 0 uneven frames in every window, worst step
  ≤ 1.8 ms.
- **Decision (the line):** strokes about 50 % bolder (lettering 2.3 dp). A soft glow from two
  wider, dim strokes under every line: opaque steps toward a warm ivory, drawn from a simpler copy
  of each route. No blur, `RenderEffect` or shader, so the glass rule holds. Four finer steps
  looked softer close up but made frames late on the phone; two ran as smoothly as no glow.
- **Decision (drawing cost), from measurements on the phone:** the line stays `drawLines`,
  anti-aliased, as in D-81. The glow is drawn as GPU paths **without anti-aliasing**: strokes of at
  most 48 segments, made once off the main thread, one path per layer for a written section, a
  growing path for the one being written. Only the stroke under the pen is rebuilt each frame.
  - Drawing the glow with `drawLines` kept the render thread over its 11.1 ms frame (p50 12.0 ms,
    4.3 % janky).
  - Anti-aliased paths were rasterised on the render thread as masks and uploaded, about 10 a
    frame (0.6–2.7 % janky).
  - Glow paths without anti-aliasing and the line as `drawLines` measured 0.10–0.25 % janky over
    three runs, interleaved with the others in one session. With two glow layers it measured
    0.30–0.36 %, as smooth as no glow (0.15–0.35 %).
  - The glow's dim, wide steps show no aliasing.
- **Decision (the bass):** `BassMeter`, a pass-through `AudioProcessor` after the equaliser in
  Podium's own player. It measures the low end (120 Hz low-pass, 10 ms RMS) and files each reading
  under the song position its audio belongs to, from Media3's `StreamMetadata.positionOffsetUs`.
  It needs no microphone permission and changes no sample. The UI asks by playback position
  (`AudioPulse`, `player:api`), so readings line up with what is heard although the player decodes
  ahead.
  `PulseFollower` turns the bass's hits (rises above its own recent average, judged against the
  song's recent strongest) into a swell through an eased push and a critically damped spring.
  Position and speed never jump, at any frame rate. Classic: the line scales by at most 1.8 %.
  Continuous line: the drawing by 1.4 %, the glow brightens, the pen blooms. Nothing while another
  app plays the music, nothing with reduced motion.
- **Debug builds:** `lyrics-demo` (invented lyrics for the test tones only), `open`,
  `lyrics-theme` and `appearance`, so the screen can be tested on the phone without the listener's
  library, real lyrics, the Wheel or Settings. The motion probe is off unless `log.tag.PodiumLyricsClock` is set to DEBUG.
- **Verified:** unit and Robolectric tests (LYRICS_ARCHITECTURE.md §10). On the phone with test
  tones: the clock's evenness, the meter's flushes at seeks and song changes, frame times
  (§12.7).
- **Not verified:** the look and the swell on the listener's own music (UI_DEVICE_ACCEPTANCE.md
  §3a.18–3a.22); 120 Hz (the phone ran the lyrics at 90 Hz).

### D-83 · The orb: long instrumentals in the non-line lyrics themes
- **Context:** user request (2026-10-15): a playback-reactive orb in Podium's black, ivory and
  gold, shown in long stretches without words. The camera pulls back from the lyrics to it and goes
  back in through its hollow centre when the words return. It must react to the song playing, not
  the microphone, never decode twice, ignore short gaps, and keep seeking and track changes safe. A
  21st.dev WebGL component was shared for its look. Mid-task the user added: "should only apply to
  the non-line lyrics themes, not the lines".
- **Decision:**
  - Classic only (any future non-line theme too). The Continuous line is unchanged.
  - `OrbTimeline` decides from the lyrics' own times and the playback position alone. A gap needs
    6 s and room for the whole move. The last line lingers 1.4 s (2.8 s after an estimated end),
    the camera pulls back over 1.8 s, holds at least 0.8 s, and goes back in over 1.5 s, arriving
    exactly on the next line. Never for Podium-paced lyrics or plain lyrics. No words at all, or
    none found: the orb throughout, with a small caption.
  - `OrbStage`: one geometric camera. The page recedes into the orb's hollow through a round window
    and comes back out of it. Reduced motion cross-fades.
  - `OrbRenderer` (`core:designsystem`): Podium's own AGSL shader (Android 13+), with a gradient
    drawing before that. The visual idea follows the shared component; no code was taken, and no
    microphone, WebGL or React.
  - The audio: D-82's meter becomes `AudioMeter` with three bands (whole, below 120 Hz, above
    2.5 kHz). It measures only while the lyrics screen listens (`AudioPulse.listen`). `OrbDrive`
    smooths the bands into energy, swell, ripple and glint, judged against the song's own recent
    loudest. Paused, everything settles and the orb's time stops.
  - Debug builds: `lyrics-demo` gains `podium.kind instrumental | notfound`, and its gap is about a
    third of the tone.
- **Verified:** unit and Robolectric tests (LYRICS_ARCHITECTURE.md §10), including the real shader's
  look. On the phone with test tones and invented lyrics: short gaps kept the words; the long gap
  pulled back to the orb and went back in on the next line's time (screen recording read frame by
  frame); 0 janky frames during the orb; the instrumental screen showed the orb with its caption.
- **After a listener report (2026-10-15):** once, at a song's start, the words reappeared for a
  moment mid-move; and the receding page showed its straight sides and cut corners. Now the camera
  moves no faster than a whole move in 0.4 s, so a position blip can't snap the words back (its own
  moves pass unchanged). The page recedes as a circle with a feathered edge (an alpha mask in its
  own layer, only while it moves; no blur). Tested (`OrbTimelineTest`, `LyricsScreenTest`); not yet
  on the phone, which disconnected. The cause of the blip is inferred, not captured: start-of-song
  positions were seen jumping by up to 270 ms. Debug builds log the camera's target jumps, and
  `lyrics-demo` gains `podium.kind intro`.
- **Not verified:** the orb with the listener's own music (how it moves with real kicks and hats),
  the not-found screen on the phone (Robolectric only), the fallback drawing below Android 13, and
  reduced motion on the phone (the phone's animation setting wasn't changed).

### D-84 · Listener fixes: standard text, Cover Flow's order, asking the lyrics service politely
- **Context:** user list (2026-10-19). A listener's phone with large text drew the wheel's arrows
  and the power button as rectangles. Cover Flow scattered an artist's albums. Lyrics said "Too
  many requests" far more often than before.
- **Decision:**
  - **Standard text.** Podium draws at its own text size and weight, whatever the phone's font
    size and Bold text settings: `MainActivity.attachBaseContext` applies an override
    configuration with `fontScale` 1 and `fontWeightAdjustment` 0 (`StandardText`). The rectangles
    couldn't be reproduced (Robolectric at 2× and bold drew the symbols correctly), so their cause
    on that phone is unknown. The owner offered standard text as the fix ("either fix it or just
    make the text size a standard"). It also stops the wheel's `MENU` wrapping. The trade-off:
    Podium no longer follows the phone's text size (accessibility.md §2).
  - **Cover Flow** goes by artist, then album (`coverFlowOrder`, using the lists' `sortKey`), so
    "Drake" is followed by "Drake, Ariana Grande", as on an iPod.
  - **Lyrics pacing.** LRCLIB now answers 429 with `Retry-After`. Podium ignored it and asked again
    at once on every song and every reopen. Now `LrclibProvider` waits the time asked (kept
    between 5 s and 10 min), or 30 s doubling to 10 min when none is given. It asks nothing
    meanwhile and spaces its own requests at least 1 s apart. The screen says "The lyrics service
    is busy" and asks again by itself once the wait is over (LYRICS_ARCHITECTURE.md §6, §8).
- **Verified:** `StandardTextTest`, `CoverFlowOrderTest`, `LrclibPacingTest`. Not yet on the
  listener's phone.

### D-85 · Full screen, with the time and the battery in the title bar (issue #3)
- **Context:** GitHub issue #3 and the owner: hide Android's status and navigation bars (or just
  the status bar), as a setting. When it's on, the iPod's title bar shows a digital clock on the
  left and, optionally, a small battery.
- **Decision:**
  - Settings ▸ **Full screen** (off by default). Off, nothing changes. On, the system bars are
    hidden (`WindowInsetsControllerCompat`, both bars; a swipe from the edge shows them for a
    moment). This is re-applied when the window regains focus. `DeviceLayout` then keeps only the
    display cutout's insets.
  - In full screen, `ScreenHeader` shows the time (`TitleBarClock`: the phone's 12- or 24-hour
    choice, changing on the minute) on the left, after the back arrow when there is one. It also
    shows the battery (`TitleBarBattery`: an outline filled to the charge, with a bolt cut out
    while charging, in the display's quiet ink). Settings ▸ **Battery in title bar** turns the
    battery off; it's on by default and does nothing outside full screen. The title keeps clear of
    both (92 dp each side instead of 56).
- **Verified:** `TitleBarStatusTest` (nothing overlaps the title, with and without the back arrow;
  screenshots checked), `DeviceSettingsPersistenceTest`. Not yet on the phone.

### D-86 · Choosing a Google account's channel (issue #4)
- **Context:** GitHub issue #4. A Google account with several YouTube channels (brand accounts)
  signs in as the account's own channel, but the listener's music is on another. The owner: "if it
  is there, implement it".
- **Decision:**
  - The channels come from YouTube's own channel switcher: `account/accounts_list` with
    `ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER`, asked as the account itself.
    `YouTubeMusicParser.channels` reads each `accountItem` tolerantly. A channel's page id comes
    from its switch endpoint's `pageIdToken`, or the channel half of a `channel||user` data-sync
    id; the account's own channel has none. Disabled items, and items without a channel, are left
    out.
  - Podium acts as a channel by sending its page id as `context.user.onBehalfOfUser` on every
    signed-in request. This is how ytmusicapi supports brand accounts. The page id is kept sealed
    with the session.
  - A switch first asks the account menu as that channel, and keeps the channel only if the
    answer comes back as that channel. Otherwise nothing changes and Settings explains. The
    account's name and key become the channel's, so each channel's likes, playlists and history
    are its own. Switching back to the account's own channel gives back the original key.
  - A refused channel list never signs the account out (it isn't watched for expiry). Signing out
    removes every account's online rows (`OnlineLibraryStore.forgetAccounts`): all of that
    account's channels, and anything an earlier sign-in left.
  - `sources:api` gains provider-neutral identities on `AuthFacet` (`identities`,
    `refreshIdentities`, `selectIdentity`, `AccountIdentity`). Settings ▸ the online service shows
    a **Channel** row when there are two or more, opening a picker with the current one checked.
- **Verified:** fixture tests for the parser, the switch (including restarts and back again),
  refusals and sign-out. **Not** with a real brand account: there wasn't one to try, so the
  issue's author should confirm.

### D-87 · ALAC on every phone: Podium's own decoder after the platform's
- **Context:** the owner asked for ALAC playback. Media3 reads ALAC from MP4, but decoding needs
  a decoder on the phone. Android's own software codecs have none: this phone has Qualcomm's, and
  many phones have nothing.
- **Options:** Jellyfin's prebuilt Media3 FFmpeg decoder stops at Media3 1.9.0 (Podium uses
  1.11.1) and adds native libraries per ABI. Building FFmpeg here is impractical. A Kotlin port of
  Apple's reference decoder (Apache-2.0) is small, pure and testable bit for bit.
- **Decision:**
  - `AlacFrameDecoder` is a port of Apple's ALAC decoder (attributed in third_party/CODE.md).
    `AlacAudioRenderer` is a Media3 `DecoderAudioRenderer`, added **after** the platform's
    renderers. A phone with its own decoder keeps using it, exactly as before. One without uses
    Podium's.
  - It handles 16, 20, 24 and 32 bit, and 1–8 channels (placed in Android's channel order). It
    doesn't read CAF files, which Media3 has no extractor for.
- **Verified:** bit-exact against FFmpeg's decoder on five generated files (16-bit stereo with
  silence, mono, 24-bit, uncompressed frames, 5.1). A file also plays to the end through ExoPlayer
  with only this renderer (Robolectric). Not yet on a phone that lacks an ALAC decoder.

### D-88 · The app icon: the wordmark's P
- **Context:** the owner wants a new icon: a "P" in the boot wordmark's typeface, replacing the
  filled circle inside a hollow one.
- **Decision:** the "P" of `archivo_wordmark.ttf` (Archivo, weight 900, width 125). Its outline is
  converted to a vector path with fontTools. It sits on the existing dark background, filled with
  the wordmark's silver stops, 38 dp tall inside the 66 dp safe zone and nudged 0.8 dp right to sit
  optically centred. The themed (monochrome) icon is the same "P" in one flat colour.
- **Verified:** rendered under circle, squircle and square masks, small, and themed. Not yet on the
  phone's launcher.

### D-89 · Sound effects: car stereo, 3D hall, 8D
- **Context:** the owner asked for more effects in the equaliser: a car stereo, an immersive 3D
  hall, and the viral "8D" sound that moves between the ears.
- **Decision:**
  - Settings ▸ Equaliser ▸ **Effect**: None, Car stereo, 3D hall, 8D. One at a time. It works
    with the equaliser on or off, is kept, and takes effect at once.
  - `SoundEffectProcessor` sits in each player's audio path after the equaliser and before the bass
    meter. With None it passes audio through bit for bit. A change crossfades over 60 ms, and a
    soft limit above 0.8 of full scale keeps everything below clipping. It handles stereo 16-bit
    and float; mono passes untouched.
  - **Car stereo:** +7 dB low shelf at 85 Hz, +2 dB at 2.5 kHz, −4 dB high shelf at 9 kHz, a
    narrower image, and five early reflections 2.3–9.8 ms late (some from the other side). A −5 dB
    preamp makes room for the bass.
  - **3D hall:** a stereo reverb with Freeverb's public-domain tuning (large room, 25 ms
    pre-delay), around a slightly widened dry sound.
  - **8D:** the music circles the head every 8 s, with equal-power level, up to 0.45 ms between
    the ears, and the head's shadow darkening the far ear (more so behind). Some of the original
    stereo stays, with a small room around it.
- **Verified:** `SoundEffectProcessorTest` measured the car's bass lift (over 4 dB against 1 kHz),
  the hall's tail after the music stops, 8D more than 6 dB to each side at a quarter turn, nothing
  past full scale, and no clicks when switching. How much of each sounds right is for the owner's
  ears: not yet heard on the phone.

### D-90 · Live radio: YouTube's live music streams, audio only
- **Context:** the owner wants live radio stations in Online, if available. CLAUDE.md makes
  YouTube Music the only online provider.
- **Decision:**
  - Stations are YouTube's live music streams (24/7 lofi, jazz and the like), found with YouTube's
    own search filtered to live: the video site's web client, signed out, so nothing of the
    account goes with it. There is one search per genre (13 genres), most listened to first.
    `www.youtube.com` is now an allowed host, that exact host only.
  - New provider-neutral pieces: `MediaKind.LIVE`, `Capability.LIVE_RADIO` and `LiveRadioFacet`.
    In the app: Online ▸ **Live radio** ▸ a genre ▸ its stations. Center tunes in, and Next goes
    to the next station.
  - Playback: the stream's HLS master playlist (from NewPipe's live info) is read for its
    audio-only AAC-LC rendition (128 kbps, the same audio as a song), so no video is fetched.
    `media3-exoplayer-hls` is added, and a station's media item is marked live HLS before it loads.
  - A station isn't a song: no crossfade (the player's "duration" is only its rewind window), no
    lyrics lookup (the lyrics screen says it's live radio), and no scrubbing. Now Playing says
    "Live radio", with "Live" where the time left would be.
  - A radio directory (Radio Browser, say) would be a second online provider. That isn't done; it
    needs the owner's decision.
- **Verified:** from this laptop against the real service: a genre found 20 live stations, two
  resolved to their audio-only AAC-LC playlists, and ExoPlayer loaded one as live HLS with
  AAC-LC 44.1 kHz stereo (Robolectric, which has no AAC decoder to play it). Unit tests cover the
  search parser, the playlist choice, the source and the media item. Not yet on the phone.

### D-91 · Custom sources: the listener's own stream sources, YouTube Music the default and fallback
- **Context:** the owner wants multi-source playback like BitChord's custom addons. YouTube Music
  stays the default, always present, and the fallback. Listeners add their own sources (for
  lossless or any other quality) under a separate "Custom sources" option, each with its own menu.
  The owner supplied an addon to test with. CLAUDE.md made YouTube Music the sole online provider.
- **Decision:**
  - **Catalogue vs streams.** YouTube Music remains the only online *catalogue*. A custom source is
    a *stream* source only: never browsed, never searched by the listener, never in Online, the
    library, autoplay or recommendations, and never in the `SourceRegistry`. A new provider-neutral
    seam, `StreamProviders` (in `sources:api`), hands the resolver the listener's sources.
    CLAUDE.md is amended accordingly.
  - **Resolution.** For an online song (not for downloads, not with `FallbackPolicy.NONE`), the
    resolver first asks the custom sources that are on, in the listener's order, after the pinned
    and owned-copy steps and before the song's own source.
    - Only an **EXACT** match plays, and only the **same edition** (the edition rule is superseded by D-92): where either side names explicit
      or clean, the other must name the same one.
    - All custom sources together get 8 s. After that, or when none has the song, YouTube Music plays
      it. The path is `ResolutionPath.PREFERRED_SOURCE`.
    - Health, pins and refresh after a refused stream work as for any source. One that is turned off
      or removed takes its pinned streams with it, except the song playing (D-18).
  - **Protocol.** The BitChord addon protocol: `manifest.json`, `search?q=`, `stream/{id}`, with the
    listener's settings as query parameters. It is implemented independently in a new pure-JVM
    `sources:addon` module, with lenient parsing, caps, no address in any error and the network
    policy on every hop.
    - DASH, HLS and file streams are played. Encrypted or DRM streams are refused.
    - The addon's quality text is kept as a claim only. Labels come from the decoder.
  - **Engine.** A queue item's placeholder URI carries the kind of stream it was built for. An item
    whose stream turns out to be DASH or HLS is rebuilt for it before it opens. A mismatch the loader
    still sees (`PodiumReopenException`) is a rebuild, never an error or a skip.
    - Live stations, local music and YouTube Music items are unchanged.
    - `media3-exoplayer-dash` is added.
  - **App.** The sources are kept sealed in the Keystore `CredentialStore`. Settings ▸ Custom
    sources has the list, **Add a source** (type or paste; the manifest is fetched first; http on
    the own network asks first) and, per source: on or off, its declared settings, move up or down,
    check now, its host and its problem, and Remove (confirmed).
  - Details: [architecture/CUSTOM_SOURCES.md](architecture/CUSTOM_SOURCES.md).
- **Consequences:**
  - With an addon that doesn't mark explicit songs (the test addon doesn't), songs YouTube Music marks
    explicit always play from YouTube Music.
  - Album titles must agree where both sides give one, so a deluxe-edition copy doesn't count for the
    standard album's song.
  - Custom sources apply only to songs Podium plays itself, not to songs handed to another app.
- **Verified:**
  - The test addon's manifest, search and stream answers were read from this machine (metadata only,
    no audio downloaded). Its plain https address (no `/manifest.json`) adds as is.
  - Its stream answers were a static DASH manifest with one FLAC representation on one read, and a
    direct FLAC file on a later one. Both kinds are handled.
  - Fixtures follow those shapes. Unit tests cover the protocol, the source against scripted HTTP
    and against a local HTTP server, the resolver step and the store.
  - Engine tests play a generated FLAC-in-fMP4 DASH stream through real DASH parsing and extraction,
    and measure FLAC. Layout tests cover every screen.
  - Not yet on the phone.

### D-92 · Custom sources first, and lossless mid-song once it has loaded
- **Context:** the owner: custom sources should play any song they have, explicit or not; songs in
  Up Next should stream from them too; YouTube Music only when a song isn't there or the network is
  slow, and then "start on YouTube Music and shift to lossless midway once it has loaded, like Apple
  Music".
- **Decision:**
  - **Any EXACT copy plays.** D-91's extra edition rule is dropped. The matcher's own rule stays: a
    copy marked clean is never the explicit song, and the reverse; one that doesn't say plays.
  - **Slow or failing custom sources are asked again.** The first ask keeps its 8 s. When they ran
    out of time or failed (`StreamResolver.mayStillAnswer`), the engine asks them again in the
    background (`StreamResolver.preferredCopy`, 30 s, at most every 2 minutes per song). When every
    one answered that it hasn't got the song, it isn't asked again until the sources change. Adding
    or turning on a custom source re-asks for the song playing and the next three.
  - **A copy found later takes over** (`PodiumPlaybackEngine.applyUpgrade`):
    - A queued song is pinned to it and its media item rebuilt. The item's placeholder now names the
      source it was rebuilt for, so the player never reuses a period it had started loading.
    - The song playing switches outright when it is paused or hasn't started.
    - Otherwise crossfade's second player carries it (`Crossfader.carry`). The copy is loaded ahead
      (5, 10, 20, 40 s), started as the song reaches that point, brought level, and crossfaded to in
      0.4 s. The first player plays on silently and is kept level, so the queue, position,
      notification and lyrics stay with it. It switches to the copy at the next pause or seek. While
      the copy is heard, the session names its source and the label is measured from that player's
      decoder (`PodiumExtras.HEARD_*`).
  - This amends D-18 ("never mid-track"): the one exception is an EXACT custom-source copy of the
    song the listener chose. CLAUDE.md is amended.
- **Verified:**
  - Unit tests: the resolver (15) and the addon (36).
  - Engine tests with Media3's test players on generated audio:
    - a song started from the lossy stream is carried to the FLAC copy mid-song (heard FLAC, level
      within 250 ms) and switches for good at a pause (where it was heard, within 500 ms);
    - a queued song moves before it plays;
    - a song no source has isn't asked for again.
  - Not yet on the phone. Two copies from different masters can differ by a fraction of a second;
    the crossfade covers that, but it hasn't been heard on a real network.

### D-93 · Channels under one Google account, found and always offered (issue #4)
- **Context:** issue #4 again: on 0.1.3 the reporter's account shows no Channel row, so the channel
  with their music can't be chosen. The owner wants channel switching under one account, as on
  YouTube.
- **Decision:**
  - The channels are asked from both of the service's lists. When `account/accounts_list` gives
    fewer than two, the music page's own switcher (`getAccountSwitcherEndpoint`, a GET answered
    behind a `)]}'` guard) is asked too, and the two are merged.
  - A Google account without a channel of its own is no longer left out: the music service can be
    used as it.
  - Settings shows the **Channel** row whenever the account is signed in, and the account menu has
    **Switch channel**. The picker says when it is asking, why it couldn't list the channels, or
    that only one came back, with what to check.
- **Verified:** fixture tests for both lists and their merge, and for an account without a channel
  of its own. Not with a real brand account; the reporter is asked to confirm.

### D-94 · Podium's ALAC decoder first (issue #6)
- **Context:** issue #6: ALAC still doesn't play on 0.1.3. D-87 put Podium's decoder after the
  phone's. A track goes to the first renderer that claims it, so on a phone whose own ALAC decoder
  claims the format and then fails on the file's configuration, Podium's never got the chance.
- **Decision:** Podium's renderer now comes before the phone's audio decoders. It takes nothing but
  ALAC, so every other format goes to the phone exactly as before. It is bit-exact (D-87).
- **Verified:** the renderer order is tested; the D-87 decoding tests still pass. CAF files still
  aren't read. Not yet on the reporter's phone.

### D-95 · Cover Flow opens and closes without the wait
- **Context:** the owner: Cover Flow lags a little when opening or closing it.
- **Decision:**
  - Cover Flow's order (D-84) made both sort keys again in every comparison of the sort, each with
    three freshly compiled regexes, on the main thread, every time the screen appeared, coming back
    from an album included. Each album's keys are now made once, the regexes are compiled once, and
    the order is made off the main thread.
  - The last order shows at once while the library is read again, so reopening never waits.
- **Verified:** 3,000 albums ordered in 80 ms instead of 819 ms on the development machine (a test
  now bounds it); phones are slower, so the saving there is larger. Not yet timed on the phone.


### D-96 · Custom sources: asked again by themselves, and a race-free switch mid-song
- **Context:** the owner had never seen a song move to lossless mid-song. Their custom source often
  showed "Not answering" or "Asked too often", and only Check now in Settings brought it back. They
  asked for Podium to keep trying in the background, and for the lossless copy to load a few seconds
  ahead so the switch is clean. Probing their addon showed why it "stopped answering": about one
  request in seven hangs for 20 s or more, while the same request sent again answers in about a
  second. Three hangs in a row opened the source's circuit.
- **Decision:**
  - **The addon client asks politely and gets an answer.** Requests start at least 200 ms apart, in
    order. One with no answer after 2.5 s is sent once more, and the first answer wins. One that
    fails at once (a dropped connection, a 502/503/504) is sent once more straight away. After a 429
    nothing is sent until its `Retry-After` (30 s when it doesn't say, 10 minutes at most).
  - **A resting source is never taken for not having the song.** The resolver skips a source whose
    circuit is open or that was asked too often, records that it was resting, and the song counts as
    "may still be there" (before, it counted as a miss and was never asked again). A custom source's
    search may use the whole budget, so a slow first answer no longer counts as a failure. The
    budget for all custom sources together is now 5 s, down from 8 s: a song starts promptly from
    YouTube Music and the copy takes over once it has loaded.
  - **The player keeps asking.** For each song that started from YouTube Music while a custom source
    might have it, the custom sources are asked again at once, then after 15 s, 30 s, 1, 2 and
    4 minutes. While every one is resting it waits for the rest to end instead. A source answering
    again (another song, or a background check) cuts any wait short. It stops when a copy turns up,
    when every source answers that it hasn't got the song, or when the song has played or left the
    queue. One song is asked for at a time.
  - **Settings checks by itself.** A source that isn't answering is asked for its manifest quietly,
    20 s after it stopped, then after 40 s, 80 s and so on, up to every 5 minutes. An answer puts it
    straight back to work. A source asked too often isn't asked until its rest is over. The status
    is how the source is doing now, so a rest that has run out reads as over. "Not answering" now
    says Podium keeps trying it.
  - **The switch can't lose the race.** The copy loads 6 s ahead of the song and waits there,
    paused and silent. It joins only once it's ready where the song is. If the song gets there first,
    the copy keeps loading and jumps forward inside what it has already loaded (3 s beyond the song
    is enough), instead of starting over further on. It starts over from further ahead only if it
    can't catch up within 12 s (twice at most), and gives up only near the end of the song.
  - **Lined up by sound.** Two copies of one recording can start their audio a little apart. Each
    player's meter records the loudness of every 10 ms it decodes, and the copy's onsets are compared
    with the song's over two seconds, at offsets of up to ±0.5 s. A clear match sets where the copy
    is brought level, so the 0.4 s crossfade doesn't play anything twice. With nothing clear to go by
    (silence, a steady sound), equal positions are used as before.
  - **YouTube Music still drives everything.** Autoplay, radio and recommendations ask only the
    registered sources about the queue's own songs. Custom sources are never registered, so they're
    never asked, whichever copy is heard. Likes and history are the YouTube Music song's, with the
    serving source noted.
- **Verified:** addon client tests for hedging, the immediate retry, the hold after a 429 and
  pacing (40 in `sources:addon`). Resolver tests for a resting source and a slow search (174 in
  `sources:api`). Player tests: a copy whose first bytes take 9 s joins from inside what it has
  loaded, 9–12 s after it was asked for, where the song is. A source that was resting is asked again
  as soon as it answers, without a check. Alignment tests on music-like envelopes: offsets found,
  quieter copies placed, other music and silence left unsure. App tests for background checks and
  a rest running out. Autoplay test: a custom source holding the seed is never asked. Not yet heard
  on the phone.

### D-97 · YouTube Music streams found like BitChord: several apps, proven streams (issue #5)
- **Context:** issue #5. Signed-in listeners on 0.1.2 and 0.1.3 got "Something went wrong playing
  this song" for online songs. The owner asked for resolution done the way BitChord does it, if
  that resists these failures better.
- **Findings (2026-10-26):**
  - **The message was wrong.** The engine sent its error to Now Playing as the class's own name
    (`::class.simpleName`). Release builds rename classes (R8), so Now Playing couldn't read it
    back. It fell back to the raw player error code: a refused stream (HTTP 403) read as
    "Something went wrong".
  - **Streams were refused.** Podium got streams from NewPipeExtractor only. That means one route
    (YouTube's visionOS app), with no check that the stream plays. NewPipeExtractor v0.26.5's
    signature solver also no longer reads YouTube's current player, so no app that needs one can
    be used through it.
  - From this development machine's address YouTube refuses nearly every stream (HTTP 403, for
    every app), so a phone's results couldn't be reproduced here.
- **Decision:**
  - **Errors by a stable name** (`PlaybackErrors.kindOf`), with a refused stream read as such even
    without it. Misses now say why: "not streamable" or "not permitted" read as "can't play right
    now", and a resting source as "isn't answering". "Busy" and "isn't answering" have their own
    words.
  - **The walk** (`YouTubeStreamWalker`). The player is asked as `ANDROID_VR`, `VISIONOS`, `IOS`
    and `ANDROID` in turn (apps whose streams need no signature), with a guest visitor id that is
    renewed after a bot check.
  - **Proven streams.** Every stream is checked with a two-byte request as its app before the
    player gets it, and the app's user agent goes with it. The app that last served a stream goes
    first; one whose stream was refused while playing goes last.
  - **What can't play, and what can't play yet.** Gone, private and age-restricted songs are misses
    at once; one app's "not available" isn't final. An answer about another video isn't used.
    HTTP 429 stops the walk.
  - **NewPipeExtractor as the failsafe**, its stream proven the same way. Live stations still come
    from it.
  - **Not built:** signature and "n" solving, BotGuard PoTokens and the listener's session for the
    web and TV apps. Each needs YouTube's player JavaScript run on the phone. Running it in the
    development sandbox, to check a solver, was refused. These are the next tier when wanted.
- **Verified:**
  - Walker tests (12) with scripted YouTube answers: order and memory, bot-check renewal, refusals,
    verdicts, 429, stand-in answers, the failsafe proven before use.
  - Error-kind tests (3).
  - Live from this machine: a song whose stream serves was found through `ANDROID_VR` in 1.6 s; a
    song whose every stream is refused came back "refused", not handed over; a missing video was a
    miss in 0.35 s.
  - Not yet on a phone, and not verified in a release build.

