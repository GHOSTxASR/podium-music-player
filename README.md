# Podium (working name)

A sideloaded personal Android music player with an iPod-inspired interaction model and a restrained Liquid Glass interface.

**Product Direction:**
- **Local/Offline Music:** Stable, first-class offline music playback from the device (MediaStore, Room, Media3 `PlaybackService`, `QueueManager`).
- **Online Music:** Redesigned exclusively around **YouTube Music** as the sole online music provider, delivering an experience comparable to BitChord:
  - Full YouTube Music catalogue and search
  - Albums, artists, playlists, user's library, liked music, listening history where supported
  - User session authentication
  - Direct in-app streaming via Media3 and background playback where supported
  - BitChord serves as an architectural and implementation reference.
- **Platform:** Native Android (Kotlin, Jetpack Compose, Media3) — minSdk 29, targetSdk 36.
- **Start here:** [docs/README.md](docs/README.md).

License: undecided (all rights reserved until decided). Third-party references and licenses are acknowledged factually.
