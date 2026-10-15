# Lyrics architecture

Status: implemented (core:lyrics, feature:nowplaying `LyricsScreen`), 2026-10-06; Continuous line
theme (§12) 2026-10-12.
Decisions: D-11 (consent), D-39 (provider and full-screen lyric mode), D-81 (Continuous line).

Now Playing ▸ Lyrics turns the whole virtual display into the lyric. Podium never claims words it
can't stand behind: lyrics are shown only when a provider's record is confidently the same recording,
synced lyrics follow the real playback position, and lyrics without usable times are paced across
the song by Podium — labelled an estimate (§5.2, D-50).

```
LyricsScreen (feature:nowplaying) ── LyricsGateway (interface, app supplies it)
        │                                   │
        │  position via PlaybackController  ▼
        │                         LyricsRepository (core:lyrics) ── consent gate (D-11)
        │                                   │            └── LyricsCache (file, app cache dir)
        ▼                                   ▼
  LyricLayout (pure)                 LyricsProvider ── LrclibProvider ── LyricsHttp (https only)
                                             └── LyricsMatcher (Podium's identity rules)
```

`core:lyrics` is a pure Kotlin module (no Android imports). It depends on `sources:api` only for the
title/artist normaliser (`TrackNormalizer`, `ArtistNames`) so lyrics matching uses the same identity
rules as the rest of Podium.

## 1. Provider research and choice

| Candidate | Synced | Access | Licensing / terms | Verdict |
|---|---|---|---|---|
| **LRCLIB** (lrclib.net) | yes (LRC) + plain + instrumental flag | open HTTP API, no key, no account | server is MIT-licensed open source; lyrics are community-contributed, copyright stays with rights holders; the service asks clients to send an identifying `User-Agent` | **chosen** |
| Musixmatch | yes | commercial API key, paid tiers | licensed catalogue; terms restrict display/caching; needs a business agreement | not now (needs a contract) |
| LyricFind | yes | commercial | licensed; business agreement | not now |
| Genius | no (plain, annotated) | API returns metadata but not lyrics text | lyrics only on web pages; scraping not permitted | rejected |
| Music-service lyrics (e.g. YouTube Music's own lyrics tab) | sometimes | unofficial web API only | tied to one provider; would leak provider identity into a provider-independent feature | rejected for now (could be a provider later, behind the same interface, only with an official basis) |

LRCLIB was checked live on 2026-10-06 from the build machine: `GET /api/get?track_name=…&artist_name=…&album_name=…&duration=…`
returns one record (`id, trackName, artistName, albumName, duration` (seconds, float), `instrumental`,
`plainLyrics`, `syncedLyrics`), or `404` with `"TrackNotFound"`. `GET /api/search?track_name=…&artist_name=…`
returns a list of the same records. No key, no cookies.

Podium asks by **metadata only** (title, artist, album, length) — never by any music service's id, so
the feature works the same for local files and online songs and providers never leak into it.

## 2. Model

- `LyricsLine(startMs, text)` — an empty text is a gap.
- `Lyrics.Synced(lines)` (never empty), `Lyrics.Plain(lines)` (never empty), `Lyrics.Instrumental`.
- `LyricsRequest(key, title, artist, album?, durationMs?)` — `key` is the source-qualified track id,
  used only for the cache fingerprint.
- `LyricsResult`: `Found(lyrics, attribution)`, `NotFound`, `NeedsConsent`, `Offline`, `Failed`,
  `RateLimited`.
- `LyricsProvider { id; attribution; lookup(request): ProviderAnswer }` — the replaceable part.

## 3. Matching (no wrong song's words)

`LyricsMatcher.matches(request, candidate)`:

1. The normalised title identity must be equal ("Song (Remastered 2011)" is "Song").
2. Version tags must agree **both ways**: live, remix, acoustic, instrumental, sped-up and similar
   versions never take the studio song's words, and the reverse.
3. The artists must share a credited name (normalised forms).
4. When both lengths are known, they must be within ±3 s; unknown on either side skips only that check.

LRCLIB's own `/api/get` answer is checked against these rules too. When it misses, `/api/search`
candidates that have words (or are instrumental) are filtered by the same rules and the closest length
wins, then the same album. Nothing confident → `NotFound` ("No lyrics found").

## 4. LRC parsing

`LrcParser.parse` reads `[mm:ss.xx]`, `[mm:ss]`, `[mm:ss.xxx]`, `[mm:ss:xx]` (hundredths, the old
form) and `[h:mm:ss.xx]` (a third colon group is hours only when a dotted fraction follows), several
stamps on one line (repeated choruses), `[offset:±ms]` (positive = earlier), ignores metadata tags,
skips lines without a readable stamp (never guesses), sorts by time, and collapses runs of empty
lines. Enhanced-LRC word stamps (`<mm:ss.xx>` before a word or syllable) become the line's
`words` (a word takes its first syllable's stamp; the offset and repeated stamps apply to them too);
a line where some words have no stamp keeps none. Malformed input gives an empty list → treated as no synced
lyrics; plain lyrics are then used if present.

### 4.1 LRCLIB's `lyricsfile`

LRCLIB answers also carry `lyricsfile`, a small YAML document (observed 2026-10-06: `lines:` with
`text`, `start_ms`, `end_ms`; `hasWordSync` marks records with words). `LyricsFile` reads just that
shape tolerantly and `enrich` gives each LRC line its end time — and words, when the file has them —
where start (±50 ms) and text agree. Without LRC, the file's own lines are used. Word-synced records
were rare in samples (none in 60 search results), so the word shape is read as the line shape nested
under `words:`; anything else fails to "no words".

## 5. Timing

`LyricsTiming.activeIndex` is a binary search (line `i` is active on `[start_i, start_{i+1})`);
`displayIndex` shows the first line while waiting for it; `nextChangeMs` gives the next boundary.
The screen wakes exactly when the next line is due (clamped to 16–500 ms, so a seek is noticed within
half a second), and only recomposes when the index changes — one redraw per line, no per-frame work.
Position comes from `PlaybackController.positionMs()`, so it is correct for Podium's own player and
for delegated online playback (the owner-aware controller reports the remote session's position).

### 5.1 Words as they are sung

`WordTiming.starts(line, nextStart)` gives each word's appearance time:

- **Provider word times** when the line has them for every word: used in order, never before the
  line starts.
- **Otherwise an estimate inside the line's real window** — from its start to its `end_ms`, or to
  85 % of the gap to the next line — shared by syllables (vowel groups) with half a syllable's pause
  after punctuation, and never slower than 380 ms per syllable, so a line before an instrumental
  break doesn't trickle its words across the break. The first word appears exactly on the line's
  time; only the pace inside the line is estimated, and the details overlay says so ("Words paced
  to the line").
- The screen wakes at the next word or line (16–500 ms), so it redraws once per word.

### 5.2 Formatted for the player (D-50)

Providers write lyrics in many ways; the screen shows one line at a time and follows the music.
`LyricsFormatter.forPlayback(lyrics, durationMs)` shapes what the repository hands the screen —
the provider's own answer is cached unchanged, so better rules apply to old entries too:

- **Usable synced lyrics are kept exactly.**
- **Repaired:** lines sharing one stamp are spread (by length) up to the next stamp; stamps running
  past the end of the song are scaled into it; a timed section label ("[Chorus]") becomes a gap; a
  line longer than 14 words is split at its phrases, the pieces timed within the line (by the
  provider's word times when it has them). Repairs other than splitting mark the lyrics
  `estimated`.
- **Times that say nothing** (six or more lines on a third as many distinct stamps or fewer, or all
  inside 15 % of the song) are treated as plain.
- **Plain lyrics are paced across the song:** labels dropped, a lead-in (7 %, 4–18 s) and an outro
  (6 %, 4–20 s) left free, each line given time by its letters and each verse break a breath.
  `Lyrics.Synced(estimated = true)`; the details overlay says "Timing estimated by Podium", and the
  Wheel still reads ahead (Center returns to the music).
- Without the song's length nothing is invented: plain lyrics stay plain.

The provider prefers synced words: an exact LRCLIB match with only plain lyrics is kept while the
search looks for the same song with times (another upload often has them); search results with
times win over those without. The cache key carries a version (`v2`) so answers kept before this are
asked for again.

## 6. Repository, consent and cache

- **Consent (D-11):** the first time lyrics are needed and consent isn't given, the screen says
  "Find lyrics online? Podium sends the song title, artist, album and length to LRCLIB. Press Center
  to allow." Settings ▸ Online lyrics turns it on or off. Without consent nothing leaves the device;
  previously cached lyrics still show.
- **Cache:** small JSON files in `cacheDir/lyrics` (the system may clear them; that only costs a
  request), plus 64 recent entries in memory. Found lyrics are kept 30 days, "not found" 3 days,
  failures never. At most 500 files, oldest removed first. Writes go to a `.partial` file and are
  renamed; a corrupt file is deleted and treated as a miss.
- **Keys:** `"<provider id>-<sha-256 prefix>"` of track key, normalised title, artist, album and
  length (2 s buckets) — different providers, songs or edited tags never share an entry, and keys are
  validated against a strict pattern before touching the file system.
- Two requests for the same song at once make one network call.
- The HTTP client is https-only, sends `User-Agent: Podium/<version> (Android music player)`, follows
  no redirects, times out after 8 s connect / 10 s read and reads at most 2 MB. Errors are reduced to
  the exception's class name — no URLs or bodies are logged.

## 7. The screen

The lyric screen is a destination of its own (`Dest.Lyrics`): no header, no mini player, no edge
fades — the whole virtual display is the lyric.

- **Words arrive as they are sung** (§5.1): the line's layout is computed for the whole line, so
  each word appears in its final place — fading in over 140 ms and settling 12 % of the type size
  upward (instant with reduced motion). Reading with the Wheel, paused on a line, and plain lyrics
  show whole lines. Before the song's first line a quiet "…" waits.
- **One line at a time**, as large as it fits: `LyricLayout` (pure, unit-tested) tries sizes from
  `min(16 % of the display height, 64 dp)` down to 20 sp in 8 % steps, wrapping greedily; each line is
  **justified edge to edge**, a single word stands centred. If even 20 sp doesn't fit, it keeps
  shrinking rather than clip. Margins are 9 % horizontally and 12 % vertically. Words are drawn on a
  Canvas with `softWrap = false` at measured positions, so nothing is ever cut off.
- **Alternating inversion:** line index even → Bone paper (light) with dark words; odd → Carbon black
  with light words. The colours are the two display finishes' own tokens, nothing else. The change is
  a 90 ms colour tween, or instant with reduced motion. The display's finish colours are not changed
  (D-26): this is content drawn inside the screen.
- **The Wheel:** turning reads one line back or ahead (synced lyrics pause following; it resumes after
  5 s). Center: while reading, jumps playback to the line read — **only if the player can seek**
  (`controls.seek`; otherwise a reject haptic and following resumes); while following, play/pause;
  on consent, allows; on an error, retries. Menu goes back. A tap or a Center long-press shows the
  song, the artist and "Lyrics from LRCLIB" for 4 s.
- **Plain lyrics** are moved through with the Wheel and are labelled "Not synced: turn the Wheel to
  read" in the details; they are never auto-advanced with invented timing.
- **Accessibility:** the line is a polite live region with the full text as its description.
- **The bass swell (D-82):** while the music plays, the line swells very slightly with the music's
  bass hits, like a speaker cone (at most 1.8 % of its size, about its centre). It reads Podium's
  audio meter by the playback position (§13) and follows a damped spring, so it never jumps. It's a
  layer property set while drawing, so nothing is laid out again. Off with reduced motion, and still
  when nothing is measured.
- **The orb (D-83, §14):** in long stretches without words the camera pulls back from the line into
  a softly luminous orb, and goes back in through its hollow as the next line starts. A song without
  words, or with none found, shows the orb throughout. Classic only: the Continuous line keeps its
  own instrumental line art.

## 8. States and copy

| State | Title | Body |
|---|---|---|
| nothing playing | Nothing playing | Choose a song, then open its lyrics. |
| loading | Finding lyrics | |
| consent | Find lyrics online? | Podium sends the song title, artist, album and length to LRCLIB. Press Center to allow. Menu goes back. |
| not found | No lyrics found | Lyrics for this song aren't available. (Classic: the orb, with "No lyrics found" small at the top, D-83.) |
| offline | Lyrics unavailable offline | Connect, then press Center to try again. |
| rate limited | Too many requests | Wait a moment, then press Center to try again. |
| failed | Couldn't load lyrics | Press Center to try again. |
| instrumental | Instrumental | This song has no words. (Classic: the orb, with "Instrumental" small at the top, D-83; Continuous line: its line art.) |

## 9. Attribution and licensing

- Settings shows "Lyrics from LRCLIB, written by its community"; the lyric details show
  "Lyrics from LRCLIB".
- LRCLIB's server code is MIT; its lyrics are user-contributed and remain the property of their
  rights holders. Podium displays them for personal use at the listener's request, keeps a local
  cache only for that, and never redistributes, exports or uploads lyrics. Before any public release
  this needs a licensing review (a commercial provider may be required in some markets); the provider
  interface makes that a swap of one class.
- No lyrics are bundled with the app or committed to the repository (test fixtures are invented text).

## 10. Tests

- `core:lyrics` (`LyricsTest.kt`, 27 tests): word timing (enhanced LRC words, offsets, repeated
  stamps, the estimate's window, cap and order, provider times, syllables, `lyricsfile` lines and
  words, enrichment, the provider's end times, the cache round trip with words); LRC formats, offsets, repeated stamps, word stamps,
  malformed input; timing boundaries (exactly at a start, between lines, before the first, after the
  last); matcher (version tags, artists, ±3 s); provider (get hit, get rejected by matcher → search,
  404 → not found, 429/offline/garbage mapping); repository (consent, TTLs, failures not cached,
  in-flight de-dup, file cache round trip and corruption).
- `feature:nowplaying`: `LyricLayoutTest` (6: short line large and justified edge to edge, single
  word centred, long lines smaller and never clipped, shrinking below the smallest size, one enormous
  word, empty), `LyricsScreenTest` (8 Robolectric screenshots with the paper colour checked: first
  line light, second line dark, long line on a small screen, before the first line, consent, not
  found, a line early in its words, a line half sung). Screenshots land in
  `feature/nowplaying/build/screenshots/`.

- Continuous line (§12): `LineHandTest` (9), `LineCompositionTest` (15), `LinePlayheadTest` (7),
  `LinePerformanceTest` (2), `ContinuousLineScreenTest` (9 Robolectric screenshots, `line-*.png`);
  `WordTiming.end` in `LyricsTest`; the setting in `AppearanceLayoutTest.lyricsThemePicker` and
  `DeviceSettingsPersistenceTest`.
- Motion and the bass (D-82, §13): `LyricClockTest` (5: even steps from an uneven position, seeks,
  pause and resume, determinism, reset), `PulseFollowerTest` (5: each kick pushes and settles, no
  jumps in position or speed, still for silence, a held note and no measurement, quiet and loud
  masters alike, the same at 60, 90 and 120 Hz), `LineMotionTest` (6: words reached on time with a
  smooth pace, positions between milliseconds, strokes covering the route once, the glow within
  its tolerance and cheaper than the line, monotone slopes, the swell outside frame comparison),
  `LyricsScreenTest` (2 more: the Classic line swells with kicks and stays still without a pulse),
  and `AudioMeterTest` in `player:service` (8: audio untouched, a kick found at its song position
  and a cymbal not, nothing claimed where nothing was measured, a flush starting afresh, float
  audio, unreadable audio passed by, the three bands apart, nothing measured while nobody listens).
- The orb (D-83, §14): `OrbTimelineTest` (8: short gaps keep the words and a long one brings the
  orb, the camera's move never jumps, the page shows the line to come, the same state at the same
  moment whichever way it was reached, introductions and endings, a longer linger after an estimated
  end, no orb for Podium's own pacing or a gap without room, and the orb throughout without words,
  wake-ups for every change), `OrbDriveTest` (4: the music moves every value within bounds, no value
  jumps, paused it stops and settles, nothing measured settles while it drifts), `OrbLookTest` (3,
  the real shader on Robolectric: a dark hollow in a lit ring with its light kept to the orb,
  brighter with the music, nothing drawn when gone; `orb-*.png`), and `LyricsScreenTest.notFound`
  (Classic shows the orb on black).

## 11. Not done / next

- No "clear saved lyrics" control yet (the system clears the cache directory when space is low).
- Device acceptance (real timing against delegated playback) is listed in
  `docs/testing/UI_DEVICE_ACCEPTANCE.md`; it was not performed from the build container.
- Continuous line: see §12.8.

## 12. The Continuous line theme (D-81)

An optional lyrics theme (Settings ▸ Appearance ▸ Virtual display ▸ Lyrics theme; Classic stays the
default). The display becomes a matte black sheet on which one fine ivory line **writes the lyrics
by hand**, runs on from each phrase's last letter into the next phrase, and draws instrumental line
art between phrases. The whole song is one drawing; the display is a window that follows the pen.
Everything else on the screen (finding the words, consent, the Wheel, the details, the error and
loading states) is the same code as Classic (§7, §8). Code: `feature:nowplaying`, package `line`.

```
LyricsScreen ── LineScript (timing, pure) ── LineComposition (plan + lazy routes, pure)
     │                                         ├── LinePhraseSetter ── LineHand (the glyphs)
     │                                         └── LineMotifs
     └── ContinuousLine (frame loop) ── LinePlayhead (retrace) ── LinePainter (Canvas)
```

### 12.1 The hand

- **A single-stroke script drawn for Podium**, not a font file: `LineHandGlyphs.kt` holds each
  character as a short path in a 100-unit grid (`L`, `Q`, `C` segments; strokes separated by `|`;
  `*` marks a stroke drawn after the letter, like an i's dot or an x's second bar), its advance and
  the direction the pen arrives in. `LineHand` parses and flattens them once (adaptive Bézier
  subdivision to 0.3 grid units), slants them 0.16 like handwriting, and caches the result.
- **Coverage:** a–z, A–Z, 0–9 and `. , ' ’ ‘ " “ ” ! ¡ ? ¿ - – — : ; ( ) [ ] & / + …`. Accented
  letters are composed from their canonical decomposition (NFD): the base letter's strokes plus
  the accent's (acute, grave, circumflex, caron, diaeresis, tilde, ring, macron, breve, dot, double
  acute, cedilla, ogonek); an accent above an i or j replaces its dot.
- **Lowercase joins.** Each lowercase letter starts where its body starts (the top of an n's first
  stem, the top right of an a's bowl) and one generated cubic connector does the upstroke from the
  previous letter's exit along both letters' directions, so every join is the same smooth curve.
  Most letters leave along the baseline with a rising flick; b, o, r, v, w leave at the x-height.
  Capitals and multi-stroke letters lift where a hand lifts (the bar of a t is its exit, so it
  joins the next letter). Punctuation and numerals are reached with a lift, not joined.
- **Words** are joined by a quieter hairline; a new row is reached with a lift.
- **Other characters** (another script, ligatures like æ, symbols): the pen traces the system
  font's outline (`OutlineGlyphs`: `Paint.getTextPath` + `Path.approximate`, sized to the hand's
  x-height, slanted the same, cached). It is still line, never fill, but it is an outline, not a
  single stroke. Characters with no outline (controls, whitespace) are skipped.

### 12.2 Phrases on the paper

`LinePhraseSetter` sets a phrase in rows at most 80 % of the display's width (each row 0.22 em right
of the one above), at the largest scale that keeps the block within 46 % of the display's height
(down to 0.42; below that it may overflow and the camera follows the pen). Words are split exactly
as `WordTiming.words` splits them, so word *n* of the drawing is word *n* of the timing.

`LineComposition` places the phrases down the paper on a diagonal: each starts 24 % of the
display's width right of the last and 0.55 em below it. The line between two phrases carries on
from the last letter, drops into the clear space between the phrases, runs back along it and comes
down into the next first letter — never across the words (`route`). After instrumental line art the
next phrase starts just ahead of it, down and to the right, so the line runs straight out of the
last motif into the first letter.

### 12.3 Timing (only what the lyrics say)

`LineScript` turns the lyrics into phrases with word start times and an end:

- **Provider word times** (enhanced LRC, LRCLIB's `lyricsfile` words): each word is reached at its
  stamp.
- **Line times only** (most lyrics): the line's start is the provider's; the words inside it are
  paced by `WordTiming` (§5.1) and the phrase is marked `estimated` — the details overlay already
  says "Words paced to the line". `WordTiming.end` gives the end of the line's estimated span (or
  the provider's end time), never past the next line.
- Phrases sung back to back keep 240 ms for the connector, taken from the end of the phrase before;
  the next phrase still starts exactly on time.
- Lyrics paced by Podium (D-50) are drawn the same way and keep their "Timing estimated by Podium".
- Plain lyrics without a song length are laid on a nominal clock only so each line can be shown
  written; they are never auto-advanced (the Wheel moves through them, as in Classic).
- A song LRCLIB marks instrumental is one instrumental composition across its length (four minutes
  when the length is unknown). Loading, consent, not found, offline and errors keep their messages:
  nothing is classified instrumental before the provider says so.

Within a word the pen moves at constant speed along its prepared route (pen-time: route length,
with lifts weighted 0.25 and hairlines 0.8). In instrumental passages the pen moves at about 0.9 em
a second.

### 12.4 Instrumental line art

`LineMotifs`: nine hand-drawn motifs in the same grid — a flowing wave, a loop, a near-spiral, a
figure eight, beat dots on an arc, a note, two beamed notes, a crossing, and a long transition. Each
enters heading right at its origin and leaves heading right, so they chain into one line. A gap is
filled when the pen would need more than the connectors to cover it: the composition picks motifs
with SplitMix64 seeded by the track's key (FNV-1a) and the section's index — no two the same in a
row, a long transition every sixth — and chains them leaning down-right. The same song always draws
the same motifs; another song draws others. Where a motif crosses itself (found once, when it is
built) a short gold glint shows as the pen closes the crossing. Beat dots light as the pen passes
them, the latest one gold; they follow the pen, not the audio — **Podium does not detect beats**.

### 12.5 One drawing, a function of the position

- `LineComposition.cursorAt(position)` maps playback to a cursor: the section index plus the
  fraction of its pen-time drawn (word keyframes inside a phrase, linear in instrumental sections).
  `frameAt(cursor)` gives the pen's position and the camera. Both are pure: the same moment is the
  same frame, however it was reached (tested to the pixel on Robolectric).
- **The camera** holds still on a phrase while it is written (centred on it), and between phrases
  moves from one phrase to the next, leaning onto the pen's route smoothed over 3 em through
  instrumental art, never letting the pen within 10 % of the display's edge. Earlier phrases dim
  and slide away up and to the left as the next one comes in from the lower right.
- **Time, frame by frame (D-82):** the drawing doesn't follow the player's position raw. Read on
  the phone, it took uneven steps from frame to frame: read at a different moment after each
  refresh, counted in whole milliseconds, and corrected every few seconds. That is what made the
  pen stutter. `LyricClock` advances by the display's frame times while the music plays and leans
  onto the reported position (time constant 400 ms, never more than 8 % faster or slower than real
  time), and the cursor takes the fractional position. So every frame moves the pen the same
  distance, and it stays within a few tens of milliseconds of the music.
- **The pace inside a phrase** is a monotone cubic through its word keyframes (Fritsch–Carlson):
  the pen still reaches every word exactly on its time and never runs back, but its speed changes
  smoothly from word to word instead of switching at each one.
- **Seeking, rewinding, reading with the Wheel:** `LinePlayhead` notices a jump (the frame clock
  seeing the position move unlike time by more than 0.3 s, the Wheel choosing another line, or the
  cursor moving more than 0.35 sections at once) and takes the drawn cursor to the new one over 280–900 ms on an
  ease-out — the pen retraces its own route and the camera its own diagonal, then lands exactly on
  the target. While the music plays the drawn cursor *is* the target.
- **Pause/resume:** paused, the drawing doesn't move (the screen looks for a seek four times a
  second instead of every frame); resumed, it carries on from the position with no jump.
- **A new song** gets a new composition and a new playhead: nothing travels over from the last.

### 12.6 Drawing

`LinePainter` draws on the display's Canvas, clipped to it; the camera is a canvas transform.

- Matte black `#060606`; ivory `#F2EBDC` ink; champagne gold `#D6B677` used only for the pen's
  point and small glow, the last 0.14 em of wet ink behind it, the brief light across a finished
  phrase's last word, motif glints and the latest beat dot. Quieter lines (earlier phrases,
  hairlines, instrumental line) are ivory mixed toward the paper, opaque, so overlaps never bead.
- Strokes (D-82, a little bolder than D-81's): 2.3 dp lettering, 1.8 dp instrumental line,
  1.3 dp hairlines; pen 2.7 dp; the pen's glow 12 dp.
- **The line glows (D-82)**, softly, like a neon tube or a highlighter's ink: two wider strokes
  under every line (4.4 and 2.2 times its width), the inner one brighter (5.5 % and 14 % of the way
  from the paper to a warm ivory). They are opaque mixes, like the quieter inks, so crossings never
  bead, and they are drawn before any line so no line is ever under another's glow. Written phrases
  glow at 55 %. The glow is drawn from a simpler copy of each route (within 0.012 em, a pixel or
  two): both layers together cost about two thirds of the line's segments. Four finer layers looked
  softer close up but made frames late on the phone (§12.7).
- The pen's glow is one radial gradient made once and positioned by a translate — **no blur, no
  `RenderEffect`, no shader**. Nothing is drawn that isn't written yet.
- **With the bass (D-82, §13)** the drawing swells a little about the display's centre (at most
  1.4 %), its glow brightens (up to 60 %) and the pen's light blooms (25 %).
- **How it is drawn (D-82, measured, §12.7):** the line itself is drawn segment by segment with
  `drawLines`, anti-aliased, as in D-81. The glow is drawn as platform paths **without
  anti-aliasing**, which the GPU draws by itself. Its simpler route is cut into strokes of at most
  48 segments, made into paths once off the main thread as the pen nears a section. A written
  section's glow is one path per layer; the section being written keeps a growing path of its
  finished glow strokes, and only the stroke under the pen is rebuilt each frame. The glow's steps
  are dim and wide, so their edges show no aliasing (checked close up on the phone). Drawn
  anti-aliased, the same paths were rasterised on the render thread as masks and uploaded every
  frame (about 10 a frame, up to 667×625 px). Drawn segment by segment, the glow made the line
  about 7,500 small shapes a frame. Both kept the render thread over its frame.
- **Reduced motion:** no swell, no pen glow, no gold accents, no retracing (the drawing jumps to the
  position); the line keeps only its innermost glow; the camera moves between phrases in a few cuts
  instead of following.

### 12.7 Cost

Prepared once per song and display shape, off the main thread: the glyphs (once per app), each
phrase's layout and timing keyframes, the motif choices and placements, the camera's smoothed
route. Each section's route (points, kinds, pen-time, and the segments as `drawLines` arrays) is
rebuilt from its plan when drawn; eight are kept, and the next two are prepared off the main thread
as the pen enters a section — memory doesn't grow with the song.

Each frame: read the position, move the frame clock, map it to a cursor (binary searches), find
the pen on the prepared route, look up the bass, and draw the visible sections: a few prepared
paths each, the growing path of the section being written and the one stroke under the pen, plus a
few small circles and lines for the pen and accents. Nothing is laid out, shaped, parsed or built
per frame beyond that one stroke; nothing is read from disk or the network.

Measured on the build container's JVM (not the phone; `LinePerformanceTest`, printed in its test
report): a 48-line song prepares in about 10 ms, a section rebuilds in about 0.04 ms, mapping a
frame takes about 0.8 µs and allocates nothing over 600 frames; the busiest frame of a 12-, 48- or
160-line song is at most 82 line-drawing calls and about 3,200 segments. With D-82 a frame is at
most about 95 drawing calls: the line's 3,100 segments as before, and the glow's 2,200 simplified
segments (both layers) in a few GPU paths.

**Measured on the Nothing Phone (3a), 2026-10-15 (D-82)**, with the generated test tones and
invented lyrics, the Glass finish, the display at 90 Hz (11.1 ms a frame):

| | Raw position | `LyricClock` |
|---|---|---|
| Mean step error per frame | 0.4–3.1 ms | 0.02–0.47 ms |
| Worst step error in 5 s | 7–270 ms | 0.3–1.8 ms |
| Frames off by more than a quarter of a frame, per 5 s | 1–144 | 0 |

How the line was drawn, and how the frames went (each run about 20 s of the same song stretch):

| Drawing | Render thread issuing commands, p50 / p90 | Janky frames |
|---|---|---|
| Two glow layers, all `drawLines` (one early run) | | 0.22 % |
| Four glow layers, all `drawLines` | 12.0 / 13.3 ms | 4.3 % |
| Four layers, everything as anti-aliased paths | 5.6 / 8.5 ms | 0.6–2.7 % (6 runs) |
| … glow paths without anti-aliasing, line as anti-aliased paths | | 1.1–3.0 % (2 runs) |
| Glow paths without anti-aliasing, line as `drawLines` | | 0.10–0.25 % (3 runs) |
| … the same, later in the session: four glow layers | | 0.77–3.34 % (2 runs) |
| … no glow at all | | 0.15–0.35 % (2 runs) |
| **… two glow layers (shipped)** | | **0.30–0.36 % (2 runs)** |

Rows measured interleaved in one session compare fairly; across sessions the same build varied
(the phone was charging and had been running tests for over an hour), and single runs say little.
Two glow layers ran as smoothly as none, four did not, so the line ships with two. The final build,
measured twice at the end of the session, gave 1.0 % and 2.9 % janky frames; the drawing is the
same as the 0.30–0.36 % runs, so this is the session's variation, not a regression, and judging the
smoothness by eye on the listener's own music is still open (UI_DEVICE_ACCEPTANCE.md §3a.18).

Late frames of the line were real hitches: the app's own work stayed small (UI thread under 1 ms,
render thread 5–11 ms), and the time went after the frame was handed to the GPU. Classic's swell
renders in bursts after each hit. Most of Classic's late frames (65 of 76 in one sample) were the
first frame of a burst after idle, finishing in about 14 ms against a one-frame deadline. A swell
starting from rest one frame late doesn't show, so gfxinfo's "janky" percentage overstates
Classic's (9–19 % by that count, about 1.3 % late in the middle of motion). A trace of the
anti-aliased paths showed the cost: about 10 path masks rasterised and uploaded every frame,
about 3 ms of render-thread time, because the swell and the camera change the drawing's transform
from frame to frame. The UI thread takes about 1 ms a frame (p90 1.4 ms) in every case; much of the
GPU's time (13–18 ms a frame) is the Glass shell's.

### 12.8 Limits

- The hand is one style, hand-authored; some capitals (B, E, P, S) and numerals are plainer than
  the lowercase, and capitals that leave at the top (N, V, W) join the next letter with a loop.
- Characters outside the hand are traced outlines of the system font, not single strokes.
- Word pacing inside line-timed lyrics is an estimate (§5.1); beat dots are not beats.
- Very long phrases set below 0.42 may run off the display; the camera follows the pen, so the
  words being written stay visible, but not the whole phrase at once.
- The glyph and motif tables are tuned by eye on Robolectric screenshots at one phone size.
  Frame times were measured on the phone with test tones only (§12.7); the rest of the device
  acceptance (§3a of the device plan) is still open.
- The glow is four opaque steps, not a true blur: up close its edge is a fine staircase.
- The swell moves only with music Podium's own player plays (§13); songs played by another app
  leave the words still.

## 13. The bass the lyrics move with (D-82)

`AudioMeter` (`player:service`; `BassMeter` in D-82) is a pass-through step in Podium's own audio
path, after the equaliser, so it hears what the listener hears. It changes no sample (tested byte for
byte) and needs no permission: it reads the decoded audio, not the microphone or the system's
output, and never decodes anything a second time.

- The audio is mixed to mono and measured as RMS over 10 ms blocks three ways (D-83): whole,
  low-passed at 120 Hz (second-order Butterworth: kick drums and bass lines) and high-passed at
  2.5 kHz (cymbals, breath, sparkle). `AudioPulse.bassAt` reads the low band; `levelsAt` all three.
- It measures only while something listens (`AudioPulse.listen`, held by the lyrics screen while
  it is resumed). Otherwise the audio passes straight through and only the song position is
  counted, so listening can start mid-stream with readings still filed in the right place.
- **Each block is filed under the song position its audio belongs to.** Media3 tells every flush
  of the audio path where the stream starts (`StreamMetadata.positionOffsetUs`, read from
  `DefaultAudioSink` in 1.11.1), and the blocks count on from there. The player decodes a few
  hundred milliseconds ahead of what is heard, so the UI asks by the playback position
  (`AudioPulse.bassAt`), never for "now". On the phone the sink flushed at every song change
  ("stream starts at 0 ms") as well as on seeks. Every 5 s window of lyrics frames found its bass,
  across song changes too, except the first after a song was started by the debug command, where
  about a third of the lookups found nothing yet.
- Five seconds of blocks are kept in a ring, written on the playback thread and read on the main
  thread without locks. A flush starts afresh, so a reading never mixes two streams. Positions not
  yet decoded, before the stream, or from audio it can't read (not 16-bit or float) answer "not
  measured".
- `AppGraph.audioPulse` gives the lyrics the meter only while Podium's own player has the music.
  While another app plays it, the answer is "not measured", so an earlier song's record never moves
  another song's words. Crossfade's second player isn't measured: the position the lyrics read is
  the first player's.
- **The swell** (`PulseFollower`, `feature:nowplaying`) moves with the bass's hits, not its level:
  how far the bass rises above its own last 220 ms. A kick pushes; a held bass note pushes once,
  then lets the words settle. Each hit is judged against the song's recent strongest (rising in
  60 ms, falling over 3 s), so quiet and loud masters move alike, and below 35 % of that nothing
  moves. The push eases in over 20 ms and a critically damped spring follows it (out at 6 Hz, back
  at 2.4 Hz). Position and speed never jump, at any frame rate. The bass is read 50 ms ahead, so
  the swell lands on the kick rather than after it.
- Classic: `rememberLyricSwell` runs while the music plays and settles after it stops. While
  nothing is measured it looks four times a second instead of every frame; without a pulse it never
  runs. Continuous line: the same follower inside its own frame loop. Reduced motion: no swell.
- Diagnostics (device testing): `adb shell setprop log.tag.PodiumLyricsClock DEBUG` logs, every 5 s,
  how evenly the drawing moved (raw position against the clock) and how often the bass was found,
  and each time the orb's camera target jumps between frames (with where the capped camera went);
  `log.tag.PodiumAudioMeter DEBUG` logs each flush's starting position. Numbers only.

## 14. The orb (D-83)

For the lyrics themes that aren't a line (Classic today), long stretches without words bring the
orb: a softly luminous ring of ivory and champagne gold around a dark hollow, on black, that moves
with the music. The Continuous line theme keeps its own instrumental line art and never shows it.

### 14.1 When (`OrbTimeline`, pure)

- A stretch without words runs from where a line's words end (`WordTiming.end`) to the next line's
  start; also from the song's start to its first line, and from its last line to the song's end.
- It brings the orb only when it lasts at least **6 s** and has room for the whole move. The last
  line lingers 1.4 s (2.8 s when its end is only Podium's estimate, in case a held note is still
  sounding). The camera pulls back over 1.8 s, the orb holds at least 0.8 s, and the camera goes back
  in over 1.5 s, **arriving exactly when the next line starts**. After the last line the orb stays
  to the end. Shorter gaps keep the words on screen.
- The states are named: lyrics, brief gap, sustained (lingering), entering, orb, exiting. The state
  is a pure function of the playback position, so a seek, a rewind, a pause or a new song can never
  leave the screen between two states. Seeking into a long gap lands at the orb; seeking into words
  lands on the words.
- Never for lyrics timed by Podium (paced plain lyrics, D-50: their gaps are guesses) or plain
  lyrics, and not while the Wheel is reading. Loading, consent and errors keep their messages.
- **No words at all** (the provider says instrumental) **or none found**: the orb throughout,
  fading in once over 0.7 s, with "Instrumental" or "No lyrics found" small at the top.

### 14.2 The camera (`OrbStage`)

One camera, two places: the page (the lyric on its paper) and the orb behind it on black.
Pulling back, the page shrinks toward the display's centre, seen through a round window that closes
in from beyond its corners to a circle (92 % of the page's width) with a softly feathered edge (the
outer third of its radius fades into the dark), and fades inside the hollow. It is never the page's
own edges or cut corners (a listener report, 2026-10-15). The black comes in around it at once, and the orb's
ring comes into view from beyond the edges. Going in reverses it: the ring passes out beyond the
display, and the next line's page grows out of the hollow, its words starting on time. While the
orb holds, the page already shows the line to come ("…"), so it emerges ready.

The zoom is geometric (4.2× between the two places) and eased. Reduced motion: the two cross-fade
and the orb holds still.

**The camera never moves faster than a whole move in 0.4 s** (`OrbTimeline.follow`). The
timeline's own moves are slower, so they pass unchanged and still arrive on the line's time. A
starting song's position often blips backward for a frame or two; uncapped, a blip across the
moment the camera starts moving put the words back on screen for a frame (a listener report,
2026-10-15). Capped, it moves the camera a few percent at most, and a real seek still lands within
0.4 s. Opening the screen mid-orb places the camera at once.

### 14.3 The orb (`OrbRenderer`, `core:designsystem`)

An AGSL shader (Android 13 and later), drawn over the orb's own square, never the whole display.
The ring's radius drifts with smooth 3D value noise taken around the circle and through time. Inside
the ring its light spreads wide and soft; outside it falls off quickly. A second, deeper ring drifts
on its own for depth. Brightness moves slowly along the ring, a highlight travels around it, and the
hollow goes dark. Before Android 13: a calmer drawing of gradients moved by the same values.

The music, through `OrbDrive`: the whole signal brightens the ring and speeds its drift and turn a
little; the bass swells it (the lyrics' own spring, at most 3.5 %); the top ripples its surface; a
hit lights the highlight toward gold. Each is judged against the song's own recent loudest and
smoothed with an attack and a release, so nothing jumps. The orb's own time advances only while the
music plays. Paused, or with nothing measured, every value settles and the orb holds still.

The visual idea follows the "voice-powered orb" (a 21st.dev component, WebGL/OGL) the listener
shared: a noisy luminous ring with a dark centre and a travelling light. Podium's shader is its own
code, written for this: no code or constants were taken from the component, and nothing of its
microphone input, WebGL or React is used. Per CLAUDE.md, `RuntimeShader` stays in
`core:designsystem`.

### 14.4 Cost

- One frame loop for the stage, inside the lyrics screen. It runs only while something moves: the
  orb shows, the swell is moving, the music is measured, or the camera moves within 0.4 s.
  Otherwise it waits for the next change. It stops when the screen goes.
- The shader is made once. Per frame only its uniforms change, and it covers the orb's square.
  No blur, no offscreen pass.
- The meter measures only while the lyrics screen is resumed.
- **Measured on the Nothing Phone (3a), 2026-10-15**, test tones with invented lyrics, Glass
  finish, during the orb's hold: 376 frames in about 3.6 s, **0 janky frames**. UI thread 0.8 ms
  and render thread 2.7 ms a frame (p50), GPU 12–14 ms, about the shell's own.
