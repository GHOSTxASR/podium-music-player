# Animation System

**Date:** 2026-10-02 · Tokens live in `PodiumTheme.motion` (`:core:designsystem`). HIG: motion must be purposeful, brief, cancellable, optional.

## 1. Principles
1. **Direct manipulation is instant.** Anything under the finger or wheel follows within one frame; springs only smooth the tail.
2. **Spatial transitions explain hierarchy.** Push/pop moves sideways (the strip); overlays rise from where they were invoked.
3. **Nothing moves on its own** except playback progress and lyrics following playback.
4. **Every animation is interruptible and retargetable** (springs, never fixed-duration chains for interactive motion).
5. **Reduced motion** replaces movement with short cross-fades; it never removes information.

## 2. Tokens
### Springs (Compose `spring(stiffness, dampingRatio)`)
| Token | Stiffness | Damping | Settle ≈ | Use |
|---|---|---|---|---|
| `focus` | 1400 | 0.86 | 110 ms | Focus lens glide per detent, row weight change |
| `focusFast` | 2400 | 1.00 | 80 ms | Lens during fast spins (no overshoot) |
| `press` | 2000 | 0.70 | 90 ms | Button/zone press & release, glass squish |
| `navigate` | 380 | 0.92 | 320 ms | Push/pop strip, predictive-back release |
| `sheet` | 300 | 0.88 | 380 ms | Sheets, menus, mini player → Now Playing |
| `flow` | 260 | 0.82 | 420 ms | Album Flow settle after rotation/fling |
| `boundary` | 3000 | 0.50 | 120 ms | Lens squash at list ends |

### Tweens
| Token | Duration | Easing | Use |
|---|---|---|---|
| `fadeFast` | 120 ms | standard (0.2, 0, 0, 1) | Glyph swaps, HUD out |
| `fadeStandard` | 220 ms | standard | Content swaps, preview pane |
| `atmosphere` | 900 ms | emphasized-decelerate (0.05, 0.7, 0.1, 1) | Ambient color change |
| `hudHold` | 1600 ms | — | Toast visibility |
| `volumeBarHold` | 1500 ms | — | Volume bar after last detent |
| `flip` | 380 ms | standard | Album Flow cover flip |
| `reducedCrossfade` | 150 ms | linear-out | All transitions under reduced motion |

## 3. Choreography
| Moment | Motion |
|---|---|
| Wheel detent | Focus index updates synchronously; lens animates `focus`; text weight 500→600 on incoming, 600→500 on outgoing (same spring); haptic at detent time, not at animation end. |
| Fast spin | Lens switches to `focusFast`; list scroll uses `animateScrollBy` retargeting; `IndexGlyph` fades in after 0.4 s of > 16 det/s, out 600 ms after slowing. |
| Push / pop | Strip slide with `navigate`; TitleBar title cross-fades (`fadeStandard`), back button scales in from 0.8 when depth goes 1 → 2. Incoming screen's focus lens is already in place (no lens entrance animation). |
| Center press on a track | Press feedback (`press`), then playback starts; if auto-push to Now Playing, push begins after ≤ 80 ms (lets the press read). |
| Mini player → Now Playing | Container transform (`sheet`): capsule bounds → full window; artwork 40dp → large artwork position (shared element); other NP content fades in during the last 40%. |
| Now Playing track change | Artwork cross-fades (`fadeStandard`) with a 2% scale settle; title/artist cross-fade; atmosphere `atmosphere`. Horizontal swipe on artwork follows the finger, then springs (`navigate`). |
| Volume | Scrubber → VolumeBar cross-fade (`fadeFast`), bar fill follows detents with `focus`. |
| Scrub mode enter | Scrubber height 4 → 8dp and thumb scale-in (`press`). |
| Sheets & menus | Rise/scale from the invoking anchor (0.9 → 1, alpha 0 → 1) with `sheet`; dismissal reverses. |
| HUD toast | Fade + 8dp rise in (`fadeFast`), hold, fade out. |
| Like | Heart fill with a single 1.0 → 1.15 → 1.0 `press` pulse. No particles. |
| Album Flow | Covers positioned by a continuous `scrollPosition` (float); each cover's transform is a pure function of its distance from center (rotationY, scale, translationZ, translationX overlap) — renders any intermediate state exactly, which is why it can follow the wheel 1:1. Settles with `flow`. |
| Lyrics follow | Current line change: scroll so it sits at 33% height with `navigate`; color/opacity cross-fade `fadeStandard`. Paused 4 s after user input. |
| Loading → content | The loading screen (D-43) appears only after 150 ms; content replaces it with no staggered entrance. The pinwheel steps about 12 times a second; still with reduced motion. |
| Wheel ↔ keyboard (D-45) | One spring (damping 0.82, stiffness 240): the Wheel turns up to 150°, shrinks and fades as its piece of the body widens into the keyboard panel; keys rise from the middle outwards. Closing reverses it. Reduced motion: 160 ms tween. interaction-model.md §2.1. |

## 4. What we never animate
Ambient floats, shimmer skeletons, marquee text, parallax on scroll, staggered list entrances, bouncing icons, animated gradients, idle wheel effects.

## 5. Reduced motion
Triggered by `Settings.Global.ANIMATOR_DURATION_SCALE == 0` ("Remove animations") or in-app toggle. Then: push/pop/container transform/sheet → `reducedCrossfade`; lens jumps (no glide, but still renders); Album Flow → flat horizontal row, no rotation, no reflection; flip → cross-fade; atmosphere → 200 ms fade. Haptics unaffected.

## 6. Performance rules
- Animate only `graphicsLayer` properties (translation, scale, alpha, rotation) or draw-phase values; never layout size during motion except the scrubber height (one small element).
- Lens position is a draw-phase offset read from an `Animatable` → no recomposition of rows per frame.
- Album Flow renders ≤ 7 covers; offscreen covers aren't composed.
- Container transform uses Compose shared elements (`SharedTransitionLayout`) only for the artwork; everything else is fades.
