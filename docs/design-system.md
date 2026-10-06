# Podium Design System

**Version:** 0.1 · **Date:** 2026-10-02 · Implementation home: `:core:designsystem` · Decisions: ADR-007 (glass), ADR-010 (type & symbols), D-02 (no Material3), D-04 (transitions).
Evidence: `research/2026-10-02-liquid-glass-and-hig.md`, `research/2026-10-02-typography-and-icons.md`.

---

## 0. Constitution

1. **The Wheel and its focus lens are the one bold thing.** Everything else is quiet, typographic, and content-led.
2. **Three planes, back to front:** *Atmosphere* (artwork-derived ambient color) → *Content* (lists, artwork, text — no glass) → *Functional glass* (Wheel, mini player, title-bar controls, menus, sheets, HUDs, focus lens).
3. **Glass only where you act.** If an element isn't a control, a navigation affordance, a transient overlay, or the focus, it is not glass.
4. **Glass needs a real backdrop.** Content scrolls beneath functional glass; on Now Playing the artwork is extended beneath the Wheel. No invented gradient meshes or blobs.
5. **Color is earned.** Neutral by default; the only tinted glass is the focus lens; color elsewhere signals state (like, error), never decoration.
6. **Calm when idle, expressive when touched.** No ambient loops, shimmer, marquees, or floating.
7. **The fallback is the heritage.** When glass is unavailable (old API, Transparency Off, high contrast), the focus lens becomes a solid highlight bar with white text — the classic iPod look.
8. **Natural language.** Sentence case everywhere. The only all-caps string in the product is the `MENU` legend on the Wheel (hardware heritage).

---

## 1. Composition

### 1.1 Compact portrait (phones) — the canonical layout (the device, D-26)
```
┌──────────────────────────────┐  status bar (system, transparent; icon colour follows the finish)
│ ╭──────────────────────────╮ │  Body: the window, in the chosen finish (§5.8)
│ │(‹)       Songs         ▶ │ │  ScreenHeader inside the screen (44dp): back, title, play state
│ │▣ Broken Tape        0:10 │ │  Virtual screen: black bezel (3dp), own canvas + atmosphere,
│ │╭────────────────────────╮│ │    cover-glass reflection and recess shadow over the content
│ ││▣ Coda              0:12 ││ │  ← focus lens on the focused row
│ │╰────────────────────────╯│ │
│ │▣ High Resolution…   0:12 │ │
│ │╭────────────────────────╮│ │  MiniPlayer capsule inside the screen (Home/Music/Songs only)
│ ││▣ So What / Miles D.  ⏸ ││ │
│ │╰────────────────────────╯│ │
│ ╰──────────────────────────╯ │
│           ╭───────╮       (⏻) │  Power button beside the Wheel (36dp in a 48dp target)
│        ╭──┤ MENU  ├──╮         │
│        │⏮  ( ◯ )  ⏭│         │  The Wheel, D = clamp(0.58 × width, 216, 300), ≤ 0.31 × height
│        ╰──┤  ⏯   ├──╯         │
│           ╰───────╯            │
└──────────────────────────────┘  gesture nav inset
```
- The screen takes all height the Wheel doesn't (window − system bars − 8dp − 2 × 18dp − D), within a device-like proportion: width:height 0.60–0.82 in portrait, 1.20–1.70 in landscape (screen left, Wheel and power button right). `DeviceLayout` owns this; the screen content never knows about the shell.
- **Readable region** = screen − ScreenHeader − (MiniPlayer + 16dp when shown). Lists pad by `LocalScreenInsets`; content fades under the header and mini player (§5.5), inside the screen only.
- Power: off = black screen and paused playback; on = boot (§5.8); any Wheel press also wakes it.

### 1.1.1 The paper (D-29, D-30)
Every list sits on one sheet that curves like the Wheel's right side:
```
          Cover Flow        ⟩ )
           Albums            ⟩ )
 ▒▒▒▒|      Artists           ⟩ ●)  |▒▒▒▒
 prev|      Songs             ⟩ )   |next
 ▒▒▒▒|     Favorites         ⟩ )    |▒▒▒▒
         (fading, softening toward the ends)
```
- **The arc** (`PaperGeometry`): a ")" whose apex sits at mid-height of the readable region at 80 % of the width, radius 1.05 × the readable height, hairline, tertiary — hollow facing the list, mirroring the Wheel's right side. The selection indicator rides on it.
- **Rows ride the arc.** Each row is shifted left by the curve at its height, so the list curves and scrolls along it; the list's own bounds reach far enough left that a bent row is never clipped. Toward an end the list *continues beyond*, rows fade (to 40 %), shrink a little (5 %) and soften (up to 3 dp blur on API 31+), ramping in over the first row of hidden content — an end with nothing beyond it stays crisp, so a short list is crisp throughout. The focused row stays crisp. Rows fade out within 24 dp of the readable region's edges, so they never drift under the title or the mini player.
- **Focus geometry (D-40, interaction-model.md §5.1).** In a long list the focused row rides at the apex (mid-height of the readable region); near the start or end it travels to the real top or bottom, so the first row sits flush under the title and the last flush on the bottom, never partly hidden and never with empty space beyond. A list that fits starts at the top of the readable region. A live miniature (the previous column's glimpse) centres the row that led here instead, so the glimpse always shows it.
- **Two glimpses at mid-height, either side of the list,** the same square (34 % of the readable height), the same distance treatment (60 % opacity, 94 % scale, 1.5 dp soften), each running off its display edge:
  - Right, beyond the arc: the focused item's next column (`MenuPreview`: cycling artwork, a sideways carousel, a swatch, or the instrument diagram).
  - Left, before the list: the previous column, live — that screen itself (`LocalPaperPeek`, a `LocalMiniature` composition with no input or side effects), zoomed in on its list with the item that led here focused (`LocalMiniatureFocusKey`), on a raised tile. Tapping it goes back. Root screens have none.
- **Moving along the paper** (`paperForward` / `paperBack`, 600 ms, all themes): forward, the next column grows out of its box at the right and rises over the top of the curve into focus, while the current screen sinks down and to the left into the previous column's box; back is the mirror. Glimpses of the leaving screen fade at once; the arriving screen's fade in as it lands. Reduced motion: a crossfade. Glass keeps Now Playing's rise from the mini player. Overlay menus are plain lists.
- **Long titles:** the column is ~56 % of the width, so titles ellipsize sooner than on a full-width list; the focused row's title scrolls slowly when it doesn't fit (see §3).

### 1.2 Compact landscape (phones)
`[ Screen (flexible) | Wheel column (D = clamp(0.78 × height, 220, 300)) ]`. Wheel on the right (left if left-handed). In Music/Albums contexts the screen shows **Album Flow**. MiniPlayer moves to the top of the wheel column as a narrower capsule.

### 1.3 Medium/Expanded (tablets, foldables, desktop mode)
Nav3 two-pane scene (list | detail) for browse screens; Now Playing becomes the detail pane when active. Wheel docks bottom-right of the window in a 320dp column; visibility setting **Wheel: Always / Auto / Hidden** (Auto hides when a hardware keyboard or mouse is present). Home shows the **preview pane** (contextual artwork/info for the focused menu item, cross-fading on focus change) — the iPod classic split menu, without its slideshow loop.

---

## 2. Color

All tokens are defined in OKLCH and shipped as sRGB. Every token has **light**, **dark**, and **high-contrast** variants (HC = `UiModeManager.getContrast() ≥ 0.5` or in-app "Increase contrast").

### 2.1 Core palette ("Graphite")
| Token | Dark (OKLCH → hex) | Light (OKLCH → hex) | Use |
|---|---|---|---|
| `canvas` | 0.165 0.010 262 → `#0C0E13` | 0.975 0.004 262 → `#F5F7F9` | No-artwork atmosphere base |
| `canvasRaised` | 0.215 0.011 262 → `#171A1F` | 1.000 0 → `#FFFFFF` | Solid-tier surfaces |
| `labelPrimary` | 0.975 0.004 262 → `#F5F7F9` | 0.215 0.012 262 → `#161A1F` | Primary text, glyphs |
| `labelSecondary` | 0.765 0.012 262 → `#AEB3BA` | 0.455 0.014 262 → `#52575F` | Subtitles, artist names |
| `labelTertiary` | 0.600 0.012 262 → `#7C8088` | 0.585 0.012 262 → `#787C83` | Disabled, hints, counters (non-essential only) |
| `separator` | 0.300 0.010 262 → `#2B2E33` | 0.890 0.006 262 → `#D8DBDF` | Hairlines (0.5dp) |
| `highlight` (Blue, default) | 0.600 0.165 257 → `#367FE0` | 0.470 0.175 260 → `#1153BA` | Focus lens stain, primary emphasis |
| `highlightText` | 0.680 0.150 255 → `#539AF2` | 0.520 0.170 258 → `#1C65C8` | Accent-colored text/glyphs (links, active toggles) |
| `like` | 0.700 0.170 12 → `#F46A82` | 0.560 0.200 12 → `#CE2854` | Filled heart only |
| `critical` | 0.690 0.180 27 → `#F7665B` | 0.550 0.200 27 → `#CC2827` | Errors, destructive actions |
| `warning` | 0.800 0.150 75 → `#F5AE39` | 0.600 0.130 70 → `#B17000` | Warnings (glyphs; light variant ≥ 3:1 only) |
| `positive` | 0.760 0.150 150 → `#61CB7C` | 0.560 0.140 150 → `#218A45` | Download complete, verified |

Measured contrast vs. canvas (WCAG 2.x): dark primary 17.98, secondary 9.15, tertiary 4.87, highlightText 6.68; light primary 16.27, secondary 6.77, tertiary 3.90, highlightText 5.22. Against the worst-case tinted atmosphere (any hue, bounds in §2.3) primary ≥ 15.2, secondary ≥ 6.3. **Rule:** `labelTertiary` is never used for essential information in light mode (3.65–3.9:1).

**High-contrast variants:** secondary → primary-adjacent (dark L 0.86 / light L 0.32), tertiary → secondary values, separators L ±0.12 stronger, glass tints +0.25 opacity, highlight lens forced to Solid tier.

### 2.2 Highlight options (Settings ▸ Appearance ▸ Highlight)
- **Blue** (default) — the iPod highlight, refined.
- **Graphite** — neutral (dark L 0.55 C 0.010 / light L 0.40 C 0.010).
- **Artwork** — hue from the artwork's most vibrant cluster, L and C pinned to Blue's values; rejected (falls back to Blue) if contrast vs canvas < 3:1 or hue is within 20° of `critical`.

### 2.3 Atmosphere (album-art environment)
Computed once per artwork (`ArtworkRepository`, Default dispatcher), cached in `artwork_cache.palette_json`:
1. Decode to 64×64, convert to OKLab, k-means (k = 5, 8 iterations, seeded).
2. **Ambient** = most populous cluster with C ≥ 0.03; else the overall mean (neutral artwork stays neutral).
3. Map to atmosphere:
   - Dark: `L ∈ [0.185, 0.205]` (darker artwork → lower), `C = min(C_ambient × 0.35, 0.040)`, hue kept.
   - Light: `L ∈ [0.955, 0.970]`, `C = min(C_ambient × 0.20, 0.020)`.
4. Render as a **vertical light falloff**: top +0.025 L → bottom −0.010 L. No radial blobs, no multiple hues.
5. Also store `luminanceTop/Bottom` (mean L of artwork's top/bottom thirds) → used for clear-glass dimming (§5.4).
6. Transition between atmospheres: 900 ms, emphasized-decelerate (reduced motion: 200 ms fade).

Atmosphere follows the **now-playing** artwork app-wide; on album/artist screens it follows that screen's artwork while visible.

---

## 3. Typography

### 3.1 Families
- **Instrument Sans** (variable `wght` 400–700, `wdth` 75–100): all UI text, numerals.
- **Inter** (variable): metadata strings not fully covered by Instrument Sans (per-string selection; ADR-010).
- System Noto: anything Inter lacks.
- OpenType: `tnum` on every numeric style; `case` on titles containing parentheses; true minus (U+2212) for remaining time.

### 3.2 Scale (sp; line height; weight; tracking; width)
| Style | Size/LH | Weight | Tracking | Width | Use |
|---|---|---|---|---|---|
| `largeTitle` | 30/36 | 700 | −0.3 | 100 | Home "Podium", empty-state titles on large screens |
| `title` | 17/22 | 600 | −0.1 | 100 | TitleBar title |
| `nowPlayingTitle` | 22/28 | 600 | −0.2 | 100→85 | Track title on Now Playing (narrows to 85 before wrapping; max 2 lines) |
| `nowPlayingSubtitle` | 17/22 | 400 | 0 | 100 | Artist on Now Playing |
| `row` | 17/22 | 500 (600 focused) | −0.1 | 100 | Menu rows, primary line of track rows |
| `rowSecondary` | 14/18 | 400 | 0 | 100 | Second line (artist, count) |
| `body` | 15/22 | 400 | 0 | 100 | Settings descriptions, sheets |
| `lyric` | 24/32 | 600 | −0.2 | 100 | Lyrics lines |
| `sectionHeader` | 15/20 | 600 | 0 | 100 | List section headers (title case, never caps) |
| `caption` | 13/16 | 500 | 0 | 100 | Timecodes (tnum), counters "3 of 12" |
| `footnote` | 13/18 | 500 | 0 | 100 | Quality label, secondary metadata |
| `indexGlyph` | 56/56 | 700 | 0 | 100 | Fast-scroll letter HUD |
| `wheelLegend` | 12/14 | 600 | +1.0 | 100 | `MENU` only (caps, heritage) |

Font scale: honoured to 200% (Android nonlinear scaling). Rows grow (`minHeight`, never fixed height). Wheel legends cap at 1.3× (fixed geometry; semantics carry the label). Bold-text (`fontWeightAdjustment`) adds +100 to weights (cap 700).

### 3.3 Truncation
Titles: end ellipsis; Now Playing: narrow width → wrap to 2 lines → ellipsis. Only the focused list row may scroll its title, slowly, after 0.9 s, when it doesn't fit (as the original iPod did; it follows the user's own focus, and is off with reduced motion). No other marquees. The scrolling title may draw 8 dp beyond its box on every side (layout keeps its own size), so neither Carbon's glow nor a glyph reaching outside its line box is cut into a hard edge, and its ends fade over the last 12 dp instead of stopping mid-letter.

---

## 4. Space, shape, elevation

- **Spacing scale (dp):** 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64. Side gutter 16 (≥ 400dp width: 20).
- **Row heights (min):** menu 52 · track 60 (44dp artwork) · album 64 (52dp artwork) · settings 52 (+ description).
- **Touch targets:** ≥ 48×48dp everywhere; Wheel zones are ≈ 70–90dp bands.
- **Concentric radii:** `r_inner = max(r_outer − inset, r_min)`. `r_outer` for full-width floating elements = display corner radius from `WindowInsets.getRoundedCorner` (API 31+; fallback 28dp).
  | Element | Radius |
  |---|---|
  | Inset sheets (8dp from edges) | display radius − 8 |
  | Menus | 22 |
  | Focus lens | 14 (smooth/continuous corners) |
  | Mini player | capsule (28) |
  | Title-bar buttons, center button | circle |
  | Artwork | `clamp(size × 0.045, 4, 14)` — 44dp → 4, 64dp → 4, 300dp → 14 |
- **Shadows** (functional layer only + Now Playing artwork):
  | Token | Dark | Light |
  |---|---|---|
  | `shadow.glass` | 0/8/24 @ 22% + 0/1/2 @ 18% | 0/8/24 @ 12% + 0/1/2 @ 8% |
  | `shadow.artwork` | 0/12/32 @ 35% | 0/12/32 @ 18% |
  Lists, rows, and cards have no shadows.

---

## 5. Materials

### 5.1 Semantic materials (ADR-007)
| Material | Used by | Full tier recipe (order: color ⇒ blur ⇒ lens) |
|---|---|---|
| `GlassRegular` | Wheel ring, menus, sheets | vibrancy (sat 1.5) · blur 20dp · lens h16/a24 · tint dark `#000` 38% / light `#FFF` 42% · rim · `shadow.glass` |
| `GlassControl` | Center button, title-bar buttons, toggles while dragged | vibrancy · blur 12 · lens h12/a18 · tint dark `#000` 30% + `#FFF` 8% / light `#FFF` 50% · rim (strong) · `shadow.glass` |
| `GlassFloating` | MiniPlayer, HUDs (index glyph, volume, "Added to queue") | vibrancy · blur 24 · lens h12/a16 · tint +6% vs Regular · rim · `shadow.glass` |
| `GlassClear` | Controls over artwork (landscape Now Playing, Album Flow overlays) | blur 6 · lens h12/a20 · tint 10% · **dimming** 0 or 35% (§5.4) |
| `GlassFocus` | Focus lens | **no blur** (content stays crisp) · lens h10/a10 + depth effect (≈ 1.04× magnification) · stain `highlight` @ 22% dark / 16% light · rim (soft) · no shadow |
| `GlassNavigation` | Back/search/more buttons | = `GlassControl` at 40dp |

**Rim:** 1dp inner stroke, linear gradient at 135°: white 55% → white 0% at 45% → white 12% at 100% (dark); light mode white 80% → 0% → 30%. This is the "edge catching light" that makes glass read as glass.

### 5.2 Tiers
| Tier | Recipe change |
|---|---|
| `Full` | as above |
| `Blur` | drop lens; tint +4% |
| `Reduced` (user setting) | `Blur` + tint +20% |
| `Solid` | no backdrop: opaque fill (dark `canvasRaised` blended 8% with atmosphere; light `#FFFFFF` 96% over atmosphere), hairline rim `separator`, same shadow. **Focus lens = solid `highlight` fill, text and glyphs `#FFFFFF`.** |

Tier selection: `GlassCapabilities` = f(API level, Transparency setting, high contrast, power-save, thermal ≥ SEVERE, sustained jank > 5% frames over 10 s → downgrade one tier for the session).

### 5.3 States
| State | Change |
|---|---|
| Pressed | tint +6%, rim +15%, lens amount ×1.2 ("squish"), scale 0.97 (buttons) / zone darken 8% (wheel zones) |
| Selected (on) | stain `highlight` @ 30% (Full/Blur) / solid highlight (Solid) + glyph `FILL=1` |
| Disabled | content alpha 35%; no press response; semantics `disabled` |
| Keyboard focus | 2dp `highlight` outline, 3dp outside the shape (in addition to the lens for list items) |
| Loading | glyph replaced by 16dp indeterminate arc (`labelSecondary`), no glass change |
| Error | glyph tinted `critical`; never tint the glass red |
| Offline | glyph swapped for offline variant; label explains |

### 5.4 Clear glass dimming
If the glass sits over artwork whose `luminanceBottom` (or relevant region) > 0.60 → add a 35% black dimming layer under the glass; else none (HIG rule). Text on clear glass is always `#FFFFFF` with the dimming guaranteeing ≥ 4.5:1.

### 5.5 Scroll-edge effect
Top (under TitleBar, 96dp) and bottom (above the functional stack, 120dp): progressive blur 0 → 16dp + alpha fade of content 100% → 40% (Full); fixed blur 12 with alpha mask (Blur); opaque atmosphere gradient (Solid).

### 5.6 Background extension (Now Playing)
Below the artwork: the artwork mirrored vertically, blurred 48dp, opacity 30% (dark) / 18% (light), masked by a vertical fade to transparent over 280dp. Gives the Wheel real content to refract (Apple's background extension effect). Not drawn in Solid tier.

### 5.8 Device finishes (D-26)
| Finish | Base (sRGB) | Wheel |
|---|---|---|
| Glass | — (atmosphere + blurred artwork behind glass) | Liquid Glass ring and center |
| Steel gray | `#5A5F66` | deeper ring, light legends |
| Burgundy | `#5C1D2B` | deeper ring, light legends |
| Glacier blue | `#B4CFDF` | paler ring (white-wheel look), dark legends |
| Silver | `#D6D8DB` | paler ring, dark legends |
| Custom color | any `#RRGGBB` | derived the same way |

- Solid palettes are derived in OKLCH from the base: body top +0.035 L / bottom −0.055 L, one diagonal sheen, ring ±L by lightness (L > 0.62 = light body), center = base. Grain (0–100 %, default 25 %) is a fixed, seeded, zero-mean noise tile overlaid on body, ring, center and power button.
- Choosing a finish previews it live on the device as the focus moves; Center keeps it, Menu restores the old one. Custom color: type a hex code or walk the hue with the Wheel (lightness and chroma held, so a full turn returns to the start).
- The screen is a text container, never glass; the power button is a control and is glass on the Glass finish.
- Boot (D-32, "old hardware, new software"): a power-on self-test types itself in the system monospace face (11 sp, phosphor off-white `#D9D8D2` on black, blinking block cursor): "Podium ROM 3.1", a copyright line, then checks with dot leaders and results in a column — Processor (cores), Memory (counts up to the phone's RAM in MB), Display (pixels), Click wheel, Audio (speaker / headphones / bluetooth), Library (songs). Each result lands with a `tick` haptic (and click, if on); the memory count purrs with detent ticks. Then the screen clears to the mark: the ring sweeps closed (700 ms), the centre lands with a `press`, the wordmark fades in, and a hairline progress capsule fills over 1.5 s with two stalls (like a disk seeking), ending on `confirm`. ~4.5 s; any Wheel press skips; reduced motion shows the lines at once and the mark static (~1.2 s). The startup chord (`res/raw/podium_boot.ogg`: an original strummed C major 9 with a room tail, synthesized for Podium — not Apple's trademarked chime) starts with the mark as a UI sound: system-sounds volume, silent on silent/vibrate, skipped when music is playing, toggle in Settings ▸ Startup sound.

### 5.8.1 Indicator lights (D-33)
Bottom corner of the body, opposite the power button: two 18 dp diodes in recessed bezels with 8 sp legends ("Battery", "Disk"). Battery: `#4CE06A` > 60 %, `#B8E04C` > 30 %, `#FFB020` > 15 %, `#FF3B30` below; breathes (1.4 s) while charging, blinks (0.9 s) at ≤ 10 %. Disk: `#FFA726`, a double flicker (60 ms + 140 ms) per `DiskActivity` pulse.

### 5.9 Display themes: Glass, Carbon, Bone (D-29)
| | Glass | Carbon | Bone |
|---|---|---|---|
| Display | Graphite / light, atmosphere tint | `#0A0A0A` matte | `#ECE8DF` warm off-white |
| Type | per §2 | `#F2F0EB` / `#9B988F` / `#65635D` | `#141311` / `#68645C` / `#9B968C` |
| Selection | stained-glass lens (D-25) | lit text + faint band + white indicator, faint glow | lit text + faint band + near-black indicator |
| Glass | functional layer | none on the display (flat surfaces, no shadows) | none |
| Hardware | chosen finish | matte black, knurled mechanical wheel | matte black, knurled mechanical wheel |
| Shapes | rounded capsules | squared: 2–3 dp corners, outlined keys, segmented strips, flat rule | same as Carbon |
Album artwork is the only colour on Carbon and Bone. The theme never changes the content's layout, only its rendering.

### 5.7 Enforcement
Lint rule (custom detector in `build-logic`): `Modifier.blur`, `RenderEffect`, `RuntimeShader`, and `com.kyant.backdrop.*` are errors outside `:core:designsystem`. A debug overlay counts simultaneous glass surfaces; > 5 persistent surfaces logs a warning.

---

## 6. Components

### 6.1 `PodWheel`
- **Geometry:** D = `clamp(0.66 × width, 232, 312)dp` (portrait), size setting S/M/L × 0.9/1.0/1.1. Center button d = 0.38 D. Gap ring 3dp between ring and center (atmosphere shows through: two pieces of glass).
- **Legends** on the ring midline: `MENU` (12 o'clock, `wheelLegend`), ⏭ (3), ⏯ (6), ⏮ (9) — symbols 22dp, weight 600, `labelSecondary` (on glass, vibrant).
- **Materials:** ring `GlassRegular`; center `GlassControl` (sampled from a combined backdrop so it refracts the ring + content).
- **Feedback:** finger specular (radial white 20%, r 48dp) follows touch on the ring; zone press = darken 8% + legend scale 0.9; center press = lens squish; haptics per `interaction-model.md` §6.
- **Idle:** completely static.
- **Disabled zones:** legend 35%, no response (e.g., ⏮/⏭ with an empty queue).
- **States per context:** the Wheel never changes appearance by context except an optional tiny center glyph in non-list modes (speaker in Volume, diamond in Scrub) at 40% opacity.
- Full gesture & input spec: `interaction-model.md`.

### 6.2 `FocusLens`
- Shape: rounded rect r 14, inset 8dp horizontally from list edges, 3dp vertically within the row.
- Material: `GlassFocus` (Full/Blur) / solid highlight (Solid, HC).
- Motion: spring `focus` (stiffness 1400, damping 0.86); fast rotation (> 16 detents/s) uses `focusFast` (2400, 1.0). Boundary hit: scaleY 0.94 for 120ms + boundary haptic.
- Non-glass indicators (always): focused row text weight 600 (animated with the same spring), trailing chevron `FILL=1`, semantics `selected`.
- Keep-in-view (D-40): on the paper the focused row rides at the readable region's middle and the list's own scroll limits put the first and last rows flush with the region's edges; plain lists (menus) keep one neighbour in view each side, the first and last rows flush. The scroll glides with the lens's own spring `focus`, so the two move as one; jumps (> 1.5 rows) are immediate.
- The lens is glued to the rows: it slides between rows in content space (a fractional row position), so it moves with the list while the list scrolls, and it is clipped to the readable region. It never clamps to a fixed band.

### 6.3 Rows
| Row | Layout |
|---|---|
| `MenuRow` | 16 gutter · label (`row`) · flexible · trailing value (`footnote`, secondary) · chevron › (16dp) · 16 gutter |
| `TrackRow` | artwork 44 (r4) · 12 · title (`row`, 1 line) / artist (`rowSecondary`) · trailing: download badge, duration (`caption`) |
| `AlbumTrackRow` | number (`caption`, tertiary, tnum, 24dp column) · title · duration (no artwork; album context) |
| `AlbumRow` | artwork 52 · title / artist · year (`footnote`) |
| `SettingRow` | label · value or toggle; description (`body` secondary) below when present |
| `SectionHeader` | `sectionHeader`, 32dp top space, no divider |
Separators: none between rows (focus lens and rhythm do the work); 0.5dp hairline only between sections in settings.

### 6.4 `TitleBar`
Floating, not a slab: left back button (`GlassNavigation`, chevron) when stack depth > 1; centered `title`; right action (search on browse screens, More on Now Playing). Offline glyph appears left of the right action when offline. Scroll-edge effect beneath (§5.5).

### 6.5 `MiniPlayer`
Capsule 56dp, width `min(window − 32, 420)`, 12dp above the Wheel; `GlassFloating`. Artwork 40 (r8) · title (15/20 600) / artist (13/16 400 secondary) · ⏯ button (44dp). Progress: 2dp hairline inset 20dp along the inner bottom edge (`labelPrimary` 80% on 20%). Tap → Now Playing (container transform). Hidden on Now Playing, Up Next, Lyrics, and when idle.

### 6.6 `AlbumArtwork`
Coil-backed; size-bucketed requests (48, 96, 192, 512, 1024 px); radius per §4; placeholder = monogram (first letter of album, `labelTertiary` on `canvasRaised`) — never a generic music note. Now Playing artwork has `shadow.artwork`. Crossfade 150ms on load (no fade if from memory cache).

### 6.7 Now Playing composition (D-28)
Inside the virtual screen, under the shell's ScreenHeader ("Now Playing", back, play state):
- **Stacked** (default): artwork box (takes all height the controls leave) · title (`nowPlayingTitle`, scrolls slowly when too long, never wraps) · artist (secondary) · album (tertiary) · progress row (elapsed — bar — remaining; tap or drag to seek; becomes the volume bar while volume changes) · status slot, two fixed lines ("N of M" + measured quality; then one note: error, scrubbing, "Playing from …", "Source reported …") · transport (⏮ 30 dp, ⏯ 42 dp, ⏭ 30 dp, bare glyphs) · one glass capsule with shuffle, repeat, favorite, Up Next, More (20 dp glyphs, quieter; slots 36–46 dp to fit).
- **Compact** (artwork box would be < 0.45 × width): artwork beside the text block, the rest below.
- **Landscape screen** (width > 1.15 × height): artwork on the left (square box), everything else beside it.
- **Artwork:** `FittedArtwork` keeps the image's proportions (square stays square; portrait/landscape aren't cropped, extremes beyond 1:2 are); shadow; hairline rim; while loading it shows a smaller cached copy, else a quiet surface; missing art gets `ArtworkFallback` (two tones from the title, Podium's ring, the initial). Breathes 0.93 (paused) → 1.0 (playing). Track change: direction-aware crossfade with ±11 % travel and 0.93 → 1 scale (crossfade only with reduced motion); no transition when the next song shares the cover.
- **Environment:** `BackgroundExtension` (blurred art, top/bottom fade) plus a scrim keyed to the art's luminance, so bright covers in dark mode (dark covers in light mode) keep text legible. It is the captured layer the capsule's glass refracts.

### 6.7.1 ONLINE (D-34)
ONLINE uses the same paper, themes and Wheel — no new paradigm. Menus are paper lists whose previews come from the source's artwork (Home: a carousel of trending covers; Liked songs and History: their covers; Radio: recent covers); shelves open as song lists (cover, title over artist, length) or playlist lists, paged as the focus nears the end. Album and playlist pages keep the cover beside every row (it's the point). Artist pages: round picture header, Radio, Play popular songs, then sections with quiet footnote labels (Popular, Albums and EPs, Playlists, Related artists). Search: a hairline field (2 dp corners on Carbon/Bone, 10 dp on Glass) as the list's first row; results stream in below by section. Unplayable songs say why in place of the artist. Now Playing marks an online song with the single word "Online" in its status line — nothing more. Offline and errors use the centred message state with a next step.

### 6.8 `ProgressScrubber` / `VolumeBar`
- Track 4dp (8dp in Scrub mode), radius full, fill `labelPrimary`, remaining `labelPrimary` 20%. Thumb appears only in Scrub mode / touch drag (12dp circle, `GlassControl`).
- Position read in draw phase only (no recomposition per frame).
- VolumeBar replaces the scrubber in place during wheel volume changes (speaker glyphs at both ends), fades back 1.5s after the last detent.

### 6.9 Overlays
- `GlassMenu` (context menus): `GlassRegular`, r22, rows 48dp, max 8 items then scroll; anchored to the focused row or the invoking control; wheel navigates it (focus lens inside).
- `GlassSheet`: inset 8dp, top radius display − 8; half height default; becomes more opaque (+20% tint) when expanded to full (HIG). Implemented with the Backdrop-documented pattern to avoid the RenderThread crash class.
- `HudToast` (`GlassFloating`, capsule, centered above the MiniPlayer): "Playing next", "Added to queue", "Added to Late Nights" — 1.6s, no stacking (replace).
- `IndexGlyph`: 96dp rounded square `GlassFloating` centered in the readable region; shows the current initial letter during fast rotation in sorted lists; fades out 600ms after rotation slows.

### 6.10 Small components
`QualityLabel` (footnote, secondary, tappable, e.g., "FLAC 24-bit/96 kHz"; absent when unknown) · `DownloadIndicator` (12dp glyph states: queued ○, progress ring, done ●, failed !, in `labelTertiary`/`positive`/`critical`) · `LikeButton` (heart outline ↔ filled `like`, TOGGLE haptic) · `Toggle` (track 51×31 classic proportions; knob becomes `GlassControl` while dragged, per HIG) · `SearchField` (BasicTextField; 44dp; `canvasRaised` fill — content, not glass).

**Quality label wording:** `<Codec> <bitDepth>-bit/<sampleRate> kHz` for lossless (`FLAC 24-bit/96 kHz`), `<Codec> <bitrate> kbps` for lossy (`AAC 256 kbps`). Sample rates print as 44.1, 48, 88.2, 96, 176.4, 192. Never "Lossless"/"Hi-Res" unless `audio-architecture.md` §5.1 holds. No all-caps, no middle dots. Unknown → no label.

### 6.11 State components
- `LoadingState`: nothing for 300 ms; then static placeholder rows (6% label fill, **no shimmer**) + 16dp arc in the TitleBar.
- `EmptyState`: glyph 48dp tertiary · title (17/22 600) · one-sentence message (15/22 secondary) · one action (focusable; lens lands on it).
- `ErrorState`: same layout, message from §11, action "Try again" (+ secondary "Settings" when relevant).
- `OfflineBanner`: inline row at top of affected lists, not a modal.

---

## 7. Iconography
Material Symbols Rounded (subset), `wght` matched to adjacent text (500 rows, 600 controls), `opsz` = size, `GRAD` 0 (HC +100), `FILL` 0/1 for off/on.
Sizes: 16 (inline badges), 20 (row trailing), 22 (wheel legends), 24 (title bar, NP actions), 48 (empty states).
Glyph set (v1): play, pause, skip_next, skip_previous, fast_forward, fast_rewind, shuffle, repeat, repeat_one, all_inclusive (autoplay), favorite, download, download_done, downloading, error, cloud_off, search, more_horiz, chevron_left, chevron_right, queue_music, lyrics, volume_down, volume_up, speaker, headphones, bluetooth, usb, album, person, music_note (fallback only), playlist_add, playlist_play, delete, edit, drag_handle, check, close, schedule (sleep), tune, info, storage, graphic_eq (now-playing indicator, static).

---

## 8. Motion (summary — full spec in `animation-system.md`)
Springs: `focus` 1400/0.86 · `focusFast` 2400/1.0 · `press` 2000/0.70 · `navigate` 380/0.92 · `sheet` 300/0.88 · `flow` 260/0.82. Fades: fast 120ms, standard 220ms, atmosphere 900ms. Reduced motion: cross-fades ≤ 150–200ms, no 3D, lens jumps.

---

## 9. Haptics (tokens)
| Token | Constant (API ≥ 34 / fallback) | Event |
|---|---|---|
| `detent` | SEGMENT_FREQUENT_TICK / CLOCK_TICK | focus moves one item; flow moves one album |
| `step` | SEGMENT_TICK / CLOCK_TICK | volume step; picker option; scrub 1% |
| `boundary` | GESTURE_THRESHOLD_DEACTIVATE / CONTEXT_CLICK | hit list start/end; volume 0/max |
| `press` | VIRTUAL_KEY (KEYBOARD_TAP) | wheel button down |
| `longPress` | LONG_PRESS | long-press recognised |
| `toggleOn/Off` | TOGGLE_ON/OFF / CONTEXT_CLICK | like, switches |
| `confirm` / `reject` | CONFIRM / REJECT | action completed / not allowed |
Rate-limited to 60/s; coalesced during fast spins. Master toggle in Appearance.

---

## 10. Accessibility behaviour (summary — full spec in `accessibility.md`)
Every glass state has a non-glass twin; Solid tier for HC/transparency-off; TalkBack sees the Wheel as five buttons plus adjustable actions; lists are standard semantics; font scale to 200%; reduced motion honoured.

---

## 11. Voice & copy
Sentence case, plain verbs, no apologies, say what happened and what to do. One action name per flow ("Download" → "Downloading" → "Downloaded").

| Situation | Title | Message | Action |
|---|---|---|---|
| No liked songs | No liked songs yet | Press and hold any song, then choose Like. | Browse music |
| No playlists | No playlists | Make one to keep songs together. | New playlist |
| No downloads | Nothing downloaded | Downloaded music plays anywhere, even offline. | Browse music |
| No search results | No matches for "query" | Check the spelling or try fewer words. | — |
| Offline (list) | You're offline | Showing music on this device and your downloads. | Show downloaded |
| Source unreachable | Home server isn't responding | Check that it's on and you're on the same network. | Try again · Settings |
| Auth failed | Sign in to Home server again | Your password or API key was rejected. | Sign in |
| Stream unavailable | This song can't be played right now | Skipping to the next song. | Cancel |
| Unsupported format | This file uses a format your phone can't play | The format is shown in Song info. | Skip |
| Rate limited | Audius is limiting requests | Try again in a minute. | Try again |
| Download failed | Download didn't finish | The connection dropped. Podium will retry. | Retry now |
| Storage full | Not enough space | Free up 1.2 GB to download this album. | Storage settings |
| Corrupt download | A download was damaged | Podium will download it again. | — |
| No music at all (first run) | Add your music | Connect a music server, allow access to music on this phone, or explore Audius. | Add a source |

---

## 12. Implementation structure

```kotlin
object PodiumTheme {                 // CompositionLocal-backed accessors
    val colors: PodiumColors          // resolved for light/dark/HC + highlight choice
    val type: PodiumType
    val spacing: PodiumSpacing
    val shapes: PodiumShapes          // concentric helpers
    val motion: PodiumMotion          // springs/tweens (reduced-motion aware)
    val glass: GlassCapabilities      // current tier
    val haptics: PodiumHaptics
}
@Composable fun PodiumTheme(atmosphere: Atmosphere, settings: AppearanceSettings, content: @Composable () -> Unit)
```
- `:core:designsystem` exposes tokens + components only. No business logic.
- Every component has light/dark/HC × Full/Blur/Solid previews and Roborazzi screenshots (Solid tier deterministic on JVM; Full tier verified on device).
