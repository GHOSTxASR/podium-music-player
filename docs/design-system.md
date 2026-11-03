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
8. **Natural language.** Sentence case everywhere. The only all-caps strings in the product are the `MENU` legend on the Wheel (hardware heritage) and the boot's "PODIUM" wordmark, which is a logotype rather than copy (D-71).

---

## 1. Composition

### 1.1 Compact portrait (phones) — the canonical layout (the device, D-26)
```
┌──────────────────────────────┐  status bar (system, transparent; icon colour follows the finish; hidden in full screen, D-85)
│ ╭──────────────────────────╮ │  Body: the window, in the chosen finish (§5.8)
│ │(‹)       Songs         ▶ │ │  ScreenHeader inside the screen (44dp): back, title, play state (full screen: + time left, battery right)
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
  - Left, before the list: the previous column, live — that screen itself (`LocalPaperPeek`, a `LocalMiniature` composition with no input or side effects), zoomed in on its list with the item that led here focused (`LocalMiniatureFocusKey`), on a raised tile whose edges feather out (eased, over 22 dp at the right and ~18 dp at the top and bottom; at the left too when the tile sits wholly on the display), so it dissolves into the display with no hard edge or corner. Tapping it goes back. Root screens have none.
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

Atmosphere follows the **now-playing** artwork app-wide; on album/artist screens it follows that screen's artwork while visible. (Until D-101 the shell read the cover before its artwork loader was provided, so the atmosphere stayed neutral; it now reads it through the loader. On Glass the album tints the display on Now Playing only; the Glass body keeps its neutral light, D-101.)

---

**Custom theme and backgrounds (D-41).** Besides Glass and Carbon, the display can be Custom: the matte instrument on the listener's own colour or picture, ink chosen by contrast (primary ≥ 4.5:1, secondary ≥ 4.5:1, tertiary ≥ 3:1 on any solid colour), a veil and a text halo over pictures. Carbon never takes a background. Since D-108 Custom is the only display that draws the instrument. Spec: `architecture/PODIUM_CUSTOMIZATION.md` §4–§5.

## 3. Typography

### 3.1 Families
- **Instrument Sans** (variable `wght` 400–700, `wdth` 75–100): all UI text, numerals.
- **Inter** (variable): metadata strings not fully covered by Instrument Sans (per-string selection; ADR-010).
- System Noto: anything Inter lacks.
- OpenType: `tnum` on every numeric style; `case` on titles containing parentheses; true minus (U+2212) for remaining time.

**Display fonts (D-41).** The listener can set the virtual display in another face (Settings ▸ Appearance ▸ Virtual display ▸ Font: Classic, Clean, Industrial, Mono, Pixel, Condensed). Each is a `TypographyPreset` that rebuilds this whole scale with a size factor matching x-heights, and falls back to Inter per whole string. Classic is the scale below, unchanged. The Wheel's legend is on the body and never changes. Spec: `architecture/PODIUM_CUSTOMIZATION.md` §3.

### 3.2 Scale (sp; line height; weight; tracking; width)
| Style | Size/LH | Weight | Tracking | Width | Use |
|---|---|---|---|---|---|
| `largeTitle` | 30/36 | 700 | −0.3 | 100 | Home "Podium", empty-state titles on large screens |
| `title` | 17/22 | 700 | −0.2 | 100 | TitleBar title |
| `nowPlayingTitle` | 24/30 | 700 | −0.3 | 100 | Track title on Now Playing (one line; scrolls slowly when too long) |
| `nowPlayingSubtitle` | 17/22 | 600 | −0.1 | 100 | Artist on Now Playing (the album at 500) |
| `row` | 17/22 | 600 (700 focused) | −0.1 (−0.15 focused) | 100 | Menu rows, primary line of track rows |
| `rowSecondary` | 14/18 | 500 | 0 | 100 | Second line (artist, count) |
| `body` | 15/22 | 500 | 0 | 100 | Settings descriptions, sheets |
| `lyric` | 24/32 | 600 | −0.2 | 100 | Lyrics lines |
| `sectionHeader` | 15/20 | 700 | 0 | 100 | List section headers (title case, never caps) |
| `caption` | 13/16 | 600 | 0 | 100 | Timecodes (tnum), counters "3 of 12" |
| `footnote` | 13/18 | 600 | 0 | 100 | Quality label, secondary metadata |
| `indexGlyph` | 56/56 | 700 | 0 | 100 | Fast-scroll letter HUD |
| `wheelLegend` | 11/14 | 900 (Archivo) | +0.5 | 125 | `MENU` only (caps, heritage; the wordmark's face, D-103) |

**Weight and depth (D-103).** The scale is one step bolder than before: titles and the focused row at 700, rows at 600, quiet lines at 500. Symbols on keys and in the title bar are drawn at 700. On the dark lit displays (Carbon, Glass in dark mode) text sits a hair above the screen: a soft shade 1 dp beneath it (45 % black, 3 dp blur). Light displays and the instrument have none, and nor do the lyrics, which lay their own paper (`LocalTextDepth`, D-81, D-100); their lines keep their own weight.

Font scale: honoured to 200% (Android nonlinear scaling). Rows grow (`minHeight`, never fixed height). Wheel legends cap at 1.3× (fixed geometry; semantics carry the label). Bold-text (`fontWeightAdjustment`) adds +100 to weights (cap 700).

### 3.3 Truncation
Titles: end ellipsis; Now Playing: narrow width → wrap to 2 lines → ellipsis. Only the focused list row may scroll its title, and only when it doesn't fit (as the original iPod did; it follows the user's own focus, and is off with reduced motion). It rests at its start for 1.4 s, then eases into the scroll and eases out as the next copy arrives where it began (about 28 dp/s on average), rests 2 s and goes round again (D-46). No other marquees. The scrolling title may draw 8 dp beyond its box above, below and at its leading end (layout keeps its own size), so neither Carbon's glow nor a glyph reaching outside its line box is cut into a hard edge; its trailing end fades over the box's last 12 dp and stops at the box, so whatever follows the title keeps its whole gap (D-103), and its leading end fades as it scrolls out instead of stopping mid-letter. A title that fits is drawn as it is, with no fade. Row labels that are calls to action are written to fit ("Allow music access"), so they never scroll.

---

## 4. Space, shape, elevation

- **Spacing scale (dp):** 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64. Side gutter 16 (≥ 400dp width: 20).
- **One edge margin (D-103):** `Spacing.edge` = 10 dp from the display's edges to anything set against them, the same on every side it touches: the title bar's housings (top and sides), the mini player (sides and foot; 10 dp above it to the content), Now Playing's column and tray (sides and foot). The title bar is the margin, a 32 dp housing (`Spacing.housing`) and the margin: 52 dp. Lists start right below it and end at the bottom block, with no extra gap of their own. Between groups on Now Playing: 16 dp, measured between what's drawn (a line's ink, the playhead, a key's edge). Inside a surface, its own padding on every side: the mini player 8, the tray 4, a menu's lens 8 from the sheet.
- **Row heights (min):** menu 52 · track 60 (44dp artwork) · album 64 (52dp artwork) · settings 52 (+ description).
- **Touch targets:** ≥ 48×48dp everywhere; Wheel zones are ≈ 70–90dp bands.
- **Concentric radii:** `r_inner = max(r_outer − inset, r_min)`. `r_outer` for full-width floating elements = display corner radius from `WindowInsets.getRoundedCorner` (API 31+; fallback 28dp).
  | Element | Radius |
  |---|---|
  | The display (Glass, Carbon) | 26 |
  | Surfaces at the edge margin (`EdgeCorner`): the mini player, Now Playing's tray | 26 − 10 = 16 |
  | Inside them | 16 less their padding: the mini player's artwork 8, the tray's key 12 |
  | Menus | 22 (Carbon 20): the lens's 14 (Carbon 12) plus its 8 dp |
  | Focus lens | 14 |
  | Title-bar housings (back key, status), center button | circle / capsule, 32 dp (radius 16, concentric) |
  | Artwork | `clamp(size × 0.045, 4, 14)` — 44dp → 4, 64dp → 4, 300dp → 14 (Carbon: 0.18 × size, 6–14) |
- **Continuous corners (D-103):** every rounded rectangle outside the instrument takes Apple's continuous curve (`ContinuousShape`, `continuousRect`; smoothing 0.6, as iOS draws them): the corner eases into the straight edge instead of meeting it as a circular arc. That covers the display, menus and notices, the mini player, keys and trays, the focus lens and band, artwork, preview tiles and the search field. Capsules stay true capsules. The instrument (Custom) keeps its squared 2–4 dp corners.
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
On Glass every tier catches the cover's light (D-103): a faint glow of the album's accent from a surface's upper edge (16 % dark, 10 % light, gone by 80 % of its height) and its rim leaning 30 % toward the accent. The solid tier keeps its fill and adds the same glow, its hairline tinted the same way.

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
- Boot (D-102, after D-32 and D-71): Podium's mark reveals itself and the camera dives through it into the menu, the same on every theme. A dot swells (overshooting) from a circle into the icon's squircle: a near-black lens with an iridescent rim holding the P (Archivo 900/125, the icon's P) in fine horizontal lines whose colours part at the edges like a prism. It breathes and leaves two fading echoes of its rim; the lines close into solid silver as the lens fades; the P spins down to its edge; PODIUM unrolls from that sliver as from a turning cylinder (prism fringes fading as it flattens) and settles, silver with its flakes, a soft glow behind. Then the camera dives into the O: the mark grows about the O's counter until the counter covers the display, black everywhere but through it, where the live menu comes forward from the distance into focus. 3.55 s, one clock (`BootTimeline`); every frame is a function of time alone. Beats: the startup chord as the lens opens, a tick at the dot, a press as the lens lands, a tick as the P lands on its edge, a press as the wordmark settles, confirm when through. Any Wheel press skips into a 260 ms dive; reduced motion shows the still wordmark for 0.9 s and crossfades to the menu. The startup chord (`res/raw/podium_boot.ogg`, made by `tools/sounds/boot_chord.py`; synthesized for Podium, not Apple's trademarked chime) is an original strummed C major 9 over a soft low impact and a sub-octave C, with a lightly overdriven electric guitar strumming Cadd9 just behind it and a dark room tail, 5 s. It is a UI sound: system-sounds volume, silent on silent/vibrate, skipped when music is playing, toggle in Settings ▸ Startup sound.

### 5.8.1 Indicator lights (D-33)
Bottom corner of the body, opposite the power button: two 18 dp diodes in recessed bezels with 8 sp legends ("Battery", "Disk"). Battery: `#4CE06A` > 60 %, `#B8E04C` > 30 %, `#FFB020` > 15 %, `#FF3B30` below; breathes (1.4 s) while charging, blinks (0.9 s) at ≤ 10 %. Disk: `#FFA726`, a double flicker (60 ms + 140 ms) per `DiskActivity` pulse.

### 5.9 Display themes: Glass, Carbon, Custom (D-29, D-108)
| | Glass | Carbon (D-101) | Custom, the instrument (D-41) |
|---|---|---|---|
| Display | Graphite / light, atmosphere tint | `#0B0B0D`, lit by the playing album's atmosphere (§5.10) | the listener's own colour or picture; ink by contrast. The warm off-white `#ECE8DF` (`PaperColors`) is what Bone was until D-108 withdrew it, and is still the light template |
| Type | per §2 | `#F3F1EC` / `#B4B1A9` / `#7B7973` | `#141311` / `#68645C` / `#9B968C` |
| Selection | stained-glass lens (D-25) | lit text on a solid raised band, the album's accent on the indicator | lit text + faint band + near-black indicator |
| Glass | functional layer | none (solid tonal surfaces with shadows; the display behind a menu defocuses) | none |
| Hardware | chosen finish | blackened steel: brushed body, spun-metal wheel, matte centre | matte black, knurled mechanical wheel |
| Shapes | continuous corners and capsules (§4) | continuous corners, concentric with the display (§5.10) | squared: 2–4 dp corners, outlined keys, segmented strips, flat rule |
| Speciality (D-103) | the cover's light on the glass, and the status housings budding and pinching like liquid | the album's light and accent on solid surfaces | none of its own |
On the instrument, album artwork is the only colour. On Carbon the album also lights the display and gives one accent; text stays neutral. The theme never changes the content's information or its layout, only its material: Now Playing is one composition on every theme (§6.7).

### 5.10 Carbon's tonal system (D-101)
Code: `core/designsystem/.../tonal/` (`TonePalette`, `AlbumTone`, `AtmosphereField`, `AlbumAtmosphere`, `TonalSurface`, `Defocus`), `component/Squiggle.kt`, `shell/Metal.kt`.

- **Layers, back to front:** the base (`#0B0B0D`) → the album's atmosphere → solid surfaces (trays, the mini player, sheets) → keys (the transport) → active and selected states (the focus band, toggled actions on an accent backing) → small lights (the indicator, the playhead).
- **Album tone:** from a 32 × 32 reduction of the playing cover, the hue family with the most colour seeds: accent OKLCH L 0.80, C 0.05–0.11 (gamut-mapped by chroma); on-accent L 0.23; accent container L 0.33; surfaces L 0.185 / 0.215 / 0.255 / 0.30, tinted ≤ 0.014 C. Colourless covers keep the neutral palette (accent `#E6E3DC`, surfaces `#141416` / `#1A1A1D` / `#232327` / `#2D2D32`). Text never takes it. All roles move together in 700 ms; read while drawing.
- **Atmosphere:** the cover pressed into OKLab L 0.10–0.30, chroma ≤ 0.24 L, blurred at 96 px (three box passes, ≈ σ 10 %), drawn 1.3× past the display; header scrim, corner vignette, a dithering grain. Strength by screen: Now Playing 1, Up Next 0.6, menus 0.55, lists 0.4, Settings 0.22, Lyrics 0. No cover: a neutral light from the top. Crossfades over 900 ms; a cached layer. On Now Playing, while the music plays, it flows (D-103, D-104): two copies of the field drift across the display and turn against each other, a turn in 47 s and 35 s, easing in over 1.4 s and to rest over 2.2 s (`animation-system.md`); still with reduced motion. A new cover's colours blend in over 1.8 s, over whatever mixture is showing. Chroma is capped at 0.30 × L on Carbon (0.38 dark Glass, 0.13 light Glass), the cover's own chroma kept in full. Glass's Now Playing uses the same field, toned for glass (OKLab L 0.16–0.50 dark, 0.80–0.95 light).
- **Surfaces (`tonalSurface`):** opaque; lit from above (1 dp inner highlight, darker lower edge); shadow 1 dp (tray), 3 dp (key), 8 dp (floating), 16 dp (sheets); pressed sinks (shade +16 % black, shadow × 0.35).
- **Radii (`CarbonRadius`, continuous):** display 26 · sheets 20 · inset surfaces 14 · keys and trays 16 · band and tiles 12 · labels 8 · artwork 0.18 × size (6–14).
- **Overlays:** behind an open menu or notice the display defocuses (14 dp blur, 220 ms in, 140 ms out, Android 12+) under a 30 % dim (50 % without blur). The panel is never blurred.
- **Hardware:** brushed blackened steel body (horizontal streaks, chamfer highlights, a seam around the display); the Wheel's ring spun graphite (fine concentric machining that turns with the finger; a faint two-lobe reflection that stays with the light and swings with the phone's tilt, at most ±50°); the centre a matte bead-blasted coat, recessed; engraved legends; contact shadows and seams. Geometry, zones and touch unchanged.

### 5.7 Enforcement
Lint rule (custom detector in `build-logic`): `Modifier.blur`, `RenderEffect`, `RuntimeShader`, and `com.kyant.backdrop.*` are errors outside `:core:designsystem`. A debug overlay counts simultaneous glass surfaces; > 5 persistent surfaces logs a warning.

---

## 6. Components

### 6.1 `PodWheel`
- **Geometry:** D = `clamp(0.66 × width, 232, 312)dp` (portrait), size setting S/M/L × 0.9/1.0/1.1. Center button d = 0.38 D. Gap ring 3dp between ring and center (atmosphere shows through: two pieces of glass).
- **Legends** on the ring midline: `MENU` (12 o'clock, `wheelLegend`: Archivo 900 at 125 % width, 11 sp, one line), ⏭ (3), ⏯ (6), ⏮ (9) — symbols 22dp, weight 700, `labelSecondary` (on glass, vibrant). Depth on every finish (D-103): light legends stand off the ring on a soft shadow (1.25 dp down, 1.5 dp blur, 60 % black; 85 % on metal), dark legends are pressed in, with a white catch-light 1 dp below.
- **Materials:** ring `GlassRegular`; center `GlassControl` (sampled from a combined backdrop so it refracts the ring + content).
- **Feedback:** finger specular (radial white 20%, r 48dp) follows touch on the ring; zone press = darken 8% (16 % on dark finishes) + legend scale 0.9, the shade full across the zone's middle (±26°) and fading out by ±58° so it blends into the ring (D-103); center press = lens squish; haptics per `interaction-model.md` §6.
- **Idle:** completely static.
- **Disabled zones:** legend 35%, no response (e.g., ⏮/⏭ with an empty queue).
- **States per context:** the Wheel never changes appearance by context except an optional tiny center glyph in non-list modes (speaker in Volume, diamond in Scrub) at 40% opacity.
- Full gesture & input spec: `interaction-model.md`.

### 6.2 `FocusLens`
- Shape: rounded rect r 14, inset 8dp horizontally from list edges, 3dp vertically within the row.
- Material: `GlassFocus` (Full/Blur) / solid highlight (Solid, HC).
- Motion: spring `focus` (stiffness 1400, damping 0.86); detents arriving < 90 ms apart (a spin) use `focusFast` (2400, 1.0) (D-65). Boundary hit: boundary haptic. A move of more than three rows jumps lens and list together. The same on every theme (Glass's liquid lens of D-103 was withdrawn, D-104).
- Non-glass indicators (always): focused row text weight 600 (animated with the same spring), trailing chevron `FILL=1`, semantics `selected`.
- Keep-in-view (D-40): on the paper the focused row rides at the readable region's middle and the list's own scroll limits put the first and last rows flush with the region's edges; plain lists (menus) keep one neighbour in view each side, the first and last rows flush. On the paper the list follows the lens: it is scrolled from the lens's own `focus` animation frames (`FocusListState.centreLensNow`), so however fast the Wheel turns the two can't drift apart — mid-list the lens and its indicator hold still at the middle while the rows move, and only an end of the list (where the scroll stops) lets the lens travel. A move of more than three rows jumps lens and list together. Plain lists glide with their own short scroll; jumps (> 1.5 rows) are immediate.
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
Floating, not a slab (`ScreenHeader`, D-103), 52 dp tall: every housing sits 10 dp from the display's top and sides (§4). On the left the back key (a 32 dp housing in the display's material, in a 40 dp touch target) when the stack is deeper than one, then the `title`, left-aligned, 10 dp past the key (at the margin without one), travelling with the paper. On the right the status, every item in a housing (`StatusPills`): the time alone in one (full screen, D-85); the output (D-52), the play state and the battery (full screen) sharing another, a hairline between each. Housings are 32 dp capsules, 6 dp apart, 8 dp either side of an item, painted as one outline: Carbon's low tonal surface, Glass's clear glass catching the cover's accent along its upper edge, the instrument's squared hairline housing. They move like liquid: an arriving item stretches its housing with a small overshoot; a housing arriving beside another buds off its side through a neck that pinches off; alone, it pops up as a bubble (motion in `animation-system.md`). Scroll-edge effect beneath (§5.5).

### 6.5 `MiniPlayer`
56 dp tall, 10 dp from the display's sides and foot (the edge margin, §4), corners 16 (concentric with the display); `GlassFloating`. Artwork 40 (r 8) 8 dp from its top, foot and start · title / artist (secondary) · ⏯ and ⏭ keys 40 × 56, the last glyph 8 dp from the end. Progress: 2dp hairline inset 20dp along the inner bottom edge (`labelPrimary` 80% on 20%). Tap → Now Playing (container transform). Hidden on Now Playing, Up Next, Lyrics, and when idle.

### 6.6 `AlbumArtwork`
Coil-backed; size-bucketed requests (48, 96, 192, 512, 1024 px); radius per §4; placeholder = monogram (first letter of album, `labelTertiary` on `canvasRaised`) — never a generic music note. Now Playing artwork has `shadow.artwork`. Crossfade 150ms on load (no fade if from memory cache).

### 6.7 Now Playing composition (D-28)
Inside the virtual screen, under the shell's ScreenHeader ("Now Playing", back, play state):
One composition on every theme (D-103, from D-101's parts in `NowPlayingControls.kt`); each theme paints only its material (Carbon's solid keys, Glass's clear glass, the instrument's outlines).
- **Stacked** (default), on the display's 10 dp edge margin, shared by the cover's edge, the text and the controls, its groups 16 dp apart (§4): the cover, flat, taking 72 % of the column, with its facts beside its lower edge ("N of M"; the measured quality as a small label in the theme's material; "Online" for an online song; the sleep timer; one note: what the player is doing about a problem, the error, scrubbing, "Source reported …") · title (`nowPlayingTitle`, left-aligned, scrolls slowly when too long, never wraps) · artist (`nowPlayingSubtitle`, secondary) · album (600 weight down to 500, secondary) · the seek bar with elapsed and remaining beneath it, in a fixed 52 dp block (the wave on Carbon and Glass, the flat rule on the instrument; tap or drag to seek; the volume bar in its place while the Wheel turns the volume) · the transport (⏮ 72 · the orb 72 · ⏭ 72 dp wide, the keys 56 tall, 10 dp apart; the keys' outer corners 26, their inner ones 20, curving away from the orb; the orb is the play key and carries no glyph — round while the music plays, a settled pebble when it is stopped, §6.10.2) · one tray, 10 dp from the display's foot and sides with corners 16, holding shuffle, repeat, favourite, lyrics, Up Next and More, a hairline between each (20 dp glyphs; an action that's on sits on an accent backing on Carbon; the Wheel's action mode slides a key under the focused one on the lens's `focus` spring). With Settings ▸ Now Playing's tray set to hide (D-108, off by default), the tray slides down off the display and fades 7 s after anything was last touched, and everything above it eases down by half the space it leaves, settling in the middle of the taller display and drawing back up when it returns (D-109); the Wheel's next move only brings it back.
- **Compact** (artwork box would be < 0.45 × width): artwork beside the text block, the rest below.
- **Landscape screen** (width > 1.15 × height): artwork on the left (square box), everything else beside it.
- **Artwork:** `FittedArtwork` keeps the image's proportions (square stays square; portrait/landscape aren't cropped, extremes beyond 1:2 are); shadow; hairline rim; while loading it shows a smaller cached copy, else a quiet surface; missing art gets `ArtworkFallback` (two tones from the title, Podium's ring, the initial). Breathes 0.93 (paused) → 1.0 (playing), from its lower corner on the margin in the stacked layout so it stays lined up with the text. It lies flat on every theme (D-58's lean retired by D-103). Track change (D-78): every new song moves the cover on, even when it shares the cover. The playing cover slides flat off the display to the left as the next comes in from beyond the right edge and settles in its place (360 ms, spatial curve). Previous (the song just before, in the same queue) runs the other way; a song chosen from a list or a search counts as new. The covers either side of the song are loaded ahead, so the incoming one is already showing. Reduced motion: a crossfade.
- **Environment:** on Glass, the cover's field toned for glass (`AlbumAtmosphere`, §5.10), flowing while the music plays; it is the captured layer the controls' glass refracts. On Carbon, the display's own atmosphere at full strength, flowing the same way. On the instrument, the display itself.

### 6.7.3 Nostalgic Now Playing (D-109)
Settings ▸ Now Playing: **New** (default) or **Nostalgic**. The nostalgic screen is the arrangement Now Playing had before D-103, with the playback controls taken out, so the Wheel does the playing as it did on the original:
- The cover at the left (70 % of the row, wider than the 62 % it had before D-103 now that nothing sits under it) with its facts beside it (30 %), both centred in what the rest leaves; the song's title, artist and album **centred** beneath it; the seek bar with its times; then the action tray.
- **No playback keys**: no orb, no ⏮ or ⏭. The title bar's play light says the state and blinks while paused (D-107).
- In their place, the vinyl's **dot visualiser** (§6.7.2; 88 % of the content's width, 40 dp tall). Stopped, it falls to the one lit line and stops asking for frames.
- Content keeps the 20 dp margin the screen had before D-103; the tray keeps the display's own 10 dp edge margin, concentric with its corners. Short displays use this layout too — with no transport it fits where the stacked screen would not.
- The tray hides here exactly as on the new screen (D-108), and the screen relaxes with it (D-109).

### 6.7.2 Vinyl (D-104)
Now Playing's tray ▸ Vinyl (the record glyph) opens a record player over the whole display (`VinylStage`, through the shell's `ImmersiveHost`), the display behind out of focus (28 dp, all themes) under a 50 % black scrim.
- **Way in** (2.4 s): the cover lifts from its place to the middle; the record slides up out of it; the sleeve drops away below the display as the record comes down to the middle and settles; the tonearm arrives and swings onto the outer groove, starting the music; the visualiser opens; the title and artist fade in at the foot (`body` 600 white, `footnote` 60 %).
- **Record:** 74 % of the display's width (at most 47 % of its height), centred at 41 % down. Black vinyl, grooves in bands, a lit rim; the label a disc of the cover (35 % of the radius) with a spindle hole. 33⅓ rpm while playing; seeks turn it by as much music as they skip. The room's light lies still across it.
- **Tonearm:** chrome, its base beyond the record's top right; rest hangs past the record, play sets the needle 86 % of the way out.
- **Visualiser:** a dark capsule (78 % of the record's width, 40 dp tall) of dots seven rows deep, two mirrored waves crossing along it at the music's loudness, tapering into its ends; silent, one lit line. While seeking, elapsed and remaining times beside it.
- **Wheel:** Menu back (out of seeking first), ⏮ ⏭ change the song (the record rolls away left, the next rolls in from the right; ⏮ the other way), Center seeks (turn to seek; 4 s untouched ends it). Nothing else does anything (a reject haptic); touches stop at the stage.
- **Way back** (2 s): the tonearm lifts away; the sleeve, with the current cover, rises from below; the record goes into it; the sleeve returns to Now Playing's cover as the display comes back into focus.
- Reduced motion: fades in and out, the record still.

### 6.7.1 ONLINE (D-34)
ONLINE uses the same paper, themes and Wheel — no new paradigm. Menus are paper lists whose previews come from the source's artwork (Home: a carousel of trending covers; Liked songs and History: their covers; Radio: recent covers); shelves open as song lists (cover, title over artist, length) or playlist lists, paged as the focus nears the end. Album and playlist pages keep the cover beside every row (it's the point). Artist pages: round picture header, Radio, Play popular songs, then sections with quiet footnote labels (Popular, Albums and EPs, Playlists, Related artists). Search: a hairline field (2 dp corners on Carbon and the instrument, 10 dp on Glass) as the list's first row; results stream in below by section. Unplayable songs say why in place of the artist. Now Playing marks an online song with the single word "Online" in its status line — nothing more. Offline and errors use the centred message state with a next step.

### 6.8 `ProgressScrubber` / `VolumeBar`
- Track 4dp (8dp in Scrub mode), radius full, fill `labelPrimary`, remaining `labelPrimary` 20%. Thumb appears only in Scrub mode / touch drag (12dp circle, `GlassControl`).
- Position read in draw phase only (no recomposition per frame).
- VolumeBar replaces the scrubber in place during wheel volume changes (speaker glyphs at both ends), fades back 1.5s after the last detent.
- **Carbon (D-101), `SquiggleProgress`:** the played part undulates in the album's accent (3 dp line, 22 dp wavelength, 2.5 dp amplitude, travelling a wavelength every 2.1 s toward the playhead) while playing, and lies flat when paused; it tapers into the start and the playhead. The rest is a straight line at 20 %. Playhead: an upright 5 × 18 dp bar with a dark halo (6 × 24 while touched or scrubbing). Times sit under the bar's ends. Touch seeks on down, drag and lift; TalkBack has range info and set-progress. Reduced motion: the wave holds still.

### 6.9 Overlays
- `GlassMenu` (context menus): `GlassRegular`, r22, rows 48dp, max 8 items then scroll; anchored to the focused row or the invoking control; wheel navigates it (focus lens inside).
- `GlassNotice` (D-99): a notice that asks for a decision. `GlassRegular`, r22 (r3 industrial), a title, a few lines of body text, an optional progress bar, then its choices as rows. The Wheel moves between choices, Center chooses, Menu dismisses (when it may). Unlike a menu, choosing doesn't close it: a choice like a download keeps it up to show progress. Used for Podium's update notice.
- `GlassSheet`: inset 8dp, top radius display − 8; half height default; becomes more opaque (+20% tint) when expanded to full (HIG). Implemented with the Backdrop-documented pattern to avoid the RenderThread crash class.
- `HudToast` (`GlassFloating`, capsule, centered above the MiniPlayer): "Playing next", "Added to queue", "Added to Late Nights" — 1.6s, no stacking (replace).
- `IndexGlyph`: 96dp rounded square `GlassFloating` centered in the readable region; shows the current initial letter during fast rotation in sorted lists; fades out 600ms after rotation slows.

### 6.10 Small components
`QualityLabel` (footnote, secondary, tappable, e.g., "FLAC 24-bit/96 kHz"; absent when unknown) · `DownloadIndicator` (12dp glyph states: queued ○, progress ring, done ●, failed !, in `labelTertiary`/`positive`/`critical`) · `LikeButton` (heart outline ↔ filled `like`, TOGGLE haptic) · `Toggle` (track 51×31 classic proportions; knob becomes `GlassControl` while dragged, per HIG) · `PodiumTextField` (D-45; the one text field: with the Podium keyboard it opens the keyboard the Wheel becomes, with the Phone setting it is a `BasicTextField` that summons the phone's keyboard; see interaction-model.md §2.1) · `PaperTextField` (a field as a list's row: a hairline on the lit displays, a solid field set into Carbon's; Online's search and both environments' playlist names, D-106).

**Quality label wording:** `<Codec> <bitDepth>-bit/<sampleRate> kHz` for lossless (`FLAC 24-bit/96 kHz`), `<Codec> <bitrate> kbps` for lossy (`AAC 256 kbps`). Sample rates print as 44.1, 48, 88.2, 96, 176.4, 192. Never "Lossless"/"Hi-Res" unless `audio-architecture.md` §5.1 holds. No all-caps, no middle dots. Unknown → no label.

### 6.10.2 `LiveOrb` (D-107)
Now Playing's play key, as a creature: a body in the theme's own material with two eyes, moved every
frame by an `OrbFace` (squash, bob, forward roll, lean; the eyes' size, bow, lean, skew and gaze; a
burst of stars). 72 dp (60 compact), between ⏮ and ⏭. No play or pause glyph — the shape says it, and
the title bar's play light blinks while paused. **An eye** is a round-capped line as thick as its short
side and as long as its long side, bowed across its length: a capsule at rest, a flat dash shut, a wide
arch happy; the two meet at a dot, so it widens and squishes without a seam. **Materials:** Carbon solid
ivory (`#F2EBDC`) with near-black eyes and no light around it; Glass a thicker frost than a key's
(`GlassMaterial.Orb`); the instrument a hairline and its ink eyes. Stars are the album's accent on Carbon, the
display's ink elsewhere. What it feels, and when, is `OrbMind` in `:feature:nowplaying`
(`animation-system.md`). Content inside the display; glass only through `Modifier.glass`.

### 6.10.1 `OrbRenderer` (D-83)
The lyrics' orb: a luminous ivory ring with a touch of champagne gold around a dark hollow, on black,
moved by an `OrbFrame` (time, turn, energy, swell, ripple, glint, alpha). An AGSL shader from
Android 13 (the only `RuntimeShader` in the app, kept here per the glass rule), gradients before.
Content inside the display, never glass, never on rows or text.

### 6.11 State components
- `LoadingScreen` (D-43): nothing for 150 ms; then Podium's pinwheel, twelve spokes with the lit one stepping round about 12 times a second and a fading tail, in the display's ink (30 dp), above one footnote word ("Loading", "Reading your music"), centred in the readable region. It stands still with reduced motion. `LoadingRow` is the same as one list row (18 dp, secondary ink), e.g. "Searching". No placeholder rows, no shimmer.
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
The motion language and its tokens are in animation-system.md §2 (D-65): TACTILE `pressIn` 6000/0.9 · `pressOut` 1500/0.6 · `pop` 1200/0.82; MECHANICAL `detent` 1800/0.75; FOCUS `focus` 1400/0.86 · `focusFast` 2400/1.0; SPATIAL 420 ms on cubic-bezier(0.2, 0.7, 0.2, 1); CONTENT 160/90 ms; DRAMATIC 220/0.85; PERSONAL `settleIn` 200/0.86 · `settleBack` 300/1.0 · `rearrange` 220/0.88. Fades: fast 120ms, standard 220ms, atmosphere 900ms. Reduced motion: cross-fades ≤ 150–200ms, no 3D, lens jumps.

---

## 9. Haptics (D-105)
Haptics are a design layer of their own: the screens name what happened (`HapticEvent`), `HapticDesign` decides how it feels on this phone in the listener's profile, and one `HapticEngine` plays it (code: `:core:interaction`, `HapticDesign.kt`, `Haptics.kt`).

**Full control first:** Podium shapes its own haptics only where it has full control of the motor (amplitude control and at least the click and tick primitives). Any other phone gets the vibration Podium always had: the phone's own constants, event for event as before D-105 (`HapticDesign.legacy`), and Settings ▸ Haptics there is On or Off.

**Profiles** (Settings ▸ Haptics, with full control): Balanced (default), More tactile (×1.2, richer contours), Reduced (×0.6, no micro textures), System default (the old vibration), Off. All within the phone's touch-feedback setting.

**The ladder**, per event: (1) an envelope (Android 16+) for what is continuous by nature, a held centre's rising pressure; (2) a composition of primitives the vibrator reports, a missing one swapped for its nearest cousin (low tick → lighter tick, thud → click, spin → tick) and a missing rise or fall dropped; (3) a predefined effect by weight (tick, click, heavy click, double click); (4) nothing. A phone without full control never reaches the ladder: it plays the old vibration.

| Event | Composition (Balanced) | Category |
|---|---|---|
| Wheel detent | slow (< 8 detents/s) CLICK 0.75→0.65; medium (< 20) TICK 0.55→0.45; spin LOW_TICK 0.35→0.20 | light / micro |
| Wheel boundary (list ends, volume 0 / max, scrub ends) | THUD 0.85 (tactile: + CLICK 0.5 at 20 ms) | heavy |
| Centre down | CLICK 0.8 (tactile: TICK 0.45 + CLICK 0.85 at 14 ms, a two-part latch) | medium |
| Quarter / key down | CLICK 0.7 · keyboard key TICK 0.6 | light |
| Centre held | envelope 0.10 → 0.42 over 220 ms (else SLOW_RISE 0.35), from 40 % of the hold; stops on release or turn | light |
| Long press | THUD 0.85 + CLICK 0.75 at 20 ms | emphasis |
| Menu opens | TICK 0.5 + QUICK_FALL 0.5 | medium |
| Into / out of a screen (touch) | TICK 0.45 + CLICK 0.65 at 30 ms / CLICK 0.6 + TICK 0.35 at 30 ms | medium |
| Toggle on / off | TICK 0.5 + CLICK 0.8 at 25 ms / CLICK 0.6 + LOW_TICK 0.4 at 25 ms | light |
| Confirm / reject | TICK 0.5 + CLICK 0.85 at 40 ms / THUD 0.7 + THUD 0.5 at 70 ms | medium / emphasis |
| Step (volume, band, line) | TICK 0.35 + 0.35 × level | light |
| Skip next / previous | CLICK 0.75 + LOW_TICK 0.4 at 35 ms / mirrored | medium |
| Play / pause | CLICK 0.85 / TICK 0.6 + QUICK_FALL 0.45 | medium |
| Favourite on / off | QUICK_RISE 0.35 + CLICK 0.9 / TICK 0.45 | light |
| Pick up / pass a slot / set down | QUICK_RISE 0.5 + CLICK 0.7 / LOW_TICK 0.4 / THUD 0.85 | medium / micro / heavy |
| Collision (the vinyl's needle) | THUD at the impact, 0.15–1 | heavy |

**Cooldown law:** textures never closer than max(15 ms, duration + 4 ms), extra detents of a spin dropped; heavier events always cut in, each kind no more than once per 40 ms (boundary 120 ms, reject 150 ms), and no texture interrupts one while it plays. A detent goes first to whoever handles it: a screen that answers it itself (a boundary, a volume step) replaces the Wheel's click rather than adding to it. The click sound (D-32) plays with its haptic, under the same limits.

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
