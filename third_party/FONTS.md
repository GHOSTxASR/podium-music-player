# Bundled fonts

| File | Family | License | Source |
|---|---|---|---|
| `core/designsystem/src/main/res/font/instrument_sans.ttf` | Instrument Sans (variable `wdth`,`wght`) | SIL Open Font License 1.1 | github.com/google/fonts `ofl/instrumentsans` |
| `core/designsystem/src/main/res/font/inter.ttf` | Inter (variable `opsz`,`wght`) | SIL Open Font License 1.1 | github.com/google/fonts `ofl/inter` |
| `core/designsystem/src/main/res/font/podium_symbols.ttf` | Material Symbols Rounded, subset to 34 glyphs (variable `FILL`,`GRAD`,`opsz`,`wght`) | Apache License 2.0 | github.com/google/material-design-icons `variablefont` |

| `core/designsystem/src/main/res/font/podium_symbols_filled.ttf` | Material Symbols Rounded, static instance (FILL 1, GRAD 0, opsz 32, wght 600) of the subset above, with each glyph's coincident hole/fill contour pair removed (no other change to the outlines) | Apache License 2.0 | derived from the subset above |

The symbols subset was produced with fontTools (`pyftsubset --unicodes=… --layout-features=''`), keeping all variation axes.
Attribution and license texts will be generated into the About screen in Phase 12 (`docs/deployment.md`).
