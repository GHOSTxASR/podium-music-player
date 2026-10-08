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
| **DRAMATIC** | entering a piece of music | `dramatic()` 220 / 0.85: the cover settles into its place and lean as Now Playing arrives | ~300 ms |
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
| Wheel touch-down | The part under the finger goes down at once (TACTILE: centre to 0.955, quarters' legends to 0.9); the quarter's shade appears; haptic. Release: up with `pressOut`. |
| Wheel turn | Focus index updates synchronously at each detent (haptic at the detent); the lens glides on `focus`, or locks on with `focusFast` during a spin. On matte finishes the knurled band turns 1:1 with the finger and clicks into the nearest detent on lift (MECHANICAL). No fling, no coasting: the Wheel stops when the finger does. |
| Lit row | Switches instantly (text weight and light): a legend lights, it doesn't fade. Only the two rows that change recompose. |
| Next-column preview | Cross-fades with the focus on CONTENT (160 / 90 ms) so it arrives while the lens settles and never stacks during a spin. |
| Push / pop (the paper) | One SPATIAL move, 420 ms: the column in front arcs into the left box (forward) and shrinks to exactly the glimpse's size and place (D-63), its lit row on the box's middle (D-61); the next column grows out of the right box — from exactly the preview tile's frame (D-66). Back is the mirror. The glimpses arrive from 47 % (the column leaving is 92 % of the way there) over 240 ms, soft then sharp (D-62); the leaving column softens from 40 % (D-64) and fades from 57 %. The header title travels on the same curve. |
| Now Playing | The page arrives (Glass: rises from the mini player; Carbon/Bone: the paper move) and the cover, the anchor, settles on its own spring (DRAMATIC): from 0.9 scale and 8° more lean to its place. Controls are live from the first frame. |
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

## 4. Input rules
- **Holds don't fire under two fingers** (`LocalSeveralFingers`): a slow pinch beginning on a row or on the Wheel can't long-press it (open a menu, go Home, switch the display off) before it's recognised. On this phone the hold fires at ~300 ms; a slow pinch takes longer to be recognised.
- The pinch is watched first (Initial pass) and claimed only once it's clearly a pinch; the Wheel lets go of a finger whose change an ancestor consumed (D-54).
- An exiting overlay never takes input (the context menu leaves at once).

## 5. What we never animate
Ambient floats, shimmer skeletons, staggered list entrances, bouncing icons, animated gradients, idle Wheel effects, the lit row's text. Ambient motion that exists: the previews' slow cycles (D-29), the space's stars, the status lights (charging breathes about 20 steps a second), playback progress and lyrics.

## 6. Reduced motion
Triggered by "Remove animations" (animator duration scale 0), followed live (a `ContentObserver`, not read once at start). Then: paper moves and Now Playing → short cross-fades; lens jumps; previews don't cycle; the space settles with a 160 ms tween and its stars stand still; the status lights stay lit instead of breathing; the cover doesn't settle. Direct manipulation (Wheel, pinch, stickers) is unchanged. Haptics unaffected.

## 7. Performance rules
- Animate only `graphicsLayer` properties or draw-phase values; animated state is read in `graphicsLayer {}`, `offset {}`, `drawBehind {}` / `drawWithCache {}` — never in composition. Exceptions, each small: the scrubber height; the keyboard's opening width; the artwork frame's proportion.
- Drive presses and gestures from the input handler (an `Animatable` started undispatched, or a snapshot state written per event), not from `animate*AsState` keyed on state set in composition — that adds a frame and a recomposition.
- The device body (finish, grain, glitter) is drawn in its own cached layer (`CompositingStrategy.Offscreen`): a frame where anything else moves composites it as one bitmap (measured: idle GPU 19 → 9 ms per frame).
- Nothing allocates per frame in the space (brushes and shapes cached); stars and status lights redraw only themselves.
- Lens position is a draw-phase offset read from an `Animatable` → no recomposition of rows per frame.
- Cover Flow renders ≤ 9 covers; they recompose only when the middle cover changes.
- The phone runs a non-debuggable build, compiled ahead of time (D-67): a debuggable build is never compiled and loses its JIT code with its process, so every fresh open stuttered.
