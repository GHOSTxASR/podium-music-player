# Debug APK

`podium-debug.apk` — Podium's debug build, kept here on purpose (every other APK stays ignored).

| | |
|---|---|
| Built with | `./gradlew :app:assembleDebug` |
| applicationId | `app.podium.debug` |
| versionName / versionCode | 0.1.0 / 1 |
| Size | 72,682,255 bytes |
| SHA-256 | `71dba42d59a5781c2387e77693fa56b8dc57ae566b32cae9455bb3296dfadb48` |
| Signed with | the Android debug certificate (not a release key) |
| Built from | commit `bcb1012` on `ccr-fcca9ac9-6juw47` (not debuggable, D-67) |

Install: `adb install -r artifacts/apk/podium-debug.apk`, then compile it at once:
`adb shell cmd package compile -m speed-profile -f app.podium.debug` (deployment.md §3). This build
was installed and driven on a Nothing Phone (3a) on 2026-10-08 (testing/UI_DEVICE_ACCEPTANCE.md §7).
