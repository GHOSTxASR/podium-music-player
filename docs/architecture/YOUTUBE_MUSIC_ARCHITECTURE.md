# YouTube Music Architecture

**Status:** Authoritative Specification · **Decisions:** D-38 (catalogue, account, library), D-48 (sole online provider, direct Media3 playback, BitChord reference, stable offline playback).

This document is the authoritative architectural specification for YouTube Music in Podium. It supersedes earlier iterations that delegated playback to the official YouTube Music app (P2) or restricted in-app playback under the former "stream-unlock boundary".

---

## 1. Summary

Podium has two environments:
1. **LOCAL (Offline):** Music on the device (MediaStore, Room, Media3 `PlaybackService`, `QueueManager`). This implementation is stable, fully functional, and must **not** be rewritten or disturbed during the YouTube Music migration.
2. **ONLINE:** **YouTube Music** is the sole online music provider for the product. It runs in-app directly inside Podium, delivering an experience comparable to BitChord.

| Concern | Implementation Approach | Owner |
|---|---|---|
| **Catalogue & Search** | Web client InnerTube endpoints (`search`, `browse`, `next`), typed renderers | `sources:youtubemusic` |
| **Account & Session** | Google account session cookies via locked-down WebView sign-in, sealed at rest via Android Keystore | `sources:youtubemusic` + `KeystoreCredentialStore` |
| **Library, Likes, History** | Account library endpoints (`VLLM`, `FEmusic_liked_playlists`, etc.); two-way like sync; listening history where technically supported | `sources:youtubemusic` + `core:database` |
| **Online Playback** | **Direct in-app playback via Media3 ExoPlayer**; background playback supported natively via foreground service | `sources:youtubemusic` (resolver) + `player:service` (Media3) |
| **Offline Playback** | Stable local playback via Media3 in `PlaybackService`; `QueueManager` single queue writer | `player:service` + `sources:local` |

Podium decodes and outputs YouTube Music audio directly within its own Media3 audio engine.

---

## 2. Positions and Principles

- **Product Decision (D-48):** Podium is a sideloaded personal Android music player. YouTube Music is its sole online music provider, offering direct in-app stream playback, background playback where supported, catalogue, search, library, and account sync.
- **Reference Project:** BitChord is an active architectural and implementation reference. Developers may study BitChord's source, extraction pipeline, and design to inform Podium's implementation.
- **Practical Architecture:** The former "clean-room" rule and "stream-unlock boundary" are revoked. Podium adopts the most practical, technically viable architecture (direct stream extraction via InnerTube, cipher deobfuscation, and token generation).
- **Offline Invariant:** Offline/local playback is stable and independent. Online changes must never break or modify local playback pipelines.
- **Providers Never Leak:** The UI interacts with generic music concepts (capabilities, availability, routes). Provider-specific network details and extraction mechanics are contained strictly within `sources:youtubemusic`.
- **Quality Honesty:** YouTube Music serves lossy streams (typically Opus ~160 kbps in WebM or AAC ~128 kbps in M4A; 256 kbps with Premium accounts). Podium never labels YouTube Music streams as "Lossless" or "Hi-Res". The Now Playing UI reflects measured decoder values.

---

## 3. Module Architecture

```
sources:api ──────────── Facets, capabilities, Track/TrackId models, DirectStream target
sources:youtubemusic ─── Pure Kotlin: Transport, InnerTube client, stream extractor, parsers, mapper, paging
player:api ───────────── PlaybackController, QueueManager, PlaybackRouter
player:service ───────── Media3 PlaybackService, ExoPlayer, ResolvingDataSource, audio focus, notification
core:database ────────── Room database, schema v5, account-scoped online tables (likes, playlists, history)
feature:online ───────── Online screens, search, detail, library, history (provider-neutral)
feature:settings ─────── OnlineServiceScreen (account status, sign in/out)
app ──────────────────── AppGraph wiring, OnlineMusicRepository, WebSignInActivity
```

Retired online sources (`sources:audius`, `sources:subsonic`, and the multi-source aggregation layer `MultiSourceCatalog`) have been removed from online browsing.

---

## 4. Identity and Data Model

- **Source-Qualified IDs:**
  - Tracks: `TrackId("ytmusic|<videoId>")`
  - Albums: `AlbumId("ytmusic|MPRE…")`
  - Artists: `ArtistId("ytmusic|UC…")`
  - Playlists: `PlaylistId("ytmusic|<playlistId>")`
  Nothing outside `sources:youtubemusic` parses provider keys.
- **Track Media Kind:**
  `Track.kind: MediaKind` (`SONG`, `MUSIC_VIDEO`, `VIDEO`, `EPISODE`) backed by schema v5 `track.media_kind`. Videos are separated from songs in search results, and Now Playing says when the sound is a video's ("Audio from the music video", "Audio from a video", D-74).
- **Playback Route:**
  YouTube Music tracks resolve to `PlaybackRoute.DIRECT` with a `PlaybackTarget.DirectStream(PlayableMedia)`.

---

## 5. Catalogue Client and Parsers

- **Transport:** HTTPS client with timeouts, size limits, and sanitised error reporting.
- **Client Endpoints:** `POST /youtubei/v1/{search|browse|next|like/like|like/removelike|account/account_menu}` using the `WEB_REMIX` client context and runtime visitor data.
- **Authenticated Headers:** Signed-in requests include session cookies and `Authorization: SAPISIDHASH <ts>_<sha1(ts SAPISID origin)>` with `X-Goog-AuthUser`.
- **Parser Robustness:** JSON parsers fail soft (omitting unrecognized fields or broken rows without crashing whole pages).
- **Paging:** Continuation tokens (both `ctoken` and body continuations) mapped to offset-based paging for UI consumption.

---

## 6. Account and Authentication

- **Session Acquisition:** Google's account sign-in page in a secure WebView (`WebSignInActivity`). Podium captures the resulting session cookies once the page has signed in to YouTube (D-73):
  - The session is looked for only once the page is on youtube.com, at each navigation and every 0.8 s for a minute; navigating there again starts a new minute. "Check sign-in" checks by hand.
  - A session found is checked once against the account menu (`WebSignInChecks`). A check that fails shows why, with Try again and Start over, and is never repeated automatically. A check still running after 15 s says a slow connection can take a while; it isn't cut short.
  - The checks live in a `ViewModel`, and turning the phone doesn't recreate the screen, so a check under way is never lost.
  - Whatever ends a check short of signing in (a refusal, a cancelled check, a Keystore failure) puts the account state back as it was.
- **Channels (D-86):** a Google account can have several channels (brand accounts). The channel switcher's list (`account/accounts_list`, asked as the account itself) gives each channel's page id (its `pageIdToken`, or the channel half of a `channel||user` data-sync id). Podium acts as a chosen channel by sending that page id as `context.user.onBehalfOfUser` on every signed-in request, as ytmusicapi does. A switch is kept only if the account menu, asked as that channel, answers as it. The channel's name and key replace the account's, so its library is its own. A refused list never expires the session. Settings ▸ the service ▸ Channel appears when there are two or more.
- **Storage Security:** The session cookies, account display name, account key and chosen channel's page id are encrypted at rest using `KeystoreCredentialStore` (AES-256-GCM with keys held in the Android Keystore).
- **Database Scope:** The local database references only the hashed account key (`ytm-<sha256>`), never plaintext credentials.
- **Lifecycle:**
  - `SignedOut`: Public browsing only.
  - `SignedIn`: Full library, likes, history, and personalized recommendations.
  - `Expired`: When session validation fails, credentials are wiped and the user is prompted to sign in again.
  - `SignOut`: Wipes account-scoped online database tables (every account's, so all of a Google account's channels, D-86) and Keystore credentials.

---

## 7. Library, Likes, and History

- **Account Library:** Authoritative library data is fetched from YouTube Music:
  - Liked songs (`browseId: VLLM`)
  - Playlists (`browseId: FEmusic_liked_playlists`)
  - Albums (`browseId: FEmusic_liked_albums`)
  - Artists (`browseId: FEmusic_library_corpus_track_artists`)
  - Service history (`browseId: FEmusic_history`)
- **Like Synchronization:** Two-way write-through. Toggling a like writes to the account (`like/like` or `like/removelike`) and updates the local online cache. Local library favorites remain separate and unaffected (D-34).
- **Listening History:**
  - Account History: Synced from the YouTube Music service where technically available.
  - Local History: Observed playback history within Podium (tracks played ≥ 5 seconds).

---

## 8. Playback Architecture

### 8.1 In-App Direct Playback via Media3

Podium plays YouTube Music audio natively in its own Media3 audio engine:
- **Audio Engine:** `PlaybackService` (hosting ExoPlayer on the application main looper).
- **Data Source:** `ResolvingDataSource` intercepts `podium://track/ytmusic|<videoId>` URIs and delegates resolution to `StreamResolver`.
- **Resolution Output:** A `PlaybackTarget.DirectStream(PlayableMedia)` containing:
  - An HTTPS progressive audio URL (Opus-in-WebM or AAC-in-MP4)
  - Required request headers (e.g. User-Agent, Origin, Referer)
  - Stream expiry timestamp
  - Stable cache key
- **Background Playback:** Managed natively by Android's `MediaLibraryService` foreground service with media session notifications, lock-screen controls, and Bluetooth integration.

### 8.2 Stream Extraction Pipeline (BitChord Reference, D-97, D-98)

Built like BitChord's resolver: several of YouTube's own apps asked in turn, a stream proven to
serve audio before the player gets it, NewPipeExtractor as the failsafe
(`YouTubeMusicStreamResolver`, `YouTubeStreamWalker`, `PlayerIdentities`, `StreamHttp`).

1. **The walk.** `/youtubei/v1/player` is asked as each app in `PlayerIdentities`, in turn:
   `VISIONOS`, then the backup `TVHTML5` (below), `ANDROID_VR`, `IOS`, and `ANDROID` as the last
   resort (always last; it answered with no audio formats on 2026-10-26). `VISIONOS`, `ANDROID_VR`,
   `IOS` and `ANDROID` get plain stream addresses. Each request carries the app's own client context
   and user agent, and a guest visitor id (`/youtubei/v1/visitor_id`, asked as a plain app, kept six
   hours).
   - **Backups that need the player's script (D-98).** `TVHTML5` (as yt-dlp's `tv_samsung`) gets
     enciphered addresses: its player request carries the player's signature timestamp, and each
     signature and `n` is solved by yt-dlp's challenge solver (yt-dlp-ejs 0.8.0) run on YouTube's own
     player script (`PlayerScripts`, `EjsChallengeSolver`). The script engine is JavaScriptSandbox, or
     a hidden WebView where the phone doesn't offer it (`AndroidScriptEngine`). The player is
     prepared once per player id and kept in the cache. A backup is asked only after the app that
     last served fails. When one serves, it goes first for ten minutes, then the usual apps again.
   - **The listener's session (D-98).** When they're signed in, `TVHTML5_SIGNED_IN` (after the
     signed-out TV app) and `WEB_REMIX_SIGNED_IN` (YouTube Music's web player, from their browser,
     after `IOS`) are asked with their session. It is sent only with the player request, to YouTube's
     own hosts: cookies, a fresh SAPISIDHASH, and the chosen channel as `onBehalfOfUser`. Never to a
     stream's server, never to the player. Signed out, they're passed over.
2. **Bot checks.** When an app is asked to confirm it isn't a bot, a fresh visitor id is fetched
   and that app is asked once more.
3. **Proven before it plays (D-98).** The best audio formats (Opus, then AAC, higher bitrate first;
   no DRC variants) are checked as the app, two formats per app at most, with two requests at once:
   the open-ended request the player itself sends (`Range: bytes=0-`, two bytes read, then closed)
   and the stream's last two bytes (size from the address's `clen`, else the format's
   `contentLength`). A stream counts only when both answer 2xx. `ANDROID_VR`'s and `IOS`'s streams
   serve only their first part without a PoToken; a check of the first bytes alone passed them and
   the player was refused seconds later. A refused stream (HTTP 403) is never handed to the player:
   the next app is asked. The app's user agent goes with the stream (`PlayableMedia.headers`), so
   the player asks for it as that app too. The app that last served a stream is asked first next
   time, after a restart too (`StreamWalkMemory`, usual apps only). One whose stream was refused while
   playing goes last, before the last resort (`forget`).
   An answer with a pre-roll ad (`adPlacements`, `adSlots`) serves its streams only once the ad could
   be skipped: the check waits for that (checked 1.5 s early, then once more just after; an ad
   longer than 6 s is a refusal).
4. **Verdicts.** A song gone, private or age-restricted for every app is a miss at once
   (`NOT_FOUND`, `NOT_PERMITTED`). One app's "not available" isn't the last word: the others are
   asked. An answer about another video (a stand-in) isn't used. HTTP 429 stops the walk (rate
   limited, with `Retry-After`).
5. **The failsafe.** When no app's stream played (all refused, bot checks everywhere, the service
   unreachable, or the 12 s walk budget spent), NewPipeExtractor is asked. Its stream is proven the
   same way before it is used. Live stations (§8.3) come from it.
6. **Watching it (debug builds).** `StreamWalkDebug.trace` logs each app's verdict to logcat
   (`PodiumStreams`): HTTP code, playability status, YouTube's reason, the check's codes and times.
   Never an address, a cookie or a visitor id. `stream-skip` (`--es podium.apps VISIONOS,…`) passes
   over chosen apps, to prove the others serve. Release builds set neither.
7. **What the listener is told.** Every stream refused gives a miss "not streamable": Now Playing
   says the song can't play right now. Bot checks everywhere rest the source for 30 s as rate
   limited: "The music source is busy". The service unreachable is a network failure.
8. **Not built yet:**
   - PoTokens minted by BotGuard in a WebView (`WEB_REMIX`'s solved streams, like `ANDROID_VR`'s and
     `IOS`'s, serve only their first part without one, signed in too, unless the account has Premium);
   - `WEB` isn't usable: it hands out streaming-protocol (SABR) audio only.

### 8.3 Live Radio (D-90)

- **Finding stations:** YouTube's own search, filtered to live (`params` `EgJAAQ%3D%3D`), on the
  video site's web client (`www.youtube.com/youtubei/v1/search`), signed out. Nothing of the
  account goes with it. It sends one query per genre (`LiveGenres`, 13), and keeps videos marked
  live now (`BADGE_STYLE_TYPE_LIVE_NOW`), once each, the most listened to first. A refused client
  version is replaced by the page's own and asked once more. `www.youtube.com` is an allowed host
  for this, that exact host only.
- **A station** is a `Track` of `MediaKind.LIVE`: the channel as its artist (not linked: a
  channel isn't an artist page), no duration.
- **Playing:** NewPipe's stream info gives the live HLS master playlist. `HlsAudio` picks its
  audio-only rendition, the AAC-LC one (the 128 kbps audio songs get) when there is one, and only
  without any the smallest variant. That playlist is handed over as a `DirectStream`, with its
  `expire` time, and the player plays it as live HLS.

### 8.4 Historical Note on Superseded Options

| Option | Description | Status |
|---|---|---|
| **DirectStream (Media3)** | In-app audio decoding in Media3 via stream extraction (BitChord reference) | **Authoritative Direction (D-48)** |
| **Delegated Playback (P2)** | Starting and controlling playback in the official YouTube Music app | Superseded by D-48 |
| **Official Embed (P1 / Y2)** | Visible video iframe embed in a foreground WebView | Superseded by D-48 |

---

## 9. Error Taxonomy and Health

`sources:youtubemusic` maps errors to `PodiumError`:
- `Offline`: No internet connectivity.
- `AuthExpired`: Account session invalid or expired; requires re-authentication.
- `RateLimited`: Backoff with `Retry-After` (HTTP 429, or bot checks for every app, §8.2).
- `NotPlayable`: every stream refused, or not allowed here (region, Premium, age).
- `NotFound`: the song is gone or private.
- `Server`: Transient remote failure, or the source resting.

The engine sends its classification to Now Playing by a stable name (`PlaybackErrors.kindOf`),
never a class's own name: release builds rename classes (issue #5, D-97).

A circuit breaker in `SourceHealthMonitor` distinguishes between transport failures (which open the breaker) and content misses (empty searches or deleted tracks, which never trip the breaker).

---

## 10. Open Technical Implementation Decisions

The following technical questions pertain to the upcoming implementation phase and represent engineering tradeoffs rather than product ambiguities:
1. **Extractor Integration Strategy:** decided (D-97): Podium's own walk over YouTube's apps, with NewPipeExtractor as the failsafe (§8.2).
2. **Cipher Execution Engine:** decided (D-98): yt-dlp's challenge solver (yt-dlp-ejs) run on the player's own script in JavaScriptSandbox, or a hidden WebView where it isn't offered (§8.2).
3. **BotGuard PoToken Strategy:** Establishing the most resilient mechanism for generating Proof-of-Origin tokens on Android (e.g., headless WebView helper or equivalent).
4. **Seeking & Buffer Configuration:** Tuning Media3 `DefaultLoadControl` and OkHttp data source buffer sizes for progressive audio streams.
