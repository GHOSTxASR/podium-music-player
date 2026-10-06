# Podium — project rules for coding agents

Read `docs/README.md` first. The docs are the source of truth; update them in the same commit as behaviour changes. Record significant decisions in `docs/decision-log.md` (ADR in `docs/adr/` if major).

## Non-negotiables
- **No circumvention (stream-unlock boundary, ADR-013).** Never implement player-cipher/throttle solving, PoToken/BotGuard or other bot-detection evasion, client-identity rotation to obtain streams, age-gate bypass, DRM removal, paywall/subscription bypass, or downloads a source doesn't permit — and don't add libraries for those purposes (e.g., NewPipeExtractor, InnerTubeX). YouTube Music only as options Y1/Y2 and only once the user approves (D-20); Spotify only as S-a/S-b once approved (D-21). See `docs/architecture/SOURCE_CAPABILITY_MATRIX.md` §3.
- **Providers never leak.** Features depend on capabilities, availability, route and owner — never on provider identity (no `if (spotify)`), never on provider id formats. Provider code lives only in `sources:<provider>`. Spec: `docs/architecture/MUSIC_SOURCE_ARCHITECTURE.md`.
- **Identity is sacred.** Cross-source fallback only at matcher tier EXACT (nothing else, no setting), always surfaced, never across version/explicitness boundaries, never mid-track (D-17, D-18).
- **No GPL code or GPL dependencies** while Podium's license is open (D-13). BitChord is an architectural reference only: don't open its source while implementing; follow ADR-014.
- **No secrets** in code or git. No analytics/crash SDKs.
- **Glass only via `:core:designsystem`** (`Modifier.glass` / `GlassSurface`). Never `Modifier.blur`, `RenderEffect`, `RuntimeShader`, or Backdrop imports elsewhere. Glass only on functional elements (wheel, power button, mini player, title-bar controls, menus, sheets, HUDs, focus lens) — never rows, cards, artwork, the virtual screen, or text containers.
- **No Material3 components.** Compose foundation/ui + Podium tokens only.
- **Never claim quality you can't measure.** "Lossless"/"Hi-Res" only per `docs/audio-architecture.md` §5.
- **UI never touches ExoPlayer**; everything goes through `PlaybackController`. `QueueManager` is the only queue writer.
- **Identity is source-qualified** (`TrackId = "<sourceId>|<id>"`; also `ArtistId`, `AlbumId`, `PlaylistId`, `ScopedKey`), never title+artist.
- **Online is multi-source (D-35).** Ask the `SourceRegistry`/`MultiSourceCatalog`, never "the online source". Route anything source-local by its id's source. Group copies across sources only at matcher tier EXACT. Never name a source in music browsing (Settings ▸ Online sources is the one place).
- **Domain modules stay pure Kotlin** (`core:model`, `core:common`, `player:api`, `sources:api`).
- **The device shell (D-26):** the whole UI lives inside `VirtualScreen` above the Wheel; finishes change the body and Wheel only, never the screen's colours.
- **Carbon and Bone (D-29):** no glass, no gloss, no blue, no pills on the display; selection is lit (text, band, indicator), never highlighted; album art is the only colour. Lists use the paper (`FocusList` default) — don't hand-roll vertical lists.
- **LOCAL and ONLINE stay separate (D-34).** Branch on `SourceDescriptor.environment` and capabilities, never provider. Online likes/playlists/history live in ONLINE's own tables; local favorites never change from online actions and vice versa; online songs never appear in the local library; autoplay never mixes environments or sources.
- **Copy:** sentence case; no all-caps except the wheel's `MENU`; no middle-dot meta strings; errors explain and give a next step.

## Environment notes
- Windows 11, 7.7 GB RAM: keep Gradle heap caps; prefer a physical device over an emulator.
- Android SDK at `D:\Android`; JDK at `D:\jdk` (22) — toolchain targets JDK 21.
- Use the Bash tool with POSIX syntax or PowerShell; ffmpeg is available for generating fixture audio.

## Workflow
Plan → implement → run → test → inspect (screenshots on device) → fix → document → commit (conventional commits). Run the design review checklist in `docs/vertical-slice-plan.md` §11 after each major screen.
