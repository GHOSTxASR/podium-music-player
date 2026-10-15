# Changelog

All notable changes to Podium will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Continuous line lyrics theme (D-81):** an optional look for Now Playing ▸ Lyrics. A single ivory line writes the words by hand on a black sheet, flows from each phrase into the next and draws loops, spirals and notes through instrumental passages, with a touch of champagne gold at the pen. It follows the music; seeking or rewinding brings back the same drawing. In Settings ▸ Appearance ▸ Virtual display ▸ Lyrics theme. Classic stays the default.
- **Lyrics move with the bass (D-82):** in both lyrics themes the words swell very slightly with the music's kick drums, like a speaker cone, smoothly and only while Podium's own player plays. Not with reduced motion.

### Changed
- **Continuous line (D-82):** the pen moves at an even pace instead of stuttering, and changes speed smoothly from word to word. The line is a little bolder and glows softly, like a neon sign.

## [0.1.2] - 2026-10-12

Release notes: [docs/releases/v0.1.2.md](docs/releases/v0.1.2.md).

### Added
- **Sleep timer:** 15 to 90 minutes, the end of the song or the end of the album. The music fades out over the last 10 seconds, then pauses. In Now Playing ▸ More and Settings (D-75).
- **Equaliser:** ten bands from 31 Hz to 16 kHz, thirteen presets or your own curve, set with the Wheel and heard as you turn. In Settings (D-76).
- **Crossfade:** songs overlap by 2 to 12 seconds, except an album's tracks that run into each other. In Settings (D-77).
- **Go to album and Go to <artist>** in Now Playing's menu, one entry per artist, for local and online songs alike; an online song listed without its album or artist pages has them looked up (D-74, D-79). Online song menus name every artist too.
- Now Playing says when the sound comes from a video: "Audio from the music video" (D-74).
- **Cover slide on every song change (D-78):** the playing cover slides off to the left as the next one comes in from the right, even when they share a cover; Previous runs the other way. Covers either side are loaded ahead, so online covers arrive already showing.

### Changed
- A song played from search is followed by songs like it, not by the rest of the search results (D-74).
- Album searches show full albums before EPs and singles (D-74).
- Search results always list songs first, then albums, whatever was searched (D-80).

### Fixed
- **Go to album and Go to <artist> missing for online songs (D-80):** a song's lookup for its album and artist pages now finishes even if the screen that started it closes, is tried again after a failure, and keeps what it found when a list stores the song again without them. Links that don't say which page they open, and a row's own Go to album and Go to artist, are read too. As a last resort, the album and each artist are found by their exact names on the same service. Opening the menu before the pages have arrived asks again, so the next time it lists them.
- **Sign-in that kept loading (D-73):** a failed account check no longer reloads the page and checks again by itself in a loop; it says why, with Try again and Start over.
- **Stuck on "Signing in":** closing the sign-in screen, or Android recreating it, during the account check no longer leaves the account signing in for good; Settings and Online can start a new sign-in at once.
- **Turning the phone during sign-in** no longer reloads the page or loses a check under way.
- **A slow connection** is told after 15 seconds of checking that it can take a while, instead of a bare "Checking your account…"; the check isn't cut short.
- **Session detection** starts once the page reaches YouTube and starts again with each navigation there, instead of ending a minute after the page opened.
- A page the sign-in stops, a page that can't load, a certificate problem and a crashed page now say so, with Start over.
- A session the phone can't seal in the Keystore is refused with a message instead of crashing the app.
- In regions where YouTube Music's page redirects requests without cookies to a consent page, the account check reads the page's settings with the session.

### Removed
- The debug APK that had been committed to the repository with the boot-wordmark merge (D-69).

## [0.1.1] - 2026-10-10

### Added
- Automated unit and regression test suite for `OnlineMusicRepository` initialization and YouTube Music session parsing / paging.
- Comprehensive sign-in flow and cookie-validation tests in `WebSignInActivityTest`.

### Fixed
- **YouTube Music WebView sign-in:** Added resilient multi-trigger and polling cookie detection, manual "Check sign-in" action, support for Google 2-Step Verification, and recognition of regional Google sign-in hosts.
- **Persistent Keystore session:** Safely encrypted and persisted YouTube Music session cookies using Android Keystore, enabling transparent session restoration across app force-stops and phone reboots without repeated sign-in prompts.
- **Cold-start crash on signed-in startup:** Corrected initialization order in `OnlineMusicRepository` where `shelvesCache` and `likesFetchedAt` were accessed before instantiation during early account flow collection, and ensured library refreshes execute off the main thread on `Dispatchers.IO`.
- **Startup deadlock / ANR prevention:** Replaced blocking synchronous `by lazy` WebSettings user-agent retrieval in `AppGraph` with volatile fallback and asynchronous warming on `Dispatchers.IO`, preventing main looper deadlocks with Chromium initialization.

## [0.1.0] - 2026-10-08

### Added
- First public release of Podium, a native Android music player with the classic wheel interface.
- Turn wheel to navigate, press centre button to select, Menu to go back.
- Cover Flow perspective view for album browsing.
- Full-screen, word-by-word time-synced lyrics with customizable typography.
- Offline-first local music library backed by Room database.
- Online streaming integration for external music sources.
- Virtual device chassis with custom finishes, materials, display themes, and physical feedback.
