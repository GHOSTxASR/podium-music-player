# Queue & Autoplay

**Date:** 2026-10-02 · ADR-006 · Code home: `player:api` (`QueueManager`, `AutoplayEngine`, pure Kotlin), executed in `PlaybackService`.

## 1. Queue model

```
 history (played)        current        Up Next ───────────────────────────────────────────────►
 [ … C3 C4 ]            [ C5 ]     [ PN2 PN1 ] [ UQ1 UQ2 ] [ C6 C7 C8 … ] [ AP1 AP2 … ]
                                    Playing Next  Your queue   Continue from <context>   Autoplay
```
| Origin | Created by | Insert rule |
|---|---|---|
| `PLAY_NEXT` | "Play Next" | Immediately after current (newest first). |
| `USER_QUEUED` | "Add to Queue" | After the last `PLAY_NEXT`/`USER_QUEUED` item (FIFO). |
| `CONTEXT` | starting playback from an album/playlist/list/search | Remaining items of the context, in context order (or shuffled). |
| `AUTOPLAY` | `AutoplayEngine` | Appended after the last non-autoplay item. Any user insertion lands *before* autoplay items. |

**Up Next UI** shows these sections with headers ("Playing next", "Your queue", "Continuing from *Kind of Blue*", "Autoplay") — headers only for non-empty sections.

## 2. Operations (all through `QueueManager`, single writer)

| Operation | Behaviour |
|---|---|
| `playContext(ctx, startAt, shuffle)` | Replace `CONTEXT` + `AUTOPLAY` items; **keep** `PLAY_NEXT`/`USER_QUEUED` items (Apple behaviour: your queue survives starting a new album). Setting: "Keep my queue when playing something new" (default On). |
| `playNext(tracks)` / `addToQueue(tracks)` | Insert per rules; confirmation HUD "Playing next" / "Added to queue". |
| `move(uid, to)` | Reorder within Up Next; moving an item across a section boundary converts its origin to the target section's origin. |
| `remove(uids)` | Remove; removing current = skip to next. |
| `skipTo(uid)` | Items between current and target move to history (not deleted), like iPod. |
| `clearUpcoming()` | Removes all Up Next except Autoplay suggestions are regenerated if autoplay is on. Confirmation required when > 10 items. |
| `saveAsPlaylist(name)` | Creates a Podium playlist from current + Up Next (excluding autoplay unless the user includes it). |
| `setShuffle(on)` | Shuffle transform (§3). |
| `setRepeat(mode)` | OFF / ALL / ONE. With ALL, the queue wraps at the end of non-autoplay items; autoplay is suspended. |

**Duplicates:** allowed (distinct `QueueUid`); the queue is a list of plays, not a set. "Add to Queue" of an item already in "Your queue" shows the HUD "Already in your queue — Add again?" (one-tap).

**Invariants (asserted in debug after every mutation, property-tested):**
1. Exactly one current item when non-empty.
2. Section order is PN* UQ* C* AP* in Up Next.
3. Media3 playlist `mediaId`s equal the domain queue's uids, in order.
4. `original_ordinal` is a permutation of context items.

## 3. Shuffle
- On: Fisher–Yates over Up Next `CONTEXT` items with seed `shuffle_seed` (persisted) — current item fixed in place; `PLAY_NEXT`/`USER_QUEUED` keep their order and stay first (they're explicit intent).
- **Spread rule:** after shuffling, a single pass swaps neighbours to avoid the same artist back-to-back when an alternative exists within the next 5 items (perceived-randomness fix).
- Off: restore `original_ordinal` order for not-yet-played context items, continuing after the current item's original position.
- Starting a context with shuffle (e.g., "Shuffle" on a playlist, Home ▸ Shuffle Songs) picks a random start item instead of item 0.

## 4. Persistence & restoration
Saved per `data-model.md` (queue tables): on every mutation (debounced 500 ms), every 10 s of playback, on pause, on `onTaskRemoved`/`onDestroy`. On launch the queue is restored **paused**. Large queues (> 500 items) are pushed into the player in chunks after the current ± 50 items are ready, so restoration never blocks first frame.

## 5. Autoplay

### 5.1 When it runs
`autoplay = on` ∧ repeat = OFF ∧ remaining non-autoplay Up Next items ≤ 1 → generate a batch of 10. Re-run whenever autoplay items remaining ≤ 2. Never runs during offline unless candidates are offline-playable.

### 5.2 Pipeline (deterministic & explainable)
```
Seeds ─► Candidate generators ─► Filters ─► Scoring ─► Diversity selection ─► Batch (+ explanation)
```
**Seeds:** current track + last 3 played + the context (album/playlist/artist).

**Candidate generators** (each tagged with its signal; all optional per capability):
| Signal | Source | Weight |
|---|---|---|
| `SOURCE_RELATED` | `MusicSource.related(seeds)` (Subsonic `getSimilarSongs2`; others when available) | 0.30 |
| `PLAYLIST_COOCCURRENCE` | Tracks sharing a user playlist with any seed (the user's own curation) | 0.20 |
| `SESSION_COLISTEN` | Tracks played within ±30 min sessions that also contained a seed (history) | 0.15 |
| `SAME_ARTIST` | Seed artists' other tracks (top tracks if source supports, else library) | 0.15 |
| `LIKED_AFFINITY` | Liked tracks sharing artist/genre/decade with seeds | 0.10 |
| `GENRE_ERA` | Library tracks with same genre and year ±5 | 0.10 |

**Filters (hard):** not in current queue (any section); not played in the last 50 history rows or last 3 h; playable now (connectivity + source health); not the same `isrc` as a recent play (cross-source duplicate); duration ≥ 30 s; not explicit if the user's explicit filter is on (P2).

**Scoring:** `score = Σ weight(signal) × strength(signal)` where strength ∈ [0,1] (e.g., source similarity rank → 1 − rank/limit; co-occurrence count normalised). Penalties: −0.15 per same-artist item among the last 5 played/selected; recency decay for anything played in the last 14 days `−0.1 × e^(−days/7)`.

**Diversity selection:** greedy pick of 10: highest score subject to (a) ≤ 2 tracks per artist per batch, (b) no consecutive same artist, (c) ≤ 4 from the seed album. Ties broken by a PRNG seeded with `hash(seedIds, yyyy-MM-dd)` → same inputs on the same day = same batch (reproducible bugs), different days = variety.

**Fallbacks:** if < 10 candidates: widen `GENRE_ERA` to genre only, then "liked tracks shuffled", then stop (show "Autoplay has nothing more to suggest" — never repeat).

### 5.3 Explanation & debugging
Each autoplay item stores its top signal ("Because you played *So What*", "From your playlist *Late Nights*", "Similar on Home server"). Up Next shows it as secondary text on autoplay rows. Developer settings include an **Autoplay inspector** listing candidates, signals, scores, and filter reasons for the last batch.

### 5.4 Feedback loop
Skipping an autoplay item within 30 s records a negative signal (−0.2 for that track for 30 days; −0.05 for its artist for 7 days). No other implicit tracking.

## 6. Tests
- Property tests (randomised op sequences: insert/move/remove/skip/shuffle toggles/repeat) asserting invariants §2.
- Shuffle: seed reproducibility; un-shuffle restores order; spread rule.
- Autoplay: golden tests with fixture libraries (expected batch for given seeds/date), "never repeats within window", "≤ 2 per artist", "empty candidates → stops", "offline → only offline-playable".
