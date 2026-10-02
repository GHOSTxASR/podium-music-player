# ADR-007 — Liquid Glass implementation

**Status:** Accepted · **Date:** 2026-10-02

## Context
Glass must be restrained (HIG), performant, accessible, and degrade gracefully. Android provides blur from API 31 (`RenderEffect`) and shader refraction from API 33 (AGSL). Android has no system "Reduce Transparency" setting. Material3 components would impose Material's visual language.

## Options considered
- **Kyant Backdrop** (blur + vibrancy + lens refraction, Apache-2.0) wrapped in an internal API. Chosen.
- Haze (blur only) — insufficient for refraction; adopting both = two engines. Rejected.
- Hand-written RenderNode/AGSL pipeline — feasible but re-derives what Backdrop already does well. Rejected for now; the wrapper keeps this option open.
- Scattered `Modifier.blur`/translucent colors per component — exactly what the brief forbids. Rejected.

## Decision
1. **Semantic material API** in `:core:designsystem`: `GlassMaterial` tokens (`Regular`, `Clear`, `Control`, `Floating`, `Focus`, `Navigation`) × states (`Default`, `Pressed`, `Selected`, `Disabled`). Components call `Modifier.glass(material, shape, state)` / `GlassSurface(...)`; nobody else touches blur or Backdrop.
2. **One backdrop capture per window**: the content layer (atmosphere + screen content) is recorded once via `layerBackdrop`; every glass surface samples it. Glass never samples glass (no stacking).
3. **Rendering tiers**, chosen at runtime by `GlassCapabilities`:
   | Tier | When | What renders |
   |---|---|---|
   | `Full` | API 33+, transparency = Full, not power-save, thermal < SEVERE | vibrancy → blur → lens refraction + rim highlight + shadow |
   | `Blur` | API 31–32, or power-save/thermal fallback | vibrancy → blur + rim + shadow |
   | `Solid` | API 29–30, or user transparency = Off | opaque tinted surface + hairline rim + shadow (no backdrop capture at all) |
   User setting **Transparency: Full / Reduced / Off** (`Reduced` = `Blur` tier with +20% tint opacity).
4. **No Material3 components.** Compose foundation + ui only; Podium components are built from tokens. (Material3 stays out of the dependency graph to prevent its ripple, shapes, and colors leaking in.)
5. **State is never glass-only.** Focus, selection, and disabled states are also conveyed by text weight/color, glyph fill, and semantics.

## Why
Centralisation makes restraint enforceable (a lint rule bans `Modifier.blur`, `RenderEffect`, and Backdrop imports outside `:core:designsystem`). Tiers turn the accessibility fallback and the old-device fallback into the same, well-tested code path.

## Tradeoffs
API 29–30 users get the solid tier (still designed, not degraded-looking). Dependency on a single-maintainer library — contained by the wrapper.

## Future implications
A custom AGSL engine can replace Backdrop behind the same API. Tier selection can incorporate frame-time telemetry (auto-downgrade after sustained jank) in Phase 10.
