# Navigation Map

**Date:** 2026-10-02 · ADR-011 · Keys are `@Serializable` `PodiumKey` subclasses in `app` (feature modules own their screens, `app` owns the key → screen mapping).

## 1. Hierarchy

```
Home
├── Music
│   ├── Album Flow ─────────────► (flip) album track list ─► [play] ─► Now Playing
│   ├── Artists ─► Artist ─► Album ─► [play] ─► Now Playing
│   │                      └► All songs by artist
│   ├── Albums ─► Album ─► [play]
│   ├── Songs ─► [play]
│   ├── Genres ─► Genre ─► Albums/Songs in genre
│   └── Sources ─► Source (e.g. "Home server") ─► Artists / Albums / Songs / Playlists (scoped to that source)
├── Playlists ─► Playlist ─► [play]        (+ "New playlist", "Edit")
├── Liked Songs ─► [play]
├── Downloads ─► (Albums · Playlists · Songs · In progress)
├── Recently Played ─► [play]
├── Search ─► Results ─► Album / Artist / Playlist / [play]
├── Settings ─► Audio Quality · Playback · Appearance · Sources ─► Source detail · Storage & Downloads · Lyrics · Privacy · About
├── Shuffle Songs (action: plays shuffled library; pushes Now Playing)
└── Now Playing (appears only when something is loaded)
        ├── Up Next ─► (item menu)
        ├── Lyrics
        └── Signal Path (sheet)

Global entry points from anywhere: Search (title-bar button, `/`), Now Playing (mini player, Home item, title-bar indicator), Home (long-press Menu).
```

**Implemented (2026-10-03):** Home ▸ Music · **Online** · Shuffle songs · Now Playing · Settings. ONLINE (D-34) is its own tree on the same paper — `Dest.Online(OnlinePlace)`:

```
Online
├── Home ─► shelves (Trending this week, Underground, Popular playlists, Trending this month)
│           + Recently played, Your liked songs, Your playlists ─► songs / playlists (paged)
├── Explore ─► genres ─► genre (Genre radio, songs)
├── Search ─► results (Songs + More songs, Artists, Albums, Playlists) ─► artist / album / playlist / [play]
├── Liked songs ─► (Shuffle) songs
├── Playlists ─► New playlist (name) · playlist ─► Play, Shuffle, Rename, Delete, songs (hold: move, remove)
├── Radio ─► from what's playing · from songs played · genre radios
└── History ─► (Clear history) songs, newest first
Artist ─► Radio, Play popular songs, Popular, Albums and EPs, Playlists, Related artists
Album/playlist ─► Play, Shuffle, Add all to a playlist, songs
```
Only rows the online source supports are shown (Search needs SEARCH, Home/Explore need BROWSE, Radio needs RECOMMENDATIONS).

Home order (iPod-like, most-used first): Music, Playlists, Liked Songs, Downloads, Recently Played, Search, Settings, Shuffle Songs, Now Playing. Focus restores to the last used item.

## 2. Keys
```kotlin
@Serializable sealed interface PodiumKey : NavKey
  Home, Music, AlbumFlow, Artists, Artist(id), Albums, Album(id, focusTrack: TrackId? = null),
  Songs, Genres, Genre(name), Sources, SourceBrowse(sourceId, section),
  Playlists, Playlist(id), PlaylistEdit(id?), LikedSongs, Downloads(section), RecentlyPlayed,
  Search(initialQuery: String? = null), Settings, SettingsPage(page), SourceSettings(sourceId?),
  NowPlaying, UpNext, Lyrics
```
Sheets and menus are **not** keys (they're overlays in the `InputRouter` stack) except where they must survive process death (none in v1).

## 3. Stack rules (`Navigator`)
| Rule | Behaviour |
|---|---|
| Push | Append key; strip slide (D-04). |
| Pop | Remove top; reverse slide. At root: no-op (wheel) / exit (system back). |
| Pop to root | Long-press Menu → animate directly to Home (single slide, not one per level). |
| **Now Playing is single-instance** | `showNowPlaying()`: if NowPlaying is on top → no-op; if deeper → remove it and push on top; Up Next/Lyrics above it are removed too. Back from Now Playing returns to wherever the user was. |
| Auto-push on play | Starting playback from a list pushes NowPlaying (setting, D-05). Starting from inside NowPlaying/UpNext doesn't push. |
| Up Next / Lyrics | Only reachable above NowPlaying (pushing them from elsewhere goes via `showNowPlaying()` first). |
| Search | Push `Search`; if Search already in stack and on top → focus the field instead. |
| Entities removed under you | If an Album/Playlist key's entity disappears (deleted playlist, source removed), the screen shows an empty state with "Go back"; it isn't silently popped. |
| Deep links (P1) | `podium://album/<id>` etc. build a synthetic stack `Home ▸ Music ▸ Albums ▸ Album` so Menu behaves predictably. |
| Depth cap | 32 (prevents runaway Artist→Album→Artist loops; oldest middle entries are collapsed). |

## 4. Transitions
| Transition | Motion |
|---|---|
| Push / Pop | iPod strip: outgoing and incoming move together 100% width; spring `navigate`; titles cross-fade in the TitleBar. |
| Predictive back | Gesture progress drives the pop strip (0–100%), with a 0.95 scale on the outgoing (current) screen edge; release past 35% commits. |
| Mini player → Now Playing | Container transform from the capsule: capsule expands to full screen, artwork morphs to the large artwork position; spring `sheet`. |
| Now Playing → back (when entered from the capsule) | Reverse container transform into the capsule. |
| Home ▸ Now Playing (menu item) / auto-push on play | Standard push. |
| Album Flow flip | 3D Y-rotation 180° of the focused cover (≤ 400 ms), revealing the track list on the back. Reduced motion: cross-fade. |
| Pop to root | Single strip slide from the current screen to Home. |
| Reduced motion | All of the above become 150 ms cross-fades (flip 200 ms). |

## 5. Adaptive scenes (P1)
- Expanded width: `ListDetailScene` — when the stack is `… List ▸ Detail`, show both; Menu pops the detail first. Now Playing occupies the detail pane when active; Up Next becomes a third pane on very wide windows (≥ 1200dp).
- The focus lens and wheel input target the pane that holds focus; `Tab` / `Rotate` past the last row does **not** jump panes (explicit: ← / Menu moves focus back to the list pane).

## 6. State restoration
Back stack via `rememberNavBackStack`; per-screen focus via `rememberSaveable` (`FocusList` saver stores item key, not just index, so inserted rows don't shift focus). On restore after process death: stack + focus + queue (paused) come back; transient overlays do not.
