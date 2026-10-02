# Research: Open-source YouTube Music clients & legitimate music sources

**Researched:** 2026-10-02 · **Method:** GitHub API (repo metadata, releases, trees, source files), provider documentation, live API probes (read-only, single requests).

This note records *facts*. The analysis and decision live in `../music-source-analysis.md`.

## A. YouTube Music clients

| Project | Stars | Last push | Latest release | License | State |
|---|---|---|---|---|---|
| InnerTune (z-huang) | 6.1k | 2025-11 | v0.5.10 (2024-09) | GPL-3.0 | Dormant; succeeded by forks |
| Metrolist | 13.2k | 2026-09-29 | v13.7.0 (2026-09) | GPL-3.0 | **Maintenance mode** (README) |
| SimpMusic | 11.7k | 2026-10-02 | v2.2.0 (2026-09) | GPL-3.0 | Active |
| OuterTune | 5.4k | 2026-09-16 | v0.11.1 pre (2026-09) | GPL-3.0 | Active; has a "glass" pre-release branch |
| ViMusic | 9.5k | 2024-07 | — | GPL-3.0 | **Archived** |
| Kreate (RiMusic lineage) | 1.2k | 2026-09-29 | — | GPL-3.0 | Active |
| Harmony Music (Flutter) | 3.1k | 2025-12 | — | GPL-3.0 | Slowing |
| NewPipeExtractor | 2.0k | 2026-10-01 | v0.26.5 (2026-08) | GPL-3.0 | Active, frequent fixes |
| YouTube.js (LuanRT) | 5.3k | 2026-09-30 | v18.1.0 (2026-09) | MIT | Active, major versions every few months |
| ytmusicapi (Python) | 3.0k | 2026-10-01 | — | MIT | Metadata only (no streams) |
| yt-dlp | 195k | 2026-09-27 | — | Unlicense | Active |

### How they work (technical, high level)
1. **Metadata** — they call **InnerTube**, YouTube's private, undocumented internal API (`/youtubei/v1/search`, `/browse`, `/next`, `/player`), posing as official clients (web music client, Android, iOS, TV, VR client identities). Metrolist's `innertube` module models ~50 renderer types (shelves, carousels, responsive list items) — i.e. they parse UI-layout JSON, which changes whenever Google changes the UI.
2. **Streams** — the `/player` response yields adaptive audio formats (typically Opus ~160 kbps in WebM and AAC ~128 kbps in M4A; 256 kbps AAC is tied to Premium accounts). Stream URLs are protected by (a) a **signature cipher** and an **"n" throttling parameter** that must be transformed by executing obfuscated JavaScript from YouTube's player, and (b) increasingly, a **Proof-of-Origin token (PoToken)** produced by Google's **BotGuard** anti-bot attestation. Metrolist ships a `PoTokenWebView`/`PoTokenGenerator` that runs BotGuard inside a hidden WebView to mint tokens.
3. **Auth** — optional cookie capture from a WebView login to unlock library sync and Premium formats.
4. **Downloads** — the same resolved URLs, written to Media3's cache/download store.
5. **Breakage** — every project above has a continuous stream of "playback broken" fixes; NewPipeExtractor shipped 4 releases in 3 months. Region restrictions apply (Metrolist README: needs VPN where YouTube Music is unavailable).

### Legal / terms facts
- **YouTube Terms of Service** (effective 2022-01-05) prohibit: accessing the Service "using any automated means (such as robots, botnets or scrapers)"; to "circumvent, disable, fraudulently engage with, or otherwise interfere with any part of the Service" including security features; downloading content except where expressly enabled by the Service.
- **Hamburg Higher Regional Court (OLG Hamburg), 2024-11-21** rejected Uberspace's appeal over hosting youtube-dl, upholding that YouTube's **rolling cipher is an "effective technical protection measure"** — i.e. deciphering it is circumvention under German/EU law (InfoSoc Art. 6 / §95a UrhG).
- 2020: RIAA DMCA §1201 notice against youtube-dl on GitHub (later reinstated in the US after EFF response) — the US position is unsettled; the EU position (above) is adverse.
- Background playback and offline downloads are **YouTube Music Premium** subscription features. A free client offering them bypasses a subscription restriction.
- The official **YouTube Data API v3** provides search/metadata but **no audio streams**; playback must go through the embedded player, which must be visible and may not separate audio from video. Daily quota (10,000 units, 100 units per search) makes it unusable for a music player.
- **Licensing contagion:** all mature clients are GPL-3.0. Copying their code would make Podium GPL-3.0.

## B. Legitimate sources

### Local files (Android MediaStore)
- `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (≤ 32). Formats decodable by Media3/platform: FLAC (incl. 24-bit; Media3 extractor exposes `pcmEncoding` for bit depth), ALAC (M4A), WAV/AIFF, MP3, AAC, Opus, Vorbis. True lossless, no network, no terms risk.
- Sidecar `.lrc` files are **not** readable through MediaStore under scoped storage; needs a user-granted SAF folder.

### OpenSubsonic / Subsonic API (Navidrome, Gonic, Airsonic-Advanced, Ampache, LMS, …)
- Navidrome: 23.9k stars, GPL-3.0 server (we only speak its HTTP API; no code reuse).
- Endpoints cover everything Podium needs: `search3`, `getAlbumList2`, `getArtists`, `getAlbum`, `getSong`, `getCoverArt`, `stream` (`format`, `maxBitRate`, transcoding), `download` (original file), `star`/`unstar`, playlists CRUD, `scrobble`, `savePlayQueue`/`getPlayQueue`, `getSimilarSongs2`, `getTopSongs`, `getLyricsBySongId` (structured, synced).
- Song entries carry `suffix`, `contentType`, `bitRate`, and OpenSubsonic adds `samplingRate`, `bitDepth`, `channelCount`, ReplayGain → **honest quality reporting is possible**.
- Auth: token + salt (`md5(password + salt)` — protocol-mandated, weak) or the OpenSubsonic **API-key extension** (preferred where supported). Transcoding decision extension available.
- Sanctioned for third-party clients by design; many Subsonic clients ship on Google Play.

### Jellyfin
- 57.7k stars; full REST API with audio `MediaStreams` (codec, bitrate, sample rate, bit depth). Candidate second server adapter; not in v1.

### Audius
- Live probe 2026-10-02: discovery returns `https://api.audius.co`; `/v1/tracks/trending?app_name=…` works **without an API key**.
- Track objects expose `is_streamable`, `is_downloadable` (artist-controlled), `is_stream_gated`, `download_conditions`, `isrc`, `license`, `genre`, `mood`, `bpm`, `musical_key`, artwork sizes.
- Designed for third-party clients; catalog is independent/electronic-heavy. Streams are lossy (MP3); originals only where the artist enables downloads.

### TIDAL Developer Platform (`tidal-sdk-android`)
- Player module (Media3-based) exists and is actively released (player-0.0.72, 2026-09-28).
- README: "currently only the Client Credentials flow is publicly supported … 30-second track previews. Full length playback is only enabled when the client is authenticated through Device Login or Authorization Code Flow." → **full playback is not publicly available to third parties.** Watch item only.

### LRCLIB (lyrics)
- Free, keyless API (`/api/get`, `/api/get-cached`, `/api/search`) matching on track, artist, album, duration; returns `syncedLyrics`/`plainLyrics`/`instrumental`. Live probe: HTTP 200 with an identifying `User-Agent`.
- Lyrics are crowdsourced; the underlying lyric texts are copyrighted works. Use as an optional, user-consented provider; cache locally only; never redistribute.

### Metadata enrichment (future)
- MusicBrainz (CC0 core data) + Cover Art Archive; ListenBrainz (CC0 listening data, similarity endpoints) — candidates for cross-source autoplay signals. Rate limits apply (MusicBrainz: 1 req/s with UA).

## Sources
- GitHub API for every repository listed above (metadata, releases, trees; `MetrolistGroup/Metrolist` `gradle/libs.versions.toml`, `app/src/main/kotlin/com/metrolist/music/utils/potoken/*`)
- https://www.youtube.com/static?template=terms
- https://www.heise.de/en/news/OLG-Hamburg-Uberspace-liable-for-hosting-Youtube-DL-10179284.html
- https://torrentfreak.com/court-rejects-appeal-of-youtube-dl-hosting-provider-uberspace-241127-1/
- https://opensubsonic.netlify.app/docs/ · https://github.com/opensubsonic/open-subsonic-api
- https://github.com/tidal-music/tidal-sdk-android (root and `player/README.md`)
- https://api.audius.co · https://docs.audius.co/developers/api
- https://lrclib.net · https://github.com/tranxuanthang/lrclib
