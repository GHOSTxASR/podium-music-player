# APKs

No APK is committed (D-69). Builds land here locally and stay ignored.

- **Release** (what people download): `./gradlew :app:assembleRelease`, signed with the release key
  from `keystore.properties` (outside git), published as a GitHub Release with the asset name
  `podium.apk` so `releases/latest/download/podium.apk` always points at the newest one.
  See `docs/deployment.md`.
- **Debug** (the build on the development phone, `app.podium.debug`):
  `./gradlew :app:assembleDebug`, then `adb install -r` and
  `adb shell cmd package compile -m speed -f app.podium.debug` (deployment.md §3). It carries the
  debug commands and the test tones, so it's never published.
