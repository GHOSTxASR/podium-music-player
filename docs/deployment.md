# Deployment

**Date:** 2026-10-02 · Distribution channel is a **pending user decision** (D-13 sibling); the build is designed to satisfy Google Play requirements so no option is closed.

## 1. Toolchain (pinned; verify at Phase 3 start)
Gradle wrapper 9.8.0 · AGP 9.4.1 · Kotlin 2.4.20 · KSP 2.3.12 · JDK toolchain 21 (Temurin; JDK 22 on the dev machine also works) · compileSdk 37 · targetSdk 36 · minSdk 29 · build-tools 37.0.0.

## 2. Local development setup (Windows)
```bash
sdkmanager "platforms;android-37" "build-tools;37.0.0" "platform-tools"
```
```bash
sdkmanager "system-images;android-36;google_apis;x86_64"
```
(only if no physical device) then create one AVD. `local.properties`: `sdk.dir=D\:\\Android`. `gradle.properties` caps: `org.gradle.jvmargs=-Xmx2g -XX:+UseParallelGC`, `kotlin.daemon.jvmargs=-Xmx1536m`, `org.gradle.configuration-cache=true`, `org.gradle.caching=true`.
Common commands:
```bash
./gradlew :app:installDebug
```
```bash
./gradlew testDebugUnitTest verifyRoborazziDebug lintDebug
```

## 3. Build variants
| Variant | Purpose | Notes |
|---|---|---|
| `debug` | Development and the phone's daily build | `sources:fixture` included; debug overlay; user CAs trusted; applicationIdSuffix `.debug` (installs beside release). **Not debuggable** by default (D-67) so ART compiles it and every open is as smooth as a warm one; `-Ppodium.debuggable=true` for a debugger |
| `release` | Distribution | R8 full mode, resource shrinking, no fixture source, no debug overlay, logs redacted, baseline profiles |
| `benchmark` | Macrobenchmark target | release-like, debuggable=false, signed with debug key |

After installing on the phone, compile it at once rather than waiting for the phone's idle-time compilation (a debuggable build can't be compiled at all):
```bash
adb shell cmd package compile -m speed -f app.podium.debug
```
Right after an install there's no profile yet, so `-m speed-profile` only verifies the code (`dumpsys package dexopt` shows `status=verify`). `-m speed` compiles all of it (`status=speed`). After the app has run for a while, `-m speed-profile` compiles what was actually used, at a smaller size.

No product flavors in v1. (If the user later chooses F-Droid + Play, a `foss`/`play` split would only differ in nothing, since Podium has no proprietary SDKs — a deliberate advantage.)

## 4. Versioning
SemVer `versionName` (`0.x` until v1.0); `versionCode` = `major*1_000_000 + minor*1_000 + patch` (monotonic), CI-overridable. Changelog per release in `CHANGELOG.md` (Keep a Changelog format).

## 5. Signing
- **As implemented (D-69):** a PKCS12 release keystore (RSA 4096, alias `podium`, valid 10,000 days)
  lives outside the repo, in `%USERPROFILE%\.podium-signing\`, with its passwords in
  `keystore.properties` (a copy sits in the project root, which git ignores). `app/build.gradle.kts`
  reads that file, or the `PODIUM_KEYSTORE_FILE`, `PODIUM_KEYSTORE_PASSWORD`, `PODIUM_KEY_ALIAS` and
  `PODIUM_KEY_PASSWORD` environment variables; without either, the release APK is built unsigned.
- **Back the keystore up** (password manager plus an offline copy). Every release must be signed with
  it: an APK signed with a different key can't install over an existing Podium.
- CI: keystore as base64 secret → decoded at build time; passwords as secrets; never echoed.
- Play App Signing recommended if Play is chosen (upload key ≠ app signing key).

## 6. CI (GitHub Actions, once a remote exists)
- `pr.yml`: assembleDebug, unit + Robolectric tests, Roborazzi verify, lint, dependency-guard, APK size check.
- `release.yml` (tag `v*`): assembleRelease + bundleRelease, signing, checksums, GitHub Release with APK + AAB + SHA-256 + changelog excerpt.
- `benchmark.yml` (manual dispatch): Macrobenchmark on a connected/self-hosted device or Firebase Test Lab (paid → user decision).
- Caching: Gradle build cache + configuration cache; JDK 21 Temurin.

## 7. Distribution options (decision for the user)
| Channel | Fit | Notes |
|---|---|---|
| Google Play | ✓ (legit sources only) | targetSdk 36+, Data safety form (no collection), content rating, privacy policy URL |
| GitHub Releases + Obtainium | ✓ | Simplest; reproducible builds encouraged |
| F-Droid / IzzyOnDroid | ✓ | Requires FOSS license for Podium and no proprietary deps (already none) |

## 7a. Publishing a release (D-69)
1. `./gradlew :app:assembleRelease` and check it: `apksigner verify --print-certs` names `CN=Podium`;
   `aapt dump badging` shows `app.podium` and no `application-debuggable`.
2. Publish a GitHub Release tagged `v<versionName>` with the APK attached as `podium.apk`, so
   `releases/latest/download/podium.apk` (the website's and README's download link) always serves
   the newest one. Never commit APKs (`.gitignore`).
3. The website (`site/`) deploys to GitHub Pages from `.github/workflows/pages.yml` on every push
   to `main` that touches `site/`.

## 8. Release checklist (Phase 12)
License audit complete (`docs/licenses.md`) · About screen attributions generated · Privacy notice published · README with screenshots · Known limitations documented · Baseline profiles regenerated · All phase-exit device checklists passed on API 29, 33, 36, 37 · Version bumped, changelog written · Signed artifacts' SHA-256 published.
