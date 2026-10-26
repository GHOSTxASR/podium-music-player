# Code ported from other projects

| Podium file | Ported from | License | Source |
|---|---|---|---|
| `sources/youtubemusic/src/main/kotlin/app/podium/sources/youtubemusic/PoTokens.kt` (`BotGuardMinter.HELPERS`, the BotGuard flow) | NewPipe's `po_token.html` and `PoTokenWebView.kt` / `JavaScriptUtil.kt` (TeamNewPipe/NewPipe, `app/.../util/potoken/`), the same helper Metrolist and BitChord use (D-98). Changed: the page's functions rewritten without `this`-bound state where they could be; the flow in Kotlin coroutines over a generic hidden page (`OriginPage`), the BotGuard requests sent by Podium's own HTTP client. | GNU GPL-3.0 (as Podium) — [licenses/GPL-3.0-NewPipeExtractor.txt](licenses/GPL-3.0-NewPipeExtractor.txt) | github.com/TeamNewPipe/NewPipe |
| `player/service/src/main/kotlin/app/podium/player/service/alac/AlacFrameDecoder.kt` | Apple's ALAC reference decoder (`ALACDecoder.cpp`, `ag_dec.c`, `dp_dec.c`, `matrix_dec.c`), Copyright (c) 2011 Apple Inc., ported to Kotlin (D-87). Changed: Kotlin; one bit reader over absolute positions; multichannel output placed in Android's channel order (the placement table FFmpeg's decoder also uses); malformed input reported as an exception instead of status codes. | Apache License 2.0 — [licenses/Apache-2.0-ALAC.txt](licenses/Apache-2.0-ALAC.txt) | github.com/macosforge/alac (Apple's open-source release, 2011) |

The test files in `player/service/src/test/resources/alac/` were made by Podium with FFmpeg's ALAC
encoder from generated tones and noise (no third-party audio).

# Code shipped unchanged

| Podium file | What it is | License | Source |
|---|---|---|---|
| `sources/youtubemusic/src/main/resources/app/podium/sources/youtubemusic/ejs/lib.min.js`, `…/core.min.js` | yt-dlp's JavaScript challenge solver, **yt-dlp-ejs 0.8.0**, as published (D-98). `lib.min.js` bundles **meriyah 6.1.4** (ISC, Copyright (c) 2019 and later, KFlash and others) and **astring** (MIT); their notices are in the file's header and in [licenses/ISC-MIT-yt-dlp-ejs-bundled.txt](licenses/ISC-MIT-yt-dlp-ejs-bundled.txt). SHA-256: `lib.min.js` `c55987fe697e5b9ee18830163f7af85327e9bb5c3e674b969d38c8d205eaa577`, `core.min.js` `18da6ce0758b416e7ae645084f4f8801f9f9d59d6c477c05eaa0ff94ebd8cc00` (from the PyPI wheel `yt_dlp_ejs-0.8.0-py3-none-any.whl`, SHA-256 `79300e5f…4bdb4`). | Unlicense — [licenses/Unlicense-yt-dlp-ejs.txt](licenses/Unlicense-yt-dlp-ejs.txt); bundled: ISC, MIT | github.com/yt-dlp/ejs, pypi.org/project/yt-dlp-ejs |
