# ADR-013 — Provider-independent, capability-faceted source architecture

**Status:** Accepted · **Date:** 2026-10-02 · **Supersedes in part:** ADR-002 (source interface shape and the blanket "no YouTube adapter" rule; ADR-002's Library-vs-Catalog sync decision remains in force) · Spec: `architecture/MUSIC_SOURCE_ARCHITECTURE.md`, `architecture/PLAYBACK_TARGETS.md`, `architecture/SOURCE_CAPABILITY_MATRIX.md`.

## Context
Product direction (2026-10-02): Podium is a *universal* music player; providers are interchangeable infrastructure. Providers differ fundamentally — some give Podium audio to decode (Local, OpenSubsonic, Audius), some only permit remote control of their own player (Spotify via App Remote), some only an embedded official player (YouTube embed), some only unofficial access. ADR-002's single `MusicSource` interface assumed every source resolves to a URL.

## Options considered
1. **Keep one wide interface** (all methods, throw `Unsupported`). Rejected: capabilities become implicit; UI discovers gaps by failure; encourages provider checks in UI.
2. **Provider-specific feature modules** (Spotify UI, YouTube UI…). Rejected: violates the core invariant; every new provider means new UI.
3. **Capability-faceted sources + explicit playback targets.** Chosen.

## Decision
- `MusicSource` = descriptor + capability state + optional **facets** (catalog, library, playback, artwork, lyrics, recommendations, downloads, auth, queue sync). Missing facet ⇒ capability `UNAVAILABLE`. Capabilities are *effective* (declared ∩ probed ∩ account ∩ policy) and observable.
- Canonical provider-neutral `Track` with opaque `SourceRef.providerData`; `TrackCapabilities` computed per context; `PlaybackTarget` computed per play.
- **Playback targets are three distinct types:** `DirectStream(PlayableMedia)`, `RemoteProvider(controller, ref, RemotePolicy)`, `Embedded(ref, constraints)`, executed by distinct engines behind one `PlaybackRouter`; the snapshot exposes `owner` and `controls` generically.
- `StreamResolver` pipeline with pinning, owned-copy preference, own-source resolution, and **identity-preserving fallback** governed by `TrackMatcher` tiers (EXACT for automatic fallback; never across version/explicitness boundaries; never mid-track). *Amended by D-36 (2026-10-06):* fallback stays within the song's environment, and the serving source is recorded (session, history, logs) rather than shown on normal Now Playing.
- `SourceHealth` per instance with miss ≠ failure and a circuit breaker.
- `ConnectedSource` model with provider-neutral `AuthFlow`s; credentials Keystore-encrypted.
- **`Basis`** (`LOCAL_DEVICE`, `USER_SERVER`, `OFFICIAL_API`, `UNOFFICIAL_API`) on every source, visible to users and usable by build policy (e.g., Play builds link no `UNOFFICIAL_API` factories).
- Hard boundary: no stream-unlock components (client-identity rotation for streams, cipher solving, attestation token minting, age-gate bypass) and no downloads a provider doesn't permit (see matrix §3).

## Why
Lets the UI be written once against capabilities and owners; makes "Spotify plays in Spotify" and "YouTube embed must stay visible" first-class rather than hacks; makes every substitution explainable and safe; makes provider replacement a module swap.

## Tradeoffs
More types up front; adapters must declare capabilities honestly; remote/embedded engines add router complexity; matcher strictness means some legitimate equivalents are missed (we prefer skip over wrong song).

## Future implications
- New providers = a module implementing facets + a factory; no UI work beyond generic renderings.
- A declarative HTTP bridge for **user-owned** servers (inspired by BitChord's addon idea) can be added as a `USER_SERVER` source kind without executing code — only with a provenance rule (the bridge must serve the user's own library) — P2.
- Partner SDKs (if ever licensed) fit as `RemoteProvider` or delegate targets.
