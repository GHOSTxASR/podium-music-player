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

### 2.1 Typing: the Podium keyboard (D-45)
Text is typed in `PodiumTextField` (core:designsystem), the only text field. Settings ▸ Keyboard picks the keyboard: **Podium** (default) or **Phone**.

- **Opening.** Focusing a field opens a `KeyboardSession` on the shell's `KeyboardHost`. This happens on a tap on the field, or when the field's screen focuses it (Search does on arrival). Podium's field never starts an input connection, so the phone's keyboard never appears.
- **The morph.** `WheelKeyboard` sits in the Wheel's slot.
  - Opening, from one progress value (spring, damping 0.82, stiffness 240): the Wheel turns up to 150°, shrinks to 78 % and fades out by halfway. The piece holding it widens from the Wheel's diameter to the body's width less 12 dp a side, its corners going from a circle to 26 dp (6 dp on matte finishes). The keys rise from the panel's middle outwards, each fading in, scaling up from 60 % and lifting 10 dp.
  - Closing runs the same path backwards. Reduced motion uses a 160 ms tween.
  - The power button and indicator lights fade aside while the keyboard is open.
- **Closing.** The close key (bottom right), Back, the field losing focus, or the field leaving the screen. The keyboard folds back into the Wheel.
- **While open.** The Wheel is gone, so its inputs are too. Touch on the screen still works: results can be scrolled, and tapping one opens it as the keyboard folds away with the screen. The action key (Search) clears the field's focus, so the Wheel comes back to browse the results.
- **Keys** (`KeyboardLayouts`, pure):
  - Letters page: QWERTY, the second row indented half a key. Shift and Delete flank the third row. The bottom row is 123, Space, full stop, the action word (Search or Done, from the field) and close.
  - Numbers and symbols page: the same shape, with abc back to letters.
  - A hex page for colour codes (digits, A–F, Delete, Done, close).
  - Every row of a page fills the same width.
- **Typing** (`KeyboardEditor`, pure):
  - A key types on release, so a finger can slide off a key to cancel it.
  - Shift capitalises the next letter. Two taps within 350 ms lock capitals until the next Shift.
  - Delete removes a whole character (never half an emoji). Held, it repeats after 420 ms, every 60 ms, and stops when the finger lifts or slides off.
  - Fields can cap the length and accept only some characters (the hex field: six hex digits).
  - Every key gives the Wheel's detent haptic and dips to 94 % while pressed.
- **Falls back to the phone's keyboard** when Settings says Phone, when the Wheel's column is narrower than 300 dp (landscape), inside a miniature, and wherever no `KeyboardHost` is provided.
- **Accessibility.**
  - Each key is a button named for what it does: the letter, "Shift", "Shift, on", "Caps lock, on", "Delete", "Space", "Numbers and symbols", "Letters", "Search"/"Done", "Close keyboard".
  - The field exposes its text and accepts text set directly by an accessibility service.

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

Global: hold ⏯ switches the device off (as on the original); any press wakes it.

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
| `Volume` (Now Playing default) | System media volume ±1 step per *k* detents (D-16) | Enter `Scrub` | More menu | Back | VolumeBar shown while turning |
| `Scrub` (Now Playing) | Seek ≈ 0.5 % of duration per detent (0.5–3 s), ×2/×5/×10 at 11/20/32 detents/s | Enter `Actions` | More menu | Return to `Volume` (does **not** navigate) | Auto-returns after 4 s idle |
| `Actions` (Now Playing) | Move the focus lens across shuffle, repeat, favorite, Up Next, More | Activate the focused action | More menu | Return to `Volume` | Auto-returns after 6 s idle (iPod: Center cycles the Now Playing items) |
| `Queue move` (Up Next ▸ song menu ▸ Move) | Move the lifted song among upcoming songs (preview) | Place it (`QueueManager.move`) | — | Cancel | Section follows its new neighbours, as in the queue |
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

### 5.1 Focus geometry (D-40)
The focus is derived only from what the list measured — the readable region (the viewport between the content paddings), the rows' offsets and sizes, and the scroll position — never from fixed coordinates (`FocusGeometry`, `FocusListState.keepFocusedInView`, `FocusList`).

| Situation | Paper lists (`FocusScroll.CENTRE`) | Plain lists — menus (`FocusScroll.EDGE`) |
|---|---|---|
| Middle of a long list | the focused row rides at the readable region's middle (the arc's apex); the list scrolls one row per detent | the focus moves; the list scrolls only to keep one neighbour each side in view |
| Near the start / end | the list stops at its end; the focus travels up / down to the real edge | same |
| First / last row focused | flush with the region's top / bottom: never partly under the title or the mini player, no empty space beyond | same (this was the bug: the first and last rows were exempt from keep-in-view) |
| The list fits | starts at the top of the readable region; no scrolling | same |
| Fast spin outruns the list | jump (`scrollToItem`), then place | same |

- **The lens** sits on the focused row and slides between rows in content space, so it is glued to the rows while the list scrolls; it is clipped to the readable region and never clamped to a band. Reduced motion: it jumps; a move of more than three rows jumps.
- **The ends** fade and soften only where the list continues beyond them (`FocusGeometry.endStrength`), ramping in over the first row of hidden content.
- **Touch:** unchanged (§5). A tap focuses a row, which then rides to the middle like any other focus move.
- Tests: `FocusGeometryTest` (pure), `FocusListGeometryTest` (Robolectric, 1/2/3/5/10/50 rows, first/middle/last and back, Carbon/Bone/Glass, phone and small screens, long titles; asserts the focused row is fully inside the readable region, no row is clipped, flush ends and the centred middle).

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

Settings (D-32): **Haptics** (on by default; on top of the system touch-feedback setting) and **Click sound** (off by default, D-08). The click pairs with the haptics: `detent` and `step` play a short tick (`res/raw/podium_click.ogg`, rate-limited to one per 24 ms so a fast spin purrs), `press` a firmer click (`podium_press.ogg`). Both sounds are original and synthesized; they play as UI sounds and stay quiet on silent/vibrate. Code: `Feedback`/`LocalFeedback` and `Clicker` in `:core:interaction`; `DeviceSounds` in the app.

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
- Keyboard (D-45): `KeyboardEditorTest` (JVM) covers typing, shift and caps lock, whole-character delete, the hex field's limits and row widths. `WheelKeyboardTest` (Robolectric, native graphics) focuses a field, types "Hi 5" with the keys across pages, deletes, closes, and saves the morph's frames in Steel, Carbon and Glass.
- `WheelGestureDetector`: synthetic pointer streams (JVM) — slop, detent emission at boundaries, hysteresis, acceleration tiers, press vs rotate disambiguation, long-press timing, center-only press.
- `InputRouter`: target stack, consumption/fall-through, global handlers.
- `FocusList`: keep-in-view math, boundaries, snap-to-visible after touch scroll, saver/restore.
- Compose UI tests (Robolectric): inject `PodiumInput`s and assert navigation/focus/playback commands.
