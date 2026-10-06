# Podium customization

Status: implemented 2026-10-06 (D-41). Code: `core:designsystem` (`theme/VirtualDisplay.kt`,
`type/Typography.kt`, `shell/Glitter.kt`, `shell/DeviceAppearance.kt`, `shell/VirtualScreen.kt`),
`feature:settings` (`Appearance.kt`, `SettingsScreens.kt`), app (`SharedPrefsDeviceSettings.kt`,
`DisplayImages.kt`, `PodiumApp.kt`).

Podium is one device (D-26): a **body** (finish, Wheel, buttons) and a **virtual display** inside it.
Customization keeps that split. Body settings never touch the display; display settings never touch
the body. Every choice is a field of one value, `DeviceAppearance`, so previewing, persisting and
restoring the look is one operation.

```
DeviceAppearance
├── preset, customArgb, grain          body finish (D-26)
├── glitter: Glitter                    body material (§6)
├── display: DisplayTheme               the display's visual system: GLASS, CARBON, BONE, CUSTOM
└── screen: VirtualDisplay              the display's dress (§2)
        ├── font: DisplayFont           (§3)
        ├── background, solidArgb, imageUri, imageOpacity   (§4)
        └── contrast: TextContrast      (§5)
```

## 1. Principles

- **Off is exactly as before.** Defaults: no glitter, Classic font, the theme's own display,
  standard contrast. A fresh install, and an install upgraded from before this change, look
  identical to the previous release (tested: `DeviceSettingsPersistenceTest`,
  `CustomizationScreenshotTest.glitterInTheBody` compares the body's pixels).
- **Readability can't be broken by a choice.** Ink colours are chosen by contrast with what is
  actually drawn behind them, never fixed (§5).
- **Carbon and Bone stay themselves (D-29).** No background colour or picture on them; album art is
  the only colour there. Custom is the theme for your own background.
- **No new paradigm.** Every setting is a Podium paper menu or a Wheel-driven level; choices preview
  live on the device as the Wheel turns, Center keeps, Menu restores.

## 2. The virtual display model

`VirtualDisplay` (pure data) holds the listener's choices. `DisplayColors` turns a theme, the
choices and the decoded picture into the display's `PodiumColors` and a `DisplaySurface` (what is
drawn behind the content). `PodiumTheme(displayTheme, display, displayImage)` provides colours,
surface, typography preset and type scale through composition locals; nothing below it picks a
font or a colour on its own.

`VirtualDisplay.backgroundFor(theme)`: Carbon/Bone → none; Custom → at least Solid; Glass → as chosen.

## 3. Fonts

| `DisplayFont` | Face | Size factor | Tracking | Notes |
|---|---|---|---|---|
| Classic | Instrument Sans (`wdth` 100) | 1.00 | — | the default; exactly the previous type scale |
| Clean | Inter | 0.95 | — | wide coverage; its own gaps fall to the system |
| Industrial | Space Grotesk | 1.03 | — | technical grotesk |
| Mono | JetBrains Mono | 0.90 | +0.1 sp | fixed width runs long |
| Pixel | Pixelify Sans | 1.10 | +0.3 sp | drawn on a pixel grid; read best larger |
| Condensed | Instrument Sans at `wdth` 75 | 1.04 | +0.1 sp | Podium's own face, narrow (no extra file) |

- `TypographyPreset.of(font)` builds the whole `PodiumType` scale in the face; size factors keep
  x-heights near Classic's, so rows, the paper and the focus geometry keep their rhythm.
- **Whole strings, never mixed faces (ADR-010).** `preset.familyFor(text)` returns the face only if
  it covers every character (tables generated from each font's cmap: `DisplayFontCoverage.kt`),
  otherwise Inter — e.g. Cyrillic in Industrial, Greek in Pixel.
- **The body is never restyled:** the Wheel's MENU legend stays Instrument Sans.
- The font picker shows each row in its own face; turning the Wheel restyles the whole display.
- All faces are bundled, unmodified, SIL OFL 1.1, from the official Google Fonts repository; license
  texts in `third_party/licenses/`, provenance in `third_party/FONTS.md`. No font is ever
  downloaded at runtime. Added APK size: about 400 KB.

## 4. Background

| Background | Shows | Ink |
|---|---|---|
| None | the theme's own display (Glass: tinted by the playing artwork; Custom: its colour) | the theme's |
| Solid | the background colour | light or dark, by contrast |
| Image | a picture over the background colour at **Background opacity**, under a veil of that colour | by contrast with the blend |

- **Choosing a picture:** the system photo picker (`ActivityResultContracts.PickVisualMedia`,
  images only). Podium asks for **no storage permission**. It keeps the URI and takes persistable
  read access (`takePersistableUriPermission`); choosing another picture releases the old one.
- **Never copied, never in a database.** Only the URI string is stored (§7). `DisplayImages`
  decodes it off the main thread, downsampled to at most 1600 px on its long edge, and measures its
  average luminance (16 × 16 sample).
- **A picture that disappears** (deleted, access revoked, unreadable): the display shows the
  background colour instead, nothing crashes, and Settings shows "Unavailable" for the background
  (Virtual display) and for the picture (Background). Nothing is logged except the
  exception class.
- **Drawing:** one bitmap draw per frame of the display (cropped to fill, centred), then the veil.
  Glass's artwork atmosphere is not drawn under your own background.

## 5. Colours and contrast

- **Ink by contrast.** For a solid colour, near-black (`#121212`) or near-white (`#F4F3EF`) ink,
  whichever has the higher WCAG contrast. Glass on your colour picks light or dark glass the same
  way.
- **Mid-tones.** A few colours (a saturated mid blue, say) leave even the better ink below 4.5:1;
  the display then nudges the colour just far enough darker or lighter (`readablePaper`). The
  setting keeps the chosen value.
- **Quieter inks** step toward the paper only as far as they keep 4.5:1 (secondary) and 3:1
  (tertiary). Tested over a sweep of the whole sRGB cube (`VirtualDisplayTest`).
- **Over a picture:** the ink is chosen against the blend of colour, picture (average luminance) and
  veil; the veil (22 %, or 40 % with High contrast) always tones the picture down; quieter inks stay
  nearer the primary; text gets a soft halo of the display's colour (`PodiumText`, only while a
  picture shows).
- **Contrast: High** (Settings ▸ Virtual display ▸ Contrast) brings secondary and tertiary text toward primary, strengthens
  separators, and keeps 60 % of the picture's opacity.

## 6. Glitter

- **What it is:** fine reflective flakes embedded in the body's material, like metal-flake paint
  seen up close. Most flakes face away from the light (darker specks in the body's own colour, a
  little deeper); some catch it (lighter, less saturated — silvery on dark bodies). No stars, no
  sparkle, no motion by default.
- **Settings** (Device body): Glitter Off/On; Glitter amount (the share of flakes catching the
  light), density (flakes per area), size (≈ 0.4–1.4 dp), opacity; Glitter animation Off/Subtle.
  Defaults when turned on: amount 40 %, density 35 %, size 30 %, opacity 50 %, animation off.
  Solid finishes only (Glass has no material to hold flakes); the matte black hardware of Carbon,
  Bone and Custom gets silver flakes.
- **Rendering:** `GlitterField` places flakes in a 512 px tile, seeded, so the pattern never changes
  between launches; it is rasterised once per setting (two white alpha tiles, quantised to 5 %
  steps, three cached) and drawn as two repeated-shader rects tinted with the body's colours.
  Static glitter costs two tiled draws when the body redraws; there is no per-frame work.
- **Subtle animation:** a soft diagonal glint crosses the lit flakes over 14 s, as if the device were
  tilted under a lamp. It updates about 12 times a second, only redraws the body, suspends while no
  frames are drawn (screen off, app in the background), and is off with reduced motion.
- The Wheel and buttons never glitter (separate pieces).

## 7. Persistence

`SharedPrefsDeviceSettings` (private `device` preferences): every field of `DeviceAppearance`, read
defensively (unknown enum names → defaults, levels clamped to 0–1, colours forced opaque). It
survives force stop, restart and reboot; it is independent of the library database, so database
migrations can't touch it. The picture is stored only as its URI. Tested by
`DeviceSettingsPersistenceTest` (round trip, fresh install, damaged values).

## 8. Settings

```
Settings
└── Appearance
    ├── Device body
    │   ├── Finish ▸ (Glass, Steel gray, Burgundy, Glacier blue, Silver, Custom color)
    │   ├── Custom color ▸ (hex or Wheel hue walk)
    │   ├── Grain ▸ (level)
    │   ├── Glitter (Off / On)
    │   ├── Glitter amount ▸   Glitter density ▸   Glitter size ▸   Glitter opacity ▸ (levels)
    │   └── Glitter animation (Off / Subtle)
    └── Virtual display
        ├── Theme ▸ (Glass, Carbon, Bone, Custom)
        ├── Font ▸ (Classic, Clean, Industrial, Mono, Pixel, Condensed)
        ├── Background ▸ (None, Solid, Picture / Choose a picture, Choose another picture)
        ├── Background color ▸ (hex or Wheel hue walk)
        ├── Background opacity ▸ (level; only with a picture)
        └── Contrast (Standard / High)
```

Rows that don't apply say why ("Solid finishes only"; on Carbon and Bone one row, "Backgrounds come
with Glass and Custom", replaces the background rows; Background opacity appears only with a
picture) and reject with a haptic. The paper column is narrow, so rows carry no redundant values
(a swatch instead of a hex code), and a row's label keeps its room before its value (`MenuRow`). Levels are edited by `LevelScreen` (Wheel ±, live preview,
Center keeps) and explain when they can't be adjusted yet.

## 9. Tests

- `VirtualDisplayTest` (8): contrast over the whole sRGB cube, ink polarity, Carbon/Bone untouched,
  picture blending and fallback, High contrast, every font's scale and fallback, glitter seeding,
  share and colours.
- `CustomizationScreenshotTest` (3, Robolectric native graphics): glitter on three bodies and the
  pixel-identical "off"; every font; Custom on paper, mid blue and a loud generated picture
  (standard and High contrast); Glass on a picture; Carbon ignoring a picture.
- `DeviceSettingsPersistenceTest` (3).
- Device steps: `docs/testing/UI_DEVICE_ACCEPTANCE.md` §2 (not yet performed on the phone).

## 10. Limits

- The picture's luminance is an average; a picture with large very bright and very dark areas can
  still make some rows harder to read at high opacity — lower the opacity or choose High contrast.
- Pictures are not cropped by the listener (centre crop only).
- Glitter on the Glass finish isn't offered.
