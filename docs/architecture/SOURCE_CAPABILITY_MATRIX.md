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
| **Search** | SUPPORTED (Podium FTS over synced metadata) | Videos: RESTRICTED (Data API `search.list`, quota) · Music catalogue: UNOFFICIAL (InnerTube) | SUPPORTED (`search3`) | SUPPORTED (keyless, rate-limited plan) | RESTRICTED (Web API; dev mode ≤ 5 users, `limit` ≤ 10) |
| **Browse** | SUPPORTED (MediaStore artists/albums/genres) | UNOFFICIAL (albums/artists/charts) · channels/playlists: RESTRICTED (Data API) | SUPPORTED (`getArtists`, `getAlbumList2`, `getGenres`) | SUPPORTED (trending, playlists, users) | RESTRICTED (single-item lookups; new-releases/categories/top-tracks removed for dev mode Feb 2026; editorial playlists restricted Nov 2024) |
| **Direct stream** (Podium plays audio) | SUPPORTED | UNOFFICIAL (client impersonation + player-cipher solving + BotGuard/PoToken; Dev Policies III.I.7, III.I.9) | SUPPORTED (`stream`; transcoding server-dependent) | SUPPORTED for streamable, non-gated tracks (MP3) | UNAVAILABLE (no raw audio to third parties; IV.1; previews restricted Nov 2024) |
| **Remote playback** (Podium commands another player) | UNAVAILABLE | UNAVAILABLE (no official remote API) | RESTRICTED (`jukeboxControl`, optional, plays on server) | UNAVAILABLE | RESTRICTED (App Remote SDK, Beta, Spotify app plays; Web API player endpoints need Premium; policy III.5/III.7/III.11 constrain combining with other sources) |
| **Embedded official player** | UNAVAILABLE | RESTRICTED (official embed: visible, foreground, video; no audio-only III.I.7, no background III.I.9) | UNAVAILABLE | UNCERTAIN (web embed exists; not evaluated for apps) | UNCERTAIN (web embeds; not evaluated; likely policy-constrained) |
| **Library** | SUPPORTED (the files) | YouTube playlists/liked videos: RESTRICTED (Data API OAuth) · YT Music library: UNOFFICIAL | SUPPORTED (it is the user's library) | RESTRICTED (requires Audius sign-in; scope details UNCERTAIN) | RESTRICTED (dev mode; Feb 2026 endpoint changes) |
| **Likes** | PODIUM | RESTRICTED (`videos.rate`, OAuth, quota) | SUPPORTED (`star`/`unstar`) | UNCERTAIN (favorite via authenticated SDK; Kotlin path unverified) | RESTRICTED (generic library endpoints since Feb 2026) |
| **Playlists** | PODIUM (MediaStore playlists deprecated API 31; M3U import via SAF later) | RESTRICTED (Data API CRUD, OAuth, quota) | SUPPORTED (CRUD) | Read: SUPPORTED (public) · Write: UNCERTAIN | RESTRICTED (items only for owned/collaborative playlists in dev mode) |
| **Downloads** (files Podium keeps) | n/a (already local) | UNAVAILABLE (III.E.1.a; ToS; offline is a Premium feature of the official app) | RESTRICTED (`download`; subject to user's server permission) | RESTRICTED (only `is_downloadable` and conditions met) | UNAVAILABLE (offline only inside Spotify's app) |
| **Lyrics** | SUPPORTED when embedded in tags · sidecar `.lrc`: RESTRICTED (needs a user-granted folder) | UNOFFICIAL (InnerTube) | RESTRICTED (`getLyricsBySongId` needs the OpenSubsonic lyrics extension) | UNAVAILABLE (no lyrics fields observed) | UNAVAILABLE (no official lyrics API) |
| **Recommendations** | PODIUM (library signals) | UNOFFICIAL (radio/related via InnerTube); official `relatedToVideoId` removed 2023-08-07 | RESTRICTED (`getSimilarSongs2`/`getTopSongs` depend on server's metadata agents) | RESTRICTED (trending, related artists); track-level related UNCERTAIN | UNAVAILABLE (Recommendations/Related Artists restricted Nov 2024) |
| **Authentication** | n/a (runtime permission) | Google OAuth for Data API: SUPPORTED · session capture for InnerTube: UNOFFICIAL | SUPPORTED (token+salt; API-key extension) | SUPPORTED (optional; not needed to stream) | SUPPORTED (auth library/PKCE) within dev-mode limits |
| **Lossless** | SUPPORTED (if files are) | UNAVAILABLE (service has none) | SUPPORTED (original files) | Streams: UNAVAILABLE (MP3) · artist-enabled originals: UNCERTAIN | UNAVAILABLE to Podium |

## 2. Per-provider assessment

### 2.1 Local (device)
1. **API capability:** MediaStore audio collections; file access via content URIs; embedded tags.
2. **Technical feasibility:** High. Permissions: `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (≤ 32).
3. **Terms/policy:** None beyond Android permission policy.
4. **Distribution:** No constraints.
5. **Podium:** Phase S2 (first real source).

### 2.2 OpenSubsonic (Navidrome, Gonic, Airsonic-Advanced, Ampache, LMS, …)
1. **API capability:** Full library, streaming, downloads, stars, playlists, play-queue sync, lyrics (extension), similar songs (server agents), jukebox (optional).
2. **Feasibility:** High; variance across server implementations → capability probing + contract tests (R-06).
3. **Terms/policy:** API designed for third-party clients; content is the user's.
4. **Distribution:** No constraints known; many Subsonic clients are on Google Play.
5. **Podium:** Phase S4; jukebox is the first real `RemoteProvider` (S5).

### 2.3 Audius
1. **API capability:** Search, browse (trending/playlists/users), stream (MP3), artist-controlled downloads, optional user sign-in for social/library features.
2. **Feasibility:** High; keyless; stream returns a redirect to a validator node.
3. **Terms/policy:** Positioned as an open catalogue for third-party apps; rate-limited free plan (10 req/s, 500k/month per secondary source). Attribution expected.
4. **Distribution:** No constraints identified; verify Audius API terms text before release (UNCERTAIN item).
5. **Podium:** Phase S7.
6. **Re-verified 2026-10-03 (D-34, implemented):** base `https://api.audius.co/v1`, anonymous reads identified with `app_name` (docs.audius.co + the live Swagger at `/v1/swagger.yaml`). Free plan 10 req/s, 500k/month. Stream: `/tracks/{id}/stream` (302 to a content node; `no_redirect=true` returns the URL) serves MP3. Gated tracks carry `stream_conditions` / `access.stream = false` and are not resolved. Discovery: `/tracks/trending` (time, genre), `/tracks/trending/underground`, `/tracks/recommended` (genre, `exclusion_list`), `/playlists/trending`, `/genres/popular`, `/search/full` (tracks, users, albums, playlists; offset/limit), `/users/{id}/related`. Personalized lists (`best_new_releases`, `most_loved`, `under_the_radar`, feeds, favorites) need a user id. Sign-in is OAuth 2.0 + PKCE with a developer API key (`api.audius.co/plans`); writes (favorites, playlists) need it — Podium has no key, so they're unavailable and ONLINE keeps likes/playlists on the device. Requests need a real User-Agent (the default Python one is refused).

### 2.4 YouTube Music
1. **API capability (official):** No YouTube Music API. YouTube Data API v3: video search/metadata (quota), user playlists/ratings (OAuth), official embedded player. No audio streams.
2. **Technical feasibility (unofficial):** Catalogue metadata via InnerTube is feasible and fragile (UI-layout JSON). Direct audio requires impersonating official client identities, solving the player cipher/throttling transform, and minting BotGuard Proof-of-Origin tokens (as BitChord does via InnerTubeX/NewPipeExtractor); breaks frequently. Quality is lossy only.
3. **Terms/policy:** Developer Policies prohibit separating audio from video (III.I.7), background players (III.I.9), caching/storing content (III.E.1.a), scraping (III.E.6), undocumented APIs (III.D.7), substitutes for YouTube applications (III.I.1); ToS prohibits automated access and circumventing security features. A German appellate court (OLG Hamburg, 2024-11-21) treated YouTube's rolling cipher as an effective technical protection measure. Background playback and offline are paid features of the official app.
4. **Distribution:** Unofficial use is incompatible with Google Play distribution; GPL-3.0 libraries (NewPipeExtractor, InnerTubeX) would impose GPL obligations on Podium if linked.
5. **Podium position:** see §3.

### 2.5 Spotify
1. **API capability:** Web API (metadata, library, playlists, player control), Authorization library, App Remote SDK (control the Spotify app; audio stays in Spotify). No raw audio for third parties.
2. **Technical feasibility:** App Remote works only with the Spotify app installed; SDK is Beta, last released 2023-07; on-demand availability exposed as a user capability flag. Web API dev mode: 5 allow-listed users, owner must hold Premium, search limited to 10 results, several endpoints removed (Feb 2026).
3. **Terms/policy (Developer Policy, effective 2025-05-15):** III.5 "Do not create any product or service which is integrated with streams or content from another service." III.7 no segue/mix/overlap with other audio. III.9 no transfer of data to another service except a user's personal data or the metadata of the user's playlists. III.11 do not mimic/replicate/replace a core Spotify user experience without written permission. IV.1 streaming only for Premium subscribers. IV.2 no commercial use for streaming SDAs. II.4 attribution with Spotify marks and link-back.
4. **Distribution:** Beyond 5 users requires extended quota — available only to registered businesses with ≥ 250k MAU. A publicly distributed Podium cannot rely on a shared Spotify client id; a bring-your-own-client-id model shifts registration (and a Premium requirement) to each user.
5. **Podium position:** see §3.

## 3. Podium positions (what will and won't be built)

| Provider | Will build (subject to phase) | Will not build | Needs user decision |
|---|---|---|---|
| Local | Full source | — | — |
| OpenSubsonic | Full source + jukebox remote target | — | — |
| Audius | Catalogue + streaming + permitted downloads | Downloads of non-downloadable tracks | — |
| **YouTube Music** | **Y1** catalogue/metadata adapter (unofficial, opt-in, `Basis.UNOFFICIAL_API`, excluded from Play builds) whose tracks play only via an **EXACT/STRONG-matched copy on the user's authorized sources**; **Y2** official embedded player target (visible, foreground) | **Y3**: the stream-unlock layer — client-identity rotation to obtain streams, player-cipher/throttle solving, BotGuard/PoToken minting, age-gate bypass, YouTube downloads — and integrating libraries for that purpose (NewPipeExtractor, InnerTubeX) | Whether to do Y1 and/or Y2 at all (Y0 = no YouTube source) |
| **Spotify** | **S-a** playlist & library **import** (Web API, read-only, mapped to the user's sources via TrackMatcher; within III.9's playlist-metadata allowance; attribution shown) · **S-b** App Remote "remote session" target (`queueOwnership = PROVIDER`, `mixesWithOtherSources = false`, hard cuts, attribution) | Spotify metadata → other-service audio substitution as a playback path; any attempt to obtain Spotify audio outside the Spotify app | Whether to build S-a/S-b given III.5/III.11 tension and the bring-your-own-client-id requirement |

## 4. What the UI sees (example renderings, provider-neutral)
- A YouTube-catalogue track with no authorized copy: row dimmed, "Not available from your sources" (availability), Center explains; Download hidden (`NotApplicable`).
- A Spotify remote session: Now Playing chip "Playing in Spotify" (owner = Remote + attribution), seek/shuffle per `controls`, Up Next read-only.
- An Audius track whose artist disabled downloads: Download shown disabled with "The artist hasn't enabled downloads".
