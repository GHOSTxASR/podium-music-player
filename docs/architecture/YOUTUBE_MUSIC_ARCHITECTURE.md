# YouTube Music architecture

Status: implemented on branch `ccr-fcca9ac9-6juw47` (2026-10-06), D-38. Device acceptance on the
Nothing Phone (3a) **not yet performed** (no device reachable from the build container; see
`docs/testing/YOUTUBE_MUSIC_DEVICE_ACCEPTANCE.md`). The proposal this implements is
`YOUTUBE_MUSIC_TRANSITION.md`; implementation facts and evidence are in
`docs/research/YOUTUBE_MUSIC_IMPLEMENTATION_NOTES.md`.

## 1. Summary

Podium has two environments (D-34): **LOCAL** — music on the phone, played by Podium's own Media3
player — and **ONLINE**, which is now one service, **YouTube Music**.

| Concern | How | Owner |
|---|---|---|
| Catalogue, search, home, albums, artists, playlists, radio | the music web client's browsing endpoints (`search`, `browse`, `next`) | `sources:youtubemusic` |
| Account library, likes, history | the listener's own web session (signed in on Google's page), sealed at rest | `sources:youtubemusic` + app `KeystoreCredentialStore` |
| Playback | **the official YouTube Music app**, under the listener's own account; Podium starts it and controls it through Android's media-session interface (P2), or hands the song over (P0) | `player:remote` + `OwnerAwarePlaybackController` |
| Local playback | unchanged: Media3 in `PlaybackService`, `QueueManager` the only queue writer | `player:service` |

Podium never requests, extracts, decrypts, caches or plays a YouTube media stream, and never calls
the player endpoint. Media3 never sees a YouTube item.

## 2. Positions and boundaries

- **D-20 revised (D-38):** catalogue on `Basis.UNOFFICIAL_API` (Y1), approved by the user's
  direction of 2026-10-06; playback delegated to the official app (P2) with hand-off (P0) as the
  fallback. Y3 (Podium-owned YouTube audio) stays unbuilt: ADR-013 forbids it.
- **Never built** (CLAUDE.md, ADR-013): cipher or throttle solving, PoToken/BotGuard, client-identity
  rotation, age-gate bypass, DRM removal, downloads, background-audio unlocking, player-JS execution,
  NewPipeExtractor/InnerTubeX or similar, impersonated playback reporting.
- **Providers never leak:** features read capabilities, environment, route and owner. The only
  provider-specific code is `sources:youtubemusic` (and one package name in the app's graph and
  manifest `<queries>`).
- **Settings ▸ Online music** is the one place the service is named in browsing UI (D-35).

## 3. Modules

```
sources:api ──────────── facets, capabilities, AuthFacet.webSignIn, AccountLibraryFacet, RemoteContext
sources:youtubemusic ─── pure JVM: Transport, YouTubeMusicClient, parsers, mapper, paging, YouTubeMusicSource
player:api ───────────── RemotePlayback, RemoteSessionState, OwnerAwarePlaybackController, PlaybackRouter
player:remote ────────── Android: MediaSessionRemotePlayback, MediaSessionAccessService
core:database ────────── schema v5 (track.media_kind), account-scoped online tables
feature:online ───────── Online menus, search, detail, library, history (provider-free)
feature:settings ─────── OnlineServiceScreen (account, app, media-control access, hand-off choice)
app ──────────────────── AppGraph wiring, OnlineMusicRepository, WebSignInActivity, RetiredSources
```

Retired: `sources:audius`, `sources:subsonic`, configured-source forms and multi-source aggregation
(`MultiSourceCatalog`); their stored credentials are deleted at startup (`RetiredSources`).

## 4. Identity and model

- Ids are source-qualified: `TrackId("ytmusic|<videoId>")`, `AlbumId("ytmusic|MPRE…")`,
  `ArtistId("ytmusic|UC…")`, `PlaylistId("ytmusic|<playlistId>")`. Nothing outside the module
  parses them.
- `Track.kind: MediaKind` (`SONG`, `MUSIC_VIDEO`, `VIDEO`, `EPISODE`) — schema v5 `track.media_kind`
  — so a video is never silently treated as a song; search has a separate Videos section.
- `providerData` carries the module's own hints (`k=KIND;e=setVideoId`) and is opaque elsewhere.
- Routes: every YouTube Music track is `{REMOTE}` only; local songs are `{DIRECT}`. The owner-aware
  controller uses that to keep the environments apart (§8.3).

## 5. Catalogue client and parsers

- **Transport** (`UrlConnectionTransport`): https only; allowed hosts `music.youtube.com`,
  `*.googleusercontent.com`, `*.ytimg.com`, `*.ggpht.com`; no redirects followed; 12 MB answer cap;
  exceptions reduced to their class name (no URL, header or body ever reaches a log).
- **Client** (`YouTubeMusicClient`): `POST /youtubei/v1/{search|browse|next|like/like|like/removelike|account/account_menu}`
  with the web client's context (`WEB_REMIX`, the page's own client version and visitor data read
  from the home page's `ytcfg`, falling back to a recent version), the device's own browser user
  agent and the listener's region. Signed in, it adds the session cookie, `Authorization:
  SAPISIDHASH <ts>_<sha1(ts SAPISID origin)>` and `X-Goog-AuthUser`.
- **Errors** (§11): 400 → refresh the page config once and retry; 401/403 signed in → `AuthExpired`;
  404 → `NotFound`; 429 → `RateLimited(Retry-After)`; 5xx and unreadable JSON → `Server`; unknown
  host → `Offline`; a signed-in answer with `logged_in = 0` → `AuthExpired`.
- **Parsers fail soft, never crash** (`Json.kt` path helpers): a missing piece drops one row, not
  the page. Renderers handled: responsive list items, two-row items, panel items, top result cards,
  carousel and list shelves, album/playlist headers, artist pages, `next` queues, library and history
  lists; podcasts and profiles are skipped.
- **Paging**: search and pages carry continuations (both the legacy `ctoken` URL style and the
  body `continuation` style); `Cursor`/`CursorCache` turn them into offset paging for the UI.
  Albums and playlists follow continuations up to 1 000 tracks; home loads two more shelf pages.
- **Search filters** (songs, videos, albums, artists, featured and community playlists) are the web
  client's protobuf `params`, encoded in `SearchFilters`.

## 6. Account and sign-in

- **Why a web session:** the account library, likes and history exist only behind the listener's
  YouTube Music session; Google offers no OAuth scope for YouTube Music's library. Podium uses the
  **service's own sign-in page** in a locked-down WebView (`WebSignInActivity`): Podium never sees
  the password or second factor; it keeps only the resulting session cookies.
- **WebView:** starts at `accounts.google.com/ServiceLogin?service=youtube` continuing to
  music.youtube.com; https only, navigation limited to `google.com`, `youtube.com`, `gstatic.com`,
  `googleusercontent.com`, `ggpht.com`, `ytimg.com`, `googleapis.com` and their subdomains; no file or content access; no mixed content; no geolocation;
  safe browsing on; certificate errors always cancel; `FLAG_SECURE`; cookies, web storage, cache,
  history and form data wiped before and after every sign-in.
- **Done when** the music origin holds `SAPISID` (or `__Secure-3PAPISID`) after landing back on
  music.youtube.com. The source validates the session with `account/account_menu` before keeping it.
- **At rest:** `{session, account name, account key}` sealed by `KeystoreCredentialStore` (AES-256-GCM,
  key in the Android Keystore, never leaves it). Never in the database, prefs plaintext, logs or
  `toString` (`WebSession` prints only a cookie count).
- **Account key:** `ytm-<sha-256 prefix of the account name>` — the database stores this, never the
  name or e-mail.
- **States:** `SignedOut`, `SigningIn` (the WebView), `SignedIn(name, key)`, `Expired` (any refused
  account call deletes the session and asks to sign in again), and errors as `SignInResult.Refused`.
- **Sign out / switch account:** wipe the account's online data (likes, playlists, observed history,
  caches), delete the sealed session, then sign in again with the same flow.
- **Known risk:** Google may refuse sign-in inside an embedded WebView ("This browser or app may not
  be secure"). Unverified from the container; listed as device step A-1. Without sign-in the
  signed-out catalogue, search and playback still work.

## 7. Library, likes, history

- **The account is authoritative** for liked songs, playlists, albums, artists and the service's
  own history (browse ids `VLLM` (liked songs), `FEmusic_liked_playlists` (without the liked-songs
  list), `FEmusic_liked_albums`, `FEmusic_library_corpus_track_artists`, `FEmusic_history`).
- **Likes** are written to the service (`like/like`, `like/removelike`) and cached per account in
  ONLINE's own table (write-through, reverted on failure, refreshed every 2 min at most, 500 kept).
  Local favorites never change from online likes and vice versa (D-34).
- **History has two lists:** "History" is the account's own (what the service says was played);
  "Played on Podium" is what Podium observed playing (time actually heard, ≥ 5 s), stored per account
  in ONLINE's history table.
- **Playlists** play; editing them isn't offered (capability DEGRADED: "Playlists can be played, not
  edited").

## 8. Playback

### 8.1 Options considered

| Option | What | Verdict |
|---|---|---|
| Y3 | Podium plays YouTube audio in Media3 | **forbidden** (ADR-013) |
| P1 | the official embedded player (IFrame) in a WebView | possible; visible video required (RMF), no background; not built |
| **P2** | the official YouTube Music app plays; Podium controls it through Android media sessions | **built** |
| **P0** | open the song in the official app | **built** (fallback, and how P2 starts when the session can't) |

### 8.2 Delegated playback (P2)

- `MediaSessionAccessService` is a notification-listener service with **no notification handling**:
  Android requires that grant for an app to see other apps' media sessions
  (`MediaSessionManager.getActiveSessions`). Settings ▸ Online music ▸ Media controls explains and
  opens the system screen; Android 13+ may block the grant for sideloaded apps until "Allow
  restricted settings" is chosen in the app's info (the screen says so).
- `MediaSessionRemotePlayback` attaches to the official app's session (`com.google.android.apps.youtube.music`)
  and mirrors it (`RemoteSessionState`: playing, buffering, position, duration, metadata, queue when
  exposed, advertised actions).
- **Starting a song:** `TransportControls.playFromUri(watch URL)` when the session advertises it
  (Podium stays in front); otherwise `ACTION_VIEW` on the song's `music.youtube.com/watch` link. With
  **After choosing a song: Stay here** (default) the app is started behind Podium, which returns to
  the front (`startActivities`); **Show YouTube Music** leaves it visible.
- Controls are sent only when the session advertises them (play, pause, seek, skip, queue item).

### 8.3 Owner-aware controller

`OwnerAwarePlaybackController` is the `PlaybackController` the UI uses:

- **Owners:** LOCAL (Podium's Media3 player and queue) and REMOTE (the official app). Never both;
  switching is a hard cut through `PlaybackRouter` (the outgoing owner pauses first).
- **The local queue is never touched** by remote playback: it waits paused exactly as it was;
  "Back to my music" (`resumeLocal`) or playing any local song resumes it.
- **No mixed queues:** a remote-only song is never handed to the local player; Play next / Add to Up
  Next are hidden for online songs; a context with online songs plays remotely as a whole
  (`RemoteContext.Collection`, `Radio`, `ArtistRadio`).
- **Start confirmation:** after a start, Podium waits up to 12 s for the session to show the song;
  otherwise the state says it didn't start (`RemoteProblem.DID_NOT_START`) with a next step.
- **Adoption:** if the listener starts playback in the app while nothing local plays, Podium adopts
  it as REMOTE and mirrors it; a local intent to play takes over again.

### 8.4 Now Playing and Up Next when another app plays

- Now Playing shows the remote song with catalogue artwork when recognised; transport, scrub and
  shuffle/repeat appear only as the session's actions allow; a quiet note says where it plays; More
  ▸ Open app / Back to my music.
- Up Next mirrors the app's queue read-only when the session exposes one; otherwise it says the
  queue is in the app (hidden, never invented). "Back to my music" and "Open app" rows.
- Problems are named: no app ("Install YouTube Music"), no access ("Allow media controls"),
  didn't start, ended.

### 8.5 Radio and autoplay

Radio from a song (`RDAMVM<videoId>`) or an artist plays in the official app, which continues on
its own (the provider's autoplay). Podium's autoplay engine stays LOCAL-only and never mixes
environments.

## 9. Online for the screens

`OnlineMusicRepository` (app) is the only way the screens reach the service: every call has a 15 s
timeout and goes through the source's health (`SourceHealthMonitor`: failures open the breaker,
misses never count, offline counts against no one); home shelves are cached 10 min; status tells the
screens what they can show (`canLibrary`, `canHistory`, `canExplore`, account). Screens never see the
provider.

## 10. Settings

Settings ▸ Online music (`OnlineServiceScreen`): Online music (On/Off); Account (Sign in, Signing
in, Sign in again, or the account's name, which opens Switch account and Sign out); YouTube Music
(Installed / Install); Media controls (Allowed / Allow, with the restricted-settings hint); After
choosing a song (Stay here / Show YouTube Music); the basis note (D-19).

## 11. Error taxonomy and health

`PodiumError`: `Offline`, `Network`, `Server(code)`, `RateLimited(retryAfter)`, `NotFound`,
`AuthRequired`, `AuthExpired`, `Unsupported`, `NotPlayable(reason)`, `UserActionRequired(action)`.
`isTransient` decides retry; misses (`NotFound`, empty) never trip the breaker. Copy explains and
gives a next step ("Couldn't reach online music. Music on this phone still plays.").

## 12. Database

Schema v5 (AutoMigration 4→5, additive): `track.media_kind TEXT NOT NULL DEFAULT 'SONG'`. Content
hashes of existing songs are unchanged (the kind is folded in only for non-songs). Online tables are
keyed by account (`ytm-…`); sign-out deletes the account's rows. Migration tested (`MigrationTest`
4→5, `SchemaV5Test`).

## 13. Security and privacy

See §6 for the session. Also: `usesCleartextTraffic=false` (the LAN-server exception left with
OpenSubsonic); `allowBackup=false`; the playback service grants custom commands only to Podium's own
package; debug commands exist only in debug builds; logs carry no URLs, cookies, account names or
titles (the debug `remote-probe` prints facts, shapes and counts only). Audit: `docs/security.md` §9.

## 14. Tests

`sources:youtubemusic`: parser tests on hand-written fixtures (search, top card, shelves, album,
playlist, artist, next, library, history, account, continuations, durations), source tests with a
scripted transport (auth states, expiry, sign-in validation, capability changes, catalogue routing,
likes, radio contexts, error mapping), session and paging tests (SAPISIDHASH test vector, cookie
parsing, cursor offsets). `player:api`: owner-aware controller (hard cuts, local queue preserved,
no remote songs in the local queue, start timeout, adoption). `player:remote`: Robolectric
media-session tests. App: online repository (likes write-through and race guard, sign-out wipe),
retired-source cleanup. Database: v4→v5 migration.

## 15. Unknowns that need the phone (U1–U6)

| # | Question | Handled now by |
|---|---|---|
| U1 | Does the app's session advertise `playFromUri`? | falls back to the link hand-off |
| U2 | Is its queue exposed? | Up Next says the queue is in the app |
| U3 | Does seek work through the session? | scrub shown only if advertised |
| U4 | Does a start behind Podium actually play? | 12 s confirmation, "didn't start" state |
| U5 | Does playback continue in the background without Premium? | the app's own rules apply; documented |
| U6 | Does sign-in in the WebView succeed? | signed-out catalogue and playback still work |

`adb shell am start -n app.podium.debug/app.podium.MainActivity --es podium.debug remote-probe`
logs the facts for U1–U4 to logcat tag `PodiumDebug` (debug builds only).

## 16. Limits

- Unofficial basis: the web client's shapes can change; parsers fail soft and say "Couldn't load".
- Signed-out catalogue from some regions/IPs is thin (podcasts and videos only from a US cloud IP).
- No playlist editing, no downloads, no lyrics from the service, no Podium-side queue editing of
  remote playback.
- Device acceptance pending (§15 and the acceptance document).
