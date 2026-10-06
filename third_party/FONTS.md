# Bundled fonts

| File | Family | License | Source |
|---|---|---|---|
| `core/designsystem/src/main/res/font/instrument_sans.ttf` | Instrument Sans (variable `wdth`,`wght`) | SIL Open Font License 1.1 | github.com/google/fonts `ofl/instrumentsans` |
| `core/designsystem/src/main/res/font/inter.ttf` | Inter (variable `opsz`,`wght`) | SIL Open Font License 1.1 | github.com/google/fonts `ofl/inter` |
| `core/designsystem/src/main/res/font/space_grotesk.ttf` | Space Grotesk 2.000 (variable `wght` 300–700), unmodified | SIL Open Font License 1.1 | github.com/google/fonts `ofl/spacegrotesk/SpaceGrotesk[wght].ttf`, fetched 2026-10-06, sha256 `acad6de1…72` |
| `core/designsystem/src/main/res/font/jetbrains_mono.ttf` | JetBrains Mono 2.211 (variable `wght` 100–800), unmodified | SIL Open Font License 1.1 | github.com/google/fonts `ofl/jetbrainsmono/JetBrainsMono[wght].ttf`, fetched 2026-10-06, sha256 `48715a42…eda` |
| `core/designsystem/src/main/res/font/pixelify_sans.ttf` | Pixelify Sans 1.000 (variable `wght` 400–700), unmodified | SIL Open Font License 1.1 | github.com/google/fonts `ofl/pixelifysans/PixelifySans[wght].ttf`, fetched 2026-10-06, sha256 `9ba86cd0…a5` |
| `core/designsystem/src/main/res/font/podium_symbols.ttf` | Material Symbols Rounded 2.973, subset to 45 glyphs (variable `FILL`,`GRAD`,`opsz`,`wght`) | Apache License 2.0 | github.com/google/material-design-icons `variablefont`; re-subset 2026-10-06 from the same 2.973 build served by fonts.gstatic.com, adding lyrics, palette, image, text_fields, auto_awesome, contrast, then backspace, shift, keyboard_capslock, keyboard_hide, keyboard_return for the Podium keyboard (45 glyphs; every earlier glyph verified byte-identical) |

| `core/designsystem/src/main/res/font/podium_symbols_filled.ttf` | Material Symbols Rounded, static instance (FILL 1, GRAD 0, opsz 32, wght 600) of the original 34-glyph subset (the six glyphs added later are outline only), with each glyph's coincident hole/fill contour pair removed (no other change to the outlines) | Apache License 2.0 | derived from the subset above |

The display fonts (D-41, `docs/architecture/PODIUM_CUSTOMIZATION.md` §3) are bundled unmodified — no subsetting, so no Reserved Font Name question arises (none of their OFL notices declares one). "Condensed" is Instrument Sans at its own `wdth` 75; "Clean" is Inter. The full license texts are in `third_party/licenses/` (OFL-InstrumentSans, OFL-Inter, OFL-SpaceGrotesk, OFL-JetBrainsMono, OFL-PixelifySans). Coverage tables (`DisplayFontCoverage.kt`, `InstrumentSansCoverage.kt`) are generated from each file's cmap with fontTools.

The symbols subset was produced with fontTools (`pyftsubset --unicodes=… --layout-features=''`), keeping all variation axes.
Attribution and license texts will be generated into the About screen in Phase 12 (`docs/deployment.md`).
