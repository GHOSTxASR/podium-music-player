# The physical Podium: its space, stickers and the guide

**Status:** Accepted (D-54 to D-57) · **Date:** 2026-10-07 · Extends D-26 (the device shell), D-41 (customization).

Podium is a small object the listener owns and decorates. Pinching it with two fingers turns the
flat app into that object: it gains thickness, moves back into a dark, sparsely starred space, and a
settings page slides in beside it — large by default, holding only what isn't the Podium's own: the
sticker gallery (and adding more), Help, Return to Podium and Turn off Podium (D-56). A switch at
the bottom, Podium | Settings, brings either close. The Podium's own look stays in its Settings
(Settings ▸ Podium body opens this space). Stickers the listener cuts out of their own pictures are
stuck onto the object (anywhere, even over the screen) and stay there. A first-launch tour plays on
a model of the Podium (D-57). None of this touches playback, sources, the queue or the database.

## 1. What exists and is kept

- The whole device is one composable tree: `GlassHost` (the body or the Glass atmosphere as its
  captured *content*; `DeviceLayout` with the virtual display, the Wheel, the power button and the
  lights as its *functional* plane) filling the window (`PodiumApp`).
- Input: `PodWheel` turns touches into `PodiumInput`, dispatched by the `InputRouter` stack; lists
  scroll by touch. Settings live on the virtual display (`Dest.Settings` …), persisted in
  `SharedPrefsDeviceSettings`.
- Nothing about the music player changes; the object is the existing device, transformed.

## 2. Layers

```
window
├── space            dark field + sparse stars (drawn only while the object is out of its flat pose)
├── object           one graphicsLayer: scale, perspective rotation, corner radius — everything
│   ├── thickness    the slab's edge, drawn behind the face in the same layer (so it turns with it)
│   ├── face         the existing GlassHost (body, display, Wheel, controls) — unchanged
│   ├── stickers     the listener's stickers, in object coordinates; never take touches
│   └── light        a faint falloff across the face, by depth
├── catcher          while not flat: touches on the object return it (or edit stickers)
├── page             the settings page, sliding in from the right (small in its corner when the Podium is close)
├── switch           Podium | Settings, at the bottom
└── overlays         the sticker maker, the arranging bar, the tour, the goodbye
```

Stickers are drawn above the face (a sticker over the screen covers it; the screen goes on
underneath, it is never baked in) and are part of the object's layer, so they turn, shrink and
recede with it. They are pointer-transparent in normal use: the Wheel under a sticker still works.

## 3. State

`SpacePhase`: `NORMAL → ENTERING → PHYSICAL ⇄ STICKER_EDITING → RETURNING → NORMAL`, owned by
`PodiumSpaceState` with three animatables: `depth` (0 flat … 1 in space; the pinch drives it directly,
release settles it), `zoom` (0 the page large and the Podium small beside it … 1 the Podium close and
the page small; reset to 0 on every visit, `SpaceFocus`) and `edit` (0 the space pose … 1 the editing
pose: the object nearer and square on). Only these exist; impossible combinations can't be expressed. Nothing of it is persisted: a
restart is always the flat Podium.

## 4. Gestures

- One finger: everything as before (Wheel, lists, buttons).
- Two fingers pinching *in* on the object (closer by a fifth and 40 dp) take it into the space.
  Recognised in the `PointerEventPass.Initial` pass at the top: once it's a pinch the changes are
  consumed, and the Wheel stops tracking a finger whose change an ancestor consumed (the one change
  inside the existing controls). Two fingers resting or turning on the Wheel are never a pinch.
- The object follows the fingers (D-65): measured from the moment the pinch is recognised and from
  the depth the object already has, so it never lurches to catch up; closing by half the fingers'
  distance takes it all the way in, and reversing the fingers reverses it. On release it goes where
  it was heading — depth + velocity × 0.12 s past 0.3, in — on a spring that keeps the fingers'
  speed (`settleIn`, `settleBack`); a quick short flick goes in, fingers that stopped carry no speed.
- In the space, two fingers spreading on the object bring it back the same way (all the way at 60 %
  wider; it stays out unless heading below 0.7). A tap on the small Podium brings it close; Back,
  "Return to Podium" or a tap on the close Podium return it. A tap on the small page brings it back
  large.
- While two fingers are down, holds don't fire (`LocalSeveralFingers`): a slow pinch starting on a
  row or on the Wheel can't open a menu, go Home or switch the display off first.
- Sticker editing: one finger moves the chosen sticker, two scale and rotate it
  (`detectTransformGestures` in the object's own coordinates).
- Accessible path: Settings ▸ Podium space opens the space without the gesture.

## 5. Rendering

Compose only — `graphicsLayer` (scale, `rotationX/Y` with a long camera distance, `shape`/`clip`),
`Canvas` for the slab edge, the shadow and the stars. No 3D engine: the object is a slab seen
almost face on, which a perspective layer plus a drawn edge renders convincingly at a fraction of
the cost. Stars: ~40 procedural points and four glints, slow drift and twinkle, drawn in one layer
whose only per-frame work is reading the clock (brush and glint shape built once); nearer specks
gather in a touch as the object recedes and slide the other way as it comes close — depth, not
decoration (D-65). Still under reduced motion; nothing is drawn while flat. The settings page
swings in from a 9° turn under the object's own camera as it lands, tied to depth like the object.

## 6. Stickers

- **Asset**: the cut-out picture with its border baked in, a PNG of at most 640 px on its longest
  side in `files/stickers/<id>.png`, plus `border` (none / black / white) and `thickness`.
- **Placement**: `stickerId`, centre `x`,`y` as fractions of the object's width and height,
  `scale` as a fraction of the object's width, `rotation` (degrees), `z` order.
- **Store**: `files/stickers/stickers.json` (assets + placements), written atomically; images are
  decoded once at display size and cached. No database migration; nothing leaves the phone.
- **Flow**: Add a sticker ▸ the system photo picker ▸ cut out (the centre first; a tap chooses
  another subject; Add and Erase brushes with undo and redo) ▸ border (off, black, white) and
  thickness, previewed on neutral grey ▸ save ▸ stuck on and arranged at once. A tile in the gallery
  opens one sticker: stick on another, arrange, take it off, delete (a second tap confirms).
  Arranging: one finger moves, two resize and turn; Take it off; Done. The gesture writes a live
  placement read only while drawing — the sticker is under the finger on the frame it moves — and
  the store once, when the fingers lift; a sticker taken hold of lifts a hair (D-65).
- **Cut-out**: MediaPipe MagicTouch (Apache-2.0) on the bare LiteRT runtime, on the phone
  (third_party/MODELS.md).
- **Border**: dilating the cut-out's alpha by the thickness (a disc kernel) and filling it with the
  border colour under the subject — it follows the silhouette, never a rectangle.

## 7. The guide

First launch shows the tour over everything — *Show me around* or *Skip* (persisted; Help ▸ Replay
the hands-on guide). It never uses the real Podium: a drawn model, in the listener's finish, on a dark
stage. Six checkpoints — Turn the Wheel, Press the center, Menu, Play and pause, Step outside, Make it
yours — each a slow camera move to its shot with a spotlight and a ghost finger looping the gesture.
The model can be tried by hand (a 150° turn, a press on the right part, two fingers together, a tap
on a sticker), which passes the checkpoint and moves on; Next, Back and Skip are always there.

## 8. Turn off Podium

Outside only. The music pauses at once; the window darkens and says goodbye; then the stickers are
written, the controller released, the task removed, the playback service stopped and, 1.2 s later,
the process ended (the service saves its position as it goes).
