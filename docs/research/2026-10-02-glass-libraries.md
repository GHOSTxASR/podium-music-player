# Research: Liquid Glass implementations & skills

**Researched:** 2026-10-02 · **Method:** GitHub API, library docs.

| Candidate | What it is | Platform | Maintenance | License | Verdict for Podium |
|---|---|---|---|---|---|
| **Backdrop** (Kyant0/AndroidLiquidGlass) | Backdrop capture + effect chain (color controls, vibrancy, blur, **lens refraction**, custom AGSL) for Compose Multiplatform | Android, JVM, iOS/macOS, Wasm/JS | 4.0k★, v2.0.1 (2026-08-26), active | Apache-2.0 | **Adopt**, wrapped behind Podium's `GlassSurface` API (ADR-007) |
| Haze (chrisbanes) | Backdrop **blur** for CMP; materials presets; progressive blur | CMP | 2.6k★, v2.0.1 (2026-09-29), very active | Apache-2.0 | Not adopted — Backdrop covers blur *and* refraction; two glass engines would be dependency soup |
| OpenGlass UI | 40 React components, CSS-first glass | Web | 6★, active | MIT | Not applicable (web) |
| Liquefy UI | React components, WebGL edge refraction | Web | 5★ | MIT | Not applicable (web; WebGL) |
| GlassKit (amnn-in) | CSS/SVG/WebGL refraction engine + generator | Web | 1★ | MIT | Not applicable |
| LiquidGlassSkill (stormaref) | Claude skill: CSS tint/rim layer + SVG displacement refraction, rules & checklist | Web (Angular directive) | 11★ | MIT | Not installed. Principles adopted (below) |
| apple-hig-designer-skill-2026 | Claude skill wrapping HIG guidance | — | 3★ | MIT | Not installed; we read the HIG directly (primary source beats a summary) |
| Frontend-design skill (Anthropic) | Craft guidance against templated aesthetics | — | — | — | Not installed in this environment; read from source and applied (see typography note) |

## Backdrop library — technical facts that constrain the design
- Effects are `RenderEffect`s → **API 31+**; lens/AGSL effects → **API 33+**.
- Required effect order: **color filter ⇒ blur ⇒ lens**.
- Lens: `lens(refractionHeight, refractionAmount, depthEffect, chromaticAberration)`; height ∈ [0, minCornerRadius], amount ∈ [0, minDimension]; shape must be `CornerBasedShape` → circles and capsules are fine (the Wheel is a circle; the focus lens a capsule).
- `rememberLayerBackdrop` + `Modifier.layerBackdrop` captures content; `drawBackdrop` draws it into a glass element; `rememberCombinedBackdrop` merges backdrops (useful for the center button sitting over the ring).
- No high-level components ship — consistent with Podium owning its components.
- Known crash class: RenderThread SIGSEGV in some bottom-sheet setups (documented FAQ fix) → `GlassSheet` follows the documented pattern and gets a device test.

## Principles adopted from LiquidGlassSkill & the HIG
1. **Glass needs a backdrop.** Over a flat fill it reads as a grey disc. Podium's backdrops are real content: list rows scrolling beneath the Wheel; on Now Playing, a mirrored/blurred extension of the artwork (Apple's *background extension effect*). No invented gradient meshes.
2. **Tint is colourless; colour comes from behind.** Neutral glass everywhere except the focus lens.
3. **Two layers per surface:** a tint/rim layer (works everywhere, also the fallback) + a refraction layer (only where supported). Podium's fallback tiers are literally the tint/rim layer alone.
4. **Refraction is capability-gated and cannot be the only state signal.**

## Sources
- https://github.com/Kyant0/AndroidLiquidGlass · https://kyant.gitbook.io/backdrop
- https://github.com/chrisbanes/haze
- https://github.com/moekoelueker/open-glass-ui
- https://github.com/liquefy-ui/liquefy-ui
- https://github.com/amnn-in/glasskit
- https://github.com/stormaref/LiquidGlassSkill
- https://github.com/tristan-mcinnis/apple-hig-designer-skill-2026
