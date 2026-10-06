# YouTube / YouTube Music Source Investigation

**Researched:** 2026-10-04 · **Status:** investigation only — no Podium code, dependency, or pipeline was changed · **Decision owner:** the user (D-20 is still *Pending user*)
**Related:** [ADR-013](../adr/ADR-013-provider-source-architecture.md) · [ADR-014](../adr/ADR-014-bitchord-inspired-independent-implementation.md) · [SOURCE_CAPABILITY_MATRIX.md](../architecture/SOURCE_CAPABILITY_MATRIX.md) · [MUSIC_SOURCE_ARCHITECTURE.md](../architecture/MUSIC_SOURCE_ARCHITECTURE.md) · [PLAYBACK_TARGETS.md](../architecture/PLAYBACK_TARGETS.md) · [BITCHORD_ARCHITECTURE_REVIEW.md](BITCHORD_ARCHITECTURE_REVIEW.md) · [2026-10-02-provider-policies.md](2026-10-02-provider-policies.md) · [2026-10-02-music-sources.md](2026-10-02-music-sources.md)

## Method and hygiene

- **Podium:** read the current source (`main`, clean, HEAD `4d9da92`) and architecture docs.
- **Convx:** `cosmictaserdev-creator/Convx`, default branch `main` at `1e2237d9` (2026-08-20 — the latest non-Dependabot commit; Dependabot branches were pushed up to 2026-10-03). Read in a scratch directory outside this repository.
- **BitChord:** `kushagrasinghx/BitChord`, default branch `main` at `85d19183` (2026-10-02, unchanged since Podium's 2026-10-02 review). The unmerged `v1.7.1` branch (pushed 2026-10-04) was inspected through its commit log and diffstat only.
- **Upstreams and history:** GitHub API (repository metadata, releases, issues), plus byte-level comparisons of a few files to establish lineage.
- **Official sources:** Google/YouTube developer documentation and Google Play policy pages, fetched 2026-10-04 (list in the appendix).
- **Nothing was built or executed** from either external project.
- **ADR-014 hygiene applies to this document:** it describes behaviour and design in prose. It reproduces no code, constants, word lists, regexes, request bodies, client version strings, or orderings from Convx or BitChord. Mechanisms that exist to get past YouTube's protections are **named, not explained**. Where a component's purpose is circumvention, the report says so and stops there (brief §65, ADR-013).
- **This is not legal advice.** Policy text is quoted only where it's needed to show what a rule says. Any conclusion drawn from it is labelled as inference.

### Evidence labels

| Label | Meaning |
|---|---|
| **[P]** | VERIFIED FROM PODIUM CODE |
| **[C]** | VERIFIED FROM CONVX CODE |
| **[B]** | VERIFIED FROM BITCHORD CODE (or its official docs site) |
| **[YT]** | VERIFIED FROM OFFICIAL YOUTUBE / GOOGLE DOCUMENTATION (Google Play policy pages are marked **[YT/Play]**) |
| **[H]** | VERIFIED FROM PROJECT ISSUE / RELEASE / COMMIT HISTORY |
| **[I]** | TECHNICAL INFERENCE |
| **[?]** | UNKNOWN |
| *LIKELY* | A judgement supported by the evidence but not proven |

---

## Executive Summary

1. **Podium can host a YouTube source architecturally.** The facet model, capability states, `Basis`, `PlaybackTarget.Embedded`, `FacetResolution.Miss/Failed`, health breaker, EXACT-only fallback and the version-aware matcher all exist and are tested **[P]**. A YouTube source would be one more `MusicSource` in its own `sources:*` module.
2. **Online can't take a second source unchanged.** It assumes one online source: `AppOnlineRepository` uses "the first enabled ONLINE source" for every catalogue call, and `OnlineRepository.source` is a single `OnlineSource?` **[P]**. The screens can stay as they are, but the repository layer must learn to choose between or combine online sources. Registry enable/priority APIs exist but nothing outside tests calls them **[P]**.
3. **Convx and BitChord use the same fundamental technique (VERIFIED).** Both use YouTube's undocumented **InnerTube** API: the web-music client identity for search, browse and next, and a per-video **player** request for streams. Both also:
   - walk a list of official-client identities when one is refused;
   - keep a guest `visitorData` session;
   - mint **PoTokens** by running Google's BotGuard in a hidden **WebView**;
   - run YouTube's player JavaScript to solve the signature and throttling ("n") transforms, with **NewPipeExtractor** involved;
   - sign in by capturing cookies from an in-app Google login.

   They share lineage through **Metrolist**: both ship a PoToken helper file identical to Metrolist's, Convx's `innertube` module tracks Metrolist's, and BitChord depends on Metrolist's **InnerTubeX** library **[C][B]**.
4. **What playback comes down to in both apps.** Each YouTube play ends as a **progressive HTTPS URL to an audio-only adaptive format** (Opus-in-WebM or AAC-in-MP4). That URL expires, is bound to request headers, and is fed to Media3 through a `ResolvingDataSource` **[C][B]**. Neither uses DASH/HLS, an embed, or remote control for YouTube audio.
   - *Can Podium represent it?* **"Existing Podium contract is sufficient"** — `PlayableMedia` already carries uri, headers, MIME type, claimed quality, expiry and a stable cache key **[P]**.
   - *Can Podium obtain it?* Not without the stream-unlock layer (Y3), which ADR-013 and the project rules exclude.
5. **The only permitted YouTube playback target is the official embedded player (Y2).** It must be:
   - visible, at least 200×200 px, with nothing overlaid;
   - started automatically only when more than half of it is on screen;
   - never audio-only (III.I.7) and never in the background (III.I.9) **[YT]**.

   Podium has the type (`PlaybackTarget.Embedded`, `EmbedConstraints`) but no engine for it. The service plays `DirectStream` only, and the tested `PlaybackRouter` isn't wired in **[P]**. So: **"Podium requires an EmbeddedEngine, router wiring and a visible player surface inside the VirtualScreen."** This target **cannot be a Media3 background session**.
6. **Catalogue access is easy; playback is not.** YouTube Music's catalogue is broad: official audio ("art tracks"), music videos, uploads, live, covers, remixes, lyric videos. But being found in search doesn't make a song playable.
   - **Y1 (catalogue-only):** mainstream songs would be *discoverable*, but they'd play only through an **EXACT** match on a source Podium may stream (local files, Audius). For mainstream music that will usually be "Not available from your sources" **[I]**.
   - **Y2:** fills the gap only as a foreground video player.
7. **Identity signals are better than titles, but there's no ISRC.** InnerTube gives a structured *music video type* (audio track / official video / user upload / private upload) and an explicit badge, and both projects use them **[C][B]**. Neither InnerTube (as parsed by either project) nor the Data API exposes ISRC, label or album fields **[C][B][YT]**. Podium's matcher already treats "music video audio" as a version tag that blocks EXACT **[P]**.
8. **Maintenance evidence: frequent, user-visible breakage.**
   - Convx had a cluster of "songs can't be played" issues from 2026-08-15 to 08-31; six are still open, with no maintainer commit on `main` since 2026-08-20 **[H]**.
   - BitChord changed its InnerTube code in 58 commits over Aug–Sep 2026, switched stream resolution to InnerTubeX in v1.7, and still has open "can't play" and "wrong song" reports **[H]**.
   - InnerTubeX shipped 6 releases in 24 days; NewPipeExtractor shipped 6 in 2026 **[H]**.
   - BitChord's resolver also downloads a **third-party, remotely hosted cipher configuration** at runtime **[B]**.
9. **Licensing.** Convx, BitChord, Metrolist, InnerTubeX, NewPipeExtractor and the third-party cipher project are all **GPL-3.0**. None can be linked while D-13 is open, and InnerTubeX/NewPipeExtractor are excluded regardless (ADR-014). Studying the ideas creates no obligation.
10. **Policy.** The current YouTube API Services Developer Policies (last updated 2026-09-14) prohibit:
    - separating audio from video (III.I.7);
    - background players (III.I.9);
    - caching or storing audiovisual content (III.E.1.a);
    - scraping (III.E.6);
    - undocumented APIs (III.D.7);
    - circumventing geographic restrictions (III.I.13).

    Google Play disallows apps that use a service in violation of its terms **[YT][YT/Play]**. Leaving the Play Store changes **Play** constraints only; it doesn't change YouTube's terms, copyright law, or GPL obligations.
11. **Mainstream-catalogue need, option by option** (decision tree in §24):
    - **Y1:** official-API search exists but is capped at 100 `search.list` calls/day per project by default **[YT]**. InnerTube search is unofficial.
    - **Y2:** the official embed is the only permitted playback target, with heavy UX constraints.
    - **Y3:** not available to Podium.

---

## 1. Current Podium Architecture

### 1.1 Files inspected (Podium)

| Area | Files |
|---|---|
| Source contracts | [MusicSource.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/MusicSource.kt), [Facets.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/Facets.kt), [Capabilities.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/Capabilities.kt), [SourceDescriptor.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/SourceDescriptor.kt), [Playback.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/Playback.kt) |
| Registry / health | [SourceRegistry.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/SourceRegistry.kt), [SourceHealth.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/SourceHealth.kt) |
| Resolution / matching | [StreamResolver.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/resolve/StreamResolver.kt), [TrackMatcher.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/matching/TrackMatcher.kt), [TrackNormalizer.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/matching/TrackNormalizer.kt), [VersionLexicon.kt](../../sources/api/src/main/kotlin/app/podium/sources/api/matching/VersionLexicon.kt) |
| Model | [Track.kt](../../core/model/src/main/kotlin/app/podium/core/model/Track.kt), [Ids.kt](../../core/model/src/main/kotlin/app/podium/core/model/Ids.kt), [Quality.kt](../../core/model/src/main/kotlin/app/podium/core/model/Quality.kt), `Version.kt` |
| Player | [PlaybackRouter.kt](../../player/api/src/main/kotlin/app/podium/player/api/PlaybackRouter.kt), [QueueItemResolver.kt](../../player/api/src/main/kotlin/app/podium/player/api/QueueItemResolver.kt), [Queue.kt](../../player/api/src/main/kotlin/app/podium/player/api/Queue.kt), `QueueManager.kt`, [Autoplay.kt](../../player/api/src/main/kotlin/app/podium/player/api/Autoplay.kt), [TrackCatalog.kt](../../player/api/src/main/kotlin/app/podium/player/api/TrackCatalog.kt), [PlaybackController.kt](../../player/api/src/main/kotlin/app/podium/player/api/PlaybackController.kt), `PlaybackState.kt` |
| Service | [PodiumPlaybackEngine.kt](../../player/service/src/main/kotlin/app/podium/player/service/PodiumPlaybackEngine.kt), [MediaMappers.kt](../../player/service/src/main/kotlin/app/podium/player/service/MediaMappers.kt), `PlaybackService.kt` |
| Audius | [AudiusMusicSource.kt](../../sources/audius/src/main/kotlin/app/podium/sources/audius/AudiusMusicSource.kt), `AudiusApi.kt`, `AudiusMapper.kt`, `HttpTransport.kt` |
| Online | [OnlineRepository.kt](../../feature/online/src/main/kotlin/app/podium/feature/online/OnlineRepository.kt), [AppOnlineRepository.kt](../../app/src/main/kotlin/app/podium/AppOnlineRepository.kt), `OnlineCommon.kt`, `OnlineDetail.kt`, `OnlineListening.kt`, [AppGraph.kt](../../app/src/main/kotlin/app/podium/AppGraph.kt), `BuildSources.kt` (debug/release) |
| Database | `core/database/…/Entities.kt`, `OnlineEntities.kt`, `OnlineDao.kt` |
| Settings | `feature/settings/…/SettingsScreens.kt`, `DeviceSettingsRepository.kt` |
| Docs | ADR-013, ADR-014, the three `architecture/*` specs, `decision-log.md` (D-13, D-17–D-21, D-34), `queue-and-autoplay.md`, `security.md`, prior research notes |

### 1.2 The ten questions

**Q1. Which interfaces would a YouTube source implement?** **[P]**
`MusicSource` (descriptor + `StateFlow<SourceCapabilities>`) with these facets:
- `CatalogFacet`: `search`, `track`, `album`, `artist`, `playlist`
- `DiscoveryFacet`: shelves, genres
- `RecommendationFacet`: `related`, `artistRadio`, `relatedArtists`
- `ArtworkFacet`
- `PlaybackFacet` — only for a permitted route (Y2: `routes = {EMBEDDED}`). Absent for a catalogue-only Y1.
- Optionally `AuthFacet`

Descriptor: `environment = ONLINE`. `basis = UNOFFICIAL_API` (InnerTube catalogue) or `OFFICIAL_API` (Data API + embed).

**Q2. Which existing interfaces are already sufficient?** **[P]**
- The facet set
- `SourceDescriptor` (with `Attribution`, `termsUrl`, `cheapResolve`, `environment`)
- `Capability` (includes `EMBEDDED_PLAYBACK`) and `CapabilityStatus` (includes `DISABLED`, `REQUIRES_SIGN_IN`)
- `PlaybackTarget.Embedded` with `EmbedConstraints(requiresVisibleSurface, minWidthDp, minHeightDp, foregroundOnly, audioOnlyAllowed)`
- `FacetResolution.Miss(MissReason)` / `Failed(HealthOutcome)`
- `SourceHealthMonitor`
- `StreamResolver` and `TrackMatcher`/`MatchQueryBuilder`
- `Track.routes`, `Track.availability`, `SourceRef.providerData`

**Q3. Which interfaces would need extension?** **[P]** unless marked
- **Online repository layer.** `AppOnlineRepository.online()` returns `registry.ordered().firstOrNull { environment == ONLINE }`. Shelves, search, artist and collection all go to that one source, and `OnlineRepository.source` is a single `OnlineSource?`.
  - Shelf ids are plain strings that aren't qualified by source.
  - `artist(id)` and `collection(id)` route to "the online source", not to `id.sourceId`.
- **Player.**
  - `PodiumPlaybackEngine` plays only `DirectStream`; any other route fails the load with "needs another engine".
  - `PlaybackRouter` and the `PlaybackEngine` interface exist and are tested in `player:api`, but nothing in the service uses them.
  - There's no `EmbeddedEngine`, and no contract for "the surface is visible".
- **Authentication.** `AuthFacet` exposes only `state`. The spec's `begin()/AuthFlow/signOut()` aren't in code, and there's no Keystore credential store yet (per `security.md`).
- **Equivalence persistence.** `EquivalenceStore` is in-memory only; the spec's `track_equivalence` table doesn't exist.
- **Attribution rendering.** `SourceDescriptor.attribution` is set (Audius: "Music from Audius") but no UI component renders it. YouTube's branding rule (III.F.2.a) would make that a requirement.
- **Source settings.** `SourceRegistry.setEnabled` / `setPriority` exist but have no caller outside tests, and there's no persistence or settings row.
- **Build policy by `Basis`.** Not in code. What exists is a per-variant source list (`app/src/debug|release/BuildSources.kt`). That's enough to keep a factory out of release builds, but it isn't the spec's `SourcePolicy`.
- **Mid-track URL refresh.** `QueueItemResolver.refreshPinned` exists, but `PodiumPlaybackEngine.handleError` records health and skips instead of calling it. This matters for any source with expiring URLs.

**Q4. Can one source expose multiple playback targets?** Yes **[P]**.
- `PlaybackFacet.routes` is a `Set<PlaybackRoute>`, and `Track.routes` is per track.
- `resolve()` may return any `PlaybackTarget`.
- `StreamResolver.resolveFrom` accepts a source if any of `DIRECT_STREAM`, `REMOTE_PLAYBACK` or `EMBEDDED_PLAYBACK` is usable.

**Q5. How does source priority work?** **[P]**
- `SourceRegistry.ordered()` returns the user's priority list first, then the rest in registration order.
- `AppGraph` registers local, then Audius, then variant sources. Priority is never set, so the effective order is registration order.
- Two things consume it: `StreamResolver`'s fallback walk, and `AppOnlineRepository`'s choice of the online source.

**Q6. How does exact matching work?** **[P]**
- Rule-based tiers: `EXACT > STRONG > POSSIBLE > NO_MATCH`.
- **Identifier evidence:** equal ISRC or MBID can reach `EXACT`. Conflicting ISRCs or MBIDs cap the result at `POSSIBLE`.
- **Hard rejections:** explicit vs clean; different title identity (unless identifiers are equal); different version-tag sets in either direction; no shared primary artist; different featured artists; Δduration > 10 s.
- **Caps:**
  - unknown duration → `POSSIBLE`;
  - Δ > 2 s → `STRONG`;
  - remaster on one side → `STRONG`;
  - different album → `STRONG`;
  - several distinct `EXACT` candidates → downgraded to `POSSIBLE` with an `Ambiguous` note.
- Duration alone never reaches a tier.
- `StreamResolver` uses only `EXACT` for fallback; `AutoplayEngine` uses `EXACT` to de-duplicate.

**Q7. How do misses differ from failures?** **[P]**
- **A miss proves the source is reachable.** `FacetResolution.Miss` is recorded as `HealthOutcome.MISS`, which resets the failure streak.
- **Failures:**
  - `Failed(NETWORK_FAILURE | SERVER_ERROR | UNKNOWN)` counts toward the breaker: 3 consecutive → `Unreachable`, backing off 30 s → 2 min → 10 min, then a half-open probe.
  - `AUTH_FAILURE` → `AuthRejected`.
  - `RATE_LIMIT` → `RateLimited`.
  - `INVALID_MEDIA` is per item and doesn't affect the breaker.
- **The overall outcome:** `StreamResolver.lastFailure` reports a failure ahead of a miss.

**Q8. How is source identity stored?** **[P]**
- `TrackId = "<sourceId>|<providerKey>"`. The code uses `|` — `CLAUDE.md` says `:`, which is a doc drift.
- `SourceRef(sourceId, providerKey, providerUri, providerData)`.
- The DB `track` row carries `source_id`, `source_track_id`, `provider_uri`, `provider_data`, `isrc`, `mbid`, `explicitness`, `version_json`, `identity_key`, `availability` and `routes`.

**Q9. How do the queue and history identify the originating source?** **[P]**
- `QueueItem` carries `track` (with its `SourceRef`), `preferredSource` and `fallbackPolicy`.
- Once resolved, `Selection(servedBy, path, servedTrack, match)` is pinned to the item.
- `queue_item.track_id` is persisted.
- ONLINE tables record `source_id` (`online_history`, `online_liked_track`, `online_playlist`) and `provider_id`.
- The session publishes `SERVED_BY` and `RESOLUTION_PATH` extras.

**Q10. Could YouTube be added without changing the Online UI?**
- **Catalogue (Y1): mostly yes.**
  - The screens render capability-gated rows and `Availability.Unavailable(reason)` generically. `OnlineCommon.kt` shows the reason, and `OnlineDetail.kt` filters unplayable tracks out of Play/Shuffle **[P]**.
  - The changes sit in the repository layer: multi-source selection and source-qualified shelves.
  - A Y1 track's playability isn't known until a match is attempted, so without an extra annotator rows would look playable and then miss **[I]**.
- **Embedded playback (Y2): no.** The official player has to be *visible*, so a video surface inside the VirtualScreen is a genuine architectural requirement. It touches Now Playing (D-26 governs the shell; the embed isn't glass and must not be overlaid) **[YT][I]**.

### 1.3 Things noticed in passing (not changed)

- **Doc drift:** `CLAUDE.md` gives `TrackId` as `"<sourceId>:<id>"`; the code uses `|` **[P]**.
- **Doc drift:** `SOURCE_CAPABILITY_MATRIX.md` §3 describes Y1 as playing "via an EXACT/STRONG-matched copy", and `MUSIC_SOURCE_ARCHITECTURE.md` §9.1 lists an "Allow close matches" STRONG setting. D-17 (revised) says EXACT only, "no setting", and the code agrees: `FallbackPolicy` has only `EXACT_ONLY` and `NONE` **[P]**.
- **Environment boundary:** `StreamResolver` step 3 walks *all* enabled sources, including LOCAL. An ONLINE track could therefore be served by a local file at EXACT, which is surfaced as `EXACT_FALLBACK` **[P]**. D-34 separates libraries, likes, history and autoplay; it doesn't say whether playback fallback may cross environments. See open question Q-3.

---

## 2. Convx Architecture

### 2.1 Snapshot

| Item | Finding |
|---|---|
| Repository | Created on GitHub 2026-07-23. Git history starts 2026-03-02 ("updated to 5.0.4" — *LIKELY* inherited from its fork origin). 622 commits. 535 stars, 28 forks **[H]** |
| Lineage | README: "started as a fork of vivi-music". vivi-music credits **Metrolist** as its foundation. Convx's `innertube/…/YouTube.kt` header says "Modified from ViMusic". Convx's `PlayerResponse` model differs from Metrolist's current one by 2 normalized lines **[C][H]** |
| License | GPL-3.0, plain text, no exceptions **[C]**. vivi-music's LICENSE adds a proprietary "musixmatch module" exception; GitHub reports it as NOASSERTION **[H]** |
| Releases | v1.0.0 (07-25) … v1.5.2 (08-15), nightly r52 (08-20) **[H]** |
| Activity | `main` last changed 2026-08-20. Only Dependabot branches since (e.g. Media3 1.7.1 → 1.11.1, 10-03, unmerged) **[H]** |
| Shape | Multi-module Gradle (app + 18 modules); 567 Kotlin files in `app`; `innertube` has 104 files / ~8.3k lines **[C]** |
| Stack | Compose, Hilt, Media3 **1.7.1** (exoplayer, session, hls, ui, okhttp datasource; cast in the GMS flavour), Ktor + OkHttp, **NewPipeExtractor v0.25.2** (GPL-3.0), quickjs-kt (Apache-2.0), a vendored copy of Kyant backdrop **[C]** |

### 2.2 Component analysis

Legend for the "Basis" column: **Doc** = official/documented, **Undoc** = undocumented behaviour, **Circ** = exists to get past a protection or access control.

| Component | What it does | Why Convx needs it | Generic / YT-specific | Podium has it? | Podium would need it? | Basis | Auth? | Where | Fragility |
|---|---|---|---|---|---|---|---|---|---|
| `innertube` request layer (`InnerTube.kt`) | Ktor client. Builds InnerTube requests with a client context (identity, locale, visitorData, optional cookie and account id), proxy and IPv4/IPv6 selection, retries **[C]** | All YouTube Music data comes through it | YT-specific | Analogue only (Audius `AudiusApi`) | Y1 via InnerTube: an independent equivalent. Y2 with the Data API: no | Undoc | Optional (cookie) | Device | High — the private API and client contexts change |
| Renderer models + page parsers (`models/*`, `pages/*`) | ~50 models of YouTube Music's UI-layout JSON (shelves, carousels, list items, headers) and parsers turning them into Song/Album/Artist/Playlist items **[C]** | InnerTube returns UI layout, not a music schema | YT-specific | No (Audius returns a data schema) | Y1: yes | Undoc | No | Device | High — commits like "fix(parser): resolve empty explore/search album and playlist pages", "fixed search section not loading" **[H]** |
| `YouTube` facade (`YouTube.kt`, 1,776 lines) | search (+ suggestions, summary), album, artist (+ items), playlist (+ continuations), home, explore, charts, moods & genres, new releases, library, history, next/radio, related, lyrics tab, queue, transcript, account, like/subscribe/playlist editing, comments **[C]** | Product surface | Mixed: the *capabilities* are generic, the implementation is YT-specific | Facets (Catalog/Discovery/Recommendation) | Y1: the read-only subset maps onto existing facets | Undoc | Library/editing need sign-in | Device | High |
| Stream orchestrator (`YTPlayerUtils.kt`, 1,469 lines) | Turns a video id into a playable URL plus metadata. Runs optional "intercepts" first (JioSaavn, 8spine modules, TIDAL proxies), then the YouTube path. Picks an audio format and validates the URL **[C]** | Stream access isn't offered officially | YT-specific (intercepts are generic in shape) | `PlaybackFacet` + `StreamResolver` (generic parts) | The YouTube path: **no** (Y3) | Undoc + **Circ** | Optional | Device | Very high (§17) |
| Client-identity catalogue (`YouTubeClient.kt`) | About a dozen official-client identities (web music, web, creator, TV, embedded-TV, iOS family, Android family incl. VR) with flags: login support, needs signature timestamp, embedded, needs web PoToken **[C]** | Some identities get streams others are refused | YT-specific | No | No (ADR-013: "client-identity rotation to obtain streams") | Undoc / **Circ** when used for streams | — | Device | Very high — r52 notes reorder the chain after live probing **[H]** |
| `BotDetectionMitigator` | On bot-check failures, rotates the guest `visitorData` identity (keeps locale) **[C]** | YouTube flags sessions | YT-specific | No | No | **Circ** | — | Device | High |
| `YouTubeClientProbe` | Debug-screen diagnostic: asks each client identity whether it can still stream **[C]** | "Which clients still work today" changes | YT-specific | No | No | **Circ**-adjacent diagnostic | — | Device | — |
| PoToken (`utils/potoken/*`, `assets/po_token.html`) | Runs Google's BotGuard in a hidden WebView to mint Proof-of-Origin tokens for web clients **[C]** | Some clients need it | YT-specific | No | **No** (ADR-013) | **Circ** | — | Device + WebView | Very high |
| Cipher (`utils/cipher/*`, `utils/sabr/*`, `assets/solver`) | Fetches YouTube's player JS and executes signature / "n" transforms (WebView-based solver; an AST-based solver modelled on yt-dlp's approach; NewPipe fallback) **[C]** | Protected and throttled URLs | YT-specific | No | **No** (ADR-013) | **Circ** | — | Device + WebView | Very high |
| NewPipe fallback (`pages/NewPipe.kt`) | Uses NewPipeExtractor for signature timestamp, deobfuscation and stream URLs as a fallback **[C]** | Extra resilience | YT-specific | No | **No** (GPL; ADR-014) | **Circ** | — | Device | High (pinned 5 releases behind) |
| Stream validation + URL cache | Checks a URL responds before use; in-memory URL cache keyed by media id with expiry (6 h default when the response omits it) **[C]** | URLs expire or die | Generic idea | `PlayableMedia.expiresAtMillis`, pinning, in-flight dedupe **[P]** | Already covered | — | — | Device | Medium |
| `MusicService` (4,335 lines) | `MediaLibraryService`. ExoPlayer with `ResolvingDataSource` over `CacheDataSource` (a player `SimpleCache` and a download `SimpleCache`); cache namespaces separate lossless and standard bytes; error recovery **[C]** | Background playback, caching | Generic infrastructure | `PlaybackService` + `PodiumPlaybackEngine` with a `ResolvingDataSource` **[P]**. No stream cache **[P]** | No for YouTube. A *stream cache* would conflict with III.E.1.a for YouTube content | Doc (Media3) | — | Device | Low |
| Downloads (`DownloadUtil.kt`, `ExoDownloadService.kt`) | Media3 `DownloadManager` into the download cache, keyed by song id; auto-download on like; downloads always take the YouTube rendition **[C]** | Offline | Generic infrastructure, YT content | ADR-005 design (own DownloadManager) | **No** for YouTube (III.E.1.a; Premium feature **[YT]**) | **Circ** of a Premium feature **[I]** | — | Device | High (v1.5.2: "downloaded stream links expiring incorrectly") **[H]** |
| Queues (`playback/queues/*`) | `YouTubeQueue` pages the InnerTube *next* endpoint (watch queue / radio) with continuations; album radio; playlist queue **[C]** | Radio / autoplay | Generic concept, YT source | `RecommendationFacet`, `AutoplayEngine`, `QueueOrigin.RADIO` **[P]** | Y1: map *next/related* onto `RecommendationFacet` | Undoc | Optional | Device | Medium |
| Playback reporting (`registerPlayback`, playback-tracking URLs) | Reports plays back to the user's YouTube history **[C]** | Keeps remote history | YT-specific | No | No (writes to YouTube with impersonated clients) | Undoc | Yes | Device | Medium |
| Login (`LoginScreen.kt`) + session | WebView Google login. Stores the cookie, `visitorData` and `dataSyncId` in **DataStore preferences**; no encrypted preferences found in `app` **[C]** | Library, likes, history, playlists | YT-specific | `AuthFacet` (state only) **[P]** | Y1: optional and risky; Data API OAuth would be the official route | Undoc session capture | Yes | Device | Medium–high (v1.5.2: "Fixed the HTTP 500 on login…") **[H]** |
| Intercepts: JioSaavn (`jiosaavn/`), TIDAL (`utils/tidal/TidalService.kt`), 8spine (`spine/`) | Before YouTube: an unofficial JioSaavn API; TIDAL FLAC via **public third-party "hifi-api" proxies**; QuickJS JavaScript modules from community module indexes (some advertise Qobuz/TIDAL lossless) **[C]** | "Lossless/high-quality audio" | Generic *shape* (alternate stream sources); the content provenance is the problem | `MusicSource` per provider + EXACT fallback **[P]** | **No** (ADR-013/BitChord review §11: no executing third-party JS, no relays of paid services) | Undoc / relay of a paid service | — | Device + external proxies | High — #36 "Jio Saavn broken and 8spine doesn't load all modules" **[H]** |
| Title-match guards in intercepts | Substring title match plus a duration sanity check, or an exact title when duration is unknown **[C]** | Avoid wrong songs | Generic | `TrackMatcher` (stricter, rule-based) **[P]** | Already covered | — | — | Device | Wrong-song risk |
| Local-only mode | A preference that hides streaming screens **[C]** | Users without YouTube | Generic | LOCAL/ONLINE environments (D-34) **[P]** | Already covered | — | — | Device | — |
| Built-in updater | Checks GitHub Releases and installs APKs in-app (FOSS/GMS flavours) **[C]** | Off-store distribution | Generic | No | Optional (distribution question, §16 of the brief) | — | — | Device + GitHub | Low |

**Observation [C]:** the README promises "Lossless/high-quality audio". In code, lossless comes from the TIDAL proxies and modules, never from YouTube. YouTube offers lossy renditions only, as BitChord's own `YouTubeSource` comment states **[B]**.

---

## 3. BitChord Architecture

### 3.1 Snapshot

| Item | Finding |
|---|---|
| Repository | Created 2026-08-11. 533 commits on `main`. 2,311 stars, 209 forks, 219 open issues + PRs **[H]** |
| Releases | v1.0 (08-11) → v1.7 (09-25): 11 releases in 45 days **[H]** |
| License | GPL-3.0 **[B]** |
| Shape | Single `app` module (~400 Kotlin files), Go listen-together backend, native analyzer (see the prior review). The unmerged `v1.7.1` branch moves `MusicSource`/`TrackMatcher` toward a shared multiplatform module, adds a desktop that "plays YouTube … through the phone's resolver" and mints BotGuard PoTokens on the desktop, plus Cast, DSD and addon download/lossless restrictions **[H]** |
| Stack (YouTube-relevant) | Media3 **1.11.0** (exoplayer, session, common, okhttp; **hls** for Apple motion artwork; **dash** because addon/module backends return manifests), **InnerTubeX v0.7.0** (MetrolistGroup, GPL-3.0), a stripped build of **NewPipeExtractor** + nanojson, quickjs-kt 1.0.14, Ktor **[B]** |
| Docs site | `bitchord.kushagrasingh.in/docs` documents only the **addon protocol**: three JSON routes over HTTPS; "BitChord never downloads or executes addon code"; quality "verifie[d] using the codec it actually decodes"; an `allowDownloads` opt-out **[B]** |

### 3.2 Component analysis

| Component | What it does | Generic / YT-specific | Podium equivalent | Podium would need it? | Basis | Fragility |
|---|---|---|---|---|---|---|
| `MusicSource` (`data/sources/MusicSource.kt`) | Deliberately small: `health()`, `search()`, `stream()`. `stream()` **returns null for a miss and throws for a failure**. `StreamFormat` has all fields nullable ("null means not stated"); lossless is decided by codec. `StreamRequest` = Lossless / Capped(kbps) / Best, computed per request **[B]** | Generic | Facet model, `FacetResolution`, `QualityRequest`, `QualityReport` **[P]** | Already covered (Podium's contract is wider) | — | Low |
| `SourceKind`, `SourceRegistry` | Fixed kinds (addon, legacy module, JioSaavn, YouTube) with ranks; addons first, **YouTube last and undeletable**; configs in encrypted prefs; per-connection quality ceiling **[B]** | Generic idea, YT-spine product | `SourceRegistry`, priority, `Basis` **[P]** | Already covered (no "spine" — ADR-013) | — | Low |
| `YouTubeSource` | Thin adapter. **Health is always OK** (no probe; failures surface at resolve time). Tracks keep **bare video ids** everywhere because "half the app" depends on them. Lossless requests "cannot" be honoured — YouTube has no lossless rendition **[B]** | YT-specific | n/a | Y1 adapter (but with source-qualified ids) | Undoc | — |
| `Innertube` (own client, 1,236 lines) + `InnertubeParser` (1,475 lines) | Minimal client using the **web-music identity against music.youtube.com** for browse, search, next, library and account; untyped JSON parsing; SAPISID-hash signing from the stored cookie; visitorData; account/brand-channel scope **[B]** | YT-specific | Analogue: Audius client | Y1 (independent) | Undoc | High — "Fix paged YouTube Music result feeds" **[H]** |
| `StreamResolver` (1,097 lines) | Video id → a URL "proven to serve bytes": **InnerTubeX first**, **NewPipe extractor as failsafe** (scrapes the watch page), then probes the URL before it's handed to the player or cached. Remembers a "permanently unplayable" verdict (age gate no session passes, takedown, region block) separately from retryable failures. Short-lived URL cache. Feeds playback refusals back. Separate download resolution with container constraints **[B]** | YT-specific (the miss/permanent/retryable *taxonomy* is generic) | `ResolveOutcome`, `HealthOutcome` (MISS vs failure), pinning **[P]** | The taxonomy: partly (see §7.3). The resolver: **no** | Undoc + **Circ** | Very high |
| `InnerTubeXResolver` | Delegates to InnerTubeX's "live-benchmarked client catalog and cipher tiers", with PoToken providers. **At runtime it loads a remote player/cipher configuration file hosted in a third-party GitHub repository (ZemerTeam, GPL-3.0)**, with a cooldown when a ciphered URL is refused **[B]** | YT-specific | n/a | **No** | **Circ** | Very high + external dependency |
| `PlayerClient` | Rebuilds the headers a media fetch must carry to match the client identity that minted the URL **[B]** | YT-specific | `PlayableMedia.headers` can *carry* headers **[P]** | No | **Circ** (identity impersonation for media fetches) | High |
| `potoken/*` | Hidden-WebView BotGuard PoToken minting; the helper file is identical to Metrolist's and Convx's **[B][C]** | YT-specific | n/a | **No** | **Circ** | Very high |
| `PlaybackTracker` | Pings playback, watch-time and attestation URLs so plays appear in YouTube history **[B]** | YT-specific | n/a | No | Undoc | Medium |
| `SourceResolver` (1,158 lines) | For a YouTube-catalogued track: builds packaging-free queries; **asks every ranked source in parallel ("race")** while YouTube's own walk also runs; picks the best answer that satisfies the request; prefetch; download selection; mid-track "upgrade" **[B]** | Generic shape | `StreamResolver` (sequential, EXACT-only, no mid-track switch — D-17/D-18) **[P]** | Already covered by a deliberately different design | — | "Wrong song" reports **[H]** |
| `TrackMatcher` (672 lines) | Identity / version / packaging parsing, artist credits, duration windows, album/explicit tie-breaks, collision → miss; CJK work on `v1.7.1` **[B][H]** | Generic | `TrackMatcher` (independent, rule tiers + ISRC/MBID) **[P]** | Already covered | — | — |
| `PlaybackFallback`, `StreamChoice`, `QualityUpgrade`, `AudioCache` | On a player error, only YouTube is asked next; pins the chosen stream per item; swaps streams mid-track when a better copy of the same duration turns up; audio cache **[B]** (prior review) | Generic | Pinning **[P]**. No mid-track switching (D-18). No stream cache | Pinning: already covered | — | — |
| `AddonSource` + docs | User-added HTTP JSON sources (manifest/search/stream); user-ordered priority **[B]** | Generic | Possible P2 "personal server bridge" (ADR-013 §Future) | Optional, not for YouTube | Doc (own protocol) | Provenance risk |
| `ModuleSource` (legacy) | QuickJS JavaScript modules from a module index **[B]** | Generic shape | n/a | **No** (no third-party code execution) | — | — |
| `JioSaavnSource` | Unofficial JioSaavn API; opt-in because matching "can select the wrong song" **[B]**; open bug #412 "may play an entirely different song" **[H]** | Provider-specific | n/a | No | Undoc | High |
| Auth (`YtMusicLoginScreen`, `WebSession`, `AuthStore`, `EncryptedPrefs`) | WebView Google login, cookie + `ytcfg` capture, **encrypted** storage, multiple accounts and brand channels **[B]** (prior review) | YT-specific | `AuthFacet` state only **[P]** | Y1 optional | Undoc session capture | Medium |
| Downloads (`download/*`) | YouTube downloads (Opus-in-WebM private, AAC-in-MP4 for export) with tags **[B]** | Generic infra, YT content | ADR-005 design | **No** for YouTube | **Circ** of a Premium feature **[I]** | High (#453 "Some songs aren't downloading in v1.7") **[H]** |

---

## 4. Convx vs BitChord Comparison

| Area | Convx | BitChord | Podium |
|---|---|---|---|
| YouTube/YouTube Music access | Own `innertube` module (Metrolist lineage), typed models **[C]** | Own minimal `Innertube` object (untyped JSON) for metadata; InnerTubeX + NewPipe for streams **[B]** | None. Audius via official API **[P]** |
| Search | InnerTube search with filters, continuations, suggestions, summary page **[C]** | InnerTube search (web-music identity), continuations, typeahead **[B]** | `CatalogFacet.search(SearchQuery(text, limit, offset, kinds))`; ONLINE uses the first ONLINE source **[P]** |
| Track identification | Video id; `SongItem` with artists, album ref, duration (s), **music video type**, explicit flag, set-video id **[C]** | Bare video ids app-wide; ATV/OMV/UGC/private video types; explicit badges **[B]** | `TrackId = sourceId|key`; ISRC/MBID/explicitness/version **[P]** |
| Artist identification | Channel / browse id **[C]** | Channel / browse id **[B]** | `ArtistId` source-qualified **[P]** |
| Album identification | Browse id + playlist id **[C]** | Browse id **[B]** | `AlbumId` source-qualified; `AlbumRef` **[P]** |
| Playlist identification | Playlist id; "VL"-prefixed browse ids **[C]** | Playlist id **[B]** | `PlaylistId` source-qualified **[P]** |
| Stream resolution | Player request under rotating client identities; intercepts first; URL validation **[C]** | InnerTubeX first → NewPipe failsafe → byte probe **[B]** | `StreamResolver`: pinned → owned copy → own source → EXACT fallback **[P]** |
| Playback target | Progressive HTTPS URL of an audio-only adaptive format **[C]** | Same; plus DASH/HLS manifests from addons/modules (non-YouTube) **[B]** | `DirectStream` (implemented), `Embedded` / `RemoteProvider` (types only) **[P]** |
| Media3 integration | `MediaLibraryService`; `ResolvingDataSource` over `CacheDataSource`; Media3 1.7.1 **[C]** | Resolving/chunked data source; Media3 1.11.0 **[B]** | `MediaLibraryService`; `ResolvingDataSource` keyed by `QueueUid`; Media3 1.11.1 **[P]** |
| Background playback | Yes (YouTube audio in a background service) **[C]** | Yes **[B]** | Yes for DirectStream sources **[P]** |
| Authentication | WebView login; cookie/visitorData/dataSyncId in DataStore (plain) **[C]** | WebView login; cookie/`ytcfg` in encrypted prefs; multi-account **[B]** | `AuthFacet.state` only; no credential store yet **[P]** |
| InnerTube | Yes (module) **[C]** | Yes (own client + InnerTubeX) **[B]** | No |
| PoToken/WebView | Hidden-WebView BotGuard minting **[C]** | Same (identical helper file) **[B]** | No; excluded (ADR-013) |
| Signature/cipher handling | WebView/AST solvers + NewPipe fallback **[C]** | InnerTubeX cipher tiers (remote third-party config) + NewPipe (Rhino) **[B]** | No; excluded (ADR-013) |
| Quality selection | Picks an audio format by preference/connection; "lossless" via TIDAL proxies/modules **[C]** | Per-connection ceiling; nullable `StreamFormat`; lossless only from addons/modules **[B]** | `QualityRequest` per request; `QualityReport` claimed vs measured; label from measured only **[P]** |
| Downloads | Media3 `DownloadManager` → cache; auto-download on like **[C]** | Own downloader; WebM/MP4 by destination **[B]** | Designed (ADR-005); YouTube downloads excluded |
| Caching | Player + download `SimpleCache`; URL cache **[C]** | Audio cache; short URL cache; permanent-unplayable memory **[B]** | No stream cache; selection pinning; in-flight dedupe **[P]** |
| Radio | InnerTube *next* (watch/radio) **[C]** | InnerTube *next* **[B]** | `RecommendationFacet.related/artistRadio`; `QueueOrigin.RADIO` **[P]** |
| Recommendations | Home/related/charts/moods via InnerTube **[C]** | Home/explore/related via InnerTube **[B]** | `DiscoveryFacet` + `RecommendationFacet` **[P]** |
| Autoplay | YouTube queue continuation **[C]** | YouTube up-next **[B]** | `AutoplayEngine`: same source only, EXACT de-dupe, avoid repeats **[P]** |
| Source fallback | Intercepts fall through to YouTube "on ANY failure" **[C]** | Race of ranked sources; on a player error only YouTube is asked **[B]** | EXACT-only, before playback, surfaced; never mid-track (D-17/18) **[P]** |
| Source health | `BotDetectionMitigator`; client probe (debug) **[C]** | `Ok/Unreachable/Rejected`; YouTube always "Ok" **[B]** | Breaker with miss ≠ failure, rate limit, auth rejected **[P]** |
| Addons/modules | 8spine QuickJS modules **[C]** | HTTP addons (current), QuickJS modules (legacy) **[B]** | None (P2 idea only for user-owned servers) |
| Licensing | GPL-3.0 **[C]** | GPL-3.0 **[B]** | Undecided (D-13); no GPL code or deps |
| Maintenance burden | 35 commits to YouTube stream code Mar–Aug 2026; playback-failure cluster in Aug; quiet since 08-20 **[H]** | 58 commits to `data/innertube` Aug–Sep 2026; resolver swapped to InnerTubeX at v1.7 **[H]** | Audius adapter: small, official API **[P]** |

---

## 5. Common YouTube Music Architecture

### 5.1 The pipeline both projects implement (derived from the code)

```
Session          guest visitorData (always)  ── optional signed-in cookie session (WebView login)
   │
Catalogue        InnerTube, web-music client identity
   │             search · browse (home/explore/charts/moods/album/artist/playlist/library) · next (up-next/radio/related/lyrics tabs)
   │             → UI-layout JSON parsed into items
   ▼
Track identity   video id (+ album browse id, artist channel id, playlist id)
   │             + music-video type (audio track / official video / user upload / private upload) + explicit badge + duration (s)
   ▼
Player info      per-video player request under one of several official-client identities
   │             → playability status · streaming data (audio-only adaptive formats, expiry) · loudness · playback-tracking URLs
   ▼
Stream unlock    [Circumvention layer — named only]
   │             client-identity walk · PoToken (BotGuard in WebView) · signature + "n" transforms from YouTube's player JS
   │             · NewPipeExtractor as fallback · header binding to the minting identity
   ▼
Stream choice    one audio-only format by codec/bitrate/connection; URL validated before use; expiry tracked
   ▼
Playback         Media3 progressive source via ResolvingDataSource (+ cache) in a MediaLibraryService (background)
   ▼
Afterwards       up-next/radio continuation for autoplay · plays reported to YouTube history · optional downloads
```
Every stage is **VERIFIED in both [C][B]**. In BitChord, the player request itself happens inside InnerTubeX, which wasn't inspected beyond its API surface. That InnerTubeX calls the same player endpoint is *LIKELY* (its types: `InnerTubeExtractor`, `YtConfigParser`, `TokenProvider`).

### 5.2 Common
- The **unofficial InnerTube API** for all catalogue data, using the web-music identity **[C][B]**.
- **Video ids as track identity** and the music-video type / explicit badge as metadata **[C][B]**.
- A **per-video player request** whose audio-only adaptive formats are the playable media **[C][B]**.
- The same **stream-unlock family**: client-identity walk, visitorData sessions, hidden-WebView BotGuard PoTokens, player-JS transforms, NewPipeExtractor **[C][B]**.
- **Cookie-capture login** in an in-app WebView **[C][B]**.
- **Expiry-aware URL caching** and **validation before playback** **[C][B]**.
- **Playback reporting** to YouTube history **[C][B]**.
- **Media3 progressive playback** in a background `MediaLibraryService` **[C][B]**.
- Non-YouTube "alternate copy" sources ahead of YouTube: an unofficial JioSaavn API, QuickJS JavaScript modules, and "lossless" via third-party relays of TIDAL **[C][B]**.
- Title + duration guards against wrong-song substitution, which neither fully prevents: open reports in both **[C][B][H]**.

### 5.3 Convx-specific
- A typed, Metrolist-lineage `innertube` module with ~50 renderer models **[C]**.
- Explicit **guest-identity rotation** on bot detection (`BotDetectionMitigator`) and an in-app **client probe** diagnostic **[C]**.
- WebView- and AST-based cipher solvers inside the app (no remote config) **[C]**.
- **TIDAL via public "hifi-api" proxy instances** as a built-in intercept **[C]**.
- Media3 `DownloadManager` downloads with **auto-download on like** **[C]**.
- Session stored **unencrypted** in DataStore **[C]**.
- A built-in APK updater **[C]**.

### 5.4 BitChord-specific
- Stream resolution delegated to **InnerTubeX**, which pulls a **remote, third-party cipher configuration** at runtime **[B]**.
- A **byte-level probe** of each URL before playback, plus feedback of in-playback refusals **[B]**.
- An explicit **permanent vs retryable** verdict for unplayable videos **[B]**.
- A **provider-neutral source contract** (miss = null, failure = throw; Unreachable ≠ Rejected) with YouTube as the undeletable "spine" **[B]**.
- **Parallel "race"** across sources plus **mid-track quality upgrade** **[B]**.
- An HTTP **addon protocol** with a public docs site **[B]**.
- **Encrypted** session storage and multi-account **[B]**.

### 5.5 Podium already has (no reinvention needed)
- **Source model:** source abstraction with facets and capability states; `SourceRegistry` with enable/priority APIs; `Basis` and `environment`.
- **Resolution:** a miss/failure taxonomy with a circuit breaker and rate-limit / auth-rejected states; `StreamResolver` with pinning, in-flight dedupe and EXACT-only fallback with an attempt log.
- **Identity:** a rule-based `TrackMatcher` with ISRC/MBID evidence, explicit/clean and version vetoes, a music-video-audio tag, and ambiguity downgrade.
- **Playback:** Media3 `MediaLibraryService` with a resolving data source and claimed-vs-measured quality reporting.
- **Online experience:** autoplay/radio engine bound to one source; ONLINE likes, playlists and history tables; capability-gated, availability-aware Online screens.
- **Types not yet backed by engines:** `PlaybackTarget.Embedded` and `EmbedConstraints`, and the `PlaybackRouter` with hard-cut handoffs.

All **[P]**.

### 5.6 Podium missing (for a permitted YouTube source)
- **Metadata client.** A YouTube metadata client and mapper: either InnerTube (unofficial, Y1) or the Data API (official, quota-limited).
- **Online repository.** Multi-source selection or aggregation, and source-qualified shelves.
- **Availability-by-equivalence annotation** for catalogue-only tracks (Y1).
- **An `EmbeddedEngine`** plus a visible embed surface, router wiring, and media-session policy for embedded items (Y2).
- **Attribution rendering**, settings rows for online sources, persisted priority/enablement, persisted equivalence decisions, and `Basis`-driven build policy.
- **(If sign-in is wanted)** an auth flow and an encrypted credential store.

---

## 6. Convx InnerTube Architecture

High level only. No request bodies, client parameters, or keys.

| Aspect | Finding |
|---|---|
| Request structure | InnerTube calls are HTTP POSTs to YouTube endpoints (`search`, `browse`, `next`, `player`, `music/get_queue`, `music/get_search_suggestions`, `account/account_menu`, `feedback`, playlist-edit paths), each carrying a JSON **client context**: client identity name/version/platform, locale (`hl`/`gl`), `visitorData`, and, when signed in, an on-behalf-of-user account id. Optional cookie-derived auth header, proxy, IPv4/IPv6 preference; JSON via kotlinx.serialization with Brotli support; retries with backoff **[C]** |
| Surfaces used | `search`, `music/get_search_suggestions`, `browse` (home, explore, charts, moods & genres, new releases, album, artist, artist items, playlist, library tabs, history, lyrics, related), `next` (watch queue, radio, related/lyrics tab endpoints), `music/get_queue`, `player`, `get_transcript`, account menu / switcher, feedback, like/unlike, subscribe, playlist create/edit/delete/thumbnail; a Return-YouTube-Dislike lookup **[C]** |
| Search | Typed filters (songs, videos, albums, artists, playlists, community playlists); continuations; a "summary" page that groups top results **[C]** |
| Browse | `browse(browseId, params)` with section-list and grid parsing into typed pages (`AlbumPage`, `ArtistPage`, `PlaylistPage`, `HomePage`, `ChartsPage`, …) **[C]** |
| Player information | `player(videoId, playlistId, client, signatureTimestamp?, poToken?)` → `PlayerResponse` with playability status/reason, player config (loudness), **streaming data** (formats + adaptive formats + `expiresInSeconds`), video details (id, title, author, channel, length, **music video type**, view count, thumbnails) and playback-tracking URLs **[C]** |
| Track metadata | `YTItem` sealed hierarchy. `SongItem` = id, title, artists (name + optional id), album (name + id), duration (s), music video type, chart position/change, thumbnail, explicit, watch endpoint, set-video id, library/history tokens **[C]** |
| Playback information | Each `Format`: itag, url **or** an enciphered form, MIME type (codec in the `codecs=` parameter), bitrate/average bitrate, content length, audio quality, sample rate, channels, loudness, approx duration, and an audio-track descriptor (original vs dubbed) **[C]** |
| Client identity | A catalogue of about a dozen official-client identities; a per-identity login capability, signature-timestamp need, embedded flag and web-PoToken need **[C]** |
| Authentication / session | WebView login → cookie string; account id (`dataSyncId`) for brand channels; "use login for browse" toggle; stored in DataStore preferences **[C]** |
| Visitor data | Fetched and stored; refreshed and rotated on bot detection **[C]** |
| Tokens | PoTokens for web identities, minted in a WebView (§2.2) **[C]** |
| Stream formats | Audio-only adaptive formats (Opus/WebM and AAC/MP4 families). The app picks one; the chosen URL is a progressive resource fetched with range requests **[C]**. Typical bitrates aren't asserted here **[?]** |

---

## 7. BitChord YouTube Architecture

### 7.1 Pipeline

1. **Registration.** `SourceRegistry` seeds a built-in YouTube config that is always enabled and can't be deleted; JioSaavn is opt-in; user addons are ranked above. YouTube tracks keep bare video ids **[B]**.
2. **Search and metadata.** The `Innertube` object (web-music identity) answers search, browse and next. `InnertubeParser` turns layout JSON into songs, albums, artists and playlists, with music-video type and explicit badges **[B]**.
3. **Track matching.** Applies only when another source might serve a YouTube-catalogued track: `TrackMatcher` scores candidates from packaging-free queries. Collisions become a conservative miss **[B]**.
4. **Player resolution.** `StreamResolver.resolve(videoId)` goes InnerTubeX first, then NewPipe as failsafe **[B]**.
5. **Stream choice and quality selection.** The highest format under the connection's ceiling, else the cheapest available. Downloads resolve separately under the download setting and container constraints **[B]**.
6. **Validation.** The URL is probed for real bytes before playback or caching. Refusals during playback are fed back so a dead URL isn't reused **[B]**.
7. **Source health.** `YouTubeSource.health()` is always OK; YouTube failures appear as resolve failures with reasons **[B]**.
8. **Fallback.** Other sources race YouTube's walk. On a player error for a source-served item, only YouTube is asked next **[B]**.
9. **Caching.** A short-lived URL cache; the "permanently unplayable" memory; an audio cache **[B]**.
10. **Queue interaction.** MediaItems carry custom URIs. Resolution happens at open time on the loader thread. The stream choice is pinned per item. Mid-track upgrade swaps to a better copy **[B]**.

### 7.2 Miss vs failure, and metadata vs playable media

| Concept | BitChord | Podium |
|---|---|---|
| **Miss** ("doesn't have it") | `stream()` returns null; resolver moves on "without logging a failure" **[B]** | `FacetResolution.Miss(reason)` → `HealthOutcome.MISS`, which **clears** the failure streak **[P]** |
| **Failure** ("tried and broke") | `stream()` throws; caught per source **[B]** | `FacetResolution.Failed(outcome)` → counts toward the breaker (infrastructure outcomes only) **[P]** |
| **Unreachable vs Rejected** | Distinct health states **[B]** | `Unreachable` (breaker) vs `AuthRejected` **[P]** |
| **Permanent vs retryable** | `PermanentlyUnplayableException`: age gate, takedown, region block — never retried; other failures retried **[B]** | Partly: `MissReason.NOT_PERMITTED/NOT_STREAMABLE` are misses (never retried by health). There's no "remember this verdict for N hours" store **[P]**; the pin covers only one queue item |
| **Metadata vs playable media** | YouTube search rows are **metadata** — a row isn't proven playable until the resolver returns a probed URL **[B]** | `Track.availability` (`Playable` / `Unknown` / `Unavailable` / `RegionBlocked` / `RequiresSubscription`) is metadata; only a `Resolved` outcome means playable **[P]** |

**Implication for Podium's resolver [I]:**
- A catalogue-only source (Y1) should report `Availability.Unknown` or `Unavailable("Not available from your sources")`, never `Playable`.
- Its `PlaybackFacet` should be **absent**, so `StreamResolver` records `SKIPPED/UNSUPPORTED_ROUTE` and moves on to EXACT fallback.
- Its region / age / removed outcomes should be **misses** (`NOT_PERMITTED`), not failures, so health isn't damaged.
- A per-track "known unplayable until T" memory would reduce repeated work. It's optional.

---

## 8. Playback Target Comparison

| Question | Convx | BitChord |
|---|---|---|
| Direct media URL? | **Yes** — a progressive HTTPS URL of one audio-only adaptive format **[C]** | **Yes** — same, "a directly streamable URL that has been proven to serve bytes" **[B]** |
| HLS? | Media3 HLS module present; not the YouTube audio path (the resolver returns single URLs) **[C]/[I]** | HLS only for Apple motion artwork **[B]** |
| DASH? | Not for YouTube **[C]** | DASH module for addon/module manifests (e.g. TIDAL), not YouTube **[B]** |
| Other representation | Header binding to the minting client; expiry **[C]** | Header binding (`PlayerClient`); expiry; probed **[B]** |
| Embedded player? | No **[C]** | No **[B]** |
| Remote playback? | Cast in the GMS flavour, for its own streams, not a YouTube remote **[C]** | Cast on the `v1.7.1` branch (own streams) **[H]** |

**Can Podium's `DirectStream` represent the result?** **"Existing Podium contract is sufficient."**
- `PlayableMedia` has `uri`, `headers` (applied via `withAdditionalHeaders`), `mimeType`, `claimedQuality`, `durationMs`, `expiresAtMillis` and a stable `cacheKey`. `Selection.isUsableAt` already rejects an expired pin **[P]**.
- Two qualifications:
  1. **Producing** that target requires the Y3 stream-unlock layer, which Podium won't build (ADR-013; CLAUDE.md non-negotiables).
  2. For any expiring-URL source, the engine should call the existing `refreshPinned` on a 401/403/410 mid-play, instead of skipping (gap noted in §1.2) **[P]**.

**Embedded (Y2):** **"Podium requires X extension"**, where X is:
- an `EmbeddedEngine` implementing `PlaybackEngine` over the official IFrame player in an Android WebView;
- a visible host surface owned by the UI;
- `PlaybackRouter` wiring in the service/controller path;
- a media-session policy for embedded items (§18).

The target **types** are sufficient **[P]**.

**RemoteProvider:** no official API lets a third-party app command YouTube or YouTube Music playback, so this route isn't applicable. The matrix already marks it UNAVAILABLE **[P]**. That no such API exists in 2026 is **[?]** beyond the docs consulted.

---

## 9. Track Identity and Matching

### 9.1 What YouTube can tell Podium

| Signal | InnerTube (as parsed by both projects) | Data API v3 |
|---|---|---|
| Stable id | Video id; album browse id; artist channel id; playlist id **[C][B]** | Video id; channel id; playlist id **[YT]** |
| Title / artists | Title; structured artist runs with ids **[C][B]** | Title; channel title only **[YT]** |
| Album | Album name + id on song rows **[C][B]** | **None** **[YT]** |
| Duration | Seconds **[C][B]** | ISO-8601 duration **[YT]** |
| Kind of media | **Music video type**: audio track (art track), official music video, user upload, private upload **[C][B]** | No equivalent field (category, tags, `licensedContent`) **[YT]** |
| Explicit | Explicit badge (absence ≠ clean) **[C][B]** | No explicit flag; `contentRating` / age rating only **[YT]** |
| ISRC / label | **Not parsed by either project** (searched: no ISRC in either InnerTube layer) **[C][B]** | **None** **[YT]** |
| Availability | Playability status (OK / unplayable / login required / age / region) — at **player** time, not search time **[C][B]** | `regionRestriction`, `embeddable`, `privacyStatus` **[YT]** |

### 9.2 Strict identity model (provider-neutral)

Podium's existing `Track` already holds the fields that matter: `title`, `artists` (roles), `album`, `durationMs`, `identifiers` (ISRC/MBID), `explicitness`, `version` (tags/variants), `source`, `routes`, `availability` **[P]**.

A YouTube adapter would map:
- **Version.** The music-video type maps to existing `VersionTag`s:
  - official video → `MUSIC_VIDEO_AUDIO`;
  - audio track → none;
  - user upload → no tag, but carries "unverified uploader" evidence.

  Open question Q-5: `TrackNormalizer.normalize` skips tracks whose `version` is already set, so adapter-supplied tags would bypass title analysis.
- **Explicitness:**
  - badge present → `EXPLICIT`;
  - absent → `UNKNOWN`, never `CLEAN`;
  - "Clean" title marker → `CLEAN` (already handled by the lexicon).
- **Identifiers:** no ISRC. EXACT therefore has to come from the non-identifier rule set: same identity key, same tag set, shared primary artist, compatible explicitness, Δ ≤ 2 s, album equal or unknown with no competing same-title release.

| Distinction | How Podium tells them apart (existing **[P]** unless marked) |
|---|---|
| Original studio recording | No identity-changing tags; audio-track kind (YouTube) |
| Live | `LIVE` tag from title/segments; YouTube "live" uploads are often user uploads → never EXACT **[I]** |
| Remix | `REMIX` tag; remixer credits |
| Acoustic | `ACOUSTIC` tag |
| Cover | `COVER` tag; **no shared primary artist → reject** |
| Clean / explicit | `Explicitness.conflictsWith` → reject |
| Sped-up / slowed | `SPED_UP`, `SLOWED` (+ `REVERB`, `NIGHTCORE`) |
| Instrumental | `INSTRUMENTAL` (+ `KARAOKE`, `A_CAPPELLA`) |
| Music video audio | `MUSIC_VIDEO_AUDIO` ("video" not preceded by lyric/visualizer); structured video type would be stronger **[I]** |
| Lyric video | Packaging (same audio, usually), but uploader is often not the artist → treat by uploader kind **[I]** |

**Rules kept:** no title-only matching; no duration-only matching; ambiguity downgrades; user "not the same song" is permanent (needs the persisted `track_equivalence`). For YouTube rows specifically **[I]**:
- **User uploads should be capped at `POSSIBLE`.** Uploader ≠ artist, and there are no identifiers.
- **Only audio-track rows should be eligible for `EXACT`.**

---

## 10. Source Resolution

### 10.1 Hypothetical flow and who does what

```
Search text ──► OnlineRepository.search                     [EXTENSION: query each enabled ONLINE source with SEARCH]
                   │
                   ▼
Source aggregation ──► group rows by recording             [NEW: SearchAggregator using TrackMatcher at STRONG (spec §9.1 grouping)]
                   │
                   ▼
Normalized candidates ──► TrackNormalizer / mapper           [EXISTING + adapter mapping]
                   │
                   ▼
Availability check ──► for catalogue-only rows, look for an EXACT copy on a playable source
                                                             [NEW: EquivalenceAvailability annotator; uses StreamResolver-style search + TrackMatcher]
                   │
User presses play ──► PlaybackController.playContext ──► QueueManager (single writer)        [EXISTING]
                   │
                   ▼
QueueItemResolver ──► StreamResolver:                                                         [EXISTING]
                     pinned → owned copy → own source PlaybackFacet → EXACT fallback (priority order)
                   │
                   ▼
Capability check  ──► DIRECT_STREAM / EMBEDDED_PLAYBACK usable? health allows?                [EXISTING]
                   │
                   ▼
PlaybackTarget    ──► DirectStream → Media3 (PodiumPlaybackEngine)                              [EXISTING]
                      Embedded     → EmbeddedEngine (visible surface only)                      [NEW]
```

### 10.2 "Blinding Lights" walk-through by option **[I]** (built on **[P]** behaviour)

| Step | Y1 (catalogue-only) | Y2 (embed) | Y3 (excluded) |
|---|---|---|---|
| Audius search | No EXACT playable match (likely for a major-label release) | same | same |
| YouTube catalogue | Audio-track row found (title, artist, album, duration, explicit) | same | same |
| Resolver: own source | No `PlaybackFacet` → `SKIPPED (UNSUPPORTED_ROUTE)` | `Embedded` target **if the surface is visible**, else `Miss` "Needs the video on screen" | (DirectStream via stream unlock — not built) |
| Resolver: EXACT fallback | Local file with EXACT match → `EXACT_FALLBACK`, surfaced. Otherwise `Miss` → "Not available from your sources" | not needed | — |
| Engine | Media3, if a local copy exists | EmbeddedEngine, foreground only | — |

The default fallback walk includes LOCAL sources, which raises an environment question (Q-3).

---

## 11. Mainstream Catalog Coverage

| Facet | Evidence | Notes |
|---|---|---|
| Breadth | Both apps build their whole product on YouTube Music's catalogue: home, charts, new releases, moods, artist discographies, playlists **[C][B]** | Official numeric catalogue size not verified here **[?]** |
| Official releases | "Audio track" (art-track) video type exists and is parsed by both **[C][B]** | The best candidates for EXACT |
| Music videos | Official-video type **[C][B]** | Different audio edits are common → `MUSIC_VIDEO_AUDIO` blocks EXACT **[P]** |
| User uploads | User-upload type **[C][B]** | Covers, live recordings, re-uploads, sped-up/slowed edits; uploader ≠ artist |
| Live / remixes / covers / lyric videos | Present as separate videos **[I]** | Must be distinguished by tags and uploader kind (§9) |
| Search coverage | InnerTube search with typed filters **[C][B]**. Data API `search.list` limited to **100 calls/day per project by default** in its own bucket **[YT]** | The official route is too small for a shared music app without per-user keys **[I]** |
| Region | Region-blocked videos and countries where YouTube Music is unavailable. Convx doesn't retry geo blocks; BitChord marks region blocks permanently unplayable **[C][B]** | Policy III.I.13 forbids circumventing geographic restrictions **[YT]** |
| Subscription-gated | User report: premium-locked albums played in BitChord 1.6 and not in 1.7 (#445) **[H]** | Podium must model this as `Availability.RequiresSubscription`, never unlock it |
| Playable ≠ searchable | Playability is known only from the player response **[C][B]** | Y1/Y2 rows should start as `Unknown` |

**Does a YouTube source address the "Audius lacks mainstream music" problem? [I]**
- **Discovery:** yes, with Y1 (unofficial) or a quota-limited Data API search.
- **Playback:**
  - **Y1:** only for songs the user already owns or that Audius carries at EXACT. Mainstream songs mostly won't play.
  - **Y2:** mainstream songs play, but as a visible, foreground, video-backed player with no background or lock-screen playback and no downloads.
  - **Y3:** would fill the gap like Convx/BitChord do, and is outside Podium's boundary.

---

## 12. Official YouTube Interfaces

| Interface | Gives | Limits (official) | Podium fit |
|---|---|---|---|
| **YouTube Data API v3** | Video/channel/playlist search and metadata; user playlists and ratings (OAuth) | `search.list`: its own 100-calls/day default allocation; other endpoints share 10,000 units/day; `videos.rate` 50 units **[YT, quota page updated 2026-09-15]**. No audio streams. No ISRC/album/explicit **[YT]**. Stored API data refreshed or deleted within 30 days (III.E.4) **[YT]**. Branding/attribution required (III.F.2.a) **[YT]** | Official catalogue (`Basis.OFFICIAL_API`), but quota makes per-user API keys or a very small feature necessary **[I]** |
| **IFrame Player API** (embed) | Official player with `playVideo/pauseVideo/seekTo/getCurrentTime/getDuration/getPlayerState`, `onStateChange`, `onAutoplayBlocked` **[YT, updated 2026-09-29]** | Viewport ≥ 200×200 px; no overlays over the player or controls; no autoplay until > 50 % visible; one autoplaying player; identify the app via Referer / WebView params; prefer OS WebViews; no player changes beyond the documented API **[YT RMF, 2026-09-14]**. Android WebView Media Integrity API may be used to verify the embedding app **[YT]** | The **only permitted playback target** (`Embedded`) |
| **YouTube API Services Terms** | Governs API clients | Audit rights (§6); registration (§4); no rights to make audiovisual content available outside authorized use (§16.3); may terminate or alter at any time (§14.1, §24.2) **[YT, 2026-09-14]** | Applies to Y2 and any Data API use |
| **YouTube Music API** | — | No official YouTube Music API found (prior research, 2026-10-02) **[?]** for 2026 beyond that search | — |
| **InnerTube** | Everything both apps use | Undocumented; Developer Policies III.D.7 forbid undocumented APIs for API clients **[YT]** | Y1 only (`Basis.UNOFFICIAL_API`) |

---

## 13. Policy / Terms Considerations

### 13.1 Rule by rule

| Topic | Official policy (verbatim excerpt) **[YT]** | Technical observation | Inference **[I]** |
|---|---|---|---|
| Audio/video separation | III.I.7: must not "separate, isolate, or modify the audio or video components" | Both apps play audio-only adaptive formats **[C][B]** | An audio-only YouTube player is the prohibited pattern; an embed must remain video |
| Background play | III.I.9: no "background player, meaning a player that is not displayed" | Both apps play YouTube in a background service **[C][B]**. Background play is a listed Premium benefit **[YT, YT Music Help]** | Y2 must pause when the surface isn't visible; no Media3 session for it |
| Download / cache | III.E.1.a: must not "download, import, backup, cache, or store copies" | Both apps cache and download YouTube media **[C][B]** | No YouTube bytes in any Podium cache or download; Podium has no stream cache today **[P]** |
| Scraping | III.E.6: must not "scrape … or obtain scraped YouTube data" | BitChord's NewPipe failsafe scrapes the watch page **[B]** | Excluded |
| Undocumented APIs | III.D.7: "must not use undocumented APIs … must not reverse engineer" | Both are built on InnerTube **[C][B]** | Y1 via InnerTube is unofficial. Whether III.D.7 binds an app that never registered as an API client is a legal question; the YouTube ToS (automated access) applies either way. Podium's matrix treats both as relevant |
| Substitute apps | III.I.1: must not "create, offer, or act as a substitute for" YouTube applications | Both describe themselves as YouTube Music clients **[C][B]** | A Y2 player whose purpose is music listening raises a III.I.1 question to evaluate before building |
| Player integrity / ads | III.I.5–6: must not modify, block or replace ads or player functionality | Both deliver ad-free playback (Convx README: "ad-free streaming") **[C]** | Y2 must leave the embed and its ads untouched |
| Geo restrictions | III.I.13: must not "circumvent … any geographical restrictions" | Convx exposes a proxy setting; Metrolist's README suggests a VPN (prior research) **[C][H]** | Region-blocked → `Availability.RegionBlocked`, never routed around |
| Attribution | III.F.2.a: must make clear YouTube is the source, using its brand features | Both show YouTube branding inconsistently **[I]** | Podium needs a rendered `AttributionBadge` (missing today **[P]**) |
| Automated access / circumvention | ToS (effective 2022-01-05, as fetched): no access "using any automated means"; no attempt to "circumvent, disable … or otherwise interfere" **[YT]** | Client impersonation, PoToken minting and cipher solving **[C][B]** | That's the Y3 layer: excluded by ADR-013. Prior research notes OLG Hamburg (2024-11-21) treated the rolling cipher as an effective technical protection measure |
| Downloads / offline | ToS restricts downloading except where expressly enabled; Premium lists "Download content for offline listening" **[YT]** | Both offer downloads **[C][B]** | Excluded |

### 13.2 Mechanism table

| Mechanism | Convx | BitChord | Official YouTube support | Policy concern | Podium implication |
|---|---|---|---|---|---|
| InnerTube catalogue (search/browse/next) | Yes **[C]** | Yes **[B]** | None (undocumented) | III.D.7, ToS automated access | Y1 only, opt-in, `UNOFFICIAL_API`, excluded from Play builds (D-19) |
| Data API catalogue | No | No | Yes, quota | Quota, 30-day refresh, attribution | Official alternative for search/metadata; small |
| Player request for streams | Yes **[C]** | Yes (via InnerTubeX, *LIKELY*) **[B]** | None | III.I.7, III.D.7 | Not built (Y3) |
| Client-identity walk | Yes **[C]** | Yes **[B]** | None | Circumvention | Not built |
| visitorData rotation on bot checks | Yes **[C]** | Session handling **[B]** | None | Circumvention of bot detection | Not built |
| PoToken / BotGuard in WebView | Yes **[C]** | Yes **[B]** | None | Circumvention | Not built |
| Signature / n-transform solving | Yes **[C]** | Yes (remote third-party config) **[B]** | None | Circumvention; TPM (EU case law, prior research) | Not built |
| NewPipeExtractor | Yes **[C]** | Yes **[B]** | None | Scraping; GPL | Not linked (ADR-014) |
| Cookie-capture login | Yes **[C]** | Yes **[B]** | Google OAuth exists for the Data API only | Session capture outside OAuth | If sign-in is ever needed: Data API OAuth (Custom Tabs/PKCE) |
| Background audio | Yes **[C]** | Yes **[B]** | No (III.I.9) | III.I.9; Premium feature | Not for YouTube content |
| Downloads / caching | Yes **[C]** | Yes **[B]** | No (III.E.1.a) | III.E.1.a; Premium feature | Not for YouTube content |
| Playback reporting to YouTube history | Yes **[C]** | Yes **[B]** | None | Impersonated writes | Not built |
| Official embed | No | No | **Yes** | Must follow RMF; III.I.1 question | Y2 candidate |
| Third-party relays of other services (TIDAL proxies, modules) | Yes **[C]** | Yes (addons/modules) **[B]** | n/a | Provenance; other services' terms | Not built (ADR-013 review §11) |

"Because Convx/BitChord do it" is evidence of **technical feasibility only**, not authorization.

---

## 14. Licensing

| Project | License | GPL? | Could Podium link it? | Notes |
|---|---|---|---|---|
| Convx | GPL-3.0 **[C]** | Yes | No (D-13 open) | Fork of vivi-music |
| vivi-music | GPL-3.0 + a proprietary-module exception (GitHub: NOASSERTION) **[H]** | Yes | No | Credits Metrolist |
| Metrolist | GPL-3.0 **[H]** | Yes | No | Source of the shared PoToken helper and the `innertube` lineage |
| ViMusic / InnerTune | GPL-3.0 (prior research) | Yes | No | Earliest lineage named in Convx code |
| BitChord | GPL-3.0 **[B]** | Yes | No | ADR-014 |
| InnerTubeX (MetrolistGroup) | GPL-3.0 **[H]** | Yes | **No, regardless** (ADR-014: purpose conflicts with ADR-013) | Releases v0.6.0 → v0.7.4 in Sep 2026 |
| NewPipeExtractor | GPL-3.0 **[H]** | Yes | **No, regardless** (ADR-014) | v0.25.0 → v0.26.5 in 2026 |
| NewPipe (app) | GPL-3.0 **[H]** | Yes | No | *LIKELY* origin of the PoToken helper idea (same file name; content differs from Metrolist's) |
| ZemerTeam cipher library/config | GPL-3.0 **[H]** | Yes | No | Fetched at runtime by BitChord via InnerTubeX **[B]** |
| quickjs-kt | Apache-2.0 **[H]** | No | Licence-compatible, but Podium doesn't execute third-party JS (ADR-013 review §11) | — |
| Media3 | Apache-2.0 | No | Already used | — |

**Obligations and boundaries:**
- **GPL obligations follow distribution.** Linking or copying GPL-3.0 code into a distributed Podium would require GPL-3.0-compatible terms for the whole work, Corresponding Source, and notices. That's true for any channel, GitHub APKs included.
- **Ideas aren't expression.** Architecture and behaviour described in prose aren't copyrightable expression. An **independent implementation** from Podium's own specs avoids those obligations; ADR-014's hygiene rules are the safeguard, and this document follows them.
- **What's reusable:**
  - *Generic architecture:* source contracts, miss/failure taxonomy, pinning, URL expiry, quality honesty, permanent-vs-retryable verdicts. Podium already has most of it.
  - *Reusable code:* none of these projects' code is usable while D-13 is open. The YouTube-specific code (unlock layer) is excluded on purpose regardless of licence.
- This is an engineering risk statement, not legal advice.

---

## 15. On-Device vs Server Architecture

### Convx **[C]**
```
Android app ──HTTPS──► music.youtube.com / www.youtube.com (InnerTube: search/browse/next/player)
     │        ──HTTPS──► YouTube player JavaScript (fetched, executed in WebView / solver)
     │        ──WebView─► Google BotGuard (PoToken minting)
     │        ──HTTPS──► googlevideo CDN (media bytes, range requests) ──► Media3 (MediaLibraryService, SimpleCache)
     │        ──HTTPS──► s.youtube.com (playback stats)
     │        ──HTTPS──► third-party TIDAL proxies ("hifi-api" instances), JioSaavn API/CDN, module indexes (optional intercepts)
     └── own server: listen-together (Node) — unrelated to YouTube resolution
```
YouTube functionality is **entirely on-device**, depends on **WebView** for tokens and the cipher, and on **session state** (visitorData, optional cookie). The optional intercepts depend on **external third-party services**.

### BitChord **[B]**
```
Android app ──HTTPS──► music.youtube.com (own Innertube client: search/browse/next/library)
     │        ──HTTPS──► InnerTubeX ► YouTube player endpoints  (LIKELY)
     │        ──HTTPS──► raw GitHub: third-party remote player/cipher config (runtime dependency)
     │        ──WebView─► Google BotGuard (PoToken minting)
     │        ──HTTPS──► www.youtube.com watch page (NewPipe failsafe)
     │        ──HTTPS──► googlevideo CDN (probe, then media) ──► Media3
     │        ──HTTPS──► YouTube playback-tracking pings
     │        ──HTTPS──► user addons / JioSaavn / module backends (optional)
     └── own server: "Jam" listen-together (Go) — unrelated to YouTube resolution;
         v1.7.1 branch: desktop asks the phone's resolver (device acts as resolver for the desktop) [H]
```
**Entirely on-device** for YouTube, but with a **runtime dependency on a third-party configuration feed** plus WebView and session state.

### Podium **[P]/[I]**
- **Y1 (InnerTube or Data API metadata):** device → YouTube endpoints (metadata only) → `Track`s. Playback goes to other sources. No WebView, no tokens, no server.
- **Y2 (embed):** device → official IFrame player in a WebView (YouTube serves the player and media itself) → the visible surface. Podium never handles media bytes. No server.
- **No Podium backend is required** for either. Data API keys would need a per-user ("bring your own key") or build-time key; CLAUDE.md forbids secrets in git **[I]**.

---

## 16. Reliability / Maintenance

| Failure class | Evidence | Who it would affect in Podium |
|---|---|---|
| InnerTube layout changes (parsers return empty pages) | Convx: "fix(parser): resolve empty explore/search album and playlist pages", "fixed search section not loading", "Liked Music sync empty — NPE on headerless auto-playlist" **[H]**. BitChord: "Fix paged YouTube Music result feeds" **[H]** | Y1 |
| Client identities refused or bot-flagged | Convx r52 notes: most identities returned "Sign in to confirm you're not a bot" or unplayable on a flagged track; chain reordered **[H]**. Convx `BotDetectionMitigator` **[C]**. BitChord v1.3 "Fixed several 403 playback errors (updated client versions…)", v1.4.1 "YouTube bot-checks" **[H]** | Y3 only |
| Player JS changes (signature/n) | Convx: signature-timestamp extraction "fails on a request that never needed it" when YouTube changes the player JS **[C]**. BitChord: remote cipher config with cooldown; "Could not parse deobfuscation function" note **[B]** | Y3 only |
| Token expiry / PoToken generation | WebView renderer death and timeouts handled in both **[C][B]** | Y3 only |
| Stream URL expiry | Convx #32 "missing stream expire time", v1.5.2 "downloaded stream links expiring incorrectly" **[H]**. Both track expiry **[C][B]** | Y3 (and any expiring-URL source → `refreshPinned` gap **[P]**) |
| URLs that pass a check then fail | BitChord feeds in-playback refusals back to the resolver **[B]** | Y3 only |
| Rate shaping / hung requests | BitChord: dedicated pool and short ceilings for the watch-page fetch **[B]** | Y1 (metadata) mildly; Y3 heavily |
| Region restrictions | Convx: no retry on geo blocks; BitChord: permanent verdict **[C][B]** | Y1/Y2 — model as `RegionBlocked` |
| Authentication | Convx v1.5.2: "Fixed the HTTP 500 on login and every authenticated request" **[H]** | Y1 with sign-in |
| Removed / age-restricted content | BitChord permanent verdict; Convx routes age-restricted items through a different client identity **[C][B]** (the latter is an age-gate bypass — excluded by CLAUDE.md) | Y1/Y2: `Unavailable` / `NOT_PERMITTED` |
| Quality changes | BitChord #420/#471 sound quality, #450 "convert to high quality not working"; Convx #24 "Lossless falls back to Opus" **[H]** | Not YouTube-specific |
| Wrong-song substitution | BitChord #412 (JioSaavn "entirely different song"), #373 "wrong song"; Convx code comments describe wrong-song cases the guards were added for **[C][H]** | Podium's EXACT-only rule is the mitigation **[P]** |
| Library churn | InnerTubeX: 6 releases 2026-09-07 → 09-30; BitChord pins v0.7.0. NewPipeExtractor: 6 releases in 2026; Convx pins v0.25.2 (Feb) **[H]** | Y3 only |

**Maintenance characteristics [I]:**
- **Y1 (metadata only)** inherits the *layout-change* class: periodic parser fixes, low severity, because the failure shows up as empty lists, not silence.
- **Y2** inherits only the official embed's changes and policies.
- **Y3** would inherit the whole stream-unlock arms race. Both projects show it as continuous, user-visible playback failure.

---

## 17. Recent Breakage / Issue History

### Convx **[H]**
- **2026-04-09** "feat(playback): implement antibot mitigation system and real-time diagnostic logs".
- **2026-08-02** #17, #18 "Playback issue" (10 comments): playback doesn't start.
- **2026-08-05** #24 "Lossless falls back to Opus even with all modules enabled".
- **2026-08-13** #32 "Playback reliability: stream-expiry retry gap for signed-in users, foreground-service crash".
- **2026-08-15** v1.5.2: 40+ fixes. Login HTTP 500; streams discarded over a missing field; foreground-notification crash on Android 16.
- **2026-08-15 → 08-31:**
  - #36 "Jio Saavn broken and 8spine doesn't load all modules";
  - #37 "No songs play/download";
  - #40/#41 "playback error … Video unavailable";
  - #42 "songs cannot be played";
  - #43 "Songs are not loading";
  - #45 "songs cannot be streamed" ("loads for hours");
  - #52 "Can't play music".

  #37, #40–#43 and #45 are **still open** on 2026-10-04.
- **2026-08-18** commit: "rotate visitorData on playback bot-detection retry for signed-in users too".
- **2026-08-20** nightly r52: client fallback chain reordered after live probing; signature timestamp fetched lazily. **Last maintainer commit on `main`.**
- 35 commits touched YouTube stream/innertube code between 2026-03-02 and 2026-08-20.

### BitChord **[H]**
- **v1.3 (08-19):** "Fixed several 403 playback errors (updated client versions, shared HTTP stack)"; "Fixed stream-resolve stalls/timeouts"; added another client identity.
- **v1.4 (08-23):** sources package, `TrackMatcher` replaces title-only matching, `QualityUpgrade`/`StreamChoice`.
- **v1.4.1 (08-24):** "smarter error recovery for broken streams, dead links, and YouTube bot-checks".
- **v1.6 (09-15):** "Fixed dead playback URLs … mismatched cached rendition playback".
- **v1.7 (09-25):** "YouTube streams now resolve through InnerTubeX, with automatic YouTube fallback when another source fails".
- **After v1.7:**
  - #442 "I cannot play anything" (closed);
  - #459 "songs are not playable and visible in the new update" (open);
  - #445 premium-locked albums no longer play (open);
  - #453 downloads failing;
  - #412 JioSaavn wrong song (open);
  - #538 playback error (10-03, open).
- 58 commits touched `data/innertube` on `main` (33 in Aug, 25 in Sep).
- `v1.7.1` branch (10-04): desktop resolution via the phone; desktop PoToken minting; CJK matcher work.

### Upstream **[H]**
- InnerTubeX: v0.6.0 (09-07), v0.7.0 (09-18), v0.7.1 (09-25), v0.7.2/v0.7.3 (09-28), v0.7.4 (09-30).
- NewPipeExtractor: v0.25.0 (01-11) … v0.26.5 (08-15).
- Metrolist: v13.4.1 (04-12) … v13.7.0 (09-07), nightly 10-03.

The history above is the evidence for each project's own experience. This isn't a popularity ranking.

---

## 18. Podium Integration Design

Hypothetical. It includes **only** the targets the investigation supports.

```
ONLINE environment (D-34)
   │
   ├── AudiusMusicSource                 [EXISTING — no change]
   │      Catalog · Discovery · Recommendation · Artwork · Playback{DIRECT}
   │
   └── YouTube source (sources:youtube-*) [NEW module; provider code stays here]
          ├── Descriptor: environment=ONLINE, basis=UNOFFICIAL_API (InnerTube) | OFFICIAL_API (Data API),
          │   attribution=YouTube brand features, termsUrl                         [EXISTING types]
          ├── Search / Metadata  → CatalogFacet, DiscoveryFacet                    [EXISTING interfaces; NEW impl]
          ├── Related / radio    → RecommendationFacet                             [EXISTING interface; NEW impl]
          ├── Artwork            → ArtworkFacet                                    [EXISTING interface; NEW impl]
          ├── Mapping            → Track (version tags, explicitness, availability=Unknown) [NEW mapper]
          ├── Matching           → TrackMatcher / MatchQueryBuilder               [EXISTING — no change; Q-5 for adapter tags]
          └── PlaybackTarget
                 ├── DirectStream  ✗ excluded (requires Y3 stream unlock — ADR-013)
                 ├── RemoteProvider ✗ not applicable (no official remote API)
                 └── Embedded      ✓ Y2 only                                       [EXISTING type; NEW engine]

Cross-cutting:
   OnlineRepository / AppOnlineRepository  → choose or aggregate ONLINE sources;
                                             source-qualified shelves; route by id.sourceId  [EXTENSION]
   SearchAggregator (STRONG grouping)       → one row per recording across sources          [NEW, optional]
   EquivalenceAvailability annotator        → "Not available from your sources" before play [NEW, Y1]
   EquivalenceStore → track_equivalence     → persisted, user-overridable                   [EXTENSION]
   StreamResolver                           → environment rule for fallback (Q-3)          [POSSIBLE EXTENSION]
   PlaybackRouter + EmbeddedEngine          → wired into the service; hard cuts             [EXTENSION + NEW, Y2]
   Embed surface in VirtualScreen/Now Playing → visible ≥200×200, no overlays, foreground   [NEW UI requirement, Y2]
   AttributionBadge                         → render descriptor.attribution                 [NEW]
   Settings ▸ Online sources                → enable/disable, priority → SourceRegistry     [NEW UI + persistence; registry API EXISTING]
   Build policy                             → keep UNOFFICIAL_API factories out of Play builds [EXTENSION of BuildSources.kt]
   AuthFacet + credential store             → only if Data API OAuth is wanted              [EXTENSION]
```

### 18.1 Online sources settings (analysis, not UI)

| Question | Finding |
|---|---|
| Independently enabled? | Yes. `SourceRegistry.setEnabled` exists **[P]**; it needs persistence and a settings row. YouTube default OFF (D-20 pending; Y1 is opt-in by matrix) |
| User-configured priority? | `setPriority` exists **[P]**. Priority decides fallback order and, today, *which* online source Online shows. With two ONLINE sources, priority alone would hide one catalogue entirely unless the repository aggregates **[I]** |
| Should priority affect search? | Either "one active online catalogue" (no aggregation; priority picks it) or "merged search grouped at STRONG" (priority orders groups and picks the representative). This is a product decision **[I]** |
| Source names in normal UI? | `MUSIC_SOURCE_ARCHITECTURE.md` §12 allows `displayName`/`basis` labels; YouTube's III.F.2.a **requires** attribution where its data is shown **[P][YT]**. So the provider name appears via a generic attribution slot, not only in diagnostics; UI never branches on it |
| Advertise capabilities? | Yes — required by the model; Y1 would declare no `DIRECT_STREAM`/`EMBEDDED_PLAYBACK` **[P]** |
| Automatic fallback on failure? | Only EXACT, only before playback, always surfaced; never mid-track (D-17/D-18) **[P]** |

### 18.2 Media3 and background playback
- **DirectStream from YouTube:** not available (Y3).
- **Embedded:**
  - plays in the WebView, not in Media3;
  - pauses when its surface leaves the screen or the display turns off (III.I.9; `EmbedConstraints.foregroundOnly`);
  - shouldn't hold a foreground media notification, because that would imply background control;
  - Podium's session keeps serving DirectStream items **[I]**.

  Handoffs Direct ↔ Embedded are hard cuts (`PlaybackRouter`) **[P]**. Autoplay into an embedded item while the surface isn't visible must skip with "Needs the video on screen" (PLAYBACK_TARGETS §5.1) **[P]**.

---

## 19. Existing Podium Components We Can Reuse

**Already solved by Podium — verified in the repository [P]:**
- **Source abstraction:** `MusicSource` + facets (`Catalog`, `Discovery`, `Recommendation`, `Artwork`, `Playback`, `Auth`, …), `Capability` / `CapabilityState` / `SourceCapabilities.restrictedBy/disable`.
- **Source identity:** `SourceDescriptor` with `Basis`, `Attribution`, `environment`, `cheapResolve`.
- **Source registry:** `SourceRegistry` — registration, enable/disable and priority APIs, `withCapability`, `connectedSources`.
- **Source health:** `SourceHealthMonitor` — miss ≠ failure, breaker with half-open probing, rate limit, auth rejected, invalid media per item.
- **Source resolver:** `StreamResolver` — pinning, owned copy, own source, EXACT-only fallback, in-flight dedupe, attempt log, `refreshPinnedSourceOnly` mode.
- **Track model:** source-qualified `Track` / `TrackId` / `SourceRef.providerData`; `Availability`, `Explicitness`, `RecordingIdentifiers`, `VersionInfo`, `routes`.
- **Track matching:** `TrackMatcher` / `TrackNormalizer` / `VersionLexicon` / `MatchQueryBuilder`, including `MUSIC_VIDEO_AUDIO`, `COVER`, `SPED_UP`, `SLOWED`, `LIVE`, `REMIX`, `INSTRUMENTAL`, explicit/clean.
- **Playback targets:** `PlaybackTarget.DirectStream` / `Embedded` / `RemoteProvider`, `PlayableMedia` (headers, expiry, cache key), `EmbedConstraints`, `RemotePolicy`.
- **Media3:** `PlaybackService` (`MediaLibraryService`) and `PodiumPlaybackEngine` (resolving data source, pre-resolution, error classification, health recording).
- **Queue:** `QueueManager` single writer; `QueueItem.selection` pinning; `QueueOrigin.RADIO/AUTOPLAY`.
- **Autoplay:** `AutoplayEngine` + `SourceRecommendationEngine` — same source only, EXACT de-dupe.
- **Online DB and history:** ONLINE DB (`online_liked_track`, `online_playlist`, `online_playlist_track`, `online_history` with `source_id`); `OnlineHistoryRecorder`; `EnvironmentFavorites`.
- **Router:** `PlaybackRouter` with hard-cut handoffs (tested, unwired).
- **Quality honesty:** `QualityReport` / `QualityLabel` (claimed vs measured).
- **Online UI:** capability-gated rows and availability reasons (`OnlineCommon.kt`, `OnlineDetail.kt`).
- **Build variants:** per-variant source lists (`BuildSources.kt` debug/release).

---

## 20. Components Podium Would Need

### 20.1 Needed for a YouTube source (only genuine gaps)

| # | Component | Option | Kind |
|---|---|---|---|
| N1 | `sources:youtube-*` adapter (Catalog/Discovery/Recommendation/Artwork facets; descriptor; capabilities) | Y1, Y2 | New module |
| N2 | Metadata client + parser — InnerTube (independent, non-GPL) **or** Data API v3 client | Y1 (InnerTube) / Y2 (either) | New |
| N3 | Mapper: music-video type → version tags, explicit badge → `EXPLICIT`/`UNKNOWN`, availability `Unknown`, ids into `providerData` | Y1, Y2 | New |
| N4 | Multi-ONLINE-source support in `OnlineRepository`/`AppOnlineRepository` (choice or aggregation; source-qualified shelf ids; route by `id.sourceId`) | Y1, Y2 | Extension |
| N5 | Availability-by-equivalence annotator (pre-match catalogue rows against playable sources) | Y1 | New |
| N6 | `EmbeddedEngine` (IFrame player in WebView, mapped to `PlaybackEngine`/`ControlSet`) | Y2 | New |
| N7 | `PlaybackRouter` wiring into the service/controller; snapshot `owner = Embedded` | Y2 | Extension |
| N8 | Visible embed surface inside VirtualScreen / Now Playing that meets RMF (≥ 200×200, no overlays, autoplay only when > 50 % visible, app identification) | Y2 | New UI (genuine requirement) |
| N9 | Rendered attribution (`AttributionBadge` from `descriptor.attribution`) | Y1, Y2 | New |
| N10 | Settings ▸ Online sources: enable/disable + priority, persisted → `SourceRegistry` | Y1, Y2 | New UI + persistence |
| N11 | `Basis`-driven build policy (no `UNOFFICIAL_API` factory in Play builds, D-19) | Y1 via InnerTube | Extension |
| N12 | API-key handling without secrets in git (per-user key or local, gitignored config) | Data API | New |

### 20.2 Optional enhancements
- **Persisted `track_equivalence`** with user "Not the same song" overrides. It's in the spec but not in code.
- **A STRONG-grouping `SearchAggregator`** so one recording shows once across Audius and YouTube.
- **A "known unplayable until T" verdict memory** (miss-side), so region and removed videos aren't re-asked.
- **Engine call to `refreshPinned`** on 401/403/410 mid-play. Generic; benefits any expiring-URL source.
- **Data API OAuth** (Custom Tabs + PKCE) + Keystore credential store, for reading the user's YouTube playlists as *metadata only*. Matched to playable sources like Spotify S-a.
- **A provider-neutral "media kind" field** (audio track / music video / user upload) on `Track`, instead of relying on version tags (Q-5).

---

## 21. Open Technical Questions

| # | Question |
|---|---|
| Q-1 | With two ONLINE sources, does Online show **one active catalogue** (priority-selected) or a **merged** catalogue grouped at STRONG? This drives N4 and the aggregator |
| Q-2 | Y1 discovery: InnerTube (unofficial, broad, layout-fragile) or the Data API (official, 100 searches/day/project default, no album/explicit)? Is a per-user API key acceptable UX? |
| Q-3 | May an ONLINE catalogue track resolve to a **LOCAL** file at EXACT? `StreamResolver` allows it today; D-34 is silent on playback fallback across environments |
| Q-4 | How does an embed surface fit D-26/D-29 (VirtualScreen, no glass on the screen, album art the only colour) when a video player must be visible, un-overlaid, and at least 200×200 px? |
| Q-5 | Should adapters set `Track.version` (bypassing title analysis) or should `Track` gain a provider-neutral media-kind field that the matcher reads? |
| Q-6 | Does the Android WebView Media Integrity API (supported by the IFrame API on GMS devices) change embed behaviour for sideloaded builds or devices without GMS? **[?]** |
| Q-7 | Does III.I.1 ("substitute for … YouTube applications") constrain a music-focused embed experience? Needs a policy reading before Y2 |
| Q-8 | Where does the IFrame player's own media session/notification land when hosted in a WebView, and how does Podium avoid two competing sessions? **[?]** |
| Q-9 | Data API caching: the 30-day refresh/delete rule (III.E.4) vs ONLINE likes/playlists/history persisting YouTube metadata indefinitely — needs a refresh job or metadata minimisation |
| Q-10 | Doc drifts: TrackId separator (`:` vs `|`); Y1 "EXACT/STRONG" vs D-17 EXACT-only; the "Allow close matches" row in MUSIC_SOURCE_ARCHITECTURE §9.1. Correct the docs when the decision is taken |

---

## 22. What Must NOT Be Implemented Yet

Per ADR-013, ADR-014, CLAUDE.md, D-20 (still pending), and this brief:
- **No YouTube source code, dependencies or settings** until the user decides D-20 (Y0/Y1/Y2).
- **Never (Y3):**
  - client-identity rotation to obtain streams;
  - visitorData/guest-identity rotation against bot checks;
  - PoToken/BotGuard minting;
  - signature/n-transform solving;
  - fetching and executing YouTube's player JS;
  - watch-page scraping;
  - age-gate bypass;
  - geo-restriction workarounds;
  - YouTube downloads or stream caching;
  - background or audio-only YouTube playback;
  - playback reporting with impersonated clients;
  - remote cipher-config feeds.
- **No GPL code or dependencies:** InnerTubeX, NewPipeExtractor, Metrolist/Convx/BitChord/zemer files.
- **No third-party JS execution** (QuickJS modules) and **no relays of other services' streams** (TIDAL proxies, "lossless" addons of unknown provenance).
- **No changes to Audius, the Online UI, Now Playing, the paper, autoplay or radio** for this investigation.

---

## 23. Implementation Prerequisites

Before any YouTube work starts:
1. **User decision D-20:** Y0, Y1, Y2, or Y1 + Y2. Record it in `decision-log.md` (and an ADR if Y2 changes the shell).
2. **Policy re-verification** on the day of implementation:
   - Developer Policies (III.D.7, III.E.1.a, III.E.4, III.F.2.a, III.I.1, III.I.5–9, III.I.13);
   - Required Minimum Functionality;
   - IFrame API;
   - quota.

   They changed on 2026-09-14/09-29.
3. **Resolve Q-1 (single vs merged online catalogue)** and implement N4 behind tests, with Audius behaviour unchanged.
4. **Resolve Q-3 (environment boundary for fallback)** and encode it in `StreamResolver` tests.
5. **Y2 only:** a design pass on the embed surface against D-26/D-29 and RMF (Q-4), plus a spike on the WebView IFrame player (state events, media session, Media Integrity) on the physical test device, inside the test-only scope agreed for that phone.
6. **Y1 via InnerTube:** confirm build policy (N11) so Play builds can't link it, and accept the `UNOFFICIAL_API` label ("Unofficial — may stop working").
7. **Data API:** decide key strategy (N12). No shared secret in git.
8. **Matcher corpus additions** for YouTube shapes (audio track vs official video vs upload; lyric video; "Topic" artist names; explicit badge absent ≠ clean) before any fallback is enabled.
9. **ADR-014 hygiene for the implementer:** implement from Podium specs and this prose. Don't consult Convx/BitChord/Metrolist source while writing the adapter.

---

## 24. Final Findings

### 24.1 Decision tree (factual)

```
Can Podium support a YouTube source architecturally?
         │
         ├── YES  (MusicSource + facets, Basis, environment, capabilities, Embedded target type,
         │         miss/failure health, EXACT-only resolver, version-aware matcher — all exist [P])
         │     ↓
         │   What interfaces are needed?
         │     CatalogFacet, DiscoveryFacet, RecommendationFacet, ArtworkFacet  (existing)
         │     PlaybackFacet{EMBEDDED} only for Y2                              (existing)
         │     + extensions: multi-source OnlineRepository, PlaybackRouter wiring, EmbeddedEngine,
         │       attribution rendering, source settings/persistence, build policy (N4–N12)
         │
         └── NO   — not applicable (no blocking architectural change is required)

Can YouTube search/catalogue discovery be integrated?
         ↓
   YES, by one of:
     • InnerTube metadata (unofficial; Basis.UNOFFICIAL_API; opt-in; not in Play builds; layout-fragile)   → Y1
     • YouTube Data API v3 (official; search.list 100 calls/day/project default; no album/explicit/ISRC) → Y1-official / Y2
   Either way, rows start as Availability.Unknown — search ≠ playable.

Can Podium obtain a permitted playback target?
         ↓
   DirectStream (audio URL)  → NO: only via the stream-unlock layer (Y3), excluded by ADR-013
   RemoteProvider            → NO: no official remote-control API
   Embedded (official player)→ YES: visible, ≥200×200, no overlays, foreground, video (Y2)
   Owned/authorized copies   → YES: EXACT match on local files or Audius via StreamResolver (Y1 path)

Can that target work with Media3/background playback?
         ↓
   Embedded  → NO background (III.I.9); runs in a WebView under a PlaybackRouter/EmbeddedEngine,
               hard cuts to/from Media3 DirectStream items; pauses when not visible
   EXACT-matched local/Audius copies → YES: ordinary DirectStream through Media3 (existing)
```

### 24.2 Summary of findings
- **Files inspected in Podium:** §1.1.
- **Convx components inspected:**
  - `innertube/` (InnerTube.kt, YouTube.kt, models incl. YouTubeClient/PlayerResponse/YTItem, pages incl. NewPipe.kt);
  - `app/…/utils/` YTPlayerUtils, BotDetectionMitigator, YouTubeClientProbe, potoken/*, cipher/*, sabr/*, tidal/TidalService;
  - `playback/` MusicService, DownloadUtil, queues/*;
  - LoginScreen, PreferenceKeys;
  - `spine/`, `jiosaavn/`;
  - build files, README, LICENSE, release notes, issues.
- **BitChord components inspected:**
  - `data/sources/` MusicSource, SourceKind, SourceRegistry, SourceResolver, TrackMatcher, YouTubeSource, AddonSource, ModuleSource, JioSaavnSource;
  - `data/innertube/` Innertube, InnertubeParser, StreamResolver, InnerTubeXResolver, PlayerClient, PlaybackTracker, potoken/*;
  - build file, docs site, release notes, issues;
  - `v1.7.1` branch log/diffstat;
  - the 2026-10-02 review for playback/auth/download details.
- **Major similarities:** the same InnerTube-based pipeline and the same stream-unlock family; shared Metrolist/NewPipe lineage; on-device only; Media3 progressive playback in a background service; alternate "lossless" sources of uncertain provenance ahead of YouTube; title + duration guards that still let wrong songs through.
- **Major differences:**
  - **Convx:** typed in-app InnerTube module, guest-identity rotation, in-app solvers, TIDAL proxies, unencrypted session, maintainer activity paused since 08-20.
  - **BitChord:** small own client + InnerTubeX with a remote third-party cipher feed, byte probes, permanent-vs-retryable verdicts, a provider-neutral source contract, parallel source races with mid-track upgrades, encrypted sessions, very high release cadence.
- **Common underlying architecture:** §5.1.
- **Reusable Podium components:** §19.
- **Missing Podium components:** §20.1 (N1–N12).
- **Playback-target possibilities:** Embedded (Y2) and EXACT-matched authorized copies (Y1). DirectStream from YouTube only via Y3 (excluded). RemoteProvider not applicable.
- **Catalogue/discovery implications:** broad mainstream discovery is achievable (unofficial, or official at tiny quota). Mainstream *playback* isn't, except as a foreground video embed.
- **Licensing implications:** every relevant project and library is GPL-3.0, so none can be linked while D-13 is open, and InnerTubeX/NewPipe are excluded regardless. Independent implementation from specs carries no licence obligations.
- **Reliability implications:**
  - Y1 metadata breaks occasionally and softly (layout changes);
  - Y2 is bound to YouTube's embed policies;
  - Y3 is a continuous arms race, evidenced by both projects' 2026 histories.
- **Unresolved questions:** §21 (Q-1 – Q-10).

---

## Appendix A — Sources

**Repositories (read 2026-10-04):**
- https://github.com/cosmictaserdev-creator/Convx (`main` 1e2237d9; releases; issues #17–#66)
- https://github.com/kushagrasinghx/BitChord (`main` 85d19183; `v1.7.1` branch; releases v1.0–v1.7; issues to #545)
- https://bitchord.kushagrasingh.in/docs
- https://github.com/vivizzz007/vivi-music (README credits, LICENSE)
- https://github.com/MetrolistGroup/Metrolist (PoToken helper and `innertube` model comparison; releases)
- https://github.com/MetrolistGroup/innertubex (license, releases)
- https://github.com/TeamNewPipe/NewPipeExtractor (license, releases) · https://github.com/TeamNewPipe/NewPipe (license; PoToken helper comparison)
- https://github.com/ZemerTeam/zemer-cipher (license, description; referenced at runtime by BitChord's InnerTubeX config store)
- https://github.com/dokar3/quickjs-kt (license)

**Official documentation (fetched 2026-10-04):**
- YouTube API Services — Developer Policies (last updated 2026-09-14): https://developers.google.com/youtube/terms/developer-policies
- YouTube API Services — Terms of Service (2026-09-14): https://developers.google.com/youtube/terms/api-services-terms-of-service
- Required Minimum Functionality (2026-09-14): https://developers.google.com/youtube/terms/required-minimum-functionality
- IFrame Player API reference (2026-09-29): https://developers.google.com/youtube/iframe_api_reference
- Data API — Videos resource: https://developers.google.com/youtube/v3/docs/videos
- Data API — Quota calculator (2026-09-15): https://developers.google.com/youtube/v3/determine_quota_cost
- YouTube Terms of Service (effective 2022-01-05 as served): https://www.youtube.com/t/terms
- YouTube Music Help — Premium benefits: https://support.google.com/youtubemusic/answer/6305537
- Android Developers Blog — WebView Media Integrity API (2023-11-02): https://android-developers.googleblog.com/2023/11/increasing-trust-for-embedded-media.html
- Google Play — Device and Network Abuse: https://support.google.com/googleplay/android-developer/answer/9888379
- Google Play — Intellectual Property: https://support.google.com/googleplay/android-developer/answer/9888072

**Prior Podium research relied on:** [2026-10-02-provider-policies.md](2026-10-02-provider-policies.md) (Data API revision history; OLG Hamburg), [2026-10-02-music-sources.md](2026-10-02-music-sources.md) (ecosystem table; ToS excerpts), [BITCHORD_ARCHITECTURE_REVIEW.md](BITCHORD_ARCHITECTURE_REVIEW.md).

## Appendix B — Distribution channels

| Constraint | A. Google Play | B. GitHub APK | C. Personal sideload | D. Self-hosted updates |
|---|---|---|---|---|
| **Google Play-specific** — Device & Network Abuse: no apps that use "a service or API in a manner that violates its terms of service" or that help "circumvent security protections"; Intellectual Property: no apps that "induce or encourage copyright infringement" **[YT/Play]**; Play review and enforcement | Applies | Doesn't apply | Doesn't apply | Doesn't apply (Play restricts in-app APK installers for Play builds **[I]**) |
| **YouTube / service-specific** — ToS, API Services Terms, Developer Policies, RMF | Applies | **Applies** | **Applies** (the user still accesses YouTube under its ToS **[I]**) | **Applies** |
| **Copyright** — infringement / anti-circumvention law, jurisdiction-dependent (e.g. OLG Hamburg on the rolling cipher) | Applies | Applies | Applies | Applies |
| **Software licence** — GPL-3.0 obligations on conveying a work that includes GPL code | Applies if GPL code is included | Applies (distribution) | Private use without conveying doesn't trigger GPL distribution duties **[I]** | Applies (distribution) |
| **Android-specific** — unknown-sources install consent, Play Protect warnings, WebView availability, Media Integrity API on GMS devices, foreground-service rules for media playback | Store install | Unknown-sources consent | Same | Same + `REQUEST_INSTALL_PACKAGES` for in-app updates (Convx ships an in-app updater **[C]**) |

**Reading [I]:** leaving Google Play removes only the first row. YouTube's terms, copyright law and licence obligations don't depend on the distribution channel. Y1 via InnerTube is therefore "excluded from Play builds" (D-19), not "permitted elsewhere".
