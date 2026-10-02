# Research: Typography, icons, and design-guidance references

**Researched:** 2026-10-02 · **Method:** downloaded OFL fonts from `google/fonts`, rendered an iPod-style specimen (title, menu rows with focus bar, track title, timecodes, quality label) with Pillow at 2×; inspected `fvar`, GSUB features, and cmap coverage with fontTools.

## Constraint
SF Pro and SF Symbols are licensed for use on Apple platforms only. Podium needs its own type and symbol system.

## Candidates rendered
Inter, Source Sans 3, Instrument Sans, Hanken Grotesk, Figtree, Onest, Albert Sans, Schibsted Grotesk.

Visual read (dark list rows, 17sp semibold, 30sp bold title):
- **Inter** — the open SF analogue; flawless but the most common face in generated UIs. Brief explicitly warns against it as a default.
- **Source Sans 3** — humanist, Myriad-like (closest to the original iPod's *Podium Sans*); reads 2008-corporate at display sizes.
- **Instrument Sans** — precise, slightly condensed grotesque; calm with quiet character; best rhythm in dense rows.
- **Hanken Grotesk** — clean neutral; **no `tnum`** → disqualified (timecodes would jitter).
- Figtree (too soft/friendly), Onest (generic), Albert Sans (geometric, colder), Schibsted (editorial).

## Feature/coverage audit
| Font | Axes | `tnum` | `case` | U+2212 | Latin Ext-A/B | Greek | Cyrillic | Vietnamese |
|---|---|---|---|---|---|---|---|---|
| Instrument Sans | wdth 75–100, wght 400–700 | ✓ | ✓ | ✓ | 106 | ✗ | ✗ | 2 (incomplete) |
| Inter | opsz 14–32, wght 100–900 | ✓ | ✓ | ✓ | 334 | 105 | 248 | 90 |
| Hanken Grotesk | wght 100–900 | ✗ | ✓ | ✓ | 159 | 10 | ✗ | 90 |

## Decision (recorded in ADR-010)
- **Primary:** Instrument Sans — all Podium-authored UI text, numerals (tabular), labels. Its width axis mirrors SF's width family; used to fit long titles before truncating.
- **Coverage fallback:** Inter — selected **per string** when a metadata string contains code points Instrument Sans lacks (prevents mid-word glyph mixing). Anything Inter lacks (CJK, Devanagari, Arabic, …) falls to the system Noto chain.
- **Symbols:** Material Symbols Rounded variable font (Apache-2.0), subset to the ~40 glyphs Podium uses. A variable symbol font gives the SF Symbols model: weight-matched to adjacent text, `FILL` axis for selected states, `GRAD` for high-contrast, `opsz` per size.

## Name lore (verified)
"Podium Sans" was the actual name of the iPod's bitmap UI typeface (iPod photo → 2007 refresh), a modification of Myriad, revealed via iPodWizard. The provisional product name therefore has genuine heritage — and an Apple association to weigh in trademark clearance.

## Frontend-design skill (Anthropic, `plugins/frontend-design`) — applied takeaways
- "Spend your boldness in one place." → The Wheel + focus lens is the memorable element; everything else stays quiet.
- Listed generated-design tells to avoid: all-caps labels, middle-dot meta strings, monospace micro-labels, tinted near-black as a default, identical rounded cards with the same soft shadow. My first specimen's quality badge ("FLAC · 24-BIT / 96 KHZ · 2,118 KBPS") hit three of them; the quality label is redesigned in natural case (`design-system.md` §6.10). The only all-caps text in Podium is the heritage `MENU` legend on the wheel.
- Copy: sentence case, active verbs, errors that explain and don't apologise, empty states that invite action.

## Sources
- https://github.com/google/fonts (ofl/instrumentsans, ofl/inter, ofl/hankengrotesk, …)
- https://en.wikipedia.org/wiki/Podium_Sans · https://theapplewiki.com/wiki/Podium_Sans
- https://fonts.google.com/icons (Material Symbols, Apache-2.0)
- https://github.com/anthropics/claude-code/blob/main/plugins/frontend-design/skills/frontend-design/SKILL.md
