# Changelog

All notable changes to Podium will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.5.1] - 2026-11-03

### Added
- **A nostalgic Now Playing (D-109):** Settings ▸ Now Playing switches between New and Nostalgic. The nostalgic screen is the one Podium had before the redesign, with the playback buttons taken out — the cover beside its facts, the song centred under it, the seek bar — and the vinyl's dot visualiser dancing where the buttons used to be. The Wheel plays, pauses and skips, the way the original did; the light in the title bar tells you which it is. The tray hides on it just the same.
- **Add an online song to a playlist (D-109):** Now Playing ▸ More now offers Add to playlist for an online song as well as one of your own, putting it in one of your online playlists. Your two libraries' playlists stay separate, as they always have.

### Changed
- **No duplicates in a playlist (D-110):** adding a song to a playlist that already has it no longer puts it in twice — anywhere you can add one, from a song's menu, an album, Add songs, or Now Playing ▸ More, for your own music and for online songs. Playlists that already have it are marked **Added** in the list, so you can see it before you choose; choosing one anyway adds nothing and the Wheel says so.
- **The screen relaxes when the tray hides (D-109):** with Now Playing's tray set to hide, the cover, the song, the seek bar and the orb now ease down into the space it leaves instead of staying put, and draw back up when it returns.


## [0.1.5] - 2026-11-02

### Added
- **Carbon, redesigned (D-101):** the playing album now lights Carbon's display — a deep, defocused field of the cover's colours, strongest on Now Playing and quiet in lists and Settings — and gives it one accent. Controls are solid, rounded surfaces you can press. Now Playing shows the cover flat and large, a seek bar that ripples gently while the music plays, and the transport as one group of keys with the play key in the album's colour. Opening a menu softens everything behind it. The hardware is blackened steel: a brushed body and a spun-metal Wheel whose shine follows the phone as you tilt it, around a matte centre button. Glass and Custom are unchanged.
- **A new start-up (D-102):** Podium's P appears in a lens of fine lines, spins, and unrolls into the PODIUM wordmark; then you dive through the O into the menu. On every theme. Any Wheel press skips it.

- **Vinyl (D-104):** Now Playing ▸ Vinyl lifts the cover out, slides the record from it and sets it on a turntable in the middle of the display, the rest out of focus. The tonearm swings on and the music plays; a dot visualiser below moves with the sound, and the song's name sits at the foot. Center seeks (the record spins forward or back with it), ⏮ and ⏭ roll the record away for the next, and Menu packs it back into its sleeve and returns to Now Playing. Nothing else on the Wheel does anything while it plays.

- **Let Now Playing clear itself (D-108):** Settings ▸ Now Playing's tray. Turn it on and the row of actions slides away towards the foot of the display a few seconds after you stop touching anything, leaving the cover, the song and the orb. Any press of the Wheel brings it back — and does nothing else, so you can't skip or pause by accident reaching for it. Once it's up the Wheel works as it always does. Off unless you ask for it.

- **The orb (D-107):** Now Playing's play button is now a little creature with two eyes. It is round while the music plays and settles into a pebble when you stop it, so you can always see which it is. Playing, it dances — the bass squashes it, it bobs and leans along, and a loud passage arches its eyes up happily — and every so often it gets carried away, rolls right forward over itself and lands in a burst of little stars. It watches the covers slide by when you change songs, follows the Wheel's focus lens around the action tray, and screws its eyes up when you press it. Leave the music stopped and it works through a whole mood: first it sits up, then it tilts its head and wonders, leans in to look, gets cross, has a cute little panic to get your attention, goes sad, and finally dozes off — nodding, catching itself, and nodding again. Ivory on Carbon, frosted glass on Glass, drawn in outline on Custom. With "Remove animations" on it holds perfectly still, and the play light in the title bar blinks while paused either way.

- **Playlists of your own music (D-106):** Music ▸ Playlists. Make a playlist, name it and fill it from Add songs (every song: Center adds it, or takes it back out), or hold Center on any song (in Songs, an album, Favorites or a playlist) or choose More on Now Playing and pick Add to playlist. In a playlist: Play, Shuffle, Rename and Delete, and holding Center on a song lets you move it with the Wheel (it ticks past each song), remove it, or queue it. Songs that leave your library wait in their playlists until they're back. Your online playlists stay separate.

### Removed
- **Bone (D-108):** the warm off-white display has been withdrawn; the display is Glass, Carbon or Custom. Custom still draws the same matte instrument — lit selection, no glass, squared corners — on a colour or picture of your own, and the Classic lyrics keep their off-white paper. If you were on Bone, Podium opens on Glass.
- **Rotation (D-108):** Podium is portrait only now. It's a device you hold upright, and it no longer turns on its side.

### Changed
- **Haptics, rebuilt (D-105):** every touch now says what it did. The Wheel clicks like a bearing when you turn it slowly, ticks faster and purrs in a spin, and thuds at the end of a list; the centre is a firm key that builds pressure as you hold it and locks in. Toggles rise on and fall off, skips lean the way they go, play is a firm click and pause a soft fall, a favourite blooms, and a song moved in Up Next lifts, ticks past each slot and lands. This needs a vibration motor Podium can fully control; on other phones Podium keeps the vibration it always had. With full control, Settings ▸ Haptics has five profiles: Balanced, More tactile, Reduced, System default and Off; otherwise it's on or off.
- **The title bar (D-103):** the back key and the title sit on the left. On the right, the time sits in a small housing of its own, and where the music plays, whether it's playing and the battery share another, a line between each. A housing that appears buds off its neighbour like a drop of liquid, or pops up as a bubble on its own, and an icon joining a housing stretches it out.
- **One Now Playing for every theme (D-103):** the cover lies flat and large with the song's title, artist and album beneath it, the controls in one group of keys, and the other actions in one tray with a line between each. Only the material changes with the theme. On Glass and Carbon the cover's colours flow slowly behind it while the music plays.
- **Bolder, smoother, clearer (D-103):** text and icons are a step bolder, and on dark screens they sit a little above the display. Corners follow Apple's smooth continuous curve. Spacing is the same everywhere: whatever sits against the display's edges keeps 10 dp from them on every side, with corners that follow the display's, and Now Playing's parts are evenly spaced. The Wheel's MENU is set in the PODIUM wordmark's heavy face and its legends have depth. Pressing the Wheel's edge darkens it softly instead of in a hard wedge. The lyrics look as they did.
- **Livelier colours (D-104):** Now Playing's colours are a little richer and travel all around the display, a little faster, and a new song's colours blend in gradually, however quickly you skip.
- **Glass (D-103):** its controls and housings catch the light of the cover, and the transport keys sit in quiet glass housings.

### Fixed
- **Two playlists with the same name** no longer stop Podium in Online ▸ Playlists or in Add to playlist, nor do two recent songs with the same title in Online ▸ Radio.
- **Dragging a song in Up Next** by its handle puts it where you let go.
- **Glass's colours from the album:** on the Glass theme, Now Playing is tinted by the playing cover again, as it was designed to be; the device body keeps its own neutral light.

## [0.1.4] - 2026-10-26

Release notes: [docs/releases/v0.1.4.md](docs/releases/v0.1.4.md).

### Added
- **A still background for Classic lyrics (D-100):** Settings ▸ Appearance ▸ Virtual display ▸ Lyrics background keeps the lyrics on black or on white, instead of turning between them with every line (Automatic, still the default).
- **Updates from inside Podium (D-99):** Podium checks GitHub for a new version by itself and shows what's new in a short notice. Download and install it right there (Android asks you to confirm), open it on GitHub, or ignore that version. Settings ▸ Software update has Check now and turns automatic checks off.
- **A backup for online songs (D-98):** when YouTube stops serving a song's stream to the app Podium asks first, Podium now asks as YouTube's TV app too, working out its streams with YouTube's own player code on the phone. Songs still play, about five seconds later to start, until the usual app serves again. When you're signed in, it can also ask with your account.
- **Lossless mid-song (D-92):** when your custom sources are slow, a song starts from YouTube Music and moves to the lossless copy once it has loaded, with a short crossfade, like Apple Music stepping up its quality. Songs in Up Next move to it before they play.
- **Switch channel (D-93):** Settings ▸ YouTube Music always shows Channel when you're signed in, and the account menu has Switch channel. Podium now also finds the channels YouTube lists only in its own switcher.
- **Custom sources (D-91):** add your own stream sources in Settings ▸ Custom sources: addons that speak the BitChord addon protocol (type or paste its https address, like `https://addon.example.com`). When one of them has the exact same recording of an online song, the song plays from it, lossless if that's what it serves; otherwise it plays from YouTube Music as before. Sources are asked in your order. You can turn each one on or off, change its settings (its quality, say), check it and remove it. Explicit songs play from it too. Addresses are kept sealed on the phone, and only the host is shown.

### Fixed
- **Custom sources show all their settings (D-98):** an addon's quality options now appear even when its manifest doesn't say what kind of setting they are, and settings of kinds Podium doesn't know are kept. Addresses ending in any `.json` file work, and an address with a token (`?token=…`) keeps it for every request.
- **The first online song after opening Podium no longer stalls (D-98):** Podium checks a song's stream the way the player asks for it, start and end, so streams that would stop after a moment are never chosen. It also remembers which of YouTube's apps served last, after a restart too.
- **Online songs that wouldn't play (D-97, issue #5):** Podium now finds a YouTube Music song's stream the way BitChord does. It asks as several of YouTube's own apps in turn, checks that each stream really plays before starting it, and keeps the old extractor as a fallback. When a song still can't play, Now Playing says why instead of "Something went wrong" (release builds always showed that message).
- **Custom sources keep working by themselves (D-96):** a source that stops answering or was asked too often is tried again in the background, and songs move to it as soon as it's back. No more Check now. Requests that hang are sent again, so a slow addon no longer looks down.
- **Lossless mid-song, reliably (D-96):** the lossless copy loads a few seconds ahead and joins only once it's ready where the song is, lined up by its sound, so the switch is clean even on a slow network.
- **ALAC files (D-94, issue #6):** Podium's own Apple Lossless decoder now comes first, so phones whose own ALAC decoder fails no longer stop the song.
- **Cover Flow (D-95):** opens and closes without the pause; large libraries are ordered about ten times faster, away from the screen's drawing.

## [0.1.3] - 2026-10-19

Release notes: [docs/releases/v0.1.3.md](docs/releases/v0.1.3.md).

### Added
- **Full screen (D-85):** Settings ▸ Full screen hides Android's status and navigation bars (a swipe from the edge shows them for a moment). The iPod's title bar then shows the time on the left and the battery on the right; Settings ▸ Battery in title bar hides the battery.
- **Choose your channel (D-86):** a Google account with several YouTube channels can pick which one Podium uses, in Settings ▸ YouTube Music ▸ Channel. Each channel's library, likes and history are its own.
- **Live radio (D-90):** Online ▸ Live radio has music stations streaming live now, by genre. They play audio only, at the same quality as songs; Next goes to the next station.
- **Sound effects (D-89):** Settings ▸ Equaliser ▸ Effect has Car stereo, 3D hall and 8D (the music circling your head), with or without the equaliser.
- **Apple Lossless on every phone (D-87):** ALAC files play even on phones without their own ALAC decoder, decoded by Podium bit for bit.
- **Continuous line lyrics theme (D-81):** an optional look for Now Playing ▸ Lyrics. A single ivory line writes the words by hand on a black sheet, flows from each phrase into the next and draws loops, spirals and notes through instrumental passages, with a touch of champagne gold at the pen. It follows the music; seeking or rewinding brings back the same drawing. In Settings ▸ Appearance ▸ Virtual display ▸ Lyrics theme. Classic stays the default.
- **The orb (D-83):** in Classic lyrics, long instrumental breaks pull the camera back from the words to a softly glowing ivory-and-gold orb that moves gently with the music, then travel back in through its centre as the next line starts. Short gaps keep the words. Songs without words, or with none found, show the orb throughout. Not in the Continuous line theme.
- **Lyrics move with the bass (D-82):** in both lyrics themes the words swell very slightly with the music's kick drums, like a speaker cone, smoothly and only while Podium's own player plays. Not with reduced motion.

### Changed
- **A new app icon (D-88):** the "P" of the Podium wordmark, in silver.
- **Text at Podium's own size (D-84):** Podium no longer grows or bolds its text with the phone's font settings, which on some phones turned the wheel's arrows and the power button into boxes.
- **Cover Flow goes by artist, then album (D-84),** so an artist's albums stand together and "Drake, Ariana Grande" follows "Drake".
- Signing out of the online service removes the things kept for all of the account's channels (D-86).
- **Continuous line (D-82):** the pen moves at an even pace instead of stuttering, and changes speed smoothly from word to word. The line is a little bolder and glows softly, like a neon sign.

### Fixed
- **Lyrics said "Too many requests" often (D-84):** Podium now waits as long as the lyrics service asks, asks again by itself afterwards, and never asks in bursts.

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
