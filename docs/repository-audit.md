# Repository Audit

**Audit date:** 2026-10-02 · **Auditor:** lead architect (Claude) · **Scope:** `D:\PODIUM` and the development machine it lives on.

## 1. Summary

`D:\PODIUM` was **empty**: zero files, zero directories, not a git repository. There is no existing code, UI, backend, database, authentication, deployment configuration, documentation, test suite, or asset to reuse or replace.

Podium is therefore a **greenfield** project. The meaningful "current state" is the development environment, which constrains platform and workflow choices. This audit records that state, and this session created the repository and its documentation foundation.

## 2. Repository state found

| Area | Found |
|---|---|
| Source files | None |
| Framework / language / runtime | None |
| Package manager / build system | None |
| Dependencies | None |
| Backend / database / auth | None |
| Environment configuration | None |
| Deployment configuration | None |
| Documentation | None |
| Tests | None |
| Assets | None |
| Version control | Not a git repository |
| Agent memory for this project | Empty |

### What works / partially works / is broken
Nothing exists, so nothing works or is broken. There is no technical debt to carry and nothing structurally wrong to replace.

### What can be reused
From the repository: nothing. From the ecosystem: see `research/` — notably Media3 (playback), Navigation 3 (stack navigation), Room 3 (database), Kyant Backdrop (glass rendering), Coil 3 (images), OkHttp 5 (network).

## 3. Development environment inventory

| Item | State | Implication |
|---|---|---|
| OS | Windows 11 Home 10.0.26200 | Android dev is fully supported; **iOS native development is not possible** (requires macOS/Xcode). |
| CPU / RAM / GPU | i5-11400H 6C/12T · **7.7 GB RAM** · RTX 2050 + Intel UHD | RAM is the binding constraint. Gradle + Kotlin daemons + an emulator + a browser will contend. |
| Disk | D: 98 GB free, C: 83 GB free | Sufficient. |
| JDK | Oracle JDK 22.0.2 (`D:\jdk`, also on PATH) | AGP 9 needs JDK 17+, so it works. JDK 22 is non-LTS; recommend Temurin 21 or 25 LTS for the Gradle toolchain. |
| Android SDK | `D:\Android` (`ANDROID_HOME` set): platform 35, build-tools 35.0.0, NDK 28.2, CMake 3.22.1, platform-tools (adb 36), emulator, cmdline-tools/latest | Needs **platform 37 + build-tools 37** (current), and either a system image or a physical device. |
| Emulator images / AVDs | **None** | No device to run on yet. |
| Android Studio | Not installed | Not required: builds run via Gradle CLI. Optional for the user's own inspection. |
| Gradle / Kotlin CLI | Not installed globally | Gradle wrapper will be committed; nothing global needed. |
| Node 24.19 / npm 12.0 / Python 3.13 / ffmpeg 9.0 / git 2.55 / gh 2.101 (authenticated) | Present | ffmpeg can generate honest test audio fixtures (e.g., 24-bit/96 kHz FLAC with tagged metadata) for the Fixture source. |
| Other agents installed | opencode, pi-coding-agent, Antigravity | The user works with multiple AI agents → `CLAUDE.md` + docs must carry project rules, not chat history. |

## 4. Platform determination

The brief references Media3/ExoPlayer, InnerTune/SimpMusic/ViMusic (all Android), audio focus, headset/Bluetooth/lock-screen controls, notifications, haptics, background playback, app-killed-during-playback scenarios, and a Windows machine with the Android SDK already installed. iOS is impossible on this machine; a web/PWA cannot do reliable background playback, real offline downloads, or system media integration, and would need a server proxy for any remote source.

**Determination: native Android (Kotlin + Jetpack Compose + Media3).** See ADR-001 for the full option analysis. Domain modules are kept pure Kotlin so a later desktop/iOS port via Kotlin Multiplatform remains possible.

## 5. Concerns

### Dependency concerns
- Room 3 is three months old (stable 2026-07-01). Mitigation: API is close to Room 2.8; fallback path documented in ADR-004.
- Media3 1.11 changed session connection defaults; one large app regressed on Android 17 media controls. Mitigation in ADR-003 / risk R-07.
- Backdrop (single maintainer, 4k★) is the only non-Google rendering dependency. Mitigation: wrapped behind `GlassSurface`; the fallback tier needs no library at all.
- All YouTube Music clients are GPL-3.0: **no code may be copied** from them into Podium unless the user chooses GPL-3.0 for Podium.

### Deployment concerns
- No signing keys exist yet; release keystore must live outside the repo.
- Play requires targetSdk 36 (since 2026-08-31). Distribution channel not yet chosen (decision for the user).

### Security concerns
- None in code (no code). Forward-looking: server credentials must be Keystore-encrypted; LAN Subsonic servers often use plain HTTP (policy in `security.md`).

### Performance concerns
- Backdrop blur/refraction cost on mid-range GPUs; mitigated by a single shared capture, tiered rendering, and a hard cap on simultaneous glass surfaces (`performance.md`).
- Developer machine RAM: Gradle heap caps and a physical test device are part of the workflow plan.

### Legal / naming concerns
- YouTube Music access via unofficial means conflicts with YouTube's Terms and, in the EU, with TPM-circumvention law (OLG Hamburg 2024). See `music-source-analysis.md`.
- "Podium" is provisional: it was the iPod's own UI font name (Apple association), and "Podium" is also an established software company's brand. Trademark clearance needed before public release.

## 6. Actions taken this session
- Initialised git (`main`), added `.gitignore` (signing material, local properties, build output) and `.gitattributes` (LF normalisation on a Windows host).
- Created `/docs` (product, architecture, design, interaction, data, playback, offline, testing, deployment, plan), `/docs/adr`, `/docs/research`.
- Created `CLAUDE.md` with the project's standing rules for future agent sessions.
- No application code was written (per the session's execution gate).

## 7. Setup required before Phase 3 (next session)
1. `sdkmanager "platforms;android-37" "build-tools;37.0.0" "platform-tools"`.
2. Either connect a physical Android device (preferred, Android 12+; Android 13+ shows full glass) with USB debugging, or install `system-images;android-36;google_apis;x86_64` and create one AVD.
3. Optionally install Temurin 21 LTS and point the Gradle toolchain at it.
4. Gradle memory caps in `gradle.properties` (`org.gradle.jvmargs=-Xmx2g`, `kotlin.daemon.jvmargs=-Xmx1536m`, parallel off on low RAM if swapping is observed).
