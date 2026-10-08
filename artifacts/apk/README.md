# Debug APK

`podium-debug.apk` — Podium's debug build, kept here on purpose (every other APK stays ignored).

| | |
|---|---|
| Built with | `./gradlew :app:assembleDebug` |
| applicationId | `app.podium.debug` |
| versionName / versionCode | 0.1.0 / 1 |
| Size | 72,682,255 bytes |
| SHA-256 | `4f81d04f55af18f8c18bfcc8394f234640ef826246b1a143aa81ffc46ce5fcc9` |
| Signed with | the Android debug certificate (not a release key) |
| Built from | commit `653cee2` on `ccr-fcca9ac9-6juw47` (not debuggable, D-67) |

Install: `adb install -r artifacts/apk/podium-debug.apk`, then compile it ahead of time:
`adb shell cmd package compile -m speed -f app.podium.debug` (deployment.md §3; right after an install,
`-m speed-profile` only verifies because there's no profile yet). This build was installed on the
Nothing Phone (3a) on 2026-10-08 and compiled with `-m speed` (status `speed`); it wasn't driven on the phone.
