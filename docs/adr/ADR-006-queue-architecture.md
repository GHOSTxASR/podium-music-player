# ADR-006 — Queue architecture

**Status:** Accepted · **Date:** 2026-10-02

## Context
Up Next must be first-class: Play Next, Add to Queue, context continuation, Autoplay, reorder, remove, clear, save-as-playlist, visible shuffle order, persistence across process death, and consistency with external controllers (notification, Bluetooth, Auto).

## Options considered
1. **Mirror the full queue into the Media3 playlist; `QueueManager` is the only writer.** Media3 executes (next/prev/gapless/notification timeline); Podium owns semantics.
2. Windowed player (current + next N), Podium implements next/prev itself. More control, but reimplements Media3 transport and breaks external controllers' view of the queue. Rejected.
3. Let Media3 own the queue entirely (UI reads Timeline). Rejected: no place for origin semantics (Playing Next vs context vs Autoplay), shuffle shown in UI order is awkward.

## Decision
- `QueueManager` (pure Kotlin in `player:api`, executed inside the service) holds `QueueState` = ordered `QueueItem(uid, trackId, origin, contextRef)` + `currentIndex` + modes. It is the **single writer**; every mutation is translated to minimal Media3 operations (`addMediaItems`, `moveMediaItem`, `removeMediaItems`, `replaceMediaItems`) and persisted (debounced 500 ms).
- **Origins:** `PLAY_NEXT`, `USER_QUEUED`, `CONTEXT`, `AUTOPLAY`. Insertion rules in `queue-and-autoplay.md`.
- **Shuffle is a queue transform**, not Media3's shuffle order: enabling shuffle reorders upcoming items (Fisher–Yates with a persisted seed, current item fixed); disabling restores original context order for items not yet played. Media3 `shuffleModeEnabled` stays false; external "set shuffle" commands are intercepted and routed to `QueueManager`.
- **Repeat** uses Media3's native repeat modes (OFF/ONE/ALL).
- Restoration: on cold start the persisted queue is rebuilt and prepared **paused** at the saved position. `onPlaybackResumption` serves the same snapshot to system "resume" (Android 13+ media controls, Bluetooth).

## Why
Media3 gives correct transport, gapless, and an accurate timeline to every controller; Podium gets Apple-Music-grade queue semantics without reimplementing playback.

## Tradeoffs
Two representations (domain queue, Media3 playlist) must stay in sync → enforced by a single writer, an invariant checker in debug builds (`assertQueueMirrorsPlayer()` after each mutation), and property tests.

## Future implications
Very large queues (≥ 5k items, "shuffle all songs") are added to the player in chunks of 500 to keep binder transactions small; the domain queue holds all items.
