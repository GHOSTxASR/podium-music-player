# UI device acceptance

Target: Nothing Phone (3a), Android 15/16, release-like debug build (`app.podium.debug`).

**Status (2026-10-06): not performed on a device.** These changes were built and verified in a
cloud container with no phone attached and no emulator acceleration. Everything below marked
*automated* ran as Robolectric tests with native graphics (screenshots in each module's
`build/screenshots/`); every *device* step is still open and must be run on the phone before the
change is called done. Nothing in this file is a claim that a device step passed.

How to install: `adb install -r artifacts/apk/podium-debug.apk` (or `./gradlew :app:installDebug`).

## 1. Focus list and text clipping (D-40)

Automated (`FocusListGeometryTest`, `FocusGeometryTest`, `PaperLayoutTest`, `OnlineLayoutTest`):
1/2/3/5/10/50 rows, focus walked with the Wheel one detent at a time to the first, middle and last
row and back, Carbon/Bone/Glass, phone (411 × 891 dp) and small (360 × 640 dp) screens, rows with
titles too long to fit. Asserted: the focused row is fully inside the readable region; no row inside
it is clipped at either side; a list that fits starts at the top; the first and last rows sit flush;
in a long list the focused row rides at the middle.

On the device:

| # | Step | Expect |
|---|---|---|
| 1.1 | Home (4 rows): turn the Wheel down and up | the lens follows each row exactly, Music flush under the title, no letter cut at the left |
| 1.2 | Music ▸ Songs with 50+ songs: spin slowly down | the focused row stays at the arc's apex and the list moves under it; the lens doesn't jump |
| 1.3 | Keep turning to the last song | the list stops; the lens travels down to the last row, which sits flush above the mini player, fully visible |
| 1.4 | Spin fast to the top | the list jumps, never lags; the first row ends flush under the title, the top end crisp (nothing above) |
| 1.5 | A settings picker with 2–3 rows | rows start at the top, crisp, the lens on the right row |
| 1.6 | Drag the list with a finger, then turn the Wheel | the lens rides with its row while dragging and hides under the title rather than clamping; the next detent snaps focus back into view |
| 1.7 | Focus a long title on Carbon | the glow is soft all round (no rectangle), the title fades at the right; it stays at its start for about a second and a half, then eases into the scroll (never sits mid-row and jumps) (D-46) |
| 1.8 | Long titles with descenders and accents (e.g. "Björk — Jóga", "gypsy"), CJK and Cyrillic titles | nothing cut at the top, bottom or sides |
| 1.9 | Settings ▸ Accessibility font size at 200 % | rows grow; first/last flush; nothing clipped |
| 1.10 | Rotate to landscape | same behaviour in the landscape layout |
| 1.11 | Context menu (hold Center on a song) with more than 8 actions | menu rows scroll with a neighbour in view; first/last flush |
| 1.12 | Back from a column: the previous column's glimpse | shows the row that led here, centred |

## 2. Customization (D-41)

Automated (`VirtualDisplayTest`, `CustomizationScreenshotTest`, `DeviceSettingsPersistenceTest`):
contrast over the whole sRGB cube, ink polarity, picture blending and fallback, every font,
glitter seeding and the pixel-identical "off", persistence round trip and damaged values;
screenshots of glitter on three bodies, every font, Custom on paper / mid blue / a loud picture,
Glass on a picture, Carbon ignoring a picture.

On the device:

| # | Step | Expect |
|---|---|---|
| 2.1 | Fresh install, or upgrade from the previous build | the device looks exactly as before |
| 2.2 | Settings ▸ Appearance ▸ Device body ▸ Glitter: On (Steel gray) | fine specks in the body, gently glowing in the middle; the Wheel clean |
| 2.3 | Tilt the phone right, then left, slowly; then the top away and back | the glow slides left, then right; down, then back; different specks catch and lose the light; no jumps |
| 2.3a | Hold the phone still at your usual angle for 10 s | the glow settles in the middle; nothing moves |
| 2.3b | Glitter glow to 0 %, then 100 %; Glitter amount up and down | 0 %: even flakes, no moving light; 100 %: strong glow and flare under the light; amount changes how many flakes catch it |
| 2.3c | Follow tilt: Off | the light stays in the middle whatever the tilt |
| 2.4 | Glitter on, tilt for a minute; then leave the phone still; then lock it | smooth lists while tilting; GPU bars well under the frame budget; no redraws while still (Profile GPU rendering); sensor stops when locked (`adb shell dumpsys sensorservice` shows no Podium listener) |
| 2.5 | Virtual display ▸ Font: turn through all six | the whole display restyles live; rows stay aligned; nothing clipped; the Wheel's MENU unchanged |
| 2.6 | Pixel and Mono with long titles, CJK, Cyrillic and Greek titles | uncovered strings in Inter, whole; nothing clipped |
| 2.7 | Theme ▸ Custom; Background color: walk the hue all the way round | text readable at every colour |
| 2.8 | Background ▸ Choose a picture (system picker opens; no permission prompt) | the picture shows under a veil; text readable |
| 2.9 | Background opacity 0 → 100 % | the picture strengthens; text stays readable; Contrast: High tones it down |
| 2.10 | Force stop, reopen; reboot, reopen | every choice is kept, the picture still shows |
| 2.11 | Delete the picture in the gallery, reopen Podium | the background colour shows; Settings says the picture is unavailable |
| 2.12 | Theme ▸ Carbon with a picture chosen | Carbon's black display; Virtual display shows "Backgrounds come with Glass and Custom" instead of the background rows |
| 2.13 | TalkBack through Appearance | every row and value is read |

## 3. Lyrics (D-39)

Automated (`LyricsTest`, `LyricLayoutTest`, `LyricsScreenTest`): parsing, timing boundaries,
matching, provider classification, cache, consent; screenshots of the light and dark lines, a long
line on a small screen, before the first line, consent and not found.

On the device:

| # | Step | Expect |
|---|---|---|
| 3.1 | Play a local song with known synced lyrics on LRCLIB; Now Playing ▸ Lyrics | consent prompt once; Center allows; lines appear |
| 3.2 | Watch several lines | each new line inverts the display (light ↔ dark), changes on time (±0.3 s) |
| 3.3 | Pause, resume | the line holds while paused and continues |
| 3.4 | Seek from Now Playing, return | the right line within half a second |
| 3.5 | Turn the Wheel back two lines, press Center | playback jumps to that line's start |
| 3.6 | Turn the Wheel, wait 5 s | lyrics return to the line being sung |
| 3.7 | A song LRCLIB doesn't have | "No lyrics found" |
| 3.8 | Airplane mode, a song not cached | "Lyrics unavailable offline"; reconnect, Center retries |
| 3.9 | Same song again offline | lyrics from the cache |
| 3.10 | A YouTube Music song playing in the YouTube Music app (delegated) | lyrics follow the remote position; Center seeks only if the session allows seeking |
| 3.11 | A very long line, unusual characters (accents, CJK) | wraps, shrinks, never clipped |
| 3.12 | Reduced motion (animator scale 0) | inversion is instant |
| 3.13 | TalkBack | each line announced politely |
| 3.14 | A song with synced lyrics; watch a few lines (D-42) | words appear one by one as they're sung, in place, never late; the line's first word on the line's time |
| 3.15 | Open the details overlay on a song without word stamps | it says the words are paced to the line |
| 3.16 | Turn the Wheel back to a sung line | the whole line shows at once |

### 3a. Continuous line lyrics theme (D-81)

**Status (2026-10-12): not performed on a device.** Built and tested in the cloud container only.

Automated (`LineHandTest`, `LineCompositionTest`, `LinePlayheadTest`, `LinePerformanceTest`,
`ContinuousLineScreenTest`, `AppearanceLayoutTest.lyricsThemePicker`,
`DeviceSettingsPersistenceTest`): glyph construction and stroke order, phrase setting, timing from
word and line stamps, determinism (the same moment draws the same pixels after rewinding and after
seeking back and forth), instrumental motifs and their seeding, bounded memory, no allocation while
mapping a frame, bounded draw calls, theme switching without any player command, a new song's own
drawing, reduced motion without gold, persistence; screenshots in
`feature/nowplaying/build/screenshots/line-*.png`.

On the device:

| # | Step | Expect |
|---|---|---|
| 3a.1 | Settings ▸ Appearance ▸ Virtual display ▸ Lyrics theme ▸ Continuous line; Center | the row says "Continuous line"; the music doesn't stop, skip or seek |
| 3a.2 | Now Playing ▸ Lyrics with a synced song | a black sheet; an ivory line writes the words letter by letter in a joined hand, the small pen point leading with a faint gold light |
| 3a.3 | A song with word stamps (enhanced LRC), then one timed by line only | the pen reaches each word on its stamp; line-only: the first word on time, the rest paced, details say "Words paced to the line" |
| 3a.4 | The end of a phrase | the last letter's stroke carries on into a curve and into the next phrase's first letter; a brief gold light crosses the last word |
| 3a.5 | An instrumental break of 10 s or more | the line keeps drawing loops, spirals, notes and beat dots, then runs into the next phrase's first letter |
| 3a.6 | Watch several phrases | each new phrase sits lower and further right; the display follows gently; earlier phrases dim and slide away up and left |
| 3a.7 | Seek back 20 s from Now Playing, return to Lyrics | the pen retraces its own route back; the same words, motifs and positions as before |
| 3a.8 | Seek to the same moment three times | the same drawing each time |
| 3a.9 | Pause for 30 s, resume | the drawing holds still, then carries on with no jump |
| 3a.10 | Skip to the next song mid-phrase | the new song's drawing starts; nothing of the last song's words or motifs remains |
| 3a.11 | Turn the Wheel back two lines, then Center | the camera travels back along the drawing to that line, written; Center seeks there |
| 3a.12 | A song LRCLIB marks instrumental | a full-screen line drawing that evolves with the song, "Instrumental" small at the top |
| 3a.13 | Accents (é, ñ, ü) and another script (Cyrillic, Japanese) | accents written by the hand; other scripts traced in outline by the same line |
| 3a.14 | Reduced motion (animator scale 0) | no glow, no shimmer, no retracing; the camera moves in cuts; the words still follow the music |
| 3a.15 | Leave the lyrics, come back repeatedly; switch themes back and forth | no stutter in the music or the Wheel; Classic looks exactly as before |
| 3a.16 | Profile: `adb shell dumpsys gfxinfo app.podium.debug` over a minute of lyrics | record frame times p50/p90/p99 here. Test tones only so far: LYRICS_ARCHITECTURE.md §12.7 (2026-10-15) |
| 3a.17 | TalkBack | each line announced politely; a song without words is announced as instrumental |
| 3a.18 | Watch the pen through a whole verse at 90 and 120 Hz (D-82) | it moves at an even pace, without hitches; word to word its speed changes gradually |
| 3a.19 | `adb shell setprop log.tag.PodiumLyricsClock DEBUG`, reopen Lyrics, `adb logcat -s PodiumLyricsClock` | "clock … uneven=0" in every 5 s window while the song plays |
| 3a.20 | The line close up | a little bolder than before, with a soft warm glow around it like a neon sign, no hard outline; written phrases glow less |
| 3a.21 | A song with a clear kick drum, in both lyrics themes | the words swell very slightly on each kick and settle back softly; never a jerk; a held bass note doesn't keep them swollen |
| 3a.22 | A quiet song and a loud one; then a song another app plays; then reduced motion | the swell is alike for quiet and loud; still with another app's playback; no swell with reduced motion |

Device notes, 2026-10-15 (D-82), test tones and invented lyrics only (`lyrics-demo`): 3a.19 met
(uneven=0 in every window; the raw position had 1–144 uneven frames per window). The probe showed
the swell responding (up to 0.67 as a tone began); it wasn't judged by eye, since steady test tones
have no kicks. Frame times, and the drawing method chosen from them, are in LYRICS_ARCHITECTURE.md
§12.7. The glow was checked close up on a screenshot: soft, with no visible aliasing. Not yet seen on the
listener's own music: 3a.18, 3a.20–3a.22.

### 3b. The orb in Classic lyrics (D-83)

Automated: `OrbTimelineTest`, `OrbDriveTest`, `OrbLookTest`, `AudioMeterTest`,
`LyricsScreenTest.notFound` (LYRICS_ARCHITECTURE.md §10).

On the device:

| # | Step | Expect |
|---|---|---|
| 3b.1 | Classic lyrics on a synced song with a long instrumental break (10 s or more) | after the last line lingers, the page recedes into a ring's dark hollow; the orb holds, moving gently with the music |
| 3b.2 | Keep watching as the next line comes | the camera goes in through the hollow; the ring passes out beyond the display; the next line's page fills it as its first word starts |
| 3b.3 | Gaps of 1–5 s between lines | the words stay; no orb |
| 3b.4 | Seek into the middle of a long break; seek back into the words | the orb at once; the words at once; never stuck between |
| 3b.5 | Pause during the orb; resume | it settles and holds still; it carries on |
| 3b.6 | A song with a clear kick and hats | the ring breathes a little with the kicks, ripples with the hats, the highlight warms to gold on hits; no jumps, no flashing |
| 3b.7 | A song LRCLIB marks instrumental; a song with no lyrics found | the orb throughout with "Instrumental" / "No lyrics found" small at the top |
| 3b.8 | The Continuous line theme on the same songs | no orb; its line art as before |
| 3b.9 | Leave the lyrics and come back during an orb | the orb again, in place |
| 3b.10 | Profile during the orb (`dumpsys gfxinfo`) | record frame times here |

Device notes, 2026-10-15, test tones and invented lyrics only: 3b.1–3b.3 met (screen recording
read frame by frame). 3b.7: the instrumental screen met (the not-found screen on Robolectric only).
3b.10: 0 janky frames during the hold (LYRICS_ARCHITECTURE.md §14.4). Not yet seen: 3b.4–3b.6,
3b.8, 3b.9 on the phone, and the listener's own music.

## 4. The Podium keyboard (D-45)

Automated (`KeyboardEditorTest`, `WheelKeyboardTest`): typing, shift and caps lock, delete, hex
limits; the morph's frames (`keyboard-<finish>-0…5.png`) in Steel, Carbon and Glass; typing "Hi 5"
with the keys and closing back to the Wheel.

On the device:

| # | Step | Expect |
|---|---|---|
| 4.1 | Online ▸ Search (empty) | the Wheel unwinds and opens into the keyboard; the phone's keyboard never appears; the power button and lights step aside |
| 4.2 | Type a query; Shift; Shift twice quickly; 123 and abc | letters type at the caret; one capital, then caps lock; pages switch |
| 4.3 | Hold Delete; then hold it and slide off the key | repeats after a moment; slide off stops it |
| 4.4 | Press Search | the keyboard folds back into the Wheel; the Wheel walks the results |
| 4.5 | Tap the field again; press Back | the keyboard opens; Back closes it (doesn't leave Search) |
| 4.6 | Close key while results load | folds away; loading continues |
| 4.7 | Settings ▸ Keyboard ▸ Phone; Search | the phone's keyboard, as before; Search key on it searches |
| 4.8 | Settings ▸ Appearance ▸ Custom color ▸ type a code | Podium: the hex page (digits, A–F); six characters at most |
| 4.9 | Each finish (Glass, Steel, Silver, Custom) and Carbon/Bone | the panel is the ring's own material (glass on Glass); keys readable |
| 4.10 | Rotate to landscape with Search open | the phone's keyboard (the Wheel's column is too narrow) |
| 4.11 | Reduced motion (animator scale 0) | a short crossfade, no spin |
| 4.12 | TalkBack on the keyboard | each key named ("Shift, on", "Delete", "Close keyboard") and pressable |
| 4.13 | Haptics on; type | each key gives the Wheel's click |

## 5. Loading (D-43)

Automated (`LoadingScreenTest`): the pinwheel and word in each theme; nothing for the first 150 ms.

| # | Step | Expect |
|---|---|---|
| 5.1 | A slow network (or first launch online); open Online ▸ Home, an album, an artist | the pinwheel and "Loading", centred; never a blank screen |
| 5.2 | Search for something | a "Searching" row under the field until results arrive |
| 5.3 | A fast answer (cached) | no flash of the loading screen |
| 5.4 | First open of the local library | "Reading your music" |
| 5.5 | Reduced motion | the pinwheel stands still; the word remains |

## 6. Music access (D-47)

Automated (`PermissionAsksTest`, `AppShellTest`): asking once, refusals Android will and won't ask
about again; without access Home leads with "Allow music access" and the row holds still.

| # | Step | Expect |
|---|---|---|
| 6.1 | Fresh install (or Settings ▸ Apps ▸ Podium ▸ Storage ▸ Clear storage); open Podium | after the startup screen, Android asks to allow access to music and audio, once |
| 6.2 | Allow | Music fills with the phone's songs; Home's "Allow music access" row disappears |
| 6.3 | Clear storage again; deny | Home and Music lead with "Allow music access"; Albums says how to allow it |
| 6.4 | Press "Allow music access"; deny again | the question showed again; after this Android won't ask any more |
| 6.5 | Press "Allow music access" | Podium's page in the system settings opens; Permissions ▸ Music and audio ▸ Allow; back to Podium: the library fills |
| 6.6 | Revoke access in system settings, return | the library hides the phone's songs and the row comes back |

## 7. Motion and responsiveness (D-65 – D-67)

**Device run (2026-10-08, Nothing Phone (3a), Carbon, glitter on, test-tone library):** done for
7.1–7.10 except where noted. `MotionProbe` (androidTest) makes real two-finger touches, which
`adb shell input` can't. Install the app and the probe, then (`am instrument` keeps the data;
`connectedAndroidTest` would uninstall the app):

```bash
adb shell am instrument -w -e class app.podium.MotionProbe -e record true app.podium.debug.test/androidx.test.runner.AndroidJUnitRunner
```

It records only once Podium is in front, puts screenshots in `Android/data/app.podium.debug/files/probe/`
and leaves the app closed (instrumentation ends its process): open Podium again afterwards.

| # | Step | Expect | 2026-10-08 |
|---|---|---|---|
| 7.1 | Press and hold Center, then a quarter of the Wheel | the part goes down on touch, comes up with a faint settle on release | ✓ (recorded: down within ~45 ms) |
| 7.2 | Turn the Wheel slowly, then fast, stop dead, reverse (matte finish) | the band turns under the finger; on lift it clicks into a detent; the lens glides a step, locks on in a spin, stops with the Wheel | ✓ band (screenshots mid-turn and after); spins scripted and timed, the lens not inspected frame by frame |
| 7.3 | Center on Music, Menu back, several times quickly | the move starts on release (no pause), arcs, settles softly; no queue of moves | ✓ 56 ms / 33 ms to first motion |
| 7.4 | Back from Music | the column shrinks into the right tile's frame and the cover melts in over it (D-66) | ✓ frame by frame |
| 7.5 | Forward into Music | the column lands where the left glimpse shows it (D-61, D-63) | ✓ within a pixel or two |
| 7.6 | Home ▸ Now Playing | the cover settles into place and lean as the screen arrives; controls live at once | ✓ |
| 7.7 | Hold Center on a song | the menu pops in at once; Menu dismisses it instantly | ✓ |
| 7.8 | Pinch in slowly / quickly / reverse / let go halfway / flick short | the object follows from where it is, no lurch; reverses with the fingers; let go still, short of a third: springs back; a quick short flick goes in | ✓ by `MotionProbe` |
| 7.9 | Spread on the object in the space | it comes forward with the fingers and settles flat | ✓ by `MotionProbe` |
| 7.10 | A slow pinch starting on a row (or the Wheel's ⏯) | no menu, no focus change, no power-off before the pinch takes over | ✓ by `MotionProbe` |
| 7.11 | Arrange a sticker: drag fast, change direction, resize and turn | the sticker stays under the finger, lifts a hair while held | not driven on the phone (Robolectric `StickerEditorTest`) |
| 7.12 | Settings ▸ Accessibility ▸ Remove animations, while Podium runs | moves become cross-fades at once; the space's stars stand still; the charging light stays lit | not run (it changes a system setting) |
