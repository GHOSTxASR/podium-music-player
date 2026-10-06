# YouTube Music device acceptance

Target: Nothing Phone (3a), its current Android release, the official YouTube Music app installed
and signed in to the listener's account; Podium debug build `app.podium.debug` 0.1.0 (1).

**Status (2026-10-06): NOT RUN.** The implementation was built and tested in a cloud container with
no phone attached (no USB, no emulator acceleration). Every step below is open. Nothing in this
document is a claim that a step passed. Automated coverage is listed per area so the device run can
focus on what only a device can show.

## 0. Setup

1. `adb install -r artifacts/apk/podium-debug.apk` (or `./gradlew :app:installDebug`).
2. Keep a logcat open: `adb logcat -s PodiumDebug PodiumDisplayImage AndroidRuntime`.
3. Do not change the phone's volume or system settings except the grants named below; revoke them
   afterwards if asked.

## 1. Questions only the phone can answer

| # | Question | How | Record |
|---|---|---|---|
| A-1 | Does Google allow sign-in in Podium's WebView? | Settings ▸ Online music ▸ Account ▸ Sign in | success / the exact refusal text |
| U1 | Does the app's session advertise `playFromUri`? | play a song in YouTube Music, then `adb shell am start -n app.podium.debug/app.podium.MainActivity --es podium.debug remote-probe` | `actions=` in the log |
| U2 | Is the app's queue exposed? | same probe | `queue=` |
| U3 | Do seek / skip work through the session? | Now Playing scrub and ⏮ ⏭ while the app plays | yes / no per control |
| U4 | Does a start behind Podium play? | choose a song with "After choosing a song: Stay here" | plays within 12 s / "didn't start" |
| U5 | Background play without Premium | lock the phone while the app plays (non-Premium account) | continues / stops (the app's own rule) |
| U6 | Restricted settings on a sideloaded build | Media controls ▸ Allow | grant offered directly / needs "Allow restricted settings" |

## 2. The 28 steps

| # | Step | Expect | Automated coverage |
|---|---|---|---|
| 1 | Launch Podium | boot screen, Home; local library intact | PaperLayoutTest, database tests |
| 2 | Home ▸ Online | Home, Search, Library, Radio, History, Sign in (or account) | OnlineLayoutTest |
| 3 | Search a known artist | songs, albums, artists, playlists, videos sections; signed out: the sign-in hint | parser + source tests |
| 4 | Open an album | header, tracks with lengths, cover beside each row | parser tests (fixtures) |
| 5 | Open an artist | picture header, radio, popular songs, albums, related | parser tests (fixtures) |
| 6 | Open a playlist | tracks; long playlists page in | paging tests |
| 7 | Select a song | Now Playing shows it as starting, then playing in YouTube Music | OwnerAwarePlaybackControllerTest |
| 8 | Playback starts | audio from the YouTube Music app; Podium mirrors it | MediaSessionRemotePlaybackTest |
| 9 | Pause | pauses in the app | same |
| 10 | Resume | resumes | same |
| 11 | Seek | only if the session allows (U3) | same |
| 12 | Next | the app's next song; Podium follows | same |
| 13 | Previous | the app's previous / restart | same |
| 14 | Queue | Up Next mirrors the app's queue read-only (U2), or says it's in the app | UpNext tests |
| 15 | Background | Podium in the background: the app keeps playing (U5) | — |
| 16 | Screen lock | lock screen controls are the app's; Podium resumes mirroring on unlock | — |
| 17 | Bluetooth / media buttons | go to the app playing (Android routes them to the active session) | — |
| 18 | Notification | the app's own notification for the online song; Podium's own (if local music was playing) shows it paused | — |
| 19 | App restart (Podium) | Podium reattaches to the playing session and shows it | adoption test |
| 20 | Provider app restart | Podium shows "ended" then reattaches when it plays again | session-destroyed test |
| 21 | Network loss | catalogue: "You're offline. Online music needs a connection. Music on this phone still plays."; local playback unaffected | repository tests |
| 22 | Network recovery | catalogue loads again without restarting | health tests |
| 23 | Account logout | Settings ▸ Online music ▸ Account ▸ Sign out: likes, playlists, history of that account removed from the phone; local favourites untouched | OnlineMusicRepositoryTest |
| 24 | Account login | sign-in page; back in Podium the account name shows; Library loads (A-1) | source sign-in tests |
| 25 | Expired session | revoke the session (sign out on the web); the next account call shows "Your sign-in expired. Sign in again from Settings." and Settings offers Sign in again | expiry tests |
| 26 | Local → online | play a local song, then an online one: local pauses (hard cut), its queue kept | controller tests |
| 27 | Online → local | "Back to my music" or play a local song: the app pauses, the local queue resumes where it was | controller tests |
| 28 | Repeated launches | ten cold starts: no crash, state restored | — |

## 3. Results

| Step | Result | Notes |
|---|---|---|
| all | not run | no device available to the build container (2026-10-06) |
