# Bundled fonts

| File | Family | License | Source |
|---|---|---|---|
| `core/designsystem/src/main/res/font/instrument_sans.ttf` | Instrument Sans (variable `wdth`,`wght`) | SIL Open Font License 1.1 | github.com/google/fonts `ofl/instrumentsans` |
| `core/designsystem/src/main/res/font/inter.ttf` | Inter (variable `opsz`,`wght`) | SIL Open Font License 1.1 | github.com/google/fonts `ofl/inter` |
| `core/designsystem/src/main/res/font/podium_symbols.ttf` | Material Symbols Rounded, subset to 32 glyphs (variable `FILL`,`GRAD`,`opsz`,`wght`) | Apache License 2.0 | github.com/google/material-design-icons `variablefont` |

The symbols subset was produced with fontTools (`pyftsubset --unicodes=… --layout-features=''`), keeping all variation axes.
Attribution and license texts will be generated into the About screen in Phase 12 (`docs/deployment.md`).
