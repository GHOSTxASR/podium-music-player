# Debug APK

`podium-debug.apk` — Podium's debug build, kept here on purpose (every other APK stays ignored).

| | |
|---|---|
| Built with | `./gradlew :app:assembleDebug` |
| applicationId | `app.podium.debug` |
| versionName / versionCode | 0.1.0 / 1 |
| Size | 52,197,115 bytes |
| SHA-256 | `7b1490dc64832310321220a5dc9dd7e79664fcad828caa3da9b010c4827a2a12` |
| Signed with | the Android debug certificate (not a release key) |
| Built from | commit `0f3922e` on `ccr-fcca9ac9-6juw47` |

Install: `adb install -r artifacts/apk/podium-debug.apk`. Not yet installed or smoke-tested on a
device; see `docs/testing/`.
