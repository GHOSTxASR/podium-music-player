# Screen Inventory

**Date:** 2026-10-02 · Navigation: `navigation-map.md` · Components: `design-system.md` §6 · Wheel contexts: `interaction-model.md` §4.

Legend — **Ctx**: wheel context · **Ph**: implementation phase · States: L = loading, E = empty, X = error, O = offline. Every list screen inherits the **list contract** (§0).

## 0. List contract (applies to every list screen)
- Rows per `design-system.md` §6.3; focus lens; keep-in-view; `IndexGlyph` for sorted lists > 50 items.
- **L:** nothing for 300 ms, then static placeholder rows. **E:** `EmptyState` with one action. **X:** `ErrorState` with retry. **O:** unavailable rows dimmed with reason + "Show downloaded" chip.
- More menu (title bar on browse screens via long-press Center on the header row, or Center on the "…" row at top): Sort (Title / Artist / Recently added / Year), Filter (Downloaded only), Shuffle all, Play all.
- Center on a track = play with the list as context. Long-press Center = item context menu.

| # | Screen | Purpose & content | Entry → Exit | Ctx | States beyond contract | Data | Ph |
|---|---|---|---|---|---|---|---|
| 1 | **Launch / splash** | System splash (Android 12 SplashScreen API): Podium mark on canvas; held only until DB + settings + back stack restored (target < 400 ms) | App start → restored top screen (default Home) | — | Safe mode (DB migration failed): explains, offers Export & Retry | DB, DataStore | 3 |
| 2 | **Onboarding** (first run only, not a carousel) | One screen: "Add your music" with three choices: Allow music on this phone · Connect a music server · Explore Audius; skippable | First launch → Home | ListFocus | Permission denied → stays, explains | Sources | 5 |
| 3 | **Home** | Main menu (9 items), last-focused restored; preview pane on expanded layouts | Root | ListFocus | "Now Playing" item only when loaded; Downloads shows count; first-run hint row when library empty | Settings, queue | 4 |
| 4 | **Music** | Submenu: Album Flow, Artists, Albums, Songs, Genres, Sources | Home → children | ListFocus | Library empty → E "Add your music" | Library counts | 4 |
| 5 | **Search** | Field at top (auto-focused, keyboard up), then sections: Recent searches / Suggestions (empty query) | Home, title bar, `/` → Results inline | TextEntry → ListFocus | History off → no recents | SearchRepository | 9 |
| 6 | **Search results** (same screen, query non-empty) | Sections: Top result, Songs, Albums, Artists, Playlists; each section shows 4 then "See all" row; source badge when > 1 source; per-source inline errors | Typing → entities | ListFocus | Partial results (a source failing) shown inline; O: "Showing results from your library"; no-results E | Local FTS + sources | 9 |
| 7 | **Songs** (generic song list) | All library songs A–Z (also used for genre songs, artist "All songs") | Music → Now Playing | ListFocus | — | Library | 4 |
| 8 | **Artist** | Header (name, artwork if any, `largeTitle` on expanded), rows: Play / Shuffle, Albums (newest first), Top songs (if source supports), All songs | Artists, Search, NP menu | ListFocus | Catalog artist O → cached only | Library/source | 7 |
| 9 | **Album** | Artwork 200dp (+ atmosphere), title, artist, then year, genre and track count on their own lines (no dot-joined strings), Play / Shuffle rows, track list (`AlbumTrackRow`, disc headers), footer: total duration, quality summary ("FLAC 16-bit/44.1 kHz" if uniform) | Albums, Artist, Flow, Search → Now Playing | ListFocus | Partially downloaded indicator; unavailable tracks dimmed | Library/source | 4 (slice uses fixture/local) |
| 10 | **Playlist** | Mosaic artwork, name, description, count/duration; rows: Play / Shuffle / Download toggle; tracks; Edit via menu | Playlists, Search | ListFocus | Deleted track refs shown as "Unavailable" rows (removable) | PlaylistRepository | 7 |
| 10b | **Playlist edit** | Rename, reorder (Reorder ctx), remove, description | Playlist menu | ListFocus / Reorder | Unsaved changes → confirm on Menu | PlaylistRepository | 7 |
| 11 | **Liked Songs** | Liked tracks, newest first; Play / Shuffle / Download all rows; search-in-list via menu | Home | ListFocus | E: "No liked songs yet" | LikesRepository | 7 |
| 12 | **Downloads** | Sections: In progress (with pause/resume all), Albums, Playlists, Songs; storage summary row → Storage | Home | ListFocus | E: "Nothing downloaded"; failures surfaced with Retry | DownloadManager | 8 |
| 13 | **Recently Played** | Counted plays, newest first, grouped Today / Yesterday / This week / Earlier; Clear history in menu | Home | ListFocus | E: "Nothing played yet" | History | 7 |
| 14 | **Now Playing** | `design-system.md` §6.7; More menu: Add to playlist, Download/Remove, Go to album, Go to artist, Sleep timer, Song info / Signal Path, Equalizer | Mini player, Home item, auto on play → Up Next, Lyrics | Volume / Scrub | Loading (artwork placeholder + "Loading…" under title after 300 ms), Error banner with skip countdown, Offline "Playing from downloads", Ended state ("Play again" focus) | PlaybackController, Likes, Downloads | 4 (slice) |
| 15 | **Up Next (Queue)** | Header toggles: Shuffle · Repeat (off/all/one) · Autoplay; "Now playing" row; sections per `queue-and-autoplay.md` §1; menu: Clear, Save as playlist | Now Playing | Queue / Reorder | E: "Nothing up next" + Autoplay toggle hint | PlaybackController.queue | 4 (slice) |
| 16 | **Lyrics** | Full-screen lines over atmosphere (no glass behind text), current line `labelPrimary`, others 45% opacity; credit line (provider) at end | Now Playing (button or artwork tap) | Lyrics | L: "Finding lyrics…"; E: "No lyrics for this song"; consent prompt (D-11); unsynced: plain scroll | Lyrics module | 9 |
| 17 | **Album Flow** | Centered cover at full size, neighbours at 0.72 scale, 35° Y-rotation, overlap 40%, soft reflection (Full/Blur tiers); title/artist under center; flip shows tracks | Music, landscape in browse contexts → Album / play | Flow | O: unavailable albums dimmed | Library albums (paged from DB) | 9 |
| 18 | **Settings** | Rows: Audio Quality, Playback, Appearance, Sources, Storage & Downloads, Lyrics, Privacy, About | Home | ListFocus | — | Settings | 4 (stub rows hidden until implemented) |
| 19 | **Audio Quality** | Streaming on Wi-Fi / on cellular / Downloads tier pickers; each option lists per-source meaning (`music-source-analysis.md` §4); Bit-perfect USB toggle (API 34+); Explanation row → "How quality works" sheet | Settings | Picker | Options a source can't honour are annotated, not hidden | Settings, capabilities | 6 |
| 20 | **Playback** | Show Now Playing on play, Keep my queue, Autoplay, Gapless, ReplayGain (P1), Crossfade (P2), Skip unavailable when offline, Clicker, Sleep timer default | Settings | ListFocus/Picker | — | Settings | 6 |
| 21 | **Appearance** | Theme (Auto/Light/Dark), Highlight (Blue/Graphite/Artwork), Transparency (Full/Reduced/Off), Increase contrast, Wheel (size, left-handed, visibility), Haptics | Settings | Picker | Live preview: a mini list + lens rendered in the chosen tier | Settings | 3 (benchmark) |
| 22 | **Storage & Downloads** | Totals by class; per-group list; Download on cellular; Location (Internal/SD); Clear streaming cache; Clear artwork cache; Verify downloads; Delete all downloads | Settings, Downloads | ListFocus | Calculating sizes (L) | DownloadManager, caches | 8 |
| 22b | **Sources** / **Source detail** | Configured sources with health; Add source flow (type → URL/credentials → probe → done); per-source: sync now, last sync, capabilities, sign out, remove (with "Keep downloads?" choice) | Settings, Onboarding | ListFocus | Probe errors explained (TLS, auth, not Subsonic) | SourceAccountRepository | 5 |
| 22c | **Online sources** (implemented, D-35) | Every online source the build registers: name, On/Off, a short note when it needs the listener (can't reach, busy, sign-in). Center toggles; hold Center (more than one source) to move up/down. Settings row value: On / Off / n of m on | Settings | ListFocus | No online sources in this build: explained | `OnlineSourceSettings` → `SourceSettings` → `SourceRegistry` | O10 |
| 23 | **About** | Version, licenses (generated), source attributions (Audius, LRCLIB, MusicBrainz if used), privacy notice, diagnostics export | Settings | ListFocus | — | static | 12 |
| 24 | **Error states** | `ErrorState` component variants per `design-system.md` §11 table, embedded in each screen (never a separate error screen) | — | ListFocus (action focusable) | — | — | 3 |
| 25 | **Loading states** | `LoadingState` (§6.11) | — | — | — | — | 3 |
| 26 | **Empty states** | `EmptyState` with copy table | — | ListFocus | — | — | 3 |
| 27 | **Offline states** | Title-bar offline glyph; `OfflineBanner` in lists; dimmed rows; Now Playing "Playing from downloads"; Search banner | — | — | — | NetworkState | 8 |
| 28 | **No-results state** | Search-specific `EmptyState` with the query echoed and suggestions (recent searches) | — | ListFocus | — | — | 9 |
| + | **Signal Path** (sheet) | `audio-architecture.md` §5.3 | Quality label, NP More | Overlay | Unknown fields omitted | QualityInspector | 6 |
| + | **Sleep timer** (sheet) | Options + "Turn off" when active | Long-press ⏯, NP More | Overlay/Picker | — | SleepTimer | 6 |
| + | **Add to playlist** (sheet) | "New playlist…" then playlists (recent first); duplicate prompt | Context menus | Overlay | E: only "New playlist…" | PlaylistRepository | 7 |
| + | **Context menu** | Per item type: Play next, Add to queue, Like/Unlike, Add to playlist…, Download/Remove download, Go to album, Go to artist, Song info | Long-press Center/row | Overlay | Disallowed actions shown disabled with reason in Song info (not hidden silently) | various | 4 |

## Relationships (summary)
- Every browse screen can reach Now Playing (play or mini player) and Search (title bar); Now Playing reaches Up Next, Lyrics, Signal Path, Album, Artist.
- Settings ▸ Sources and Onboarding share the same Add Source flow.
- Downloads ↔ Storage & Downloads share `DownloadManager` state.
- Album Flow and Albums are two views of the same data (shared focus: focusing an album in one and switching keeps the album focused).
