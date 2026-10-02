# Accessibility

**Date:** 2026-10-02 · Target: WCAG 2.2 AA where applicable to native apps; Android accessibility guidelines; HIG accessibility principles.

## 1. Commitments
1. **The Wheel is never required.** Every function is reachable by touch on content, TalkBack, Switch Access, keyboard, and D-pad.
2. **Glass is never required to understand state.** Focus, selection, disabled, error, and offline all have non-glass signals (text weight/color, glyph fill, labels, semantics).
3. **Text scales to 200%** without clipping or overlap.
4. **Motion and transparency are user-controllable** (system settings + in-app overrides).

## 2. Settings Podium honours
| Signal | Source | Effect |
|---|---|---|
| Font scale (nonlinear, Android 14+) | `Configuration.fontScale` | Text styles scale; rows grow; wheel legends cap 1.3× |
| Bold text | `Configuration.fontWeightAdjustment` (API 31+) | +100 weight, cap 700 |
| Color contrast | `UiModeManager.getContrast()` (API 34+) ≥ 0.5, or in-app "Increase contrast" | HC color variants; Solid glass tier; focus lens = solid highlight |
| Remove animations | `ANIMATOR_DURATION_SCALE == 0` or in-app "Reduce motion" | `animation-system.md` §5 |
| Reduce transparency | **No Android system setting** → in-app Transparency: Full / Reduced / Off | Glass tiers (ADR-007) |
| Touch feedback (haptics) | System setting via `View.performHapticFeedback` + in-app Haptics | Haptics off |
| Dark theme | `uiMode` + in-app Theme | Light/Dark tokens |
| Color correction / inversion | System | Tokens remain distinguishable; never rely on hue alone (like = filled heart shape, not just pink) |

## 3. TalkBack
- **Wheel:** container labelled "Wheel"; five child buttons: "Back" (Menu), "Previous track", "Play"/"Pause" (`stateDescription`), "Next track", "Select". Custom actions on the container: "Move up", "Move down" (ListFocus), "Volume up", "Volume down" (Now Playing), "Scrub forward/back" (Scrub). Long-press equivalents exposed as custom actions ("More options", "Go to Home", "Sleep timer").
- **Rows:** single merged node: "So What, Miles Davis, 9 minutes 22 seconds, downloaded, liked". Role button. Custom actions = context menu items. Selected state = focus.
- **Unavailable rows:** "…, unavailable offline" and the activation explains rather than doing nothing.
- **Now Playing:** title/artist as heading; scrubber as `ProgressBarRangeInfo` with `setProgress` (seek); volume as a separate adjustable; quality label as button "Audio quality, FLAC 24-bit 96 kilohertz. Opens signal path."
- **Live regions:** HUD toasts are polite announcements; errors are assertive once.
- **Traversal order** = visual reading order; the mini player and wheel come after content; TitleBar first.
- TalkBack focus and the Podium focus lens stay in sync (accessibility focus → lens moves; avoids two cursors).

## 4. Visual
- Contrast budget in `design-system.md` §2 (primary ≥ 15:1, secondary ≥ 6.3:1 against worst-case atmosphere; tertiary only for non-essential text).
- Non-text contrast ≥ 3:1 for controls' boundaries in HC and Solid tiers (rim/hairline).
- Focus indicators: lens + weight + filled chevron; keyboard focus ring 2dp outside glass controls.
- Target sizes ≥ 48dp; spacing prevents accidental activation.
- No information by color alone; status glyphs have distinct shapes.

## 5. Motor
- Rotation is optional; Center/Menu/⏯/⏮/⏭ are large zones.
- Long-press timeout follows system `ViewConfiguration` (user-adjustable "Touch & hold delay").
- No time-limited interactions except auto-dismissing HUDs (information also available elsewhere) and Scrub auto-exit (no effect on content).
- Left-handed wheel placement in landscape; wheel size S/M/L.

## 6. Cognitive
- One hierarchy, consistent Menu=back, consistent wording (`design-system.md` §11).
- Errors say what happened and what to do.
- Onboarding hints are contextual and dismissible.

## 7. Testing
- Automated: Compose semantics assertions in UI tests (labels, roles, actions, state); Android Lint accessibility checks; Accessibility Test Framework checks via Espresso/Compose (`enableAccessibilityChecks`) on key screens.
- Screenshot matrix: font scale 1.0/1.5/2.0 × light/dark/HC × Full/Solid.
- Manual each release: TalkBack full journey (§71 of brief), Switch Access basic journey, keyboard-only journey on ChromeOS/desktop mode, reduced motion, transparency Off.
