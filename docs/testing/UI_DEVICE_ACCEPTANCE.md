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
| 1.7 | Focus a long title on Carbon | the glow is soft all round (no rectangle), the title fades at the right, scrolls after ~1 s |
| 1.8 | Long titles with descenders and accents (e.g. "Björk — Jóga", "gypsy"), CJK and Cyrillic titles | nothing cut at the top, bottom or sides |
| 1.9 | Settings ▸ Accessibility font size at 200 % | rows grow; first/last flush; nothing clipped |
| 1.10 | Rotate to landscape | same behaviour in the landscape layout |
| 1.11 | Context menu (hold Center on a song) with more than 8 actions | menu rows scroll with a neighbour in view; first/last flush |
| 1.12 | Back from a column: the previous column's glimpse | shows the row that led here, centred |

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
