# ADR-014 — BitChord as Architectural & Implementation Reference

**Status:** Superseded by D-48 (2026-10-06) · **Date:** 2026-10-02 (amended 2026-10-06) · Review: `research/BITCHORD_ARCHITECTURE_REVIEW.md`.

> **Note on D-48 reset:** The former "clean-room" rule ("BitChord source is not opened while implementing") and the prohibition against extractors (NewPipeExtractor, InnerTubeX) are obsolete restrictions and are revoked. Under the current product direction, Podium is a sideloaded personal Android music player, and BitChord may be studied directly as an architectural and implementation reference. Podium will adopt the most practical, technically viable YouTube Music architecture.

## Context
Originally (2026-10-02), Podium enforced a strict "clean-room" posture and avoided opening BitChord's source during implementation to maintain separation and avoid GPL library dependencies. Under D-48, the product goal has been reset: deliver a YouTube Music experience comparable to BitChord (catalogue, search, albums, artists, playlists, user library, liked music, listening history, user session, direct in-app streaming, and background playback).

## Decision
1. **BitChord as an Active Reference:** BitChord is officially recognized as an architectural and implementation reference. Developers and agents may inspect its source code, architecture, and extractor approaches to inform Podium's implementation.
2. **Revocation of Clean-Room Hygiene Restrictions:** The earlier rule forbidding opening BitChord's source during implementation is revoked. Podium should use the most practical, technically viable YouTube Music architecture rather than artificially re-deriving logic.
3. **Factual Licensing Compliance:** Factual open-source licensing principles apply. BitChord is licensed under GPL-3.0, as are extractors like NewPipeExtractor and InnerTubeX. Third-party copyright notices and license requirements must be factually respected in any distributed build.
4. **Attribution:** BitChord is credited as an architectural and implementation reference.

## Why
Enables rapid, practical development of a working, high-fidelity YouTube Music experience in a personal sideloaded music player, eliminating artificial friction while preserving intellectual honesty and licensing compliance.
