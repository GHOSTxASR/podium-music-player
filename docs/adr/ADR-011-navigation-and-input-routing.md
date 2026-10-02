# ADR-011 — Navigation and wheel input routing

**Status:** Accepted · **Date:** 2026-10-02

## Context
The iPod model is a single stack of screens with one focused element, driven by a wheel whose meaning depends on context. Modern Android adds predictive back, process-death restoration, adaptive layouts, and alternative inputs (touch, keyboard, D-pad, rotary, TalkBack).

## Options considered
- Navigation-Compose 2.x (graph + routes): stack is library-owned; awkward for single-instance Now Playing and stack surgery. Rejected.
- **Navigation 3** (app-owned `SnapshotStateList` back stack, `NavDisplay`, scenes, entry decorators, predictive back). Chosen.
- Fully custom navigator. Rejected — Nav3 already gives us the owned-stack model plus predictive back and scenes.

## Decision
- Back stack = `rememberNavBackStack(Home)` of `@Serializable` `PodiumKey`s; survives process death.
- `Navigator` (single object) owns all stack mutations: `push`, `pop`, `popToRoot`, `showNowPlaying()` (single-instance: moves an existing NowPlaying to top instead of duplicating), `replaceTop`.
- **Wheel input routing:** raw input (`WheelGesture`, keys, rotary, mouse wheel) → `InputRouter` → normalised `PodiumInput` events (`Rotate(detents, velocity)`, `Press(button)`, `LongPress(button)`, `Hold/Release(button)`) → the **top-most registered `InputTarget`** (screen, or an overlay like a menu/sheet). Targets declare a `WheelContext` (`ListFocus`, `Volume`, `Scrub`, `Flow`, `Picker`, `Reorder`, `Lyrics`). Unhandled events fall through to global handlers: `Menu` → `Navigator.pop()`, `LongPress(Menu)` → `popToRoot()`, ⏯/⏮/⏭ → `PlaybackController`.
- Screens never interpret raw touches on the wheel; they receive semantic inputs. One wheel implementation, many contexts.

## Why
Matches the iPod's mental model exactly while keeping every alternative input on the same semantic path — the precondition for consistent behaviour and testability (inputs can be injected in tests).

## Tradeoffs
Screens must declare focus models explicitly (small upfront cost per screen; shared `FocusList` helper covers most).

## Future implications
Adaptive scenes (two-pane list/detail on tablets) are Nav3 `SceneStrategy`s; the input router targets the pane holding focus.
