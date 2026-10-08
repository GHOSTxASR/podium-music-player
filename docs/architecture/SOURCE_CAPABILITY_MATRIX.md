# Source Capability Matrix

**Researched:** 2026-10-02 · Evidence: [`../research/2026-10-02-provider-policies.md`](../research/2026-10-02-provider-policies.md), [`../research/2026-10-02-music-sources.md`](../research/2026-10-02-music-sources.md), [`../research/BITCHORD_ARCHITECTURE_REVIEW.md`](../research/BITCHORD_ARCHITECTURE_REVIEW.md).
This document records what each provider officially offers and what its own published terms say. It does not state legal conclusions. Re-verify before implementing each provider (APIs and policies changed in Nov 2024, May 2025, Feb 2026, Sep 2026).

## Legend
| Code | Meaning |
|---|---|
| **SUPPORTED** | Officially documented and permitted (or user-owned data/server used as intended) |
| **RESTRICTED** | Officially exists but limited by policy, tier, quota, server configuration, or user permission |
| **UNOFFICIAL** | Technically possible only via undocumented/unofficial means that conflict with the provider's published terms or policies |
| **UNAVAILABLE** | Not offered by any means Podium would use |
| **UNCERTAIN** | Not verified yet — must be verified before use |
| **PODIUM** | Not a provider feature; Podium provides it locally on top of any source |

## 1. Matrix

| Capability | Local (device) | YouTube Music | OpenSubsonic | Audius | Spotify |
|---|---|---|---|---|---|
| **Search** | SUPPORTED (Podium FTS over synced metadata) | SUPPORTED (InnerTube catalogue search) | SUPPORTED (`search3`) | SUPPORTED (keyless, rate-limited plan) | RESTRICTED (Web API; dev mode ≤ 5 users, `limit` ≤ 10) |
| **Browse** | SUPPORTED (MediaStore artists/albums/genres) | SUPPORTED (InnerTube albums/artists/shelves/charts) | SUPPORTED (`getArtists`, `getAlbumList2`, `getGenres`) | SUPPORTED (trending, playlists, users) | RESTRICTED (single-item lookups; new-releases/categories/top-tracks removed for dev mode Feb 2026; editorial playlists restricted Nov 2024) |
| **Direct stream** (Podium plays audio) | SUPPORTED | SUPPORTED (in-app Media3 via stream resolution; BitChord reference) | SUPPORTED (`stream`; transcoding server-dependent) | SUPPORTED for streamable, non-gated tracks (MP3) | UNAVAILABLE (no raw audio to third parties; IV.1; previews restricted Nov 2024) |
| **Remote playback** (Podium commands another player) | UNAVAILABLE | UNAVAILABLE | RESTRICTED (`jukeboxControl`, optional, plays on server) | UNAVAILABLE | RESTRICTED (App Remote SDK, Beta, Spotify app plays; Web API player endpoints need Premium; policy III.5/III.7/III.11 constrain combining with other sources) |
| **Embedded official player** | UNAVAILABLE | UNAVAILABLE (direct streaming used instead) | UNAVAILABLE | UNCERTAIN (web embed exists; not evaluated for apps) | UNCERTAIN (web embeds; not evaluated; likely policy-constrained) |
| **Library** | SUPPORTED (the files) | SUPPORTED (via account web session: liked songs, playlists, albums, artists) | SUPPORTED (it is the user's library) | RESTRICTED (requires Audius sign-in; scope details UNCERTAIN) | RESTRICTED (dev mode; Feb 2026 endpoint changes) |
| **Likes** | PODIUM | SUPPORTED (synced to account via `like/like` endpoints) | SUPPORTED (`star`/`unstar`) | UNCERTAIN (favorite via authenticated SDK; Kotlin path unverified) | RESTRICTED (generic library endpoints since Feb 2026) |
| **Playlists** | PODIUM (MediaStore playlists deprecated API 31; M3U import via SAF later) | SUPPORTED (read & play account playlists) | SUPPORTED (CRUD) | Read: SUPPORTED (public) · Write: UNCERTAIN | RESTRICTED (items only for owned/collaborative playlists in dev mode) |
| **Downloads** (files Podium keeps) | n/a (already local) | UNAVAILABLE / Future | RESTRICTED (`download`; subject to user's server permission) | RESTRICTED (only `is_downloadable` and conditions met) | UNAVAILABLE (offline only inside Spotify's app) |
| **Lyrics** | SUPPORTED when embedded in tags · sidecar `.lrc`: RESTRICTED (needs a user-granted folder) | SUPPORTED (LRCLIB metadata matching; InnerTube lyrics where available) | RESTRICTED (`getLyricsBySongId` needs the OpenSubsonic lyrics extension) | UNAVAILABLE (no lyrics fields observed) | UNAVAILABLE (no official lyrics API) |
| **Recommendations** | PODIUM (library signals) | SUPPORTED (radio/related endpoints via InnerTube) | RESTRICTED (`getSimilarSongs2`/`getTopSongs` depend on server's metadata agents) | RESTRICTED (trending, related artists); track-level related UNCERTAIN | UNAVAILABLE (Recommendations/Related Artists restricted Nov 2024) |
| **Authentication** | n/a (runtime permission) | SUPPORTED (web session with Google account cookies sealed at rest) | SUPPORTED (token+salt; API-key extension) | SUPPORTED (optional; not needed to stream) | SUPPORTED (auth library/PKCE) within dev-mode limits |
| **Lossless** | SUPPORTED (if files are) | UNAVAILABLE (service is lossy: Opus ~160k / AAC 128k; 256k Premium) | SUPPORTED (original files) | Streams: UNAVAILABLE (MP3) · artist-enabled originals: UNCERTAIN | UNAVAILABLE to Podium |

## 2. Per-provider assessment

### 2.1 Local (device)
1. **API capability:** MediaStore audio collections; file access via content URIs; embedded tags.
2. **Technical feasibility:** High. Permissions: `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (≤ 32).
3. **Terms/policy:** None beyond Android permission policy.
4. **Distribution:** No constraints.
5. **Podium:** Phase S2 (stable foundation).

### 2.2 OpenSubsonic (Navidrome, Gonic, Airsonic-Advanced, Ampache, LMS, …)
1. **API capability:** Full library, streaming, downloads, stars, playlists, play-queue sync, lyrics (extension), similar songs (server agents), jukebox (optional).
2. **Technical feasibility:** High; variance across server implementations → capability probing + contract tests (R-06).
3. **Terms/policy:** API designed for third-party clients; content is the user's.
4. **Distribution:** No constraints known; many Subsonic clients are on Google Play.
5. **Podium:** Configured source prototype (retired for online browsing in D-38/D-48 in favor of YouTube Music).

### 2.3 Audius
1. **API capability:** Search, browse (trending/playlists/users), stream (MP3), artist-controlled downloads, optional user sign-in for social/library features.
2. **Feasibility:** High; keyless; stream returns a redirect to a validator node.
3. **Terms/policy:** Positioned as an open catalogue for third-party apps.
4. **Distribution:** No constraints identified.
5. **Podium:** Retired for online browsing in D-38/D-48 in favor of YouTube Music.

### 2.4 YouTube Music
1. **API capability (official vs unofficial):** No official public YouTube Music API. Unofficial access via InnerTube endpoints (`search`, `browse`, `next`, `player`) enables full catalogue browsing, account library access, and direct stream URLs.
2. **Technical feasibility:** Feasible and proven in mature clients (BitChord, Metrolist, NewPipe, ViMusic). Stream URLs require client identity configuration, cipher deobfuscation, and token minting (e.g. BotGuard PoToken) to maintain reliability. Quality is lossy (Opus ~160 kbps / AAC 128–256 kbps).
3. **Terms/policy context:** Unofficial client access conflicts with standard Google ToS / Developer Policies. Mature third-party clients exist as sideloaded/open-source tools.
4. **Distribution:** Intended for sideloaded personal use. GPL-3.0 libraries (NewPipeExtractor, InnerTubeX) require factual licensing compliance if integrated.
5. **Podium product decision (D-48):** Podium is a personal sideloaded music player. YouTube Music is the sole online music provider, offering direct in-app stream playback in Media3, catalogue, search, library, and account sync comparable to BitChord.

### 2.5 Spotify
1. **API capability:** Web API (metadata, library, playlists, player control), Authorization library, App Remote SDK (control the Spotify app; audio stays in Spotify). No raw audio for third parties.
2. **Technical feasibility:** App Remote works only with the Spotify app installed; SDK is Beta, last released 2023-07. Web API dev mode: 5 users.
3. **Podium position:** Not planned. YouTube Music is the sole online provider.

## 3. Podium positions (what will and won't be built)

| Provider | Will build | Will not build | Status |
|---|---|---|---|
| **Local (device)** | Full source (MediaStore, Room, Media3, QueueManager) | — | Built, stable, preserved |
| **YouTube Music** | Full online source: catalogue, search, albums, artists, playlists, user library, liked music, listening history where supported, user session, direct in-app streaming in Media3, background playback (BitChord reference) | Delegated app playback (superseded) | Sole online provider (D-48) |
| **OpenSubsonic** | — | — | Retired from online browsing (D-38) |
| **Audius** | — | — | Retired from online browsing (D-38) |
| **Spotify** | — | — | Not built; YouTube Music is sole online provider |

## 4. What the UI sees (example renderings, provider-neutral)
- An online song playing: rendered seamlessly in Now Playing with measured format info from Media3.
- An offline local song playing: rendered identically, marked as local/device source.
- An account's liked song: displays heart state synced with YouTube Music account.
