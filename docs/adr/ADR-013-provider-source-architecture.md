# ADR-013 — Provider-independent, capability-faceted source architecture

**Status:** Superseded in part by D-48 (2026-10-06) · **Date:** 2026-10-02 (amended 2026-10-06) · **Supersedes in part:** ADR-002.
> **Note on D-48 reset:** The generic capability/facet concepts and stable offline/local architecture are preserved. However, the multi-provider online architecture and the former "stream-unlock boundary" prohibition have been superseded. For online music, YouTube Music is the sole provider, playing directly in Media3 (`DirectStream`) comparable to BitChord.

## Context
Original direction (2026-10-02): Podium was conceived as a universal player with interchangeable providers. Providers differed fundamentally: local files and servers provided direct audio, while external services were subject to policy restrictions. In this original draft, direct YouTube audio extraction was excluded ("stream-unlock boundary").

Under the current product decision (D-48), Podium is a sideloaded personal Android music player. Offline/local playback is stable and preserved. YouTube Music is the sole online provider, with direct in-app streaming in Media3.

## Decision
- `MusicSource` = descriptor + capability state + optional **facets** (catalog, library, playback, artwork, lyrics, recommendations, downloads, auth, queue sync). Missing facet ⇒ capability `UNAVAILABLE`.
- Canonical provider-neutral `Track` with opaque `SourceRef.providerData`; `TrackCapabilities` computed per context; `PlaybackTarget` computed per play.
- **Playback target for audio:** Audio played within Podium (both local files and online YouTube Music tracks) resolves to **`DirectStream(PlayableMedia)`**, decoded and played natively by Media3.
- `StreamResolver` pipeline resolves streams per play; offline tracks use content/file URIs; YouTube Music resolves stream URLs using practical, technically viable extraction (inspired by BitChord).
- `SourceHealth` per instance with miss ≠ failure and a circuit breaker.
- Credentials (such as user session cookies) Keystore-encrypted.
- **Historical Note (Superseded):** The original draft imposed a "stream-unlock boundary" forbidding cipher solving and token minting. That restriction is revoked by D-48 for Podium's sideloaded personal player.

## Why
Keeps UI and playback cleanly decoupled from transport mechanisms while enabling full in-app playback for both stable local media and online YouTube Music streams.

## Future implications
- Offline/local playback remains stable and isolated.
- YouTube Music is the sole online provider. Practical extractor and stream resolution components operate under `sources:youtubemusic` to yield `DirectStream(PlayableMedia)`.
