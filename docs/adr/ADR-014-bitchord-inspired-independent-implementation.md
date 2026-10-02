# ADR-014 — Learn from BitChord; implement independently

**Status:** Accepted · **Date:** 2026-10-02 · Review: `research/BITCHORD_ARCHITECTURE_REVIEW.md`.

## Context
BitChord (GPL-3.0) is a current, working multi-source Android music client with useful ideas (quality honesty, health semantics, matcher structure, stream pinning). Podium's license is undecided (D-13). Copying or closely paraphrasing GPL code would make Podium a derivative work subject to GPL-3.0 on distribution. BitChord also depends on GPL-3.0 libraries (NewPipeExtractor, InnerTubeX) whose purpose includes defeating YouTube's protection measures.

## Options considered
1. **Fork or copy BitChord's source layer** and license Podium GPL-3.0. Rejected for now: forecloses the license decision; imports provider coupling and stream-unlock code Podium won't ship.
2. **Ignore BitChord.** Rejected: wastes hard-won lessons.
3. **Study for ideas; reimplement from Podium's own written specification.** Chosen.

## Decision
1. **Ideas, not expression.** The review describes behaviour and design in prose; it contains no BitChord code, constants, word lists, or regexes. Podium's specs (`architecture/*`) are the only input to implementation.
2. **Implementation hygiene** (practical clean-room for a small team): BitChord source is not opened while implementing Podium's source layer; no file, identifier naming scheme, comment text, or test fixture is carried over; Podium's matcher lexicon is built from MusicBrainz style guidelines and Podium's own test corpus; scoring is rule-based with Podium-defined tiers (differs structurally from BitChord's additive scores).
3. **Dependencies:** no GPL-licensed library is added while D-13 is open. NewPipeExtractor and InnerTubeX are excluded regardless (their purpose conflicts with ADR-013's hard boundary).
4. **If reuse is ever proposed** for a specific file: record the file, its license, the obligations (GPL-3.0: whole distributed work under GPL-3.0-compatible terms, Corresponding Source, license notices, no additional restrictions), and the user's explicit approval in the decision log **before** copying.
5. **Attribution of ideas:** `README`/About credits BitChord as an architectural reference (courtesy; not a license requirement for ideas).

## Why
Keeps Podium's licensing options open, avoids provider coupling and stream-unlock code, and still captures the lessons.

## Tradeoffs
Re-deriving logic costs time (estimated: matcher + resolver ≈ one focused phase with tests). True two-team clean-room isn't possible with a single implementer who has read the code; the hygiene rules plus structurally different design and independent test corpus are the mitigation. This is an engineering safeguard, not legal advice.

## Future implications
If the user later chooses GPL-3.0 for Podium (D-13), selective reuse becomes possible under rule 4 — still excluding stream-unlock components per ADR-013.
