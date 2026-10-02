# Podium — project rules for coding agents

Read `docs/README.md` first. The docs are the source of truth; update them in the same commit as behaviour changes. Record significant decisions in `docs/decision-log.md` (ADR in `docs/adr/` if major).

## Non-negotiables
- **No circumvention.** Never implement signature/cipher deobfuscation, PoToken/BotGuard or other bot-detection evasion, client impersonation, DRM removal, paywall/subscription bypass, or downloads a source doesn't permit. No YouTube Music adapter (see `docs/music-source-analysis.md`).
- **No GPL code copied** into this repo (all YouTube Music clients are GPL-3.0); Podium's license is undecided.
- **No secrets** in code or git. No analytics/crash SDKs.
- **Glass only via `:core:designsystem`** (`Modifier.glass` / `GlassSurface`). Never `Modifier.blur`, `RenderEffect`, `RuntimeShader`, or Backdrop imports elsewhere. Glass only on functional elements (wheel, mini player, title-bar controls, menus, sheets, HUDs, focus lens) — never rows, cards, artwork, or text containers.
- **No Material3 components.** Compose foundation/ui + Podium tokens only.
- **Never claim quality you can't measure.** "Lossless"/"Hi-Res" only per `docs/audio-architecture.md` §5.
- **UI never touches ExoPlayer**; everything goes through `PlaybackController`. `QueueManager` is the only queue writer.
- **Identity is source-qualified** (`TrackId = "<sourceId>:<id>"`), never title+artist.
- **Domain modules stay pure Kotlin** (`core:model`, `core:common`, `player:api`, `sources:api`).
- **Copy:** sentence case; no all-caps except the wheel's `MENU`; no middle-dot meta strings; errors explain and give a next step.

## Environment notes
- Windows 11, 7.7 GB RAM: keep Gradle heap caps; prefer a physical device over an emulator.
- Android SDK at `D:\Android`; JDK at `D:\jdk` (22) — toolchain targets JDK 21.
- Use the Bash tool with POSIX syntax or PowerShell; ffmpeg is available for generating fixture audio.

## Workflow
Plan → implement → run → test → inspect (screenshots on device) → fix → document → commit (conventional commits). Run the design review checklist in `docs/vertical-slice-plan.md` §11 after each major screen.
