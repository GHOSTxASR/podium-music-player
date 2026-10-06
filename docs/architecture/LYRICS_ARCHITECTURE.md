# Lyrics architecture

Status: implemented (core:lyrics, feature:nowplaying `LyricsScreen`), 2026-10-06.
Decisions: D-11 (consent), D-39 (provider and full-screen lyric mode).

Now Playing ▸ Lyrics turns the whole virtual display into the lyric. Podium never claims words it
can't stand behind: lyrics are shown only when a provider's record is confidently the same recording,
synced lyrics follow the real playback position, and plain lyrics never pretend to be synced.

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

## 8. States and copy

| State | Title | Body |
|---|---|---|
| nothing playing | Nothing playing | Choose a song, then open its lyrics. |
| loading | Finding lyrics | |
| consent | Find lyrics online? | Podium sends the song title, artist, album and length to LRCLIB. Press Center to allow. Menu goes back. |
| not found | No lyrics found | Lyrics for this song aren't available. |
| offline | Lyrics unavailable offline | Connect, then press Center to try again. |
| rate limited | Too many requests | Wait a moment, then press Center to try again. |
| failed | Couldn't load lyrics | Press Center to try again. |
| instrumental | Instrumental | This song has no words. |

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

## 11. Not done / next

- No "clear saved lyrics" control yet (the system clears the cache directory when space is low).
- Device acceptance (real timing against delegated playback) is listed in
  `docs/testing/UI_DEVICE_ACCEPTANCE.md`; it was not performed from the build container.
