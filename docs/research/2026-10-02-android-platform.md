# Research: Android platform & toolchain state

**Researched:** 2026-10-02 · **Method:** Google Maven / Maven Central metadata, Android SDK repository XML, androidx release notes, developer.android.com.

## Platform levels
| Item | Value |
|---|---|
| Newest platform | API 37 (Android 17), minor releases 37.1/37.2 in SDK repo |
| Newest build-tools | 37.0.0 |
| Play target requirement | Since **2026-08-31**: new apps & updates must target **API 36**+ (extension to 2026-11-01) |
| Installed locally | `platforms;android-35`, `build-tools;35.0.0`, NDK 28.2, CMake 3.22.1, emulator — **no system images, no AVDs** |

Behaviour relevant to Podium:
- targetSdk 36 on large screens (sw ≥ 600dp): orientation & resizability restrictions are ignored → tablets/foldables **must** have real adaptive layouts.
- Predictive back is on by default for targetSdk 36 → back must be driven by `OnBackPressedDispatcher`/Navigation 3, never `onBackPressed`.
- Media notifications are exempt from `POST_NOTIFICATIONS`; download notifications are not.
- `dataSync` foreground services are time-limited on Android 15+; long user-initiated transfers should use **User-Initiated Data Transfer (UIDT) jobs** (API 34+, `RUN_USER_INITIATED_JOBS`).

## Library versions (stable unless noted)
| Library | Version | Note |
|---|---|---|
| Gradle | 9.8.0 | |
| Android Gradle Plugin | 9.4.1 | |
| Kotlin | 2.4.20 | |
| KSP | 2.3.12 | |
| Compose BOM | 2026.09.00 | Compose UI 1.12.1 |
| Material3 | 1.4.0 | **not used** (see ADR-007) |
| Media3 | 1.11.1 | see session caveat below |
| Navigation 3 | 1.2.0 | app-owned back stack, scenes, predictive back |
| Room 3 | 3.0.3 | stable since 2026-07-01; KSP-only, SQLiteDriver, coroutines-first |
| Room 2 | 2.8.5 | mature fallback |
| Lifecycle | 2.11.0 | |
| DataStore | 1.2.1 | |
| WorkManager | 2.12.0 | |
| Coroutines | 1.11.0 | |
| kotlinx.serialization | 1.11.0 | |
| OkHttp | 5.5.0 | |
| Coil 3 | 3.6.3 | |
| Backdrop (Kyant) | 2.0.1 | Liquid Glass effects, Apache-2.0 |
| Haze | 2.0.1 | blur only; **not used** |
| Robolectric | 4.17 | |
| Roborazzi | 1.76.0 | |
| Turbine | 1.2.1 | |
| Hilt / Koin / Metro | 2.60.1 / 4.2.2 / 1.4.5 | **none used** (ADR-009) |

## Media3 1.11 findings
- `media3-ui-compose` now ships Compose state holders (play/pause, progress, current item) — useful as references; Podium renders its own controls.
- Gapless/offload fixes; Opus decoder memory fix in 1.11.1.
- **Session behaviour change:** if `MediaSession.Callback.onConnect` is not overridden, untrusted controllers now get **read-only** access; session getters throw off the application looper. `onConnectAsync` is the new recommended entry point.
- Field signal: Metrolist pinned Media3 1.10.1 because on 1.11.1 "media controls" vanished on Android 17 (their issue #4404, 2026-09-22). Root cause not confirmed; plausibly the defaults above. **Podium mitigation:** override `onConnectAsync` explicitly, keep the player on the main looper, and verify system media controls on API 36 and 37 in the first playback slice (risk R-07).

## Haptics APIs
| Constant | API | Podium use |
|---|---|---|
| `SEGMENT_FREQUENT_TICK` | 34 | wheel detent (preferred) |
| `SEGMENT_TICK` | 34 | volume step, picker step |
| `CLOCK_TICK` | 21 | detent fallback < 34 |
| `GESTURE_THRESHOLD_ACTIVATE/DEACTIVATE` | 34 | list boundary, scrub mode enter |
| `CONFIRM` / `REJECT` | 30 | action outcome |
| `TOGGLE_ON/OFF` | 34 | like, settings toggles |
| `LONG_PRESS` | 3 | long-press recognised |
`View.performHapticFeedback` honours the system touch-feedback setting automatically; direct `Vibrator` use does not → Podium uses the View path only.

## Glass rendering on Android
- `RenderEffect` (blur, color filters): API 31+.
- `RuntimeShader` / AGSL (lens refraction, custom highlights): API 33+.
- Backdrop library: "Backdrop effects … only take effect with Android 12 and above. Some effects involving `RuntimeShader` need Android 13." Order must be color filter ⇒ blur ⇒ lens. FAQ documents a RenderThread SIGSEGV scenario with bottom sheets → mitigation needed in `GlassSheet`.
- Android has **no system "Reduce Transparency" setting**. Available signals: `UiModeManager.getContrast()` (API 34), `Configuration.fontWeightAdjustment` (API 31, Bold text), animator duration scale (Remove animations), power-save mode, thermal status.

## Developer machine constraints (from local inventory)
- RAM 7.7 GB, i5-11400H (6C/12T), RTX 2050. JDK 22 at `D:\jdk` (works for AGP 9, but non-LTS; recommend 21 or 25 LTS).
- No Android Studio; CLI builds are fine. Emulator + Gradle daemons concurrently will be memory-tight → prefer a physical device over USB/Wi-Fi ADB.

## Sources
- https://dl.google.com/android/maven2 (maven-metadata.xml per artifact)
- https://repo1.maven.org/maven2 (maven-metadata.xml per artifact)
- https://dl.google.com/android/repository/repository2-3.xml
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/develop/background-work/background-tasks/uidt
- https://developer.android.com/jetpack/androidx/releases/room3
- https://developer.android.com/guide/navigation/navigation-3
- https://github.com/androidx/media/blob/release/RELEASENOTES.md
- https://kyant.gitbook.io/backdrop (api/backdrop-effects.md, api/backdrops.md, faq.md)
- https://github.com/MetrolistGroup/Metrolist/issues/4404
