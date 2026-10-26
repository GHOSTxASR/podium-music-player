# Custom sources (D-91)

Written 2026-10-19. A custom source is a stream source the listener adds themselves: an addon
that speaks the BitChord addon protocol. Songs still come from the online catalogue (YouTube
Music). When a custom source has the **exact same recording** as a song, the song plays from that
source, in the listener's order. Otherwise it plays from YouTube Music as before. YouTube Music
stays the default, it is always there, and it is the fallback for everything.

## 1. What it is, and what it isn't

- **It is a stream source.** The resolver asks it for one song's audio, nothing else.
- **It isn't a catalogue.** It is never browsed or searched by the listener, and it never appears
  in Online, the library, autoplay or recommendations. YouTube Music drives all of that (autoplay,
  radio, suggestions, likes, history) about the queue's own songs, whichever copy is heard (D-96). It never enters the `SourceRegistry`, which
  the online screens read (the app treats the first ONLINE source in the registry as the online
  service).
- **It is ONLINE only.** Local music always plays from the phone (D-34, D-36).
- **It never changes what a song is.** The song the listener chose stays the song in the queue,
  on Now Playing, in history and in likes. Only the serving source changes. That source is
  recorded (session `servedBy`, history, logs) and never named on normal Now Playing (D-36).

## 2. The protocol

Compatible with BitChord's addon protocol (see
[research/BITCHORD_ARCHITECTURE_REVIEW.md](../research/BITCHORD_ARCHITECTURE_REVIEW.md) §9).
Podium's client is its own implementation (`sources:addon`) and contains no BitChord code. Every
call is a GET under the addon's address, answered with JSON:

| Call | Answer (fields Podium reads) |
|---|---|
| `/manifest.json` | `id`, `name`, `version`, `description`, `resources` (`search`, `stream`), `allowDownloads`, `settings[]` (`key`, `type`, `label`, `default`, `options[] {label, value}`) |
| `/search?q=…` | `tracks[]` (or a bare array, or `results`/`items`): `id`, `title`, `artist` (or `artists[]`), `album`, `duration` (seconds; `durationMs` or `m:ss` also read), `isrc`, `explicit` |
| `/stream/{id}` | `url`, `format`/`manifest` (`dash`, `hls`, `flac` …), `quality` (free text), `encrypted`/`drm`, `headers`, `expiresAt` |

- **Settings of a kind Podium doesn't know (D-98).** A setting with an unknown `type`, or none (BitChord
  reads only defaults and options, so addons often leave it out), is kept: with `options` it's a
  choice, without them text, and its default travels like any other. An option with only a `value`
  is named from it (`HI_RES` → "Hi res").
- **The address's own query (D-98).** A query typed with the address (`?token=…`) is kept as typed and
  sent with every request, the manifest's too, ahead of the settings.
- **Settings.** The listener's value for each declared setting goes with search and stream as a
  query parameter (`?quality=lossless`). Only declared keys are sent, and only values the addon
  declares (a choice's options, `true`/`false` for a toggle, text up to 256 characters).
- **Reading.** Answers are read leniently and fail soft (`AddonProtocol`): unknown fields are
  ignored, what can't be read is null, and nothing throws. Sizes are capped (2 MB per answer,
  1,000-character ids, 20 settings, 10 headers). A header with a bad name, or a value with a line
  break, is dropped.
- **Errors.** 404/410 → miss. 429 → rate limited (honours `Retry-After`). 401/403 and 5xx → server
  error. Network problems → network failure. An unreadable answer → unknown failure. The address
  never appears in an error or a log, because it can carry the listener's token.
- **Asking politely, and getting an answer (D-96)** (`AddonClient`):
  - Requests to one addon start at least 200 ms apart, in the order they were made.
  - Some addons now and then leave one request hanging while the same request sent again answers
    at once. A request with no answer after 2.5 s is sent once more, and the first answer wins. One
    that fails at once (a dropped connection, a 502/503/504) is sent once more straight away. Twice
    down is down.
  - After a 429 nothing is sent until its `Retry-After` (30 s when it doesn't say, 10 minutes at
    most). Asks in the meantime get "rate limited" without touching the network.

## 3. Resolution (`StreamResolver`)

The resolver's order is now:

1. Pinned selection (reused while valid).
2. An owned copy (a download or a file on the phone), as before.
3. **Custom sources** (`StreamProviders.ordered(environment)`), in the listener's order. This step
   runs only when **all** of the following hold:
   - the request allows fallback (`FallbackPolicy.EXACT_ONLY`, the default);
   - it isn't a download;
   - the song's environment is known (and only that environment's providers are asked).

   For each source, in order:
   - A copy already known to be EXACT (`EquivalenceStore`) is used directly. Otherwise the source
     is searched (`MatchQueryBuilder`) and `TrackMatcher` decides. Only **EXACT** qualifies: the
     same title and version tags, a shared artist, featured artists that agree, duration within
     ±2 s and the same album where both state one.
   - **Any EXACT copy plays (D-92).** The matcher's own rule on editions applies, and only that:
     a copy marked clean is never the explicit song (or the other way round), but a copy that
     doesn't say which edition it is plays like any other. If the song is there, it plays from there.
   - The first source that resolves wins. A miss or failure moves on to the next source.
   - **A resting source** (its circuit open, or asked too often) is skipped and recorded as
     "resting". That never counts as not having the song (D-96).
   - **Budget:** all custom sources together get 5 s (`DEFAULT_PREFERRED_BUDGET_MILLIS`, D-96). A
     custom source's search may use all of it, and a search cut short by the budget never counts
     against the source. After that the song's own source plays it, and the attempt records "custom
     sources took too long".
4. The song's own source (YouTube Music).
5. EXACT fallback on the other registered sources of the environment (unchanged).

Health is recorded per custom source in the same `SourceHealthMonitor`: a miss never hurts it,
failures open its circuit breaker, and a benched source is skipped until it recovers. A refused
stream mid-song is asked for afresh from the **same** custom source (D-18). A custom source that is
turned off or removed takes its pinned streams with it; the song playing keeps its stream.

### When custom sources are slow: YouTube Music first, then the lossless copy (D-92, D-96)

When the custom sources don't answer within their 5 s (a slow network, a server waking up), fail,
or are resting, the song starts from YouTube Music, and they're asked again in the background
(`StreamResolver.preferredCopy`, 30 s each time):

- At once, then after 15 s, 30 s, 1, 2 and 4 minutes. While every custom source is resting
  (`preferredRestEndsAt`) the player waits for the rest to end instead of asking. A source answering
  again (another song, a background check in Settings) cuts any wait short.
- It stops when a copy turns up, when every source answers that it hasn't got the song (then it
  isn't asked again until the sources change), or when the song has played or left the queue.
  Songs are asked for one at a time. One that ran out of asks is asked again, no sooner than 5
  minutes later, when it comes round.

When a copy turns up:

- **A queued song** (the next one, or any in Up Next when it comes round) is pinned to the copy and
  its media item rebuilt, so the player never uses a YouTube Music stream it had started loading.
- **The song playing, paused or not yet started** switches outright.
- **The song playing** moves over mid-song, like Apple Music stepping up its quality: crossfade's
  second player loads the copy 6 s ahead and waits there, paused and silent. It starts as the song
  gets there, is brought level, and a 0.4 s equal-power crossfade moves the sound to it. It can't
  lose the race (D-96):
  - It starts only once it's ready where the song is.
  - If the song gets there first, the copy keeps loading and jumps forward inside what it has
    already loaded once that reaches 3 s beyond the song, instead of starting over further on.
  - It starts over from further ahead only if it can't catch up within 12 s (twice at most), and
    gives up only near the end of the song.
  - **Lined up by sound** (`SoundAlignment`): two copies can start their audio a little apart. Both
    players' meters record the loudness of every 10 ms they decode. The copy's onsets are compared
    with the song's over two seconds, at offsets of up to ±0.5 s, and a clear match sets where the
    copy is brought level. With nothing clear to go by, equal positions are used. The first player plays on silently, kept level, so the queue, position, notification and
  lyrics stay with it. At the next pause or seek it switches to the copy itself, where the copy was
  heard; when the song ends it simply moves on. Crossfade into the next song works as usual. While
  the copy is heard, the session names its source and the quality label is measured from the
  second player's decoder.
- When the mid-song move can't happen (the song is nearly over, a crossfade is under way), the
  switch waits for the next pause or seek, or the next time the song plays.

Adding or turning on a custom source does the same for the song playing and the next few. This is
the one exception to "never mid-track" (D-18): only to an EXACT copy, of the song the listener chose.

## 4. Playback

- **Media.** The addon's `url` becomes `PlayableMedia.uri`. A relative address is resolved against
  the addon's own address. The MIME type comes from `format`/`manifest`, the explicit MIME, or the
  file extension: DASH → `application/dash+xml`, HLS → `application/x-mpegURL`; `.flac`, `.mp3`,
  `.m4a` and so on map to their audio types; anything else is left for the player to sniff.
- **Open media only.** `encrypted: true` (or `drm: true`) is a miss (`NOT_STREAMABLE`). Podium
  never works around protection.
- **Network policy (D-09)** on every request and every stream: https anywhere; http only to the
  listener's own network, after they agree to it. Redirects are followed by hand, at most three
  hops, and each hop is checked.
- **Expiry.** `expiresAt` if the addon gives one; otherwise 15 minutes, after which the song is
  resolved again before it plays.
- **Quality is a claim.** The addon's `quality` text is parsed only for what it states outright:
  bit depth, sample rate, bitrate, and a codec only when named ("lossless" alone names none). It
  becomes `claimedQuality`. Labels come from what the decoder measures
  ([audio-architecture.md](../audio-architecture.md) §5).
- **DASH and HLS in the engine.** The player picks the kind of media source when an item is
  added, before its stream is known. A queue item's placeholder URI therefore carries the kind it
  was built for (`podium://queue/<uid>?as=dash`):
  - When an item resolves to another kind, the engine rebuilds that media item for it
    (`replaceMediaItem`). This happens before the loader waiting on the resolution is released,
    and a playing item resumes where it was.
  - If a loader still reaches a stream of the wrong kind, the data-source resolver raises
    `PodiumReopenException` instead of letting a file extractor misread a manifest. The engine
    treats that as a rebuild, not an error: no error is counted, nothing is skipped, and the source
    isn't penalised.
  - Live stations stay live HLS from the start and never change (D-90).
  - Local and YouTube Music items are files, so they keep exactly the placeholder URI and media
    source they had.
  - `media3-exoplayer-dash` is added. Headers an addon binds to a stream apply to its first request
    (the manifest).

## 5. Storage and security

- Custom sources are kept sealed in the app's Keystore-backed `CredentialStore`. For each source
  that is its address, its manifest (rewritten in the protocol's own shape), on or off, and the
  listener's settings. The order is kept under `custom-sources`. Writes are serialised and run off
  the main thread.
- A source's id is `custom-` plus the first 40 bits of the SHA-256 of its address, so it is stable
  and doesn't reveal the address. Settings show only the host.
- No analytics, no telemetry. Nothing about custom sources leaves the phone except the listener's
  own requests to the sources they added.

## 6. Settings ▸ Custom sources

- **The list:** each source with how it's doing now (On, Off, Checking, Not answering, Asked too
  often), then "Add a source", then one line on how it works. A rest that has run out reads as over.
- **Checked by itself (D-96):** a source that isn't answering is asked for its manifest quietly,
  20 s after it stopped, then after 40 s, 80 s and so on, up to every 5 minutes. An answer puts it
  straight back to work, and the player asks it again for the song playing and the ones coming up.
  A source asked too often isn't asked until its rest is over. Check now is still there.
- **Add a source:** type or paste the source's https address as it gives it, for example
  `https://addon.example.com`. A `.json` file on the end (`/manifest.json`, or any other the addon
  names itself by) is trimmed to its folder; a query (a token) is kept (D-98).
  - **Paste** reads the clipboard.
  - **Center**, Done or **Add source** asks the source for its manifest first. A source that can't
    both search and stream isn't added.
  - An http address on the listener's own network asks before connecting (D-09).
  - Problems are explained with a next step, for example "No source answered at that address.
    Check it and try again."
- **A source:**
  - Use this source (on/off) and each setting it declares (a choice opens a menu; a toggle
    flips; text opens an entry screen).
  - Move up and Move down (shown with the place it is asked in), and Check now (asks for the
    manifest again; a source that now answers as a different addon is flagged).
  - Its problem, if it has one, its host, and Remove (confirmed).
- Generic throughout: no provider is named in code, and the UI shows only the name the addon gives
  itself.

## 7. Tests

- `sources:api` — `PreferredSourcesTest` (17): order, EXACT only, any edition the matcher allows,
  the background ask (`preferredCopy`, `mayStillAnswer`), a resting source and a slow search (D-96),
  fall-through on miss and failure, the budget, NONE and downloads, environments, pins and
  refresh, known equivalents, nothing changes without custom sources.
- `sources:addon` (40) — hedged and retried requests, the hold after a 429, pacing (D-96), protocol parsing, quality and MIME, the source against scripted HTTP, the
  resolver end to end (a catalogue song plays from the addon's exact copy, never its radio edit;
  an explicit song stays on its own source), and the real transport against a local HTTP server
  (redirects, policy, size cap).
- `player:service` — `CustomSourceUpgradeTest` (5): a song that started from the catalogue moves to
  the custom source's copy mid-song and for good at a pause; a copy whose first bytes take 9 s joins
  from inside what it has loaded, where the song is (D-96); a source that was resting is asked again
  as soon as it answers (D-96); a queued song moves before it plays; a song no custom source has
  isn't asked for again. `SoundAlignmentTest` (7): offsets found either way, a quieter copy placed,
  other music and silence left unsure, nothing said too early. `CustomSourceStreamTest` (3): a catalogue song plays from a custom source's DASH
  stream (FLAC, measured from the media, real DASH parsing and extraction of a generated fixture),
  a song the source doesn't have plays from its file, and a queued item built for DASH plays from
  its file once the source is off.
- `app` — `CustomSourcesTest` (10): add (addresses, consent, problems, duplicates), order, on and off,
  settings, sealed persistence across restarts, remove, check, background checks and a rest running
  out (D-96).
- `player:api` — `MultiSourceAutoplayTest`: a custom source holding the seed is never asked for
  suggestions (D-96).
- `feature:settings` — `CustomSourcesLayoutTest` (6): every screen stays inside the display, the Wheel
  drives it, and Remove asks first.

Not yet verified on the phone.
