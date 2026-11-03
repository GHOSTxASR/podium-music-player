# Animation System

**Date:** 2026-10-02, motion language 2026-10-08 (D-65) · Tokens live in `PodiumMotion` (`:core:designsystem`, `theme/Theme.kt`). The audit that led here: [research/2026-10-08-motion-audit.md](research/2026-10-08-motion-audit.md).

Podium is a small physical device, not an app with animations. Its motion has to do two things at once: answer on the frame the finger lands, and move the way a real object would — mass, a little inertia, friction, a settle — without ever becoming a cartoon or a presentation. Smooth is not slow.

## 1. Principles
1. **Nothing waits.** Input is never held until an animation ends; a running animation is interrupted or retargeted and converges on the latest intent. No `delay()` to look animated — the few waits that exist are choreography (§3) or avoid a flash (loading), and say so where they're written.
2. **Direct manipulation is the finger.** Anything under the finger or the Wheel is set on the frame the finger moves (drawn from state read in the draw phase); a spring only takes over when the finger lets go, keeping its speed.
3. **Moves leave on the press.** A choreographed move uses a curve that is already moving in its first frame and settles long and soft — never one that sets off from rest and sits still for the first frames.
4. **Spatial transitions explain the hierarchy.** Forward: the column in front leaves for the left box and the next one comes in from the right; back: the mirror. Never replaced by a generic fade.
5. **Not everything moves.** A light switches (the lit row); only the selector glides. The best motion goes unnoticed.
6. **Reduced motion** replaces movement with short cross-fades and stills ambient motion; it never removes information or direct manipulation.

## 2. The motion language
Seven kinds of motion, each with one meaning. Nothing borrows another's curve.

| Kind | Means | Implementation (`PodiumMotion`) | Settles in |
|---|---|---|---|
| **TACTILE** | a key under the finger | `pressIn()` spring 6000 / 0.9 down (no bounce); `pressOut()` 1500 / 0.6 up (≈ 0.4 % settle); `pop()` 1200 / 0.82 for something arriving under a press (context menu). `Modifier.pressFeedback(scale)` or `KeyTravel` | ~50 ms down, ~160 ms up |
| **MECHANICAL** | the Wheel's own parts | the band turns 1:1 with the finger (draw phase); `detent()` 1800 / 0.75 clicks it into the nearest detent on lift | ~155 ms |
| **FOCUS** | the selector moving through a list | `focus()` 1400 / 0.86 for a step; `focusFast()` 2400 / 1.0 when detents arrive < 90 ms apart (a spin), so the lens stays locked to the Wheel and stops dead | ~120 ms |
| **SPATIAL** | moving along the paper | tween `SpatialMillis` 420 ms on `Spatial` = cubic-bezier(0.2, 0.7, 0.2, 1); `spatial()` | 71 % at 100 ms, 92 % at 200 ms |
| **CONTENT** | the music changing in place | `contentIn()` 160 ms / `contentOut()` 90 ms (Standard); cover travel on a skip: 360 ms on `Spatial` | ≤ 360 ms |
| **DRAMATIC** | entering a piece of music | `dramatic()` 220 / 0.85: the cover settles into its place as Now Playing arrives | ~300 ms |
| **PERSONAL** | the object, its space, stickers | the fingers drive (snap per event); on release `settleIn()` 200 / 0.86 or `settleBack()` 300 / 1.0 with the fingers' velocity; `rearrange()` 220 / 0.88 for the space's own moves | ~380 ms |

Hierarchy: micro-interactions (TACTILE, FOCUS) are the fastest; navigation (SPATIAL) fast but spatial; major moments (DRAMATIC, the space) a little slower; gestures are as fast as the hand.

Early progress of the curves in use (one frame at 120 Hz is 8.3 ms):

| Curve | 8 ms | 17 ms | 33 ms | 50 ms | 100 ms | 200 ms |
|---|---|---|---|---|---|---|
| `Spatial`, 420 ms | 7 % | 14 % | 29 % | 42 % | 71 % | 92 % |
| `pressIn` | 14 % | 39 % | 78 % | 94 % | 100 % | — |
| `focus` | 4 % | 14 % | 38 % | 61 % | 95 % | — |
| `focusFast` | 6 % | 20 % | 49 % | 70 % | 96 % | — |
| `dramatic` | 1 % | 3 % | 9 % | 18 % | 48 % | 87 % |
| `settleIn` (from rest) | 1 % | 2 % | 8 % | 16 % | 44 % | 82 % |

### Other tokens
| Token | Duration | Easing | Use |
|---|---|---|---|
| `navigateOffset()` | spring 380 / 0.92 | — | Mini player in/out; Now Playing rising from it (Glass) |
| `fadeFast` | 120 ms | Standard (0.2, 0, 0, 1) | Glyph swaps, the progress ↔ volume swap |
| `fadeStandard` | 220 ms | Standard | Title fade with the paper |
| `atmosphere` | 900 ms | emphasized-decelerate | Ambient colour change |
| reduced cross-fade | 120–160 ms | — | Every move under reduced motion |

## 3. Choreography
| Moment | Motion |
|---|---|
| Wheel touch-down | The part under the finger goes down at once (TACTILE: centre to 0.955, quarters' legends to 0.9); the quarter's shade appears, feathered into the ring at its borders (D-103); haptic. Release: up with `pressOut`. |
| Wheel turn | Focus index updates synchronously at each detent (haptic at the detent); the lens glides on `focus`, or locks on with `focusFast` during a spin. On matte finishes the knurled band turns 1:1 with the finger and clicks into the nearest detent on lift (MECHANICAL). No fling, no coasting: the Wheel stops when the finger does. |
| Lit row | Switches instantly (text weight and light): a legend lights, it doesn't fade. Only the two rows that change recompose. |
| Next-column preview | Cross-fades with the focus on CONTENT (160 / 90 ms) so it arrives while the lens settles and never stacks during a spin. |
| Push / pop (the paper) | One SPATIAL move, 420 ms: the column in front arcs into the left box (forward) and shrinks to exactly the glimpse's size and place (D-63), its lit row on the box's middle (D-61); the next column grows out of the right box — from exactly the preview tile's frame (D-66). Back is the mirror. The glimpses arrive from 47 % (the column leaving is 92 % of the way there) over 240 ms, soft then sharp (D-62); the leaving column softens from 40 % (D-64) and fades from 57 %. The header title travels on the same curve. |
| Now Playing | The page arrives (Glass: rises from the mini player; Carbon and the instrument: the paper move) and the cover, the anchor, settles on its own spring (DRAMATIC): from 0.9 scale to its place, flat. Controls are live from the first frame. |
| Skip (⏭ / ⏮) | The cover travels off the display and the next one in, 360 ms on `Spatial` — it leaves on the press. Title and artist cross-fade. |
| Context menu | Arrives under the hold that asked for it: scale 0.96 → 1 and fade on `pop()`, interactive from its first frame; leaves at once (nothing lingers taking input). |
| Keys (transport, mini player, header back, keyboard, power, the space's keys) | TACTILE, from `Modifier.pressFeedback` / `KeyTravel`: glyphs 0.86–0.88, keys 0.94–0.97, large surfaces (the mini player) 0.985. |
| Volume | Scrubber → volume bar cross-fade (`fadeFast`); the bar follows detents. |
| Pinch into the space | The object follows the fingers from the moment the pinch is recognised, from wherever it is (no jump); closing by half the fingers' distance takes it all the way in; reversing the fingers reverses it. On release it goes where it was heading (depth + velocity × 0.12 s past 0.3 in, below 0.7 out after a spread) on `settleIn` / `settleBack`, keeping the fingers' speed. The settings page swings in from a 9° turn under the object's camera as it lands, tied to depth. |
| Spread back | The same, the other way: the object comes forward with the fingers. |
| Stars | ~42 specks and 4 glints, drifting a few dp a second; nearer specks gather in a touch as the object recedes and slide the other way as it comes close (parallax). Still under reduced motion. |
| Stickers (arranging) | The sticker is drawn where the finger is on the frame it moves (a live placement read in the draw phase); taken hold of, it lifts 3.5 % (TACTILE) and settles back on release; the store is written once, on lift. |
| Cover Flow | Covers placed by a continuous position (draw phase); the Wheel moves it on a spring; a drag follows 1:1 and a flick carries on to the cover it was heading for, keeping its speed. |
| Loading → content | The loading screen appears only after 150 ms (avoids a flash); the loading bar after 250 ms. |
| Wheel ↔ keyboard (D-45) | One spring (0.82, 240); keys' appearance read in the draw phase. |
| Boot (D-102) | One 3.55 s clock: the lens swells on an overshoot, breathes, resolves, the P spins to its edge, the wordmark unrolls from a cylinder and settles, then the camera dives into the O (eased in and out, exponential in scale) while the menu behind comes forward from 86 % into focus. Any Wheel press: a 260 ms dive. Reduced motion: still wordmark, 200 ms crossfade. |
| Album tone (Carbon, D-101) | All roles move together over 700 ms (Standard, Oklab) when the cover changes; the atmosphere crossfades over 900 ms; its strength follows the screen on the paper's curve. Read in the draw phase. |
| Menu defocus (Carbon, D-101) | The display behind a menu or notice blurs in over 220 ms and clears in 140 ms (Standard), read in the draw phase. |
| Now Playing's keys (D-101, D-103) | Transport keys go down on `pressFeedback` and their housing sinks in the draw phase (Carbon: shade and shadow; Glass: the glass's pressed state). |
| Now Playing's tray hiding (D-108, D-109) | With the setting on, 7 s after anything was last touched the tray translates down past the display's foot and fades out on a 420 / 0.92 spring, read in the draw phase. Everything above it — cover, facts, song, seek bar, and the orb or the visualiser — eases down on the same spring by half the space the tray leaves, so the screen settles in the middle of the taller display; both draw back up when the Wheel brings the tray back. A translation, so nothing is relaid out and the cover is never resized frame by frame. Never while the Wheel is in its action mode or the vinyl is open. |
| Now Playing's orb (D-107) | The play key is a creature (`OrbFace`, `OrbMind`), read entirely in the draw phase. Its **shape** crosses between round (playing) and a settled pebble (stopped) on a 420 / 0.74 spring — a recomposition, so the glass's lens and rim follow it. Everything else is per frame: the bass squashes the body and squishes the eyes, its own 2.6 s beat bobs and leans it (faster the louder the music), and the mood behind its happy squint follows the music over 500 ms out and 1100 ms back, never frame by frame. **Stopped** it holds one of eight postures (calm, alert, questioning, curious, annoyed, panicking, sad, dozing) and does one short thing every few seconds; postures cross over 600 ms and the side it leans to eases over 260 ms. **Carried away** (first at 22 s of playing, then every 42–78 s): a 220 ms crouch, a 820 ms whole turn forward, a 260 ms landing, stars for 760 ms, then delight. A **song change** sweeps its eyes the way the covers travel over 520 ms (D-78's 360 ms of travel, and a little to arrive with them); the **focus lens** pulls its gaze on the lens's own spring (1400 / 0.86). Frames are asked for only while it moves. |
| The seek bar's wave (Carbon and Glass, D-101, D-103) | The wave rises over a soft spring (90 / 1.0) when playing starts and settles flat (260 / 1.0) on pause; it travels a wavelength every 2.1 s while playing, frame by frame, and stops when paused. |
| The cover's light flowing (Carbon and Glass, D-103, D-104) | On Now Playing, while the music plays, two copies of the cover's field drift across the display and turn against each other: one turning once in 47 s and drifting up to 16 % × 13 % of the display on 15 / 19 s cycles, the other ×1.25, at 55 % strength, turning the other way once in 35 s and drifting up to 22 % × 18 % on 21 / 13 s cycles; each enlarged exactly as far as its drift needs. A new cover blends in over 1.8 s (an even ease, 0.42 / 0.58) over the mixture as it stands. A speed eases from 0 to 1 over 1.4 s when playing starts and back to 0 over 2.2 s when it stops (Standard), so it comes to rest rather than freezing. A frame clock runs only while it moves; read in the draw phase. |
| Status housings (D-103) | Each item has a presence, 0 to 1. Arriving: spring 420 / 0.55 (a small jelly overshoot as its housing stretches out to take it; the icon swells in from 60 % scale, fading with the square of its presence). Leaving: 620 / 0.9, back into the neighbour. A housing arriving beside another opens the gap with its own presence and stays joined by a metaball neck that thins as they part and pinches off near 12 % of the resting gap: it buds off that one's side. Alone, it grows as a bubble from a point, then stretches into its pill. An icon changing in place shrinks away as the new one swells in. Motions outlive a change of items, so a new arrival never cuts another short. |
| Vinyl (D-104) | One 2.4 s clock in: the cover lifts to the middle (0–0.5 s, ease-out, the display behind defocusing with it), the record slides up out of it (0.45–1.05 s), the sleeve falls away (ease-in) as the record comes to the middle and grows (1.05–1.65 s), sitting with a 2 % give; the tonearm swings on (1.7–2.26 s, Standard), the visualiser opens from a dot (1.85 s, 520 ms) and the words fade in (2.0 s). The record turns at 200°/s × its speed (up 0.7 s, down 0.9 s); a position jump over 400 ms turns it by the same (spring 140 / 1.0). A new song: 0.9 s roll on `Spatial`, the arm lifting to 60 % and back. One 2 s clock out: the arm up (340 ms), the sleeve up from below (0.3–0.8 s), the record gathering above it and sliding in (0.25–1.25 s), the sleeve home and the focus back (1.3–1.9 s). Reduced motion: 220 ms fades, the record still. |
| Carbon's Wheel (D-101) | The spun metal turns with the finger like the knurled band (MECHANICAL); its reflection doesn't turn with it, only with the phone's tilt (the glitter's tilt filter, D-44), at most ±50°, redrawn only when the tilt moves. |

## 4. Input rules
- **Holds don't fire under two fingers** (`LocalSeveralFingers`): a slow pinch beginning on a row or on the Wheel can't long-press it (open a menu, go Home, switch the display off) before it's recognised. On this phone the hold fires at ~300 ms; a slow pinch takes longer to be recognised.
- The pinch is watched first (Initial pass) and claimed only once it's clearly a pinch; the Wheel lets go of a finger whose change an ancestor consumed (D-54).
- An exiting overlay never takes input (the context menu leaves at once).

## 5. What we never animate
Ambient floats, shimmer skeletons, staggered list entrances, bouncing icons, animated gradients (the cover's light on Now Playing is the one exception, below), idle Wheel effects, the lit row's text. Ambient motion that exists: the previews' slow cycles (D-29), the space's stars, the status lights (charging breathes about 20 steps a second), playback progress (on Carbon and Glass, the seek bar's travelling wave while the music plays, D-101), the cover's light flowing behind Now Playing while the music plays (D-103) and lyrics (including their slight swell with the bass, D-82: a damped spring, read in the draw phase), the lyrics' orb (D-83: it drifts and turns slowly while the music plays, moved a little by it, and holds still when paused), Now Playing's own orb (D-107: it dances while the music plays, and while the music is stopped it holds a posture still and blinks or fidgets every few seconds — except the two moods meant to be seen, a freak-out it never holds still in and a sleeping orb's breath), the title bar's play light blinking while paused (D-107), and the vinyl's dot visualiser — on the vinyl's stage (D-104) and, on the nostalgic Now Playing, in the transport's place (D-109), where it falls to one still line and stops asking for frames as soon as the music is stopped.

## 6. Reduced motion
Triggered by "Remove animations" (animator duration scale 0), followed live (a `ContentObserver`, not read once at start). Then: paper moves and Now Playing → short cross-fades; lens jumps; previews don't cycle; the space settles with a 160 ms tween and its stars stand still; the status lights stay lit instead of breathing; the cover doesn't settle; the lyrics don't swell with the bass, and the Continuous line keeps only its innermost glow; the orb cross-fades with the words instead of the camera moving, and holds still; the cover's light on Now Playing holds still; the status housings fade in and out (140 ms) instead of budding; the vinyl fades in and out, its record still; Now Playing's tray goes and returns instantly instead of sliding; Now Playing's orb does nothing at all — no dance, no mood, no stars, eyes open and snapping to whatever they follow — though its shape still changes between playing and stopped and the title bar's light stays lit, which is information rather than decoration (D-107). Direct manipulation (Wheel, pinch, stickers) is unchanged. Haptics unaffected.

## 7. Performance rules
- Animate only `graphicsLayer` properties or draw-phase values; animated state is read in `graphicsLayer {}`, `offset {}`, `drawBehind {}` / `drawWithCache {}` — never in composition. Exceptions, each small: the scrubber height; the keyboard's opening width; the artwork frame's proportion.
- Drive presses and gestures from the input handler (an `Animatable` started undispatched, or a snapshot state written per event), not from `animate*AsState` keyed on state set in composition — that adds a frame and a recomposition.
- The device body (finish, grain, glitter) is drawn in its own cached layer (`CompositingStrategy.Offscreen`): a frame where anything else moves composites it as one bitmap (measured: idle GPU 19 → 9 ms per frame).
- Nothing allocates per frame in the space (brushes and shapes cached); stars and status lights redraw only themselves.
- Lens position is a draw-phase offset read from an `Animatable` → no recomposition of rows per frame. Glass's trailing edge (D-103) is a second `Animatable`, read in the same draw.
- The status housings' outline (capsules and necks, a path union) is rebuilt in the draw phase from the items' presences; layout reads them too, so an arriving item moves its neighbours without recomposing them. At rest nothing animates.
- The atmosphere stays one cached layer; while it flows (Now Playing, playing) that layer is redrawn every frame: two scaled draws of the 96 px field.
- Cover Flow renders ≤ 9 covers; they recompose only when the middle cover changes.
- The phone runs a non-debuggable build, compiled ahead of time (D-67): a debuggable build is never compiled and loses its JIT code with its process, so every fresh open stuttered.
