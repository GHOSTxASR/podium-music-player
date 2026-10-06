# Music Source Analysis

**Date:** 2026-10-02 · Evidence: `research/2026-10-02-music-sources.md` · Decision: ADR-002.

> **Update (2026-10-02, later the same day):** the product direction changed to a *universal player* with interchangeable providers (Local, YouTube Music, OpenSubsonic, Audius, Spotify). The source model is now ADR-013 / `architecture/MUSIC_SOURCE_ARCHITECTURE.md`; per-provider capabilities and Podium's positions are in `architecture/SOURCE_CAPABILITY_MATRIX.md`; BitChord findings in `research/BITCHORD_ARCHITECTURE_REVIEW.md`. The YouTube analysis below still stands; its conclusion (§2.4) is refined into options Y0–Y3.

This document separates three questions for every candidate source:
1. **Technically possible?** (can it be built and kept working)
2. **Authorised?** (do the provider's terms and applicable law permit it)
3. **Distributable?** (can Podium ship it, e.g. on Google Play, without legal exposure for the user or the developer)

## 1. Summary table

| Source | Technically possible | Authorised | Distributable | Lossless | Downloads | Podium decision |
|---|---|---|---|---|---|---|
| Local files (MediaStore) | ✓ trivial | ✓ user's own files | ✓ | ✓ FLAC/ALAC/WAV | n/a (already local) | **Launch (Phase 5)** |
| OpenSubsonic servers | ✓ simple REST | ✓ API designed for third-party clients; content is the user's | ✓ (many Subsonic clients on Play) | ✓ original files | ✓ via `download`/`stream` | **Launch (Phase 5/9)** |
| Audius | ✓ simple REST, no key | ✓ public API for third-party apps | ✓ | ✗ (lossy streams) | ✓ only where the artist enables | **Launch (Phase 9)** |
| Jellyfin | ✓ | ✓ | ✓ | ✓ | ✓ | P1 adapter |
| TIDAL SDK | ✓ (SDK exists) | ✗ full playback not publicly available to third parties (previews only) | — | (✓ for partners) | — | Watch |
| Apple MusicKit for Android | ✓ | Requires paid Apple developer membership + user subscription | Possibly | ✗ via SDK | ✗ | Watch; needs user's paid account |
| YouTube Data API v3 | Search only | ✓ within quota | — | ✗ | ✗ | Rejected: no audio streams; visible player required; 100 searches/day |
| **YouTube Music via InnerTube (InnerTune-style)** | ✓ but fragile (monthly breakage) | **✗** (see §2) | **✗** | ✗ (Opus ~160 / AAC 128–256) | ✗ ToS | **Not implemented by Podium** |

## 2. YouTube Music — detailed analysis

### 2.1 Technical possibility (how existing clients do it)
- **Metadata:** InnerTube private endpoints (`search`, `browse`, `next`, `player`), accessed by presenting official client identities. Responses are UI-layout JSON (renderers), so parsers break when Google changes the UI.
- **Streams:** the `player` endpoint returns adaptive audio formats. URLs require (a) transforming a signature cipher and an anti-throttling parameter by executing YouTube's obfuscated player JavaScript, and (b) increasingly a Proof-of-Origin token minted by running Google's BotGuard attestation (Metrolist runs it in a hidden WebView).
- **Quality:** Opus ~160 kbps or AAC ~128 kbps; 256 kbps AAC tied to Premium. **No lossless.** (Relevant: the brief's lossless goals cannot be served by this source at all.)
- **Maintenance:** continuous breakage; Metrolist (largest client) entered maintenance mode; ViMusic archived; NewPipeExtractor ships fixes monthly. Region-locked.

### 2.2 Authorisation
- YouTube ToS prohibit automated access, circumventing or interfering with security features, and downloading except via Service features.
- The cipher is held to be an **effective technical protection measure** by OLG Hamburg (2024-11-21) — deciphering it is circumvention under EU/German law.
- BotGuard/PoToken generation exists specifically to defeat bot detection.
- Background playback and downloads are **YouTube Music Premium** features; providing them free bypasses a subscription restriction.
- Client impersonation misrepresents the software to the service.

### 2.3 Distribution
- Not acceptable on Google Play; exposes the developer to takedowns (cf. youtube-dl/Uberspace, Vanced).
- Every mature client is GPL-3.0; reusing their code forces Podium's license.

### 2.4 Conclusion
**Technically possible, not authorised, not distributable.** The brief's §65 explicitly forbids implementing functionality designed to circumvent access controls, DRM, subscription restrictions, or authentication barriers. Podium therefore **does not implement** the stream-unlock layer: signature/n-parameter solving, PoToken/BotGuard generation, client-identity rotation to obtain streams, age-gate bypass, or YouTube downloading.

**Refined options (see `architecture/SOURCE_CAPABILITY_MATRIX.md` §3):**
| Option | What it is | Podium builds it? |
|---|---|---|
| Y0 | No YouTube source | — |
| Y1 | Catalogue/metadata adapter (unofficial, opt-in, excluded from Play builds); tracks play only via an EXACT-matched copy on the user's authorized sources (D-17) | Yes, if the user accepts the documented policy risk |
| Y2 | Official embedded player target (visible, foreground, video) | Yes, if wanted |
| Y3 | Direct YouTube audio (BitChord-style stream unlock) | **No** (conflicts with the brief's §65 and ADR-013's hard boundary) |
Nothing in Podium's core assumes YouTube; the matcher-based design keeps provider identity out of the UI either way.

## 3. Launch source designs

### 3.1 On This Device (`sources:local`) — Library
- **Discovery:** `MediaStore.Audio.Media` with `IS_MUSIC=1`; incremental sync by `MediaStore.getGeneration()` (API 30+) / `DATE_MODIFIED` (29); `ContentObserver` triggers resync.
- **Permissions:** `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (29–32). Denied → the source shows a "Grant access" state; the app works without it.
- **Metadata:** MediaStore columns + `MediaMetadataRetriever`/Media3 `MetadataRetriever` on demand for bit depth / sample rate / embedded artwork (cached in `artwork_cache` keyed by album).
- **Format:** measured from the file (extractor `Format`: `sampleMimeType`, `sampleRate`, `pcmEncoding` → bit depth, `channelCount`; bitrate computed from size/duration for VBR files when the container lacks it).
- **Lyrics:** embedded tags where Media3 exposes them; sidecar `.lrc` via an optional user-granted SAF folder (P1).
- **Downloads:** `NotApplicable` (shown as "On this device").

### 3.2 OpenSubsonic (`sources:subsonic`) — Library
- **Setup:** server URL, username, password **or** API key. Probe `ping`, `getOpenSubsonicExtensions`, `getLicense`; persist capabilities. Cleartext only for LAN hosts after warning (D-09).
- **Auth:** prefer OpenSubsonic `apiKey`; else token = `md5(password + salt)` with fresh salt per request. The password is stored Keystore-encrypted, never logged.
- **Sync:** artists (`getArtists`), albums (`getAlbumList2 type=alphabeticalByName` paged 500), songs per album (`getAlbum`); incremental: `getIndexes ifModifiedSince`, `getAlbumList2 type=newest` until a known album; full reconcile weekly to catch deletions.
- **Streams:** `stream?id=&format=&maxBitRate=` — `format=raw` for Maximum (original), transcoded tiers per server's transcoding extension. Reported format from song entry (`suffix`, `bitRate`, `samplingRate`, `bitDepth`, `channelCount`), then measured.
- **Likes:** `star`/`unstar` (two-way via outbox). **Playlists:** remote playlists are imported as *linked* Podium playlists (P1 two-way). **Lyrics:** `getLyricsBySongId`. **Related:** `getSimilarSongs2`, `getTopSongs`. **Scrobble:** `scrobble` (P1). **Downloads:** `download` (original) or `stream` with chosen format.
- **Compatibility:** contract tests against recorded fixtures from Navidrome, Gonic, and Ampache (R-06).

### 3.3 Audius (`sources:audius`) — Catalog
- Host from `https://api.audius.co` discovery; `app_name=Podium` on every request.
- `search` tracks/playlists/users; `trending` for Home preview / discovery; stream via `/v1/tracks/{id}/stream` only when `is_streamable` and not `is_stream_gated`.
- Downloads only when `is_downloadable` and no unmet `download_conditions`; otherwise `NotPermitted("The artist hasn't enabled downloads")`.
- Format reported as lossy MP3 (bitrate measured at play). Never shown as lossless.
- Attribution: artist name + link to the Audius page in track info (their terms favour attribution).

### 3.4 Fixture (`sources:fixture`, debug only)
- Generated with ffmpeg at build time: sine/pink-noise tracks in FLAC 16/44.1, FLAC 24/96, ALAC, AAC 256, Opus 160, MP3 V0; procedurally generated cover art; deliberately long/Unicode titles (Greek, Cyrillic, Vietnamese, Devanagari, CJK) for typography tests.
- Exists for screenshot tests, the design benchmark, and emulator runs. Titles say "Test tone" — never presented as music.

## 4. Quality tiers mapped to real options

The Audio Quality screen shows tiers, but each tier lists **what it means per enabled source**:

| Tier | Local | OpenSubsonic | Audius |
|---|---|---|---|
| Maximum | Original file | Original file (`format=raw`) | Best available stream (MP3, bitrate shown) |
| High | Original file | Opus 256 kbps if server transcodes, else original | same |
| Standard | Original file | Opus 160 kbps / AAC 192 kbps (server-dependent) | same |
| Low | Original file | Opus 96 kbps | same |
| Auto | Original file | Maximum on unmetered Wi-Fi, Standard on metered | same |

If a server cannot transcode, the lower tiers say "Original file (this server can't convert)". The Now Playing label always shows the **actual** stream (§ `audio-architecture.md` 5).

## 5. Open decision for the user (superseded — see the checkpoint report of 2026-10-02 and `SOURCE_CAPABILITY_MATRIX.md` §3)

Podium as specified is a *player for music you own or that is legitimately free*, not a front-end for a commercial catalog. If a mainstream catalog is essential to the product vision, the legitimate paths are partner programs (TIDAL, Apple MusicKit) that require paid accounts and agreements. Please confirm the launch source strategy, and whether you have a Subsonic-compatible server (or local files) to develop against.
