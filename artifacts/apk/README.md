# Debug APK

`podium-debug.apk` — Podium's debug build, kept here on purpose (every other APK stays ignored).

| | |
|---|---|
| Built with | `./gradlew :app:assembleDebug` |
| applicationId | `app.podium.debug` |
| versionName / versionCode | 0.1.0 / 1 |
| Size | 50,374,931 bytes |
| SHA-256 | `e551e02c99489baf82889656b5aab1ba93fc83e5b60db15b0b6036d7c9f69b34` |
| Signed with | the Android debug certificate (not a release key) |
| Built from | commit `ae65bf2` on `ccr-fcca9ac9-6juw47` |

Install: `adb install -r artifacts/apk/podium-debug.apk`. Not yet installed or smoke-tested on a
device; see `docs/testing/`.
