# Interaction Model

**Date:** 2026-10-02 · ADR-011 · Code home: `:core:interaction` (`WheelGestureDetector`, `InputRouter`, `FocusList`, `HapticsAdapter`).

## 1. The model in one paragraph
There is always one **focused** element. **Rotate** moves focus (or adjusts the context's value). **Center** acts on the focused element. **Menu** goes back. **⏯ ⏮ ⏭** always control playback. **Long-press** reveals more. Every other input — touch, keyboard, D-pad, rotary crowns, mouse wheels, TalkBack — is translated into these same semantic inputs, so the product behaves identically however it's driven.

## 2. Inputs → semantic events

| Physical input | `PodiumInput` |
|---|---|
| Wheel ring drag (circular) | `Rotate(±n detents, velocity)` |
| Wheel zone tap: top / right / bottom / left | `Press(MENU / NEXT / PLAY_PAUSE / PREVIOUS)` |
| Wheel center tap | `Press(CENTER)` |
| Long-press any of the above (≥ system long-press timeout, ~400 ms) | `LongPress(button)`; for ⏮/⏭ followed by `Release(button)` |
| Keyboard ↑/↓ · PgUp/PgDn | `Rotate(∓1)` · `Rotate(∓visibleRows)` |
| Keyboard Enter / → | `Press(CENTER)` |
| Keyboard Esc / ← / Backspace | `Press(MENU)` |
| Keyboard Space / media Play-Pause | `Press(PLAY_PAUSE)` |
| Keyboard `[` `]` / media Prev/Next | `Press(PREVIOUS)` / `Press(NEXT)` |
| Keyboard Shift+Enter or context-menu key | `LongPress(CENTER)` |
| Keyboard Home | `LongPress(MENU)` (go Home) |
| Keyboard `/` or Ctrl+F | open Search |
| D-pad up/down/center/back | as keyboard ↑/↓/Enter/Esc |
| Rotary encoder (`onRotaryScrollEvent`), mouse wheel | `Rotate(±n)` (accumulate to detents: 1 notch = 1 detent) |
| System back gesture / button | `Press(MENU)` semantics via Nav3 predictive back |
| Touch on screen content | direct manipulation (§5), not routed through the wheel |
| TalkBack | standard semantics (§8) |

Hardware volume keys and Bluetooth media buttons go to the system / media session, never through `InputRouter`.

## 3. Wheel gesture detection (`WheelGestureDetector`)
Parameters are tokens (tunable on device; values are starting points):
| Token | Value |
|---|---|
| `wheel.detentDeg` | 18° (20 detents/rev; 15° felt twitchy on device) |
| `wheel.rotationSlopDeg` | 8° angular travel, or `touchSlop` along the tangent |
| `wheel.ringTouchBand` | from `d/2 − 4dp` to `D/2 + 12dp` |
| `wheel.reverseHysteresisDeg` | 9° (half detent) |
| `wheel.accel` | ×1 < 22 det/s · ×2 < 36 · ×4 ≥ 36 (ListFocus with ≥ 50 items only; shorter lists always move one item per detent) |
| `wheel.maxHapticRate` | 60/s |

Algorithm:
1. `down` inside the ring band → candidate gesture; record the zone under the finger; play `press` haptic and show zone pressed state **immediately** (tactility).
2. Track the angle `θ = atan2(y − cy, x − cx)` with unwrap. If |Δθ| > slop before `up` → enter **rotation**: cancel the press visual (no button fires), accumulate Δθ, emit a `Rotate` each time the accumulator crosses ±`detentDeg` (with hysteresis on direction change).
3. `up` without rotation → `Press(zone)` (fires on release so a rotation can start anywhere, including on legends). If held ≥ long-press timeout without rotation → `LongPress(zone)` (fires at the timeout, haptic `longPress`), then `Release` on up.
4. `down` inside the center circle → only press/long-press (rotation never starts from center).
5. Multi-touch: second pointer ignored.
6. Velocity: detents/s over the span of the detents in the last 120 ms (a lone detent has no rate). The tracker emits raw detents + velocity; the list applies `WheelAcceleration`, since only it knows its length.

Latency budget: detent emission → focus state change in the same frame; lens begins moving next frame (≤ 16 ms at 60 Hz, ≤ 8 ms at 120 Hz).

## 4. Contexts (`WheelContext`)

| Context | Rotate | Center | Long-press Center | Menu | Notes |
|---|---|---|---|---|---|
| `ListFocus` (Home, Music, all lists, Search results, Settings) | Move focus ±1 (accelerated in long lists); `IndexGlyph` HUD on sorted lists during fast spins | Activate: push destination / play track (queue = list context) / toggle setting | Context menu for focused item | Back | Boundary: lens squash + `boundary` haptic, no wrap |
| `Volume` (Now Playing default) | System media volume ±1 step per *k* detents (D-16) | Enter `Scrub` | Now Playing context menu | Back | VolumeBar shown while turning |
| `Scrub` (Now Playing) | Seek ±1% of duration per detent (min 1 s, max 10 s) | Commit & return to `Volume` | — | Return to `Volume` (does **not** navigate) | Auto-returns after 3 s idle; `step` haptic per detent |
| `Flow` (Album Flow) | Move album ±1 (accelerated) | Flip cover → track list; Center again plays the focused track | Album context menu | Back (flip back first if flipped) | |
| `Queue` (Up Next) | Move focus | Skip to focused item | Item menu: Play next, Move, Remove, Go to album | Back | |
| `Reorder` (Up Next / playlist edit) | Move the grabbed item ±1 position | Drop | — | Cancel (item returns) | Entered via item menu ▸ Move |
| `Lyrics` | Move line focus (auto-follow paused 4 s after input) | Seek to focused line's timestamp | — | Back | Plain lyrics: Rotate scrolls |
| `Picker` (option lists inside settings/sheets) | Move option | Choose & close | — | Close without change | |
| `Overlay` (menu/sheet open) | Move focus in overlay | Choose | — | Dismiss overlay | Overlays are separate `InputTarget`s on top of the stack |
| `TextEntry` (search field focused) | Move focus into results (field loses focus, keyboard hides) | — | — | Clear query; if empty → back | |

Global (when not consumed by the target): `Press(PLAY_PAUSE)` → toggle · `Press(PREVIOUS)` → restart if > 3 s else previous · `Press(NEXT)` → next · `LongPress(PREVIOUS/NEXT)` → scan while held · `LongPress(PLAY_PAUSE)` → Sleep sheet · `LongPress(MENU)` → Home (pop to root) · `Press(MENU)` at Home → no-op with `boundary` haptic (never exits the app; system back at Home exits per Android convention).

## 5. Touch on content (direct manipulation)
- **Tap a row** → focus moves there (lens animates) and the row activates on the same tap. (Two-step "tap to focus, tap to activate" would be hostile on a phone.)
- **Drag-scroll a list** → viewport scrolls; focus stays on its item. If the focused item leaves the readable region, the next `Rotate` first snaps focus to the nearest visible row (no jump far away), then moves.
- **Long-press a row** → focus moves there + context menu (same as `LongPress(CENTER)`).
- **Swipe left on a row** (Up Next, playlists) → reveals Remove (matches the top action of its context menu, per HIG).
- **Now Playing:** drag on the scrubber to seek; tap artwork → Lyrics (if available) else nothing; horizontal swipe on artwork → previous/next track (with 30% threshold and spring-back).
- **Album Flow:** horizontal fling scrolls with momentum and snaps to an album; tap the center cover → flip.
- **Mini player:** tap → Now Playing; ⏯ button toggles; swipe up → Now Playing.

## 6. Haptics mapping
| Event | Token |
|---|---|
| Wheel button down | `press` |
| Focus moved by rotation | `detent` (coalesced to ≤ 60/s; during acceleration ×2/×4 one haptic per emitted step, not per item) |
| List boundary / volume 0 or max / scrub at 0 or end | `boundary` |
| Volume step, scrub step, picker step | `step` |
| Long-press recognised | `longPress` |
| Like on/off | `toggleOn` / `toggleOff` |
| Added to queue / playlist, download complete (foreground) | `confirm` |
| Disallowed action (e.g., Download on a non-permitted track) | `reject` |
Touch taps on rows: no haptic (system default only). Haptics off → no other behaviour changes.

## 7. Debounce & robustness
- `Press(CENTER)` on the same target within 400 ms after an activation is ignored (prevents double-play / double-push).
- Navigation pushes ignore input to the outgoing screen once the transition starts; the incoming screen receives input immediately (you can rotate during the slide).
- Rapid rotation never queues work proportional to detents: focus index updates synchronously; scrolling targets the latest index (animations retarget, not chain).
- `Press(NEXT)` bursts: each press moves the queue pointer immediately; stream resolution only for the item that remains current for ≥ 150 ms (`playback-state-machine.md` §7).

## 8. Accessibility alternatives (no wheel required)
- **TalkBack:** the Wheel exposes five buttons ("Back", "Previous track", "Play" / "Pause" with state, "Next track", "Select") plus custom actions on the ring: "Move focus up/down" in lists, "Volume up/down" in Now Playing. Lists are fully navigable with standard swipe gestures; rows have proper roles, labels ("So What, Miles Davis, 9 minutes 22 seconds, downloaded"), and custom actions mirroring the context menu (Play next, Add to queue, Like, Download, Add to playlist).
- **Switch Access / keyboard / D-pad:** §2 mappings; focus order follows reading order; the focus lens follows keyboard focus.
- **Volume** is also adjustable with hardware keys; **seeking** with the scrubber's `setProgress` semantics.
- **Wheel hidden** (setting) is fully supported: all functions remain reachable via touch + title-bar + Now Playing controls.

## 9. Onboarding hints (first run, dismissible)
Three one-line hints shown in context the first time each applies: "Turn the wheel to move. Press the center to choose." · "Press and hold the center for more options." · "On Now Playing, the wheel changes volume. Press the center to scrub." No tutorial carousel.

## 10. Tests
- `WheelGestureDetector`: synthetic pointer streams (JVM) — slop, detent emission at boundaries, hysteresis, acceleration tiers, press vs rotate disambiguation, long-press timing, center-only press.
- `InputRouter`: target stack, consumption/fall-through, global handlers.
- `FocusList`: keep-in-view math, boundaries, snap-to-visible after touch scroll, saver/restore.
- Compose UI tests (Robolectric): inject `PodiumInput`s and assert navigation/focus/playback commands.
