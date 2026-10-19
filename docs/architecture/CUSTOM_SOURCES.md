# Custom sources (D-91)

Written 2026-10-19. A custom source is a stream source the listener adds themselves: an addon
that speaks the BitChord addon protocol. Songs still come from the online catalogue (YouTube
Music). When a custom source has the **exact same recording** as a song, the song plays from that
source, in the listener's order. Otherwise it plays from YouTube Music as before. YouTube Music
stays the default, it is always there, and it is the fallback for everything.

## 1. What it is, and what it isn't

- **It is a stream source.** The resolver asks it for one song's audio, nothing else.
- **It isn't a catalogue.** It is never browsed or searched by the listener, and it never appears
  in Online, the library, autoplay or recommendations. It never enters the `SourceRegistry`, which
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
   - **Edition guard:** where either side names an edition (explicit or clean), the other has to
     name the same one. An edition left unstated isn't proof. So an explicit song never plays from
     a copy that doesn't say it's explicit, and a song YouTube Music doesn't mark never plays from
     a copy marked explicit.
   - The first source that resolves wins. A miss or failure moves on to the next source.
   - **Budget:** all custom sources together get 8 s (`DEFAULT_PREFERRED_BUDGET_MILLIS`). After
     that the song's own source plays it, and the attempt records "custom sources took too long".
4. The song's own source (YouTube Music).
5. EXACT fallback on the other registered sources of the environment (unchanged).

Health is recorded per custom source in the same `SourceHealthMonitor`: a miss never hurts it,
failures open its circuit breaker, and a benched source is skipped until it recovers. A refused
stream mid-song is asked for afresh from the **same** custom source (D-18). A custom source that is
turned off or removed takes its pinned streams with it; the song playing keeps its stream.

### What this means with an addon that doesn't say "explicit"

Many addons (the one used to test this, for example) don't mark explicit songs. With them,
YouTube Music songs marked explicit always play from YouTube Music. That is the identity rule
(CLAUDE.md: never across explicitness boundaries), not a fault.

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

- **The list:** each source with how it's doing (On, Off, Checking, Not answering), then "Add a
  source", then one line on how it works.
- **Add a source:** type or paste the source's https address as it gives it, for example
  `https://addon.example.com` (`/manifest.json` on the end is optional).
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

- `sources:api` — `PreferredSourcesTest` (13): order, EXACT only, edition guard both ways,
  fall-through on miss and failure, the budget, NONE and downloads, environments, pins and
  refresh, known equivalents, nothing changes without custom sources.
- `sources:addon` (36) — protocol parsing, quality and MIME, the source against scripted HTTP, the
  resolver end to end (a catalogue song plays from the addon's exact copy, never its radio edit;
  an explicit song stays on its own source), and the real transport against a local HTTP server
  (redirects, policy, size cap).
- `player:service` — `CustomSourceStreamTest` (3): a catalogue song plays from a custom source's DASH
  stream (FLAC, measured from the media, real DASH parsing and extraction of a generated fixture),
  a song the source doesn't have plays from its file, and a queued item built for DASH plays from
  its file once the source is off.
- `app` — `CustomSourcesTest` (8): add (addresses, consent, problems, duplicates), order, on and off,
  settings, sealed persistence across restarts, remove, check.
- `feature:settings` — `CustomSourcesLayoutTest` (6): every screen stays inside the display, the Wheel
  drives it, and Remove asks first.

Not yet verified on the phone.
