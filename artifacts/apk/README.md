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

## On the `boot-wordmark` branch only

`podium-boot-wordmark-debug.apk` is a debug build of this branch (D-71: the glitter wordmark and the
new startup chord), committed here at the owner's request so it can be tried before it's merged.
It installs beside the release as `app.podium.debug`. Drop the commit that added it before merging,
so `main` stays free of APKs (D-69).
