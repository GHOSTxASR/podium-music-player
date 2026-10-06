# Debug APK

`podium-debug.apk` — Podium's debug build, kept here on purpose (every other APK stays ignored).

| | |
|---|---|
| Built with | `./gradlew :app:assembleDebug` |
| applicationId | `app.podium.debug` |
| versionName / versionCode | 0.1.0 / 1 |
| Size | 52,205,051 bytes |
| SHA-256 | `c8a0f440a8675d1861217fae26cd27101c45a3c26acb56fc69e616c155623fcf` |
| Signed with | the Android debug certificate (not a release key) |
| Built from | commit `d11134c` on `ccr-fcca9ac9-6juw47` |

Install: `adb install -r artifacts/apk/podium-debug.apk`. Not yet installed or smoke-tested on a
device; see `docs/testing/`.
