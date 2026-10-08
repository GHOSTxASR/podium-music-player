# Motion audit

**Date:** 2026-10-08 · **Scope:** every animation, gesture and press in the app as it stood after the
2026-10-07 pass (D-50 – D-64), before the motion pass (D-65). Read with `animation-system.md` (the
motion language that came out of it).

**Outcome:** D-65 (the motion language and the changes below), D-66 (the right box hands over frame
to frame), D-67 (the phone's build isn't debuggable); measured results in `performance.md` §6.

**Method.** Every `animate*`, `Animatable`, `spring`, `tween`, `keyframes`, `AnimatedVisibility`,
`AnimatedContent`, `Crossfade`, `Transition`, `graphicsLayer`, `pointerInput`, gesture detector and
`delay` in `app`, `core` and `feature` was read in place. Curves were tabulated (progress after
1, 2, 4, 6, 12, 18, 24 and 36 frames at 120 Hz) rather than judged by eye; the Nothing Phone (3a)
runs at 120 Hz, so one frame is 8.3 ms. The question for each was the brief's: does it start on
the frame the finger lands, does it say something physical, and is it the right size for how often
it happens?

**How much of a move is done, early on** (the numbers that drove most decisions):

| Curve (as used) | 8 ms | 17 ms | 33 ms | 50 ms | 100 ms | 200 ms |
|---|---|---|---|---|---|---|
| Paper move: `Smooth` (0.3, 0, 0.1, 1), 560 ms | 0.1 % | 0.4 % | 1.6 % | 4.0 % | 22 % | 67 % |
| Cover travel: FastOutSlowIn, 460 ms | 0.1 % | 0.3 % | 1.3 % | 3.1 % | 17 % | 68 % |
| `press` spring 2000 / 0.7 (press *and* release) | 5.8 % | 19 % | 53 % | 80 % | 105 % | 100 % |
| `navigate` spring 380 / 0.92 | 1.2 % | 4.3 % | 14 % | 27 % | 61 % | 93 % |
| Space enter 120 / 0.82 (from rest) | 0.4 % | 1.5 % | 5.4 % | 11 % | 33 % | 72 % |
| Space return 140 / 1.0 (from rest) | 0.5 % | 1.7 % | 6.0 % | 12 % | 33 % | 68 % |

A full-width column moving 4 % in its first 50 ms has hardly moved for six frames after the press:
that is the "touch, pause, then glide" the brief describes, even though the curve itself is smooth.

---

## 1. The Wheel

**Animation:** Center press
**Trigger:** touch-down on the center
**Current implementation:** `animateFloatAsState(if (pressed == CENTER) 0.96 else 1)`; `pressed` is a
`mutableStateOf` also read in composition (glass pressed tint, the solid finishes' face colour)
**Current duration / easing / spring:** `press` spring 2000 / 0.7, for both press and release
**Current visual result:** the center sinks with a 5 % overshoot past its pressed size, and springs back
**Problem:** the press itself bounces (0.7 damping), so pressing feels springy rather than solid; the
target is set by recomposition, so the motion starts a frame after the touch; every press and release
recomposes the whole Wheel
**Recommended change:** TACTILE: press-in 6000 / 0.9 (no bounce, ~50 ms), release 1500 / 0.6 (a
settle of about 0.4 %), driven from the gesture handler by an `Animatable` read in the draw phase
**Reason:** a key goes down hard and comes up with a whisper of spring; nothing waits for composition

**Animation:** Ring buttons (MENU, ⏭, ⏯, ⏮)
**Trigger:** touch-down on a quarter of the ring
**Current implementation:** the legend's `graphicsLayer` scale jumps to 0.9 while `pressed == button`; the
quarter's shade (`drawArc`) appears at once
**Current duration / easing / spring:** none (a jump down, a jump back)
**Current visual result:** a 10 % flick of the legend
**Problem:** a 10 % jump reads as a flicker; there is no release at all
**Recommended change:** TACTILE, as the center (to 0.92); the shade stays instant
**Reason:** immediate feedback is kept (the press-in reaches 77 % in 33 ms) but it now reads as a key

**Animation:** Knurl turn (matte finishes)
**Trigger:** each detent
**Current implementation:** `spinTarget += 18°` (a `mutableFloatStateOf` read in composition as a
`LaunchedEffect` key), then `spin.animateTo(spinTarget, spring(900, 0.9))`
**Current duration / spring:** ~170 ms settle per step
**Current visual result:** the band clicks round 18° per detent, a little after the finger
**Problem:** the whole Wheel recomposes on every detent, and the animation starts a frame later still; the
band moves in steps that trail the finger, so during a spin it lags behind the hand turning it
**Recommended change:** MECHANICAL: the band turns 1:1 with the finger's angle while touched (draw
phase only), and settles into the nearest detent when the finger lifts (detent spring 1800 / 0.75: a
small click into place)
**Reason:** "the wheel has weight" and "precise, not slippery": the material moves with the hand and only
the settling is animated

**Animation:** Finger highlight, zone shade, detent haptic
**Trigger:** pointer moves / down / each detent
**Current implementation:** draw-phase reads; haptic fired at detent time
**Problem:** none
**Recommended change:** keep
**Reason:** already direct

**Kept as tuned:** 18° detents (20 a turn), 8° slop before a touch becomes a turn, 9° hysteresis on
reversing, acceleration only in lists of 50+ at ≥ 22 detents/s. The tracker is precise and has no
inertia: the list never moves after the finger stops, which is right for a click wheel (no fling).

## 2. Focus and lists

**Animation:** Focus lens glide
**Trigger:** a detent (focus moves)
**Current implementation:** `Animatable` lens position; `focus` spring 1400 / 0.86 for every move; jumps
over 3 rows snap; on the paper the list scrolls from the lens's own frames (D-49)
**Current duration / spring:** ~120 ms settle
**Current visual result:** a soft glide per detent; retargets keep their speed
**Problem:** at spin speed (detents 40–70 ms apart) every move uses the same soft spring, so the lens
runs half a row behind the Wheel and swims as the spin stops. `focusFast` existed in the tokens for this
and was never used
**Recommended change:** FOCUS: one detent glides on `focus`; moves arriving within 90 ms of the last
use `focusFast` (2400 / 1.0, no overshoot), so a spin stays locked to the Wheel and stops dead
**Reason:** "rapid rotation should feel different from tiny movement"; "stopping should feel natural"

**Animation:** Lit row (text weight and colour)
**Trigger:** a detent
**Current implementation:** each row reads `focusedIndex` in composition
**Problem:** the change itself is right (a legend lights, it doesn't fade), but every visible row
recomposes on every detent
**Recommended change:** keep it instant; only the two rows that change recompose (`derivedStateOf`)
**Reason:** instant is the honest motion for a light; the work per detent drops from ~10 rows to 2

**Animation:** Next-column preview (right box)
**Trigger:** focus moves
**Current implementation:** `AnimatedContent`, fade in 320 ms, out 200 ms (FastOutSlowIn)
**Current visual result:** the preview catches up long after the lens has landed
**Problem:** during a spin every detent starts a fresh 320 ms fade, so several previews (each loading
artwork) are composed at once and the right box is a blur of half-faded covers
**Recommended change:** CONTENT, fast: in 160 ms (Standard), out 90 ms
**Reason:** the preview belongs to the focus; it should arrive while the lens settles, and old ones
should get out of the way

**Kept:** the paper's bend, fade and softening of rows near the ends (draw/layout phase, per scroll
frame); plain lists' `keepFocusedInView` on the `focus` spring; the marquee (D-46).

## 3. Navigation along the paper

**Animation:** Forward / back move (D-29, D-60 – D-64)
**Trigger:** Center on a row, Menu, a tap
**Current implementation:** `keyframes` arc + `scaleIn`/`scaleOut` + fades, every part on
`PodiumMotion.Smooth` (0.3, 0, 0.1, 1)
**Current duration:** 560 ms; glimpses fade in from 347 ms; the leaving column blurs from 308 ms
**Current visual result:** a smooth arc into the boxes, with no stop part-way (D-60 fixed that)
**Problem:** the curve leaves at rest: 1.6 % of the way after 33 ms, 4 % after 50 ms. After a press the
column sits still for four to six frames, then glides: the move reads as a delay. 560 ms for a move
made dozens of times a session is a presentation, not a device. Interrupted (Menu pressed mid-move),
each part restarts a full 560 ms
**Recommended change:** SPATIAL: the same arc and choreography on one curve that leaves at once and
settles softly, cubic-bezier (0.2, 0.7, 0.2, 1), over 420 ms: 14 % at 17 ms, 29 % at 33 ms, 71 % at
100 ms, 92 % at 200 ms. Glimpses, the departure blur and the leaving column's fade re-timed to where
the column now is on that curve
**Reason:** the move starts on the frame of the press and keeps the long, soft finish the listener
asked for in D-60; it is the "smooth, not slow" the brief asks for

**Animation:** Header title
**Trigger:** navigation
**Current implementation:** slide (half its width) on the `navigate` spring 380 / 0.92, fade in 220 ms,
out 120 ms
**Problem:** a different curve from the column it names: it starts later (4 % at 17 ms) and settles
at a different time, so title and column drift apart
**Recommended change:** SPATIAL, the column's own curve and duration
**Reason:** one move, one motion

**Animation:** Now Playing (Glass): rise from the mini player
**Trigger:** choosing a song, the mini player
**Current implementation:** `slideInVertically` on `navigate` + fade in 200 ms; what's beneath fades
(260 ms) and shrinks to 0.97; back reverses
**Problem:** the whole page arrives as one sheet: the artwork — the screen's anchor — arrives like any
other pixel, so it reads as "replace the screen"
**Recommended change:** DRAMATIC: the sheet rises as before; the artwork settles into place on its own
(scale 0.9 → 1 and its lean 34° → 26°, spring 220 / 0.85) and the words and controls follow 60 ms
behind it
**Reason:** "I selected this piece of music and entered it": the record arrives, then its details

**Animation:** Now Playing (Carbon, Bone)
**Trigger:** as above
**Current implementation:** a paper move like any column
**Recommended change:** the same artwork settle inside the move
**Reason:** the same moment means the same thing in every finish

**Kept:** the mini player's show and hide; power off (a dim, a beat, black: the device's own
behaviour); the boot sequence (a deliberate start-up, its waits are choreography); the loading screen
and the loading bar waiting 150 / 250 ms before showing (they exist to avoid a flash).

## 4. Now Playing

**Animation:** Cover change on skip (travel)
**Trigger:** ⏭ / ⏮
**Current implementation:** `slideIn`/`slideOut` across the display, FastOutSlowIn
**Current duration:** 460 ms
**Problem:** 3 % of the way after 50 ms: a skip shows nothing for six frames; rapid skips stack
460 ms moves
**Recommended change:** CONTENT on the SPATIAL curve, 360 ms
**Reason:** a skip is a push; the cover should leave on the press

**Animation:** Cover change (no travel)
**Trigger:** the song changes
**Current implementation:** fade in 300 ms after a 60 ms delay, scale and slide springs; out 180–240 ms
**Problem:** the 60 ms delay holds the new cover back for no reason
**Recommended change:** no delay, fade in 200 ms
**Reason:** respond now

**Animation:** Action lens (Now Playing's action row)
**Trigger:** Wheel in action mode
**Current implementation:** `animateDpAsState` read through `Modifier.offset(x = lensX)`
**Problem:** the offset is read in composition, so the action row recomposes every frame of the glide
**Recommended change:** read it in the placement lambda (`offset { }`)
**Reason:** same motion, no per-frame composition

**Animation:** Transport (Glass glyphs) and keys (Carbon, Bone)
**Trigger:** touch
**Current implementation:** glyphs: no visual feedback at all (haptic on release); keys: the background
darkens while pressed
**Problem:** a touch on the main controls shows nothing until the music changes
**Recommended change:** TACTILE (glyph to 0.88; keys to 0.97 alongside the darkening)
**Reason:** every press gets an immediate answer

**Kept:** the pause "breath" (0.93, 240 / 0.8; a deliberate state change), title/artist cross-fade,
progress ↔ volume cross-fade (120 ms), the artwork frame's proportion change (layout per frame, but
only when a song's proportions differ), artwork fading in as it decodes.

## 5. Overlays, keys and controls

**Animation:** Context menu
**Trigger:** hold Center
**Current implementation:** none: composed and removed in one frame
**Problem:** it pops into existence with no relation to the press that made it
**Recommended change:** TACTILE entrance: scale 0.96 → 1 and fade in over ~140 ms, interactive from its
first frame; a 90 ms fade out during which it no longer takes the Wheel or touches
**Reason:** continuity without a single frame of waiting; dismissal never holds input hostage

**Animation:** Keyboard (Wheel → keyboard, D-45)
**Trigger:** a field opens
**Current implementation:** one spring 240 / 0.82; keys' appearance computed in composition
**Problem:** all ~35 keys recompose on every frame of the opening
**Recommended change:** keep the motion; read the appearance in the draw phase
**Reason:** performance only

**Animation:** Keyboard key, power button, mini player, header back, space keys
**Trigger:** touch
**Current implementation:** keys and power: a scale jump or `press` spring; mini player, header back and
the space page's keys: no visual feedback
**Recommended change:** TACTILE everywhere, from one shared modifier
**Reason:** one physical vocabulary for every key

**Kept:** the accessories' 120 ms wait before fading back after the keyboard folds (choreography: they
come back once the keyboard is out of the way).

## 6. The physical space, pinch and stickers (D-54 – D-56)

**Animation:** Pinch into the space
**Trigger:** two fingers closing on the object
**Current implementation:** claimed after 40 dp and 22 % closer; then `depth = closer / (0.6 × start)`,
set by `scope.launch { depth.snapTo(...) }` per event
**Problem:** at the moment the pinch is recognised `closer` is already ≥ 22 % of the start, so depth
jumps from 0 to about 0.37 in one frame: the object lurches back, then follows. A pinch caught during
the return jumps the same way from wherever the object was
**Recommended change:** PERSONAL: measure from the fingers' distance at the moment the pinch is claimed,
and from the depth the object already has: it never jumps, it follows from where it is
**Reason:** "I'm moving the object"

**Animation:** Pinch release
**Trigger:** fingers lift
**Current implementation:** past 0.3 → enter (spring 120 / 0.82, ~550 ms), else back (140 / 1.0, ~560 ms),
both from rest
**Problem:** the decision ignores how the fingers were moving (a quick flick in that ends at 0.25 springs
back), and both springs start from rest, so the object stalls at the instant of release before moving
off — the velocity of the hand is thrown away
**Recommended change:** decide on where the motion was heading (depth + velocity × 0.12 s); hand the
finger's velocity to the spring; settle 200 / 0.86 (in), 300 / 1.0 (back)
**Reason:** continuity of momentum; "spring toward final state or spring back depending on progress and
velocity"

**Animation:** Spread to return
**Trigger:** two fingers opening on the object in its space
**Current implementation:** recognised (40 dp, 30 %), then a fixed `leave()`
**Problem:** a fixed animation set off by a gesture, not the gesture
**Recommended change:** the spread drives the object back toward flat; release decides as above
**Reason:** the same gesture in both directions

**Animation:** Settings page
**Trigger:** depth 0.5 → 1
**Current implementation:** slides in from the right with depth (translation and alpha), scales with
`zoom`
**Problem:** flat: it slides across the space rather than occupying it
**Recommended change:** the page swings in from a slight turn (8°) under the same camera as the object
and loses it as it lands; still tied to depth, so it follows the pinch
**Reason:** "the panel should feel like it occupies the same space as the device"

**Animation:** Stars
**Trigger:** while the object is out
**Current implementation:** 42 points and 4 glints, drift 1.5–4.5 dp/s, one Canvas redrawn each frame
**Problem:** a radial-gradient brush and four paths are allocated every frame; no depth relation to the
object; they drift under reduced motion
**Recommended change:** build the brush and glint shapes once; a slight parallax against the object's
pose (nearer specks move more); still under reduced motion
**Reason:** depth, not decoration; nothing allocated per frame

**Animation:** Sticker move / resize / turn
**Trigger:** one or two fingers while arranging
**Current implementation:** each pointer event writes the store's `StateFlow`; both sticker layers
collect it, recompose and redraw
**Problem:** a flow hop and a recomposition between the finger and the pixels (up to a frame behind),
and a new placements list per event
**Recommended change:** PERSONAL: the gesture writes a live placement read only in the draw phase (the
same frame, no recomposition); the store is written once when the fingers lift. A sticker taken hold
of lifts a hair (1.03) and settles back down when let go (TACTILE)
**Reason:** "finger → sticker with essentially zero perceived lag"; "attached to the Podium"

**Kept:** zoom (Podium | Settings) and the editing pose (springs of ~350 ms, retargetable); the slab
edge drawn with depth.

## 7. Elsewhere

| Animation | Current | Verdict |
|---|---|---|
| Cover Flow covers | position read in composition: the whole stage recomposes per frame; a drag ends on the nearest cover with no velocity | read in the draw phase; release flings to the cover the drag was heading for |
| Atmosphere colour | 900 ms, emphasized decelerate | keep (ambient, follows the artwork) |
| Device body finish | 420 ms colour tween | keep (previewing finishes) |
| Status lights | battery breathe / blink, disk pulse | keep (status, not decoration) |
| Preview cycles (artwork, carousel, instrument) | slow cycles every 3–4 s | keep (D-29; the only ambient motion, off with reduced motion) |
| Lyrics | colour cross-fades, words fading in as sung | keep |
| Tour | slow camera springs, fades | keep (once, deliberate) |
| Goodbye | dark 420 ms, words, a 1 s hold | keep (the end) |

## 8. Reduced motion

`PodiumTheme` reads `ANIMATOR_DURATION_SCALE == 0` once, when the theme is first composed: switching
"Remove animations" while Podium runs changed nothing until a restart. Compose itself also scales its
animations by that setting, so most motion already collapsed; what remained were the space's stars
(drifting), the pinch release and the space's springs, which never looked at the flag.
**Change:** observe the setting live; under reduced motion the stars stand still, the space settles with
a short tween, the paper still cross-fades (as before) and direct manipulation (Wheel, pinch, stickers)
is unchanged — it is the finger, not an animation.

## 9. Performance findings

| Where | Per | Finding | Change |
|---|---|---|---|
| Wheel | press, detent | whole `PodWheel` recomposes (`pressed`, `spinTarget` read in composition) | draw-phase state |
| Lists | detent | every visible row recomposes | `derivedStateOf` per row |
| Now Playing action lens | frame | offset read in composition | `offset { }` |
| Cover Flow | frame | whole stage recomposes | draw-phase transforms |
| Keyboard opening | frame | ~35 keys recompose | draw-phase appearance |
| Sticker arranging | pointer event | flow → recomposition of two layers | draw-phase live placement |
| Space stars | frame | brush and paths allocated | cached |
| Paper rows near the ends | scroll frame | a `BlurEffect` per row per frame (GPU blur) | kept (D-49 look); measured |
| Column departure / glimpse arrival | ~250 ms per move | full-column blur per frame (D-62, D-64) | kept; shortened with the move |

No database, network, bitmap or sticker work runs on the main thread during any of these interactions:
sticker images decode on IO, the sticker index writes on its own executor, artwork loads through a
suspending loader, the sticker cut-out, border and preview run on `Dispatchers.Default`. The one piece of pixel work in
composition is the atmosphere (a 32 × 32 sample of the cover), once per song change, never during a
gesture; left as it is.
