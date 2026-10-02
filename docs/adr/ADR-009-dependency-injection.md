# ADR-009 — Dependency injection

**Status:** Accepted · **Date:** 2026-10-02

## Context
~15 modules, ~60–90 injectable classes at v1, two Android entry points besides Activities (`PlaybackService`, `DownloadJobService`), CLI builds on a 7.7 GB machine, strong preference in the brief for a small coherent dependency set.

## Options considered
| Option | Compile-time safety | Build cost | Ecosystem | Verdict |
|---|---|---|---|---|
| Hilt 2.60 | ✓ | KSP processing in every module | Excellent (ViewModel, WorkManager, Services) | Strong default for large teams |
| Koin 4.2 | ✗ (runtime) | None | Good | Runtime graph errors in a playback service are unacceptable |
| Metro 1.4 | ✓ | Compiler plugin, fast | Young | Promising; revisit |
| **Manual composition root** | ✓ (it's just constructors) | None | n/a | **Chosen** |

## Decision
- One `AppGraph` (in `:app`) constructs singletons lazily (`by lazy`), exposed through narrow interfaces per feature (`LibraryDeps`, `PlayerDeps`, …).
- Constructor injection everywhere; no service locators inside domain code.
- ViewModels via `viewModelFactory { initializer { … } }`, scoped to Navigation 3 entries.
- Services obtain the graph from `PodiumApplication`. Tests construct classes directly with fakes.

## Why
Zero codegen and zero framework surprises; the graph is readable in one file; fits the low-RAM build environment.

## Tradeoffs
Boilerplate grows linearly; scoping discipline is on us (documented in `architecture.md` §6).

## Future implications
Revisit when the graph exceeds ~120 bindings or a second app (e.g., Wear) shares the graph — Metro would be the first candidate.
