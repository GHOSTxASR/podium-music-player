# Research: Apple Liquid Glass & HIG

**Researched:** 2026-10-02 · **Method:** Apple DocC JSON endpoints (`developer.apple.com/tutorials/data/...json`), read in full and converted to text. Secondary press coverage only for WWDC26 deltas.

## Primary sources read

| Page | Last change-log entry |
|---|---|
| Technology Overviews → Liquid Glass | — |
| Technology Overviews → Adopting Liquid Glass | — |
| HIG → Materials | 2025-09-09 "Updated guidance for Liquid Glass" |
| HIG → Color | 2025-12-16 "Updated guidance for Liquid Glass" |
| HIG → Motion | 2025-09-09 "Added guidance for Liquid Glass" |
| HIG → Layout, Playing audio, Playing haptics, Menus, Sheets, Searching, Lists, Buttons, Sliders, Tab bars, Toolbars, Accessibility, Typography | various |

## Findings that drive Podium decisions

### 1. Two layers, and glass belongs to only one of them
- "Liquid Glass forms a distinct functional layer for controls and navigation elements … that floats above the content layer."
- "Don't use Liquid Glass in the content layer." Exception: transient interactive elements in content (sliders, toggles) "take on a Liquid Glass appearance to emphasize its interactivity when a person activates it."
- "Use Liquid Glass effects sparingly … Limit these effects to the most important functional elements in your app."
- Avoid "overcrowding or layering Liquid Glass elements on top of each other."

**→ Podium:** glass is allowed on: the Wheel, the mini-player capsule, title-bar controls, menus/sheets, transient overlays (index glyph, volume HUD), and the **focus lens** (justified by the transient-interaction exception and by tvOS focus behaviour, below). Never on rows, cards, artwork, or text containers.

### 2. Regular vs clear variants
- Regular: "blurs and adjusts the luminosity of background content to maintain legibility". Use when background "might create legibility issues" or with significant text.
- Clear: "highly translucent … for components that float above media backgrounds". Over bright content, "consider adding a dark dimming layer of 35% opacity." Over sufficiently dark content, no dimming needed.

**→ Podium:** `GlassRegular` is the default. `GlassClear` only where glass sits over artwork, with a computed dimming layer (0 or 35%) chosen from the artwork's measured regional luminance (see `design-system.md` §5.4).

### 3. tvOS: glass appears on focus
- "In tvOS … Certain interface elements, like image views and buttons, adopt Liquid Glass when they gain focus."

**→ Podium:** the iPod is a focus-driven UI (like tvOS), not a touch-target UI. The classic iPod selection bar is reinterpreted as a **glass focus lens** that glides between rows. This is the single most important design idea in Podium and is directly grounded in Apple's own focus-platform behaviour.

### 4. Color on glass
- "By default, Liquid Glass has no inherent color, and instead takes on colors from the content directly behind it."
- "Apply color sparingly … To emphasize primary actions, apply color to the background rather than to symbols or text." "Refrain from adding color to the background of multiple controls."
- Small glass elements flip their symbols light/dark based on underlying content; larger elements become more opaque.
- Custom colors need light, dark, and increased-contrast variants for each.

**→ Podium:** exactly one tinted ("stained") glass element exists at a time — the focus lens. Everything else is neutral glass. Every color token ships light/dark/high-contrast variants.

### 5. Concentricity and shape
- "The shape of the hardware informs the curvature … use rounded shapes that are concentric to their containers."

**→ Podium:** radii are derived, not chosen (`inner radius = outer radius − inset`); see `design-system.md` §4.

### 6. Legibility under scrolling content
- Scroll edge effects "blur and reduce the opacity of background content" beneath bars. Prefer them over opaque bar backgrounds.

**→ Podium:** the title bar is not a glass slab; it is floating glass controls over a progressive scroll-edge fade. Same at the Wheel's top edge.

### 7. Performance guidance
- "Combine custom Liquid Glass effects to improve rendering performance" (`GlassEffectContainer`).

**→ Podium:** one shared backdrop capture per window; every glass surface samples it. No per-surface captures.

### 8. Motion & haptics
- Glass motion "responds to direct touch interaction with greater emphasis … more subdued effect when a person interacts using a trackpad."
- "Avoid adding motion to UI interactions that occur frequently." "Let people cancel motion."
- Haptics: use system patterns per their documented meaning; prefer short transient haptics for discrete events; make haptics optional; don't overuse.

**→ Podium:** wheel detent = selection-tick haptic + lens glide with a very fast spring (no visible "animation wait"); input-method-aware emphasis (touch vs. rotary/keyboard).

### 9. Audio behaviour (HIG → Playing audio)
- Disconnecting headphones must pause immediately; rerouting shouldn't interrupt.
- "Avoid repurposing audio controls." Respond to remote controls only when in an audio context.
- On interruption end, resume only if the interruption type is resumable.

### 10. Writing (HIG + frontend-design guidance)
- Section headers moved to title-style capitalization (no forced ALL CAPS).

## WWDC26 / iOS 27 (secondary sources)
Press coverage (2026-06) reports: Liquid Glass retained; tuned to "diffuse complex background content more effectively"; a user-facing transparency/opacity preference; refreshed developer guidance emphasising functional layering. The DocC Materials page change-log has no 2026 entry as of this research date, so no new primary rule was found. **Implication:** Podium must offer its own transparency preference (Android has no system "Reduce Transparency" setting) — this matches the direction Apple took.

## What we deliberately do NOT take from Apple
- SF Pro / SF Symbols: licensed for Apple platforms only. Podium ships its own type and icon choices (`research/2026-10-02-typography-and-icons.md`).
- Trademarked names: "iPod", "Click Wheel", "Cover Flow" are not used in UI or marketing.

## Sources
- https://developer.apple.com/documentation/technologyoverviews/liquid-glass
- https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass
- https://developer.apple.com/design/human-interface-guidelines/materials
- https://developer.apple.com/design/human-interface-guidelines/color
- https://developer.apple.com/design/human-interface-guidelines/motion
- https://developer.apple.com/design/human-interface-guidelines/playing-haptics
- https://developer.apple.com/design/human-interface-guidelines/playing-audio
- https://macmyths.com/wwdc-2026-apple-refines-liquid-glass-across-its-next-generation-operating-systems/ (secondary)
- https://www.techadvisor.com/article/3160730/apples-liquid-glass-heading-in-a-bold-new-direction-to-an-off-switch.html (secondary)
