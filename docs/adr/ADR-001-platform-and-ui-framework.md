# ADR-001 — Platform and UI framework

**Status:** Accepted (pending user confirmation) · **Date:** 2026-10-02

## Context
Podium needs: reliable background playback, system media session (notification, lock screen, Bluetooth/AVRCP, headset buttons), audio focus, real offline files, haptics, a custom high-frame-rate wheel and glass rendering. Development happens on Windows 11 with the Android SDK already installed and 7.7 GB RAM. iOS builds require macOS.

## Options considered
| Option | Playback & system integration | Glass rendering | Offline | Dev loop on this machine | Verdict |
|---|---|---|---|---|---|
| **A. Native Android: Kotlin + Jetpack Compose + Media3** | First-class (MediaSessionService, AVRCP, focus, noisy) | Backdrop: blur API 31+, refraction API 33+ | Real files, UIDT jobs | CLI Gradle + device/emulator; Robolectric/Roborazzi for JVM screenshots | **Chosen** |
| B. Web/PWA | Background audio unreliable (esp. iOS); Media Session API partial | Best CSS tooling | OPFS with eviction risk | Fastest | Rejected: fails playback & offline requirements; remote sources need a server proxy (cost + legal exposure) |
| C. React Native / Expo | Good via react-native-track-player (Media3 underneath) | Weak on Android (blur only, native modules) | OK | Heavier (Metro + Gradle) | Rejected: Android glass is the weakest link and the app is Android-first anyway |
| D. Flutter | Good (just_audio/audio_service) | Impeller BackdropFilter; refraction via shaders | OK | Flutter not installed | Rejected: no advantage over native for an Android-first product; second language |
| E. Compose Multiplatform (Android + Desktop JVM) | Android same as A; desktop needs a separate player | Backdrop supports JVM | — | Desktop hot-reload for UI iteration | Deferred: real gain (fast UI loop) but adds source-set complexity now; keep the door open (below) |

## Decision
Native Android, Kotlin 2.4, Jetpack Compose (foundation + ui; **no Material3 components**, see ADR-007), Media3 for playback.

## Why
Every hard requirement in the brief (background playback, media controls, Bluetooth, focus, offline, haptics) is native-first on Android. All reference clients are Android. Compose's draw-phase APIs, `RenderEffect`, and AGSL make a high-quality wheel and glass achievable.

## Tradeoffs
- No iOS. A user on iPhone cannot use Podium.
- Glass fidelity varies by Android version (tiered; ADR-007).
- Dev loop is slower than web; mitigated by JVM screenshot tests and a physical device.

## Future implications
- Domain modules (`core:model`, `core:common`, `player:api`, `sources:api`) are pure Kotlin/JVM with no Android imports, so a KMP port (desktop/iOS) is a module-plugin change, not a rewrite.
- Revisit option E if UI iteration speed becomes the bottleneck.
