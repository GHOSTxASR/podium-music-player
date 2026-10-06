# Debug APK

`podium-debug.apk` — Podium's debug build, kept here on purpose (every other APK stays ignored).

| | |
|---|---|
| Built with | `./gradlew :app:assembleDebug` |
| applicationId | `app.podium.debug` |
| versionName / versionCode | 0.1.0 / 1 |
| Size | 50,374,931 bytes |
| SHA-256 | `34ea52a55b31830abc3b1231c6c9a72eb1b39d5667482ea573fa59f842188a9e` |
| Signed with | the Android debug certificate (not a release key) |
| Built from | commit `83b10d0` on `ccr-fcca9ac9-6juw47` |

Install: `adb install -r artifacts/apk/podium-debug.apk`. Not yet installed or smoke-tested on a
device; see `docs/testing/`.
