# Code ported from other projects

| Podium file | Ported from | License | Source |
|---|---|---|---|
| `player/service/src/main/kotlin/app/podium/player/service/alac/AlacFrameDecoder.kt` | Apple's ALAC reference decoder (`ALACDecoder.cpp`, `ag_dec.c`, `dp_dec.c`, `matrix_dec.c`), Copyright (c) 2011 Apple Inc., ported to Kotlin (D-87). Changed: Kotlin; one bit reader over absolute positions; multichannel output placed in Android's channel order (the placement table FFmpeg's decoder also uses); malformed input reported as an exception instead of status codes. | Apache License 2.0 — [licenses/Apache-2.0-ALAC.txt](licenses/Apache-2.0-ALAC.txt) | github.com/macosforge/alac (Apple's open-source release, 2011) |

The test files in `player/service/src/test/resources/alac/` were made by Podium with FFmpeg's ALAC
encoder from generated tones and noise (no third-party audio).
