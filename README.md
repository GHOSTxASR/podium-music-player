# Podium (working name)

A modern universal music player with an iPod-inspired interaction model and a restrained Liquid Glass interface. Music providers are interchangeable infrastructure behind a capability model; the wheel, the navigation, Now Playing and the library are Podium.

**Status:** pre-implementation. Phases 0–2 (audit, product/UX specification, architecture) and the source-architecture research (S0) are complete; next is S1, the source and playback contracts in pure Kotlin. No application code exists yet.

- Platform: native Android (Kotlin, Jetpack Compose, Media3) — minSdk 29, targetSdk 36.
- Sources: music on the device, OpenSubsonic-compatible servers (Navidrome, Gonic, …) and Audius are planned; YouTube Music and Spotify integrations are optional and limited to what each provider's terms allow (see `docs/architecture/SOURCE_CAPABILITY_MATRIX.md`). Podium does not circumvent any service's access controls.
- Start here: [docs/README.md](docs/README.md).

License: not yet chosen (all rights reserved until decided).
