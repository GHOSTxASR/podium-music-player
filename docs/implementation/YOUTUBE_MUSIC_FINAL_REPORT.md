# YouTube Music — final report

Date: 2026-10-06 · Branch: `ccr-fcca9ac9-6juw47` (GitHub `GHOSTxASR/podium-music-player`) · Base: `main` at `b850587`.

> **Historical Context Notice (D-48):** This report documents the interim implementation milestone executed under D-38, which implemented delegated playback (P2) through the official YouTube Music app. Under the authoritative product decision **D-48** ([`YOUTUBE_MUSIC_ARCHITECTURE.md`](../architecture/YOUTUBE_MUSIC_ARCHITECTURE.md)), the delegated playback approach is superseded in favor of **direct in-app Media3 stream playback** (`PlaybackTarget.DirectStream(PlayableMedia)`), with BitChord serving as an architectural and implementation reference. Offline/local playback remains stable and untouched. This report is preserved as a record of the D-38 transition work (catalogue, web session auth, database v5, lyrics, and UI enhancements).

**Read this first.** Everything below was built and tested in a cloud container with no Android
device attached. Builds, unit tests and Robolectric (rendered UI) tests ran and pass. **Nothing was
installed or run on the Nothing Phone (3a).** Where a result depends on the phone, this report says
"not verified on device" — it is not a pass.

## 1. What was implemented

- **YouTube Music as Podium's online service** (`sources:youtubemusic`): search (songs, videos,
  albums, artists, featured and community playlists, top result; continuations), home shelves (with
  continuation pages), album, artist and playlist pages (long lists paged), radio from a song or an
  artist, artwork, account sign-in/out/expiry/switch, the account's library (liked songs, playlists,
  albums, artists), its history, likes written to the account.
- **Legitimate playback through the official YouTube Music app** (`player:remote`): Podium starts a
  song or a whole context in the app (media-session `playFromUri`, or the song's link), then
  controls and mirrors it through Android's media-session interface. Podium never handles YouTube
  media.
- **Strict LOCAL / REMOTE ownership** (`OwnerAwarePlaybackController`): hard cuts, the local queue
  preserved and restorable ("Back to my music"), online songs never in the local Media3 queue,
  owner-aware Now Playing and an honest Up Next.
- **Retirement** of Audius, OpenSubsonic, configured sources and multi-source aggregation, with
  stored credentials of retired servers deleted at startup.
- **Database v5** (additive), **security audit**, **documentation**.
- **Secondary mission:** edge-aware focus geometry and the text-clipping fix; body glitter; display
  fonts; a Custom display theme with solid or picture backgrounds, opacity and text contrast;
  Now Playing ▸ Lyrics with synced lyrics from LRCLIB in a full-screen alternating lyric mode.

## 2. Architecture

`docs/architecture/YOUTUBE_MUSIC_ARCHITECTURE.md` (as built), `YOUTUBE_MUSIC_TRANSITION.md`
(proposal + execution outcome). In one picture:

```
Online UI (provider-free) ─ OnlineMusicRepository ─ SourceRegistry ─ YouTubeMusicSource (sources:youtubemusic)
                                                                         ├─ catalogue: search / browse / next
                                                                         ├─ account: sealed web session
                                                                         └─ playback: PlaybackTarget.RemoteProvider
UI ─ OwnerAwarePlaybackController ─┬─ LOCAL: MediaControllerPlaybackController → PlaybackService (Media3) → QueueManager
                                   └─ REMOTE: MediaSessionRemotePlayback → the official app's media session
```

## 3. YouTube Music access method

The music web client's browsing endpoints (`POST /youtubei/v1/search|browse|next`, `like/like`,
`like/removelike`, `account/account_menu`) with the `WEB_REMIX` context, the page's own client
version and visitor data read at runtime. `Basis.UNOFFICIAL_API` (D-19), approved by D-38. The player
endpoint is never called; no stream is ever requested.

## 4. Authentication method

The service's own sign-in page (accounts.google.com → music.youtube.com) in a locked-down WebView;
Podium keeps only the session cookies, validated with `account_menu`, sealed by the Keystore-backed
credential store (AES-256-GCM). Requests carry the cookies and a `SAPISIDHASH` header. Expiry
(401/403 or `logged_in = 0`) deletes the session and asks to sign in again. Sign out wipes the
account's online data. The database stores only `ytm-<sha-256 prefix>`. **Not verified on device**:
Google may refuse sign-in in an embedded WebView (A-1).

## 5. Playback method

P2 (delegated): the official app plays under the listener's account; Podium sends play/pause/seek/
skip only as the app's session advertises them, mirrors its state and queue. P0 (hand-off) when the
session can't start a song: the song's link opens the app; with "After choosing a song: Stay here"
Podium returns to the front. **Not verified on device** (U1–U6 in the device plan).

## 6. Playback ownership

Two owners, never both: LOCAL (Podium's Media3) and REMOTE (the official app). Switching pauses the
outgoing owner first. The local queue is untouched by remote playback. A start is confirmed within
12 s or reported as "didn't start". Playback the listener starts in the app is adopted as REMOTE
while nothing local plays.

## 7. Catalogue implementation

Parsers over the web client's renderers (responsive and two-row items, cards, shelves, headers,
artist pages, `next` queues, library and history lists), failing soft. Continuations (both styles)
behind offset cursors. `MediaKind` keeps videos and episodes apart from songs (schema v5). Live
check (signed out, cloud address): page config, search and home answered 200 with the expected
top-level renderers; results were podcasts and videos only. Album/artist/playlist/next shapes are
from public knowledge with hand-written fixtures (live probing beyond search/home was stopped by the
environment's safety policy) — **to be confirmed on device**.

## 8. Search

Typed sections (songs, videos, albums, artists, playlists); "more" pages
by continuation; songs-only paging; a sign-in hint when signed out. Tests: parser and source tests.

## 9. Library

Online ▸ Library: Liked songs, Playlists, Albums, Artists from the account (paged), when signed in.

## 10. Likes

Like/unlike from track menus and Now Playing write to the account, with a per-account cache
(write-through, reverted on failure, refreshed at most every 2 min, a write counter prevents a
stale refresh from undoing a fresh like). Local favourites are separate (D-34).

## 11. Playlists

The account's playlists and public playlists open and play (as a whole context in the app).
Editing playlists is not offered (capability DEGRADED, stated in the UI).

## 12. History

Two lists: History (the account's own, from the service) and Played on Podium (what Podium heard
playing, ≥ 5 s, stored per account in ONLINE's table).

## 13. Radio

Start radio from a song (`RDAMVM<id>`) or an artist; it plays in the official app.

## 14. Autoplay

Remote playback continues with the app's own autoplay/radio. Podium's autoplay engine stays LOCAL
and never mixes environments.

## 15. Queue

Local: unchanged (`QueueManager` the only writer). Remote: Up Next mirrors the app's queue
read-only when exposed, or says the queue is in the app; online songs never enter the local queue
(Play next / Add to Up Next hidden for them).

## 16. Now Playing

Owner-aware: the remote song with catalogue artwork when recognised, controls as the session
allows, a quiet note on where it plays, More ▸ Lyrics / Open app / Back to my music; problems named
(no app, no access, didn't start, ended). New: the Lyrics action.

## 17. Offline compatibility

Offline code paths unchanged; every Offline test passes (library, database, queue, playback service,
paper layouts). LOCAL playback never routes through the remote engine. **Not verified on device.**

## 18. Database migrations

v4 → v5 AutoMigration: `track.media_kind` (default `SONG`); content hashes of existing songs
unchanged. Tested: `MigrationTest` (v4 → v5 with data), `SchemaV5Test` (4 tests).

## 19. Security changes

Sealed web session; WebView hardening (+`FLAG_SECURE` from the audit); https-only transport with a
host allow-list and no redirects; no secrets in logs; `usesCleartextTraffic=false` again;
`allowBackup=false`; custom media-session commands only for Podium's own package; lyrics only with
consent; background pictures through the system picker without storage permission. Repository and
APK scanned: no keys, tokens, cookies, signing keys, personal data or databases. Audit table:
`docs/security.md` §9.

## 20. Device test results

**Not performed.** No device was reachable from the build container. The full plan, with the
questions only the phone can answer (A-1, U1–U6) and the 28 steps, is
`docs/testing/YOUTUBE_MUSIC_DEVICE_ACCEPTANCE.md`; UI steps (focus, customization, lyrics) are in
`docs/testing/UI_DEVICE_ACCEPTANCE.md`. A debug command (`remote-probe`) logs the facts U1–U4 need.

## 21. Test count

`./gradlew test`: **492 tests, 0 failures, 0 errors, 0 skipped** (JVM unit tests and Robolectric
tests; there are no instrumented `androidTest` suites).

| Module | Tests |
|---|---|
| sources:api | 157 |
| player:api | 54 |
| core:database | 45 |
| sources:youtubemusic | 41 |
| core:designsystem | 43 |
| feature:nowplaying | 23 |
| app | 24 |
| core:lyrics | 27 |
| core:interaction | 19 |
| player:service | 12 |
| core:model | 10 |
| feature:library | 9 |
| feature:online | 7 |
| sources:local | 7 |
| player:remote | 4 |
| feature:settings | 10 |

## 22. Known limitations

- **No device acceptance** (§20); live catalogue verified only signed out from a datacenter address.
- WebView sign-in may be refused by Google (A-1); without it, the account features are unavailable
  (the signed-out catalogue and playback still work).
- P2 depends on what the official app's session exposes (U1–U5); fallbacks are in place.
- Unofficial basis: response shapes can change; parsers fail soft.
- No playlist editing, no downloads, no service lyrics; remote queue is read-only.
- Lyrics: LRCLIB's community lyrics need a licensing review before any public release.
- The debug APK is 52.2 MB (unminified debug code), above GitHub's 50 MB recommendation.

## 23. Future work

1. Run the device plan; fix what it finds; capture real fixtures for album/artist/playlist/next/
   library/history.
2. If WebView sign-in is refused: evaluate a supported sign-in path; keep the signed-out experience.
3. Playlist editing, account-side "save to library", and a confirmation UI for "not the same song".
4. P1 (official embedded player) only if P2 proves unusable.
5. A release build (minified) and its own size and smoke test.

## 24. Git commits (branch, oldest first)

| Commit | Message |
|---|---|
| `85c21ca` | youtube: add YouTube Music source module (catalogue, search, home, library, account) |
| `b7d931d` | youtube: owner-aware playback with delegated remote engine |
| `f7b3f84` | database: schema v5 adds track.media_kind; account-scoped online ops |
| `824477c` | youtube: make YouTube Music the online service; retire Audius/OpenSubsonic |
| `a309b64` | feat(lyrics): synced lyrics from LRCLIB and a full-screen lyric mode |
| `468068e` | fix(paper): edge-aware focus geometry; lists never clip their rows |
| `9d035e4` | feat(appearance): body glitter, display fonts, a Custom theme and your own background |
| `83b10d0` | docs: YouTube Music architecture, implementation notes, device plan; security audit |
| `ba74ce2` | docs: YouTube Music final report |
| `992ad0a` | build: add verified debug APK artifact |
| `1ed91b7` | docs: record the APK commit in the final report |
| `ae65bf2` | fix(settings): row labels keep their room; Appearance fits the narrow paper |
| `10028f5` | build: update verified debug APK artifact |
| `c21ac53` | docs: final report for the updated APK |
| `923abdd` | feat(lyrics): words appear as they are sung |
| `e40439b` | feat(ui): a loading screen wherever content loads |
| `b82bc14` | feat(glitter): the glitter catches the light as the phone tilts |
| `0f3922e` | feat(keyboard): the Wheel becomes a keyboard |
| `f3a77a1` | build: update verified debug APK artifact |
| `f034e1c` | docs: final report for the third pass |
| `d11134c` | fix(ui): long titles ease into their scroll; music access is asked for |
| `6de8915` | build: update verified debug APK artifact |
| (next) | docs: final report for the fourth pass |

## 25. GitHub branch

`ccr-fcca9ac9-6juw47` on `GHOSTxASR/podium-music-player` (the session's designated branch; the
mission's suggested `feature/youtube-music-full` was not used because this environment allows
pushing only to the designated branch). No pull request opened.

## 26. Exact build command

```
export ANDROID_HOME=/path/to/android-sdk   # or sdk.dir in local.properties
./gradlew test assembleDebug               # tests, then the debug APK
./gradlew :app:assembleDebug               # the APK alone
```

(JDK 21 toolchain; compileSdk 37, targetSdk 36, minSdk 29.)

## 27. Exact APK output path

- Gradle output: `app/build/outputs/apk/debug/app-debug.apk`
- Committed copy: `artifacts/apk/podium-debug.apk`

| | |
|---|---|
| Build task | `:app:assembleDebug` |
| Build type | debug |
| applicationId | `app.podium.debug` |
| versionName / versionCode | 0.1.0 / 1 |
| Size | 52,205,051 bytes |
| SHA-256 | `c8a0f440a8675d1861217fae26cd27101c45a3c26acb56fc69e616c155623fcf` |
| Signature | Android debug certificate (SHA-256 `e68e65c9…0d4e11`, APK Signature Scheme v2), verified with `apksigner` |
| Built from | `d11134c` (later commits change only documentation and the artifact) |
| Secret scan of the APK | clean (no API keys, OAuth tokens, private keys, session cookies, keystores, databases, test fixtures, local paths) |
| Installed on a device | **no** |
| Smoke test on a device | **no** (rendered-UI tests only) |

### APK record

```
APK STATUS:        BUILT
APK PATH:          artifacts/apk/podium-debug.apk
APK SIZE:          52,205,051 bytes (49.8 MiB)
BUILD TASK:        ./gradlew :app:assembleDebug
BUILD TYPE:        debug
VERSION NAME:      0.1.0
VERSION CODE:      1
COMMIT:            6de8915 (artifact commit; built from code at d11134c; earlier artifacts 992ad0a, 10028f5, f3a77a1 superseded)
BRANCH:            ccr-fcca9ac9-6juw47 (verified on GitHub: blob f774f6be, 52,205,051 bytes, matches the build)
DEVICE INSTALL:    NOT AVAILABLE (no device reachable from the build container)
DEVICE SMOKE TEST: NOT RUN
```

## Acceptance summary

| Criterion | Status |
|---|---|
| Build | PASS |
| Unit tests | PASS (492/492) |
| Integration tests | PASS for Robolectric/JVM integration tests; no instrumented suite exists |
| Device build | PASS (debug APK built, signed, inspected); not installed |
| Offline playback / regression | automated PASS; not verified on device |
| YouTube Music search / catalogue / home | implemented and tested; live: signed-out search and home answered; full catalogue not verified |
| Account / library / playlists / likes / history | implemented and tested; not verified on device (A-1) |
| Playback / background / media controls | implemented (P2 + P0); clearly documented limitation: not verified on device (U1–U6) |
| Now Playing / Up Next | implemented and tested; remote queue read-only or hidden by design |
| Local ↔ online transition | implemented and tested (hard cuts, queue preserved); not verified on device |
| Security audit | PASS (code, tree, history, APK); one fix applied |
| Database migration | PASS (v4 → v5 tested) |
| Documentation | PASS |
| Git | clean, pushed |

## Secondary mission (UI, customization, lyrics)

- **Settings layout:** rendering the new Appearance screens showed `MenuRow` squeezing labels ("Vi…") because its value was measured first; labels now keep their room and Appearance rows carry no redundant values (`AppearanceLayoutTest`).
- **Focus list (D-40):** root causes found in rendered device frames — rows bent along the arc were
  clipped by the list's bounds ("tem 1"), the marquee clipped the focused title's glow, keep-in-view
  exempted the first/last rows, the lens was clamped to a band, short lists were centred and ends
  faded with nothing beyond. Fixed with a pure `FocusGeometry` (centre-riding focus with true ends,
  lists that fit start at the top, the lens glued to rows, edge-aware fades) and a list that reaches
  far enough to never clip. Tests: `FocusGeometryTest`, `FocusListGeometryTest` (1/2/3/5/10/50 rows,
  first/middle/last and back, three themes, two screen sizes, long titles).
- **Customization (D-41):** glitter (off by default; amount, density, size, opacity, subtle glint),
  six display fonts (bundled OFL faces), the Custom theme, None/Solid/Picture backgrounds with
  opacity and text contrast, persistence, Settings ▸ Appearance. Readability guaranteed over the
  whole sRGB cube for solid colours; veil and halo over pictures.
- **Lyrics (D-39):** LRCLIB by metadata with consent, a conservative matcher, LRC parsing, cache,
  one line on the whole display alternating light and dark, Wheel browsing, seek-to-line only when
  the player can seek, honest states. Docs: `LYRICS_ARCHITECTURE.md`.
- Device steps for all three: `docs/testing/UI_DEVICE_ACCEPTANCE.md` (not run).

## Third pass (words, loading, tilt glitter, keyboard)

- **Lyrics word by word (D-42):** synced lines now fill in as they're sung. Real word times come from enhanced-LRC stamps or LRCLIB's `lyricsfile`. Otherwise words are paced inside the line's real window by syllables; the first word is always on the line's time. Words fade in at their places in the fixed justified layout. Tests: `WordTimingTest`, the provider's `lyricsfile` case, and two `LyricsScreenTest` frames (words arriving, a line half sung).
- **Loading (D-43):** one loading screen, Podium's stepping pinwheel and a word, wherever content loads (online pages, library, local library), with a "Searching" row in Search. Nothing shows for the first 150 ms. Test: `LoadingScreenTest`.
- **Glitter that follows the tilt (D-44):** the gravity sensor (accelerometer fallback), remapped to screen rotation, moves a soft light across the body opposite to the tilt. Flakes in three facing groups catch and lose it, and the best-facing group flares. New settings: Glitter glow and Follow tilt, beside Glitter amount. The sensor listens only while resumed. Tests: `GlitterTiltTest`, and `CustomizationScreenshotTest.glitterFollowsTilt`, which checks the light moves left when tilted right and right when tilted left.
- **The Wheel becomes a keyboard (D-45):** focusing a text field (Search, a hex colour) turns the Wheel into Podium's keyboard; the close key, Back or leaving the screen turns it back. Settings ▸ Keyboard chooses Podium or Phone. In landscape the phone's keyboard is used. Tests: `KeyboardEditorTest`, and `WheelKeyboardTest` (Steel, Carbon, Glass), which types "Hi 5" with the keys, closes, and reopens with a tap.
  - Found while testing: Back would have left Search instead of closing the keyboard, because its handler was registered before the navigation's. It is now registered when the keyboard opens.
  - Found while testing: Delete would have kept repeating with a finger slid off the key. It now stops.
- Device steps: `docs/testing/UI_DEVICE_ACCEPTANCE.md` §3.14–3.16, §4, §5 and §2 (tilt). **None were run on a device.**

## Fourth pass (list-top jump, music access)

- **Text sat centred, then jumped to the side (D-46):** found by rendering the whole app frame by frame (new `AppShellTest`). Music's first row was the long "Allow access to music on this phone": focused, its clipped text sat mid-lens until the marquee started at full speed. Now the label is "Allow music access" (fits), and long focused titles rest 1.4 s and then ease into their scroll (`ScrollingTitleTest`). Album, artist and song lists no longer flash a centred "No albums yet" before the first sync.
- **No permission screen (D-47):** Podium now asks once when it first switches on, offers "Allow music access" on Home, and opens its system settings page once Android stops asking (`PermissionAsksTest`).
- **In-app YouTube Music playback, BitChord-style (historical note, superseded by D-48):** Not implemented in this interim build; at the time, stream extraction was ruled out under former ADR-013. Under **D-48**, this policy is superseded: Podium adopts in-app Media3 direct stream decoding using BitChord as an architectural and implementation reference.
- **Device test:** not possible from this session. The phone is attached to the user's computer; this session runs in a cloud container with no route to it (`adb devices` lists nothing). Steps: `docs/testing/UI_DEVICE_ACCEPTANCE.md` §1.7 and §6.

