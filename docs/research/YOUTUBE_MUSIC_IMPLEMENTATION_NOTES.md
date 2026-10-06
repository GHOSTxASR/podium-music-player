# YouTube Music implementation notes

Date: 2026-10-06. Companion to `architecture/YOUTUBE_MUSIC_ARCHITECTURE.md`. Records what was
observed, what was assumed, and what still needs the phone — so nobody mistakes an assumption for a
measurement.

## 1. Environment

- Work happened in a cloud build container (Linux, no USB, no KVM, no Android device or emulator).
  Outbound HTTPS went through an egress proxy; the container's address is a Google Cloud US address.
- Builds: Gradle wrapper, JDK 21 toolchain, Android SDK platform 37 (compileSdk 37, targetSdk 36,
  minSdk 29), Robolectric (SDK 35 runtime)
  with native graphics for screenshots.

## 2. What was observed live (signed out, 2026-10-06)

| Request | Observation |
|---|---|
| `GET https://music.youtube.com/` | 200, ~270 KB HTML; `ytcfg` held `INNERTUBE_CLIENT_NAME: WEB_REMIX`, `INNERTUBE_CONTEXT_CLIENT_NAME: 67`, `INNERTUBE_CLIENT_VERSION: 1.20261004.17.00`, `VISITOR_DATA`, `GL: US`, `HL: en` |
| `POST /youtubei/v1/search` (context `WEB_REMIX` + that version) | 200, ~110 KB; `contents.tabbedSearchResultsRenderer` → a `musicCardShelfRenderer` (top result) and `itemSectionRenderer` sections. From this address the results were podcasts and videos only — no songs or albums |
| `POST /youtubei/v1/browse` `FEmusic_home` | 200, ~125 KB; `singleColumnBrowseResultsRenderer` with a `musicCarouselShelfRenderer` "Podcasts to get you started" (14 items) and a `musicTastebuilderShelfRenderer`; no continuation |

Further live probing (artist, album, playlist, `next`) was stopped by the environment's safety
policy and was **not** pursued by any other route. Consequences:

- The parsers for album, artist, playlist, `next`, library and history follow the shapes the music
  web client is publicly known to return; their fixtures are hand-written, not captured. They are
  written to fail soft (a missing field drops a row, never the page).
- The signed-out catalogue from a datacenter address is thin; a phone on a residential network, and
  especially a signed-in session, is expected to see the full catalogue. Unverified.

Podium never used the player endpoint and never requested a stream.

## 3. Decisions taken from the evidence

1. **Page config at runtime.** The client version changes often; Podium reads it (and visitor data)
   from the home page's `ytcfg` and falls back to a recent value. A 400 refreshes the page config
   once.
2. **Degrade honestly when signed out.** Search and browse report DEGRADED ("Sign in for the full
   catalogue") until signed in; search shows a sign-in hint above results.
3. **Videos are videos.** Search results can be videos and podcasts; `MediaKind` keeps them apart and
   the UI shows videos in their own section with a note.
4. **The session, not OAuth.** No OAuth scope covers YouTube Music's library; a web session from
   Google's own sign-in page is the only account path that doesn't hand Podium a password.
5. **Playback through the official app.** P2/P0 are the only routes that play music the listener is
   entitled to without Podium touching media.

## 4. Assumptions that need the phone

| # | Assumption | If wrong |
|---|---|---|
| A-1 | Google lets the account sign in inside an embedded WebView | sign-in fails with "browser may not be secure"; signed-out catalogue and playback still work; next step would be a Custom Tab + manual session import (not built: it would require the listener to copy cookies, which is worse) |
| A-2 | The official app's media session is visible with notification-listener access | Podium can only hand songs over (P0); Now Playing shows the hand-off state |
| A-3 | `playFromUri` is advertised (U1) | Podium opens the link instead; with Stay here Podium returns to the front |
| A-4 | The app exposes its queue (U2) | Up Next says the queue is in the app |
| A-5 | Seek and skip work through the session (U3) | the controls appear only as advertised |
| A-6 | A start behind Podium plays (U4) | the 12 s confirmation reports "didn't start" with a next step |
| A-7 | Background playback for non-Premium accounts (U5) | the app's own rules apply; documented, not worked around |
| A-8 | Parser shapes for album/artist/playlist/next/library/history | rows missing; "Couldn't load" states; fix the parser with a captured fixture |

## 5. Security notes

- The page's embedded public key is not used or stored; requests carry only context, cookies and
  the SAPISIDHASH header.
- `WebSession` keeps cookies in memory only while needed; `toString` prints a count.
- Test fixtures use invented values (`SAPISID=sapi-secret/123`); a scan of the tree and its history
  found no real keys, tokens, cookies, signing keys or machine paths.

## 6. Provider-change playbook

When the service changes its responses:

1. Capture the failing response on the phone (debug build), strip personal data, add it as a fixture.
2. Fix the parser against the fixture; keep failing soft.
3. If the client version is refused, the page-config refresh should recover; if not, update the
   fallback version.
4. Never respond to a block by changing client identity or adding evasion (ADR-013).

## 7. References

- `architecture/YOUTUBE_MUSIC_TRANSITION.md` (proposal, options, BitChord comparison — BitChord was
  read as documentation only; its source was not opened while implementing, ADR-014).
- Android `MediaSessionManager.getActiveSessions` (requires an enabled notification listener),
  `MediaController.TransportControls.playFromUri`, restricted settings for sideloaded apps (Android 13+).
