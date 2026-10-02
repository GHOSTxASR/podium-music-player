# BitChord Architecture Review

**Reviewed:** 2026-10-02 · **Repository:** [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord) at `main` = `85d19183` (2026-10-01) · **Release:** v1.7 (2026-09-25) · **License:** GPL-3.0
**Method:** read the current source of the source layer, InnerTube stream path, playback fallback/quality code, auth, and build file through the GitHub API (study only, in a scratch directory outside this repo). This document describes **behaviour and design ideas in prose**. It intentionally reproduces **no code, constants, word lists, or regexes** from BitChord (see §12 and ADR-014).

> Purpose: learn from a current, working, multi-source Android music client so Podium's own source architecture is informed by real failure modes — without creating a derivative work.

---

## 1. Current architecture (as of the reviewed commit)

| Aspect | Finding |
|---|---|
| Age / activity | Created 2026-08-11; 2.2k stars, 199 forks, 205 open issues; releases every ~1–2 weeks |
| Shape | **Single Gradle module** (`app`, ~400 Kotlin files) + `backend/` (Go "Jam"/party listen-together server, deployed to an Oracle VM) + `native/analyzer` (Automix audio analysis) |
| UI | Compose + Material 3, Haze for frosted bars, artwork-derived palette |
| Platform | minSdk 26, targetSdk 36, compileSdk 37; Media3 1.11.0 |
| State style | Kotlin `object` singletons for registries/stores (e.g., the source registry), a very large playback service (`playback/PlaybackService.kt` ≈ 7,700 lines), audio cache ≈ 1,500 lines |
| Positioning | "Aesthetic YouTube Music client"; YouTube Music is the catalogue *spine*; other sources supply alternative copies of YouTube-catalogued tracks |

Key third-party dependencies relevant to sources: **NewPipeExtractor v0.26.3 (GPL-3.0)**, **InnerTubeX v0.7.0 by MetrolistGroup (GPL-3.0)**, **quickjs-kt (Apache-2.0)** for JavaScript modules, SMBJ (SMB shares), ONNX Runtime (Automix).

## 2. Important classes / modules (paths under `app/src/main/java/com/music/bitchord/`)

| Area | Files | Role |
|---|---|---|
| Source contract | `data/sources/MusicSource.kt` | Small interface: `health()`, `search(query, …, request)`, `stream(trackId, request)`; plus `StreamFormat`, `SourceStream`, `StreamRequest`, `SourceHealth` value types |
| Source kinds | `data/sources/SourceKind.kt` | Fixed enum: user **Addon**, legacy **Custom module** / **Module**, **JioSaavn**, **YouTube**; carries rank (try order), lossless capability, "worth prefetching" |
| Registry | `data/sources/SourceRegistry.kt` | Singleton; configs in encrypted prefs; seeds built-ins; YouTube undeletable; JioSaavn opt-in; user-reorderable addon priority; quality-ceiling filtering per connection |
| Resolution | `data/sources/SourceResolver.kt` (~1,150 lines) | Quality request per connection; ranked source walk; cross-source substitution; prefetch; download selection; live "upgrade" |
| Matching | `data/sources/TrackMatcher.kt` (~670 lines) | Title parsing (identity vs version vs packaging), artist credit comparison, duration windows, album/explicit tie-breaks, collision handling |
| Adapters | `YouTubeSource.kt`, `JioSaavnSource.kt`, `AddonSource.kt` + `addon/*`, `ModuleSource.kt` + `module/*` | Per-provider implementations |
| YouTube streams | `data/innertube/StreamResolver.kt` (~1,100 lines), `PlayerClient.kt`, `InnerTubeXResolver.kt`, `potoken/*` | Client-identity walk, PoToken minting, NewPipe failsafe, URL cache, 403 handling |
| Playback | `playback/PlaybackFallback.kt`, `QualityUpgrade.kt`, `StreamChoice.kt`, `StreamContainer.kt`, `AudioCache.kt`, `QueueCoordinator.kt` | Fallback on player error, mid-play upgrade, per-item stream pinning, caching |
| Auth | `auth/YtMusicLoginScreen.kt`, `WebSession.kt`, `AuthStore.kt`, `AccountSessions.kt`, `EncryptedPrefs.kt` | WebView Google sign-in, cookie/session capture, multi-account |
| Downloads | `download/*` (Downloader, taggers for FLAC/MP4/WebM, offline DASH/HLS) | Files with embedded tags |
| Lyrics | `data/lyrics/*` (~15 providers) | Provider chain incl. LRCLIB and several non-official sources |

## 3. Source lifecycle
1. **Configure** — a `SourceConfig` (id, kind, label, base URL, enabled) is stored in encrypted preferences.
2. **Seed & migrate** — on init, built-in kinds are added if missing (YouTube always on; JioSaavn forced off once and then opt-in because "catalogue matching can select the wrong song"); retired kinds are dropped; stored entries decode one by one so one bad entry doesn't wipe the list.
3. **Instantiate** — one instance per config, built cheaply, cached while its config is unchanged (keeps warm state such as a fetched module index).
4. **Order** — `active()` sorts enabled sources by kind rank (user addons first, then legacy modules, JioSaavn, YouTube last); user order among addons is the stored list order.
5. **Budget** — `activeForPlayback()` removes sources the current connection's quality ceiling doesn't permit; downloads skip this filter (download quality is a separate setting).
6. **Health** — `health()` returns Ok / Unreachable / Rejected; the sources screen distinguishes "server down" from "credentials refused".

## 4. Search → metadata → matching → stream resolution
```
YouTube Music (InnerTube) search/browse ──► Song rows keyed by YouTube video id   (catalogue spine)
                                              │  user plays a row
                                              ▼
                     Is any source ranked above YouTube enabled and able to satisfy the request?
                         │ yes                                              │ no
                         ▼                                                  ▼
  TrackMatcher builds packaging-free queries (with and without artist)   YouTube stream path
  ─► each ranked source searched (in parallel) ─► candidates scored ─► best confident match
  ─► source.stream(match, request) ─► first playable answer that satisfies the request wins
                         │ nothing
                         ▼
                YouTube stream path (client-identity walk → NewPipe failsafe)
```
- Tracks from non-YouTube sources travel as `bitchord://source?…` URIs carrying title/artist/duration, because resolution runs on ExoPlayer's loader thread with only a `DataSpec`.
- YouTube tracks keep **bare video ids** everywhere (like button, lyrics, radio, canvas, scrobbler) — by the code's own admission, re-keying would break "half the app".
- Speculative **prefetch** only asks sources fast enough to answer in a round trip (JioSaavn ~0.4 s vs module indexes 7–13 s, per code comments).

## 5. Playback flow
- MediaItems carry custom URIs; a resolving data source picks the stream at open time.
- **Stream pinning** (`StreamChoice`): once a stream is chosen for an item, that choice is recorded so playback and the cache warm-up don't pick different copies and corrupt the same cache entry.
- **Fallback** (`PlaybackFallback`): when a source-backed item's chosen stream fails in the player, only YouTube is asked next (the failed source is not given a chance to return the same URL).
- **Quality upgrade** (`QualityUpgrade`): while a track plays on a lower-quality stream, a slower, exhaustive search runs; if a lossless (or materially higher-bitrate) copy of the *same duration* is found, playback **switches streams mid-track**.

## 6. Error / fallback behaviour
- A source returning *null* is a **miss** (not logged as failure); a throw is a failure caught per source so one bad source never crashes resolution.
- Health states distinguish **Unreachable** (keep enabled, retry later) from **Rejected** (credentials).
- Addons: retry with limits; "rate limiting BitChord" surfaced as a specific error; manual "Upgrade quality" clears cached empty answers.
- YouTube: multiple player-client identities tried in turn; 403 handling; NewPipe as last resort.
- Matching collisions (multiple releases with the same title/artist and no album to separate them) → treated as a conservative **miss**, staying on YouTube.

## 7. Quality handling
- `StreamRequest`: **Lossless**, **Capped(maxKbps)** (metered), **Best**; computed **per request from the connection in hand**, not once from Settings.
- `StreamFormat`: codec, kbps, sample rate, bit depth — **all nullable; "null means not stated"**; losslessness is decided by codec alone; reported numbers are later superseded by the decoder's measured numbers, and the two are compared to catch "source claims 24/192, sink runs 16/48".
- `belowRequest` flag marks a stream that is worse than asked for.
- Download quality is a separate setting from streaming ceilings.
- "Stats for nerds" shows codec/bit depth/sample rate.
- The "lossless" tier in practice comes from **addon/module sources** (see §9).

## 8. Authentication
- **YouTube Music:** in-app WebView loads the real Google login page (2FA/passkeys work); after redirect to YouTube Music, the user confirms the channel; the app captures the page's session (cookie jar + `ytcfg`) and stores it encrypted; supports multiple accounts and brand channels.
- **Discord** login for Rich Presence; **Last.fm/ListenBrainz** scrobbling.
- **Addons:** tokens may live in the addon URL path; logs redact them.

## 9. Addon / module architecture
| Mechanism | How it works | Status |
|---|---|---|
| **Addon** | A plain HTTP server: `GET /manifest.json`, `/search?q=`, `/stream/{id}` → JSON. Declared settings schema; host sends declared defaults as query params. "No code is downloaded or run." User-ordered priority. Code refers to an external addon spec and "reference host/web client". | Current, user-addable |
| **Module** | A module-index URL lists JavaScript plugins; each exports search and stream-URL functions; executed in pooled **QuickJS** VMs with only an injected `fetch`. Described in-app as able to "search and stream from services like Tidal, Qobuz, Apple Music and more". | Legacy (kept for old configs) |

Observed ecosystem (GitHub, 2026-10-02): a self-hosted addon that exposes **your own Plex/Jellyfin** library (legitimate use of the protocol); several "real FLAC"/hi-res addons. BitChord's own model comments record a live addon in September 2026 returning a **TIDAL Hi-Res DASH manifest URL** labelled as a TIDAL lossless stream. TIDAL's own SDK documentation states that full-length third-party playback is not publicly available (research note 2026-10-02-music-sources.md). The addon protocol is content-neutral; the ecosystem around it is not.

## 10. Ideas Podium should adopt (re-expressed independently)

| Idea | Why it's good | Podium form |
|---|---|---|
| Reported vs measured quality, nullable "unknown" | Honest labels; detects transcodes | `QualityReport(advertised, resolved, measured, verification)` — `PLAYBACK_TARGETS.md` §4 |
| Quality request computed per connection at request time | Network changes are real | `QualityPolicy.requestFor(purpose, network)` |
| Download quality independent of streaming ceilings | A file outlives the connection | Separate `Purpose.DOWNLOAD` |
| Miss ≠ failure | Health shouldn't punish "don't have it" | `ResolveOutcome.Miss` vs `Failed` |
| Unreachable ≠ Rejected | Different user remedies | `SourceHealth` states + circuit breaker |
| Title = identity + version + packaging; version markers must agree both ways | Prevents live/remix/sped-up mismatches | Podium `TrackMatcher` with its own lexicon (MusicBrainz-informed) |
| Conservative miss on ambiguous collisions | Better skip than wrong song | Matcher tier `AMBIGUOUS` → never auto-substitute |
| Packaging-free search queries (with/without artist) | Catalogues store titles differently | `MatchQueryBuilder` |
| Pin the chosen stream per queue item | Cache coherence, no flip-flopping | `ResolvedChoice` per `QueueUid` |
| Cache key independent of expiring URL | URLs rotate | `PlayableMedia.cacheKey` |
| User-ordered source priority | User intent decides | `SourcePriority` in settings |
| Log redaction of tokens in URLs | Privacy | Already in `security.md` |
| A declarative, code-free HTTP protocol for *user-owned* servers | Extensibility without executing code | Considered for P2 as "Personal server bridge", restricted to user-owned content (ADR-013 §Future) |

## 11. Ideas Podium should NOT adopt

| BitChord behaviour | Why not |
|---|---|
| One provider as an undeletable catalogue spine; its raw ids leak across features | Violates Podium's core rule that providers never leak into the app; makes provider replacement impossible |
| Client-identity walk, PoToken/BotGuard minting in a hidden WebView, player-cipher solving (InnerTubeX/NewPipe), age-restricted access via those tokens | These exist to defeat YouTube's protection and access controls; conflicts with the brief's §65 and YouTube policies (`SOURCE_CAPABILITY_MATRIX.md`) |
| Unofficial JioSaavn API | Undocumented provider API; its own UI warns of wrong-song matches |
| Executing third-party JavaScript modules | Remote code execution surface; modules described as streaming from paid services |
| Open addon ecosystem without provenance; addons relaying paid-service streams (e.g., TIDAL manifests) | Content provenance unknowable; relays a service's content outside its sanctioned channels |
| Mid-track stream swapping to a different source (`QualityUpgrade`) | Changes the audio under the listener on a duration-only identity check; risk of wrong recording; breaks gapless/analysis assumptions |
| "First acceptable answer wins" races for identity-sensitive substitution | Search ranking of another catalogue shouldn't decide identity |
| Matcher without ISRC/MBID evidence | Identifiers are the strongest identity signal when present |
| Monolithic playback service and global singletons | Hard to test; Podium uses modules + DI |
| Haze/Material 3 visual stack | Podium has its own design system (ADR-007) |
| YouTube downloads; Premium features (background play, offline) without subscription | Brief §65; YouTube ToS and API policies |

## 12. What would create GPL-3.0 obligations if copied
- **Any BitChord source file or substantial excerpt** (all GPL-3.0): including but not limited to `TrackMatcher` (its scoring constants, version-word list, title/artist regexes, parsing passes), `SourceResolver` logic, `SourceRegistry`, addon wire models, `QuickJsExecutor`, `StreamChoice`/`QualityUpgrade`, auth screens, taggers, lyrics providers. Translating or "lightly rewriting" such code can still be a derivative work.
- **Linking GPL-3.0 libraries** BitChord depends on — **NewPipeExtractor**, **InnerTubeX** — into Podium and distributing the result would, under the FSF's interpretation, require the combined work to be offered under GPL-3.0-compatible terms with Corresponding Source.
- Not GPL: quickjs-kt (Apache-2.0), Media3 (Apache-2.0), Haze (Apache-2.0) — but Podium doesn't need them for this purpose.
- Ideas, architecture, and behaviour described in prose here are not themselves copyrighted expression; Podium reimplements from its own specification (ADR-014). This is an engineering-risk statement, not legal advice.

## 13. What Podium reimplements independently
From Podium's own specs (`docs/architecture/*`), without consulting BitChord source during implementation: capability-faceted `MusicSource`, `SourceRegistry`, `SourceHealth` with circuit breaking, `StreamResolver` → `PlaybackTarget`/`PlayableMedia`, `TrackMatcher` (own lexicon derived from MusicBrainz style guidelines and Podium's test corpus; adds ISRC/MBID evidence and explicit/clean and re-recording handling), quality reconciliation, stream pinning, per-connection quality requests, connected-source/auth model, remote playback targets. Process and safeguards in ADR-014.
