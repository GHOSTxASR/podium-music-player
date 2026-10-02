# ADR-010 — Typography and iconography

**Status:** Accepted · **Date:** 2026-10-02

## Context
SF Pro/SF Symbols cannot ship on Android. The type must feel precise, calm, and premium; numerals must be tabular; metadata arrives in any script. The brief warns against generic choices (it names "Inter + purple gradient").

## Options considered
Eight OFL families rendered side by side (`research/2026-10-02-typography-and-icons.md`). Icons: SF Symbols (not licensable), Phosphor (MIT, static weights), Lucide (ISC, stroke-only), Material Symbols Rounded (Apache-2.0, variable font).

## Decision
- **Instrument Sans** (OFL, variable `wght` 400–700, `wdth` 75–100) for all Podium-authored UI text and all numerals (`tnum`).
- **Inter** (OFL, variable `opsz`/`wght`) as the **per-string coverage fallback** for metadata (titles, artists, albums, lyrics) containing characters Instrument Sans lacks (Greek, Cyrillic, full Vietnamese…). Selection is per string (via a precomputed code-point set), not per glyph, to avoid mixed-font words. Remaining scripts fall to the system Noto chain.
- **Material Symbols Rounded** (variable `wght`, `FILL`, `GRAD`, `opsz`), subset at build time to the glyphs in `design-system.md` §9 (~40 glyphs, ≈ 40–60 KB). Symbol weight tracks adjacent text weight; `FILL=1` marks selected/on states; `GRAD` rises in high-contrast mode.
- Fonts are bundled (not downloadable fonts) so first frame is correct offline.

## Why
Instrument Sans gives Podium a distinct, quiet voice with SF-like width variants; Inter guarantees international coverage without visible font salad; a variable symbol font is the closest open equivalent of the SF Symbols model.

## Tradeoffs
Two text families to maintain; per-string selection costs a cached code-point scan (ASCII fast path).

## Future implications
Localising the UI into non-Latin scripts would move those locales' UI text to Inter wholesale (a locale-level switch in the type tokens).
