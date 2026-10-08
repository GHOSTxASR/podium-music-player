# Podium — project rules for coding agents

Read `docs/README.md` first. The docs are the source of truth; update them in the same commit as behaviour changes. Record significant decisions in `docs/decision-log.md` (ADR in `docs/adr/` if major).

## Non-negotiables
- **Offline/local playback is stable.** The existing offline/local music implementation (MediaStore, Room, Media3 `PlaybackService`, `QueueManager`) is stable and must not be rewritten or broken as part of online changes.
- **YouTube Music is the sole online provider.** Podium is a sideloaded personal Android music player providing a YouTube Music experience comparable to BitChord: full catalogue, search, albums, artists, playlists, user library, liked music, listening history where supported, user session/auth, in-app playback via Media3, and background playback where technically supported.
- **BitChord as architectural and implementation reference.** BitChord may be studied directly as an architectural and implementation reference. Podium should use the most practical, technically viable architecture (including practical extraction, cipher deobfuscation, and token minting) rather than enforcing obsolete "clean-room" or "stream-unlock boundary" restrictions.
- **Factual licensing and integrity.** Maintain factual licensing notices and never misrepresent third-party licenses (e.g., GPL-3.0 for BitChord/extractors). Factual attribution is preserved.
- **Providers never leak into generic UI.** Features depend on capabilities, availability, route and owner — never on hard-coded provider checks in UI screens. Provider-specific logic lives inside its source module.
- **Identity is sacred.** Cross-source fallback only at matcher tier EXACT (nothing else, no setting), never across version/explicitness boundaries, never mid-track, never across environments (LOCAL ↔ ONLINE) (D-17, D-18, D-36). The serving source is recorded (session, history, logs), never named on normal Now Playing (D-36). Only EXACT equivalence is persisted; a user's "not the same song" is final.
- **No secrets in code or git.** No analytics/crash SDKs.
- **Glass only via `:core:designsystem`** (`Modifier.glass` / `GlassSurface`). Never `Modifier.blur`, `RenderEffect`, `RuntimeShader`, or Backdrop imports elsewhere. Glass only on functional elements (wheel, power button, mini player, title-bar controls, menus, sheets, HUDs, focus lens) — never rows, cards, artwork, the virtual screen, or text containers.
- **No Material3 components.** Compose foundation/ui + Podium tokens only.
- **Never claim quality you can't measure.** "Lossless"/"Hi-Res" only per `docs/audio-architecture.md` §5.
- **UI never touches ExoPlayer**; everything goes through `PlaybackController`. `QueueManager` is the only queue writer.
- **Identity is source-qualified** (`TrackId = "<sourceId>|<id>"`; also `ArtistId`, `AlbumId`, `PlaylistId`, `ScopedKey`), never title+artist.
- **Domain modules stay pure Kotlin** (`core:model`, `core:common`, `player:api`, `sources:api`).
- **The device shell (D-26):** the whole UI lives inside `VirtualScreen` above the Wheel; finishes change the body and Wheel only, never the screen's colours.
- **Carbon and Bone (D-29):** no glass, no gloss, no blue, no pills on the display; selection is lit (text, band, indicator), never highlighted; album art is the only colour. Lists use the paper (`FocusList` default) — don't hand-roll vertical lists.
- **LOCAL and ONLINE stay separate (D-34).** Branch on `SourceDescriptor.environment` and capabilities, never provider. Online likes/playlists/history live in ONLINE's own tables; local favorites never change from online actions and vice versa; online songs never appear in the local library; autoplay never mixes environments or sources.
- **Copy:** sentence case; no all-caps except the wheel's `MENU` and the boot wordmark (D-71); no middle-dot meta strings; errors explain and give a next step.

## Environment notes
- Windows 11, 7.7 GB RAM: keep Gradle heap caps; prefer a physical device over an emulator.
- Android SDK at `D:\Android`; JDK at `D:\jdk` (22) — toolchain targets JDK 21.
- Use the Bash tool with POSIX syntax or PowerShell; ffmpeg is available for generating fixture audio.

## Workflow
Plan → implement → run → test → inspect (screenshots on device) → fix → document → commit (conventional commits). Run the design review checklist in `docs/vertical-slice-plan.md` §11 after each major screen.
