# Research: Provider APIs and policies (Spotify, YouTube, Audius, OpenSubsonic, Media3)

**Researched:** 2026-10-02 · **Method:** official developer documentation and policy pages (fetched 2026-10-02), GitHub API for SDK repositories, read-only live API probes. Quotes are verbatim from the fetched pages. Nothing here is a legal conclusion; it records what each provider documents and permits on its own terms.

## 1. Spotify

### 1.1 Android SDK (App Remote + Authorization)
- Docs: "allows you to control playback in the Spotify app after user logs in with the access token"; "processing of playback and caching as well as network traffic is accounted for by the Spotify app" which "handles system integration such as audio focus, lockscreen controls and incoming calls."
- "The Android SDK is currently in Beta. The content and functionality is likely to change significantly without warning in future versions."
- Getting started: "App Remote SDK requires the Spotify app to be installed on the device." Third-party apps do **not** play audio; they control the Spotify app.
- `spotify/android-sdk` repo: last release `v0.8.0-appremote_v2.1.0-auth` (2023-07-04), last push 2024-08-19, Apache-2.0.
- User capability flag exposed by the SDK: `Capabilities.canPlayOnDemand`.

### 1.2 Web API access rules
- **2024-11-27** — restricted for new apps / dev-mode apps without pending extension: Related Artists, Recommendations, Audio Features, Audio Analysis, Get Featured Playlists, Get Category's Playlists, 30-second preview URLs in multi-get responses, algorithmic and Spotify-owned editorial playlists.
- **Quota modes (as of 2025-05-15):** Development mode — "Up to 5 authenticated Spotify users", allow-listed; "The app owner must have a Spotify Premium account for apps in development mode to function." Extended quota requires an "Established Business Entity", "Operating an active, and Launched Service", "at least 250k MAUs", availability in key markets, commercial viability, adherence to terms.
- **February 2026 migration** (new apps 2026-02-11; existing 2026-03-09): 1 Client ID per developer, 5 users per app; removed batch endpoints (`GET /tracks`, `/albums`, `/artists`, …), `GET /browse/new-releases`, `/browse/categories*`, `GET /artists/{id}/top-tracks`, `GET /users/{id}`, `GET /users/{id}/playlists`, `POST /users/{user_id}/playlists`, `GET /markets`; old library save/contains endpoints replaced by generic endpoints; playlist `/tracks` → `/items` with `GET /playlists/{id}/items` "Only available for playlists the user owns or collaborates on"; search `limit` max 50 → **10** (default 5). "Player/playback control endpoints continue functioning." Extended-quota apps unaffected.

### 1.3 Developer Policy (effective 15 May 2025) — verbatim excerpts
- III.5: "Do not create any product or service which is integrated with streams or content from another service."
- III.7: "Do not permit any device or system to segue, mix, re-mix, or overlap any Spotify Content with any other audio content."
- III.9: "Do not build an SDA that enables the transfer of data to another service, except for the purpose of enabling a user to transfer their personal data, or the metadata of the user's playlists to another service."
- III.11: "Do not build products and services that mimic, or replicate or attempt to replace a core user experience of Spotify or its group companies without our prior written permission."
- III.14: "Do not use the Spotify Platform or any Spotify Content to train a machine learning or AI model…"
- IV.1: "Streaming of music sound recordings through the Spotify Platform shall only be made available to subscribers to the Premium Spotify Service."
- IV.2: "Except for the limited commercial uses for Non-Streaming SDAs, commercial uses are not permitted for SDAs."
- II.4.1: "If you display any Spotify Content you must clearly attribute the content as being supplied and made available by Spotify, by using the Spotify Marks."
- II.4.2: "Metadata, cover art and Audio Preview Clips must be accompanied by a link back to the applicable album, content or playlist on the Spotify Service."
- II.5: "…there shall be no playback of Spotify Content without showing relevant cover art and metadata in your SDA."
- VI: app names must not begin with "Spot" or imply endorsement.

## 2. YouTube / YouTube Music
- **No official YouTube Music API** exists (search for official docs finds only unofficial libraries such as ytmusicapi).
- **YouTube Data API v3**: videos/playlists/channels/search metadata with quota (default 10,000 units/day; search costs 100); OAuth for user playlists and ratings. `search.list relatedToVideoId` deprecated 2023-06-12, removed 2023-08-07. Granular quota system introduced 2026-06-01 starting with `videos.insert` and `search.list`.
- **YouTube API Services Developer Policies** (last updated 2026-09-14) — verbatim:
  - III.I.7: must not "separate, isolate, or modify the audio or video components of any YouTube audiovisual content"
  - III.I.8: must not "promote separately the audio or video components of any YouTube audiovisual content"
  - III.I.9: must not "create, include, or promote features that play content, including audio or video components, from a background player, meaning a player that is not displayed in the page, tab, or screen that the user is viewing"
  - III.E.1.a: must not "download, import, backup, cache, or store copies of YouTube audiovisual content without YouTube's prior written approval"
  - III.E.6: must not "scrape [YouTube Applications] or [Google Applications], or obtain scraped YouTube data"
  - III.D.7: "You must not use undocumented APIs without express permission…"
  - III.I.1: must not "create, offer, or act as a substitute for, or substantially similar service to, any [YouTube Applications]"
- **YouTube Terms of Service**: see `2026-10-02-music-sources.md` (automated access, circumvention, downloading).
- **Unofficial access** (InnerTube) and how current clients obtain streams: see `BITCHORD_ARCHITECTURE_REVIEW.md` §4/§11 and `2026-10-02-music-sources.md` §A.

## 3. Audius
- Public REST API + JS SDK; probes 2026-10-02: `GET /v1/tracks/search` 200, `GET /v1/playlists/search` 200 (keyless, `app_name` param); `GET /v1/tracks/{id}/stream` → **302** to an Open Audio validator node serving `audio/mpeg` (MP3 with ID3).
- Published plans (secondary source, apievangelist/Audius docs): Free 10 req/s and 500k req/month; Unlimited on request.
- Track flags: `is_streamable`, `is_stream_gated`, `is_downloadable`, `download_conditions`; user sign-in ("Log in with Audius") and SDK write operations (favorite, repost, playlists) exist for authenticated users.
- Positioned by Audius as an open, permissionless catalogue for third-party apps.

## 4. OpenSubsonic
See `2026-10-02-music-sources.md` §B. Additional capability notes used in the matrix: `jukeboxControl` (server-side playback on the server's audio device; optional, server-dependent), `getLyricsBySongId` (OpenSubsonic `songLyrics` extension), `getSimilarSongs2`/`getTopSongs` (depend on the server's external metadata agents), `download` (subject to the user's download permission on the server).

## 5. Media3
See `2026-10-02-android-platform.md` (1.11 session defaults, threading). Relevant to remote targets: a `Player` can be implemented over another engine (`SimpleBasePlayer`), and Media3 ships `CastPlayer` (Google Cast SDK — proprietary Play Services dependency).

## Sources
- https://developer.spotify.com/documentation/android · https://developer.spotify.com/documentation/android/tutorials/getting-started
- https://github.com/spotify/android-sdk
- https://developer.spotify.com/policy
- https://developer.spotify.com/documentation/web-api/concepts/quota-modes
- https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide
- https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security
- https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api
- https://techcrunch.com/2026/02/06/spotify-changes-developer-mode-api-to-require-premium-accounts-limits-test-users/ (secondary)
- https://developers.google.com/youtube/terms/developer-policies
- https://developers.google.com/youtube/v3/revision_history
- https://github.com/sigma67/ytmusicapi (unofficial)
- https://api.audius.co · https://docs.audius.co/developers/api · https://providers.apievangelist.com/providers/audius/ (secondary)
- https://opensubsonic.netlify.app/docs/
