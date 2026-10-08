# Podium — Product Specification

**Status:** v0.1 foundation · **Date:** 2026-10-02 · **Name:** "Podium" is provisional; nothing in the architecture depends on it.

## 1. Vision

> An iPod redesigned as a modern music operating system.

> **Podium is a modern universal music player with an iPod-inspired interaction model and a highly polished Liquid Glass interface.** Music providers are interchangeable infrastructure behind a capability model (ADR-013); Podium's identity is the Wheel, the navigation, the glass, Now Playing, Up Next, Album Flow, the library, lyrics, offline, system integration, haptics, motion, and accessibility.

- **The iPod is the interaction model** — one hierarchy, one focus, one wheel; Menu goes back; center selects.
- **Liquid Glass is the material** — a restrained functional layer floating above content. Content stays dominant.
- **A modern music app is the functionality** — sources, search, queue, autoplay, likes, playlists, lyrics, downloads, background playback, system media integration.

Litmus test for every screen: *someone who used an iPod in 2006 knows how to use it in five seconds, and someone who never did thinks it is the most considered music app on their phone.*

## 2. Who it is for

| Person | What they need from Podium |
|---|---|
| **The collector** — owns a FLAC/MP3 library on device or on a home server (Navidrome, Gonic, etc.) | A player worthy of the library: honest quality reporting, gapless, fast browsing of 20k+ albums, offline sync. |
| **The nostalgic minimalist** | Calm, focused listening without feeds, autoplaying video, or engagement bait. A tactile wheel. |
| **The discoverer** | A free public catalog to explore (Audius at launch), autoplay that doesn't loop the same five songs. |

Under **D-48**, Podium is a sideloaded personal Android music player that combines a rock-solid offline local library with an online YouTube Music experience comparable to BitChord.

## 3. Product principles

1. **One hierarchy.** Everything is reachable by rotate → center → menu. No tab bar. Touch, keyboard, rotary input, and screen readers are first-class *alternative* paths to the same hierarchy.
2. **Focus is the cursor.** There is always exactly one focused thing, shown by the glass focus lens, and every input acts on it.
3. **Glass is for doing; content is for looking.** Glass only on the Wheel, controls, menus, sheets, transient overlays, and the focus lens.
4. **Calm when idle, expressive when touched.** Nothing moves on its own except playback progress.
5. **Honest by construction.** Show the actual codec, bitrate, sample rate, and bit depth, and the actual output path. Never "Lossless" unless it is. Never a download button for content the source doesn't permit downloading.
6. **Playback is sacred.** Navigation, errors elsewhere, process death, and configuration changes never interrupt or reset playback or the queue.
7. **The user owns their data.** Likes, playlists, history, and downloads are local-first, exportable, and never leave the device except to a source the user configured.

## 4. Music sources at launch

| Source | Kind | What it provides | Status |
|---|---|---|---|
| **On This Device** (MediaStore) | Library | Your files, true lossless, always offline | Active (stable, frozen) |
| **YouTube Music** (D-48) | Catalog + Library | YouTube Music catalogue, search, albums, artists, playlists, user library, liked music, history, and direct Media3 streaming | Active (authoritative online provider) |
| **Fixture** (debug builds only) | Library | Generated test tones and artwork for screenshots/tests; never shipped | Active (test only) |
| **OpenSubsonic / Audius** | Retired | Multi-source setup replaced by YouTube Music under D-48 | Retired (historical) |

*Library* sources are synchronised into Podium's database (metadata only), so browsing is instant and works offline — exactly like syncing an iPod. *Catalog* sources are searched live and cached.

## 5. Feature scope

Priority: **P0** = v1.0 must ship · **P1** = v1.x · **P2** = later.

| Area | Capability | P |
|---|---|---|
| Interaction | Wheel (rotate, center, Menu, ⏮, ⏭, ⏯, long-presses), focus lens, keyboard/D-pad/rotary/mouse-wheel mapping, TalkBack parity | P0 |
| Navigation | Single hierarchy (Home → …), Now Playing & Up Next always reachable, global Search, predictive back | P0 |
| Browse | Artists, Albums, Songs, Genres, Playlists, Liked Songs, Downloads, Recently Played | P0 |
| Album Flow | Cover Flow-style album browser (landscape + menu entry), wheel-driven | P1 |
| Playback | Background playback, media notification, lock screen, Bluetooth/headset controls, audio focus, becoming-noisy pause, gapless, seek, repeat, shuffle | P0 |
| Quality | Per-track format info; Signal Path sheet (source → decoder → output, bit-perfect/resampled); quality tiers mapped to real per-source options | P0 |
| Queue | Up Next with Playing Next / context / Autoplay sections; play next, add, remove, reorder, clear, save as playlist; survives process death | P0 |
| Autoplay | Deterministic, explainable continuation; no repeats | P0 (library signals) · P1 (source similarity) |
| Library | Like/unlike, playlists CRUD + reorder + search, history | P0 |
| Lyrics | Synced + plain, offline cache; providers: source → embedded → LRCLIB (consented) | P1 |
| Search | Unified across sources + local library, suggestions, history, offline fallback | P0 |
| Downloads | Queue, pause/resume/retry/cancel, groups (album/playlist/liked) with auto-sync, storage accounting, integrity checks | P1 |
| Settings | Audio quality, playback, appearance (theme, highlight, transparency, wheel), sources, storage, lyrics, privacy, about | P0 |
| Extras | Sleep timer, Clicker sound, ReplayGain, crossfade | P1–P2 |
| Sync | Two-way stars/playlists with Subsonic servers; scrobbling (server/ListenBrainz) | P1–P2 |
| Platforms | Phones (portrait + landscape), tablets/foldables, ChromeOS/desktop-mode | P0 phone · P1 large screens |

## 6. Non-goals

- Widevine DRM cracking or paid subscription circumvention. (Stream resolution via InnerTube client rotation, cipher deobfuscation, and PoToken is permitted for public YouTube Music streams per D-48.)
- Video playback. Podcasts (maybe P2 as a separate source kind). Social feeds. Ads. Engagement metrics.
- Recreating 2005 pixels. No bitmap fonts, no brushed metal, no fake LCD.
- Shipping a server or proxy. Podium is a client.

## 7. Signature experience (the brief's §71 journey, mapped)

| Step | Behaviour |
|---|---|
| Open Podium | Restores last queue paused at its position; Home shows with focus on the last-used item. |
| Rotate the wheel | Focus lens glides row to row, one haptic tick per detent. |
| Select Music | The paper moves one step: Music rises out of its preview into focus; Home sinks into the glimpse at the left (D-30). |
| Search an artist/song | Home ▸ Search, or the title-bar search button from anywhere. Results across sources stream in. |
| Open an album | Album screen: artwork, tracks; or flip a cover in Album Flow. |
| Play a song | Queue = album from that track; Now Playing pushes automatically (setting). |
| Rotate → volume | On Now Playing the wheel is system media volume; the progress bar becomes a volume bar while turning. |
| Press ⏭ | Next track; ⏮ restarts if > 3 s in, else previous. |
| See Up Next | Long-press center ▸ Up Next, or the Queue button. |
| Like it | Heart on Now Playing or context menu. Persists locally, syncs to the server where supported. |
| Add to a playlist | Context menu ▸ Add to Playlist ▸ pick/create. |
| Download | Context menu ▸ Download — offered only if the source permits. |
| Leave the app | Media session + foreground service keep playing; notification and lock-screen controls. |
| Return later / after process death | Same queue, same position, same screen stack. |
| Album Flow | Rotate phone to landscape while browsing, or Music ▸ Album Flow. |
| Lyrics | Now Playing ▸ Lyrics; current line follows playback; center on a line seeks to it. |

## 8. Definition of done (v1.0)

All of: coherent architecture per `architecture.md`; every P0 row above implemented end-to-end against a real source (no placeholder functionality presented as complete); wheel feels right on a physical device (measured latency budget met, see `performance.md`); playback survives navigation, backgrounding, lock, process death; queue/likes/playlists persist; downloads work for sources that permit them; offline mode behaves per `offline-architecture.md`; all loading/empty/error/offline states exist; TalkBack, keyboard, large text, reduced motion, reduced transparency, high contrast verified; performance budgets met on a mid-range device; dependencies license-audited; README, privacy notice, attributions, known limitations written; signed release build reproducible from CI.

## 9. Assumptions (made without asking; revisit if wrong)

| # | Assumption |
|---|---|
| A1 | Native Android is the intended platform (ADR-001). |
| A2 | English-only UI for v1; metadata can be any script. Localisation-ready strings from day one. |
| A3 | Single user, single device; no Podium account or cloud service. |
| A4 | minSdk 29 (Android 10). Glass fidelity scales with API level (ADR-007). |
| A5 | Podium's own source code license is undecided → all rights reserved until the user chooses. Factual GPL-3.0 notices respected on BitChord/extractor components. |
| A6 | Sideloaded personal Android application (D-48). |

## 10. Decisions that need the user (summary — details in the checkpoint report)

1. Confirm Android-native.
2. Online provider: YouTube Music confirmed as sole online provider (D-48).
3. Test device availability.
4. Podium's license and distribution channel.
5. Name clearance (provisional "Podium").
