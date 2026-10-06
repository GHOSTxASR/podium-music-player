# Podium documentation

Foundation written 2026-10-02 (Phases 0–2 and source research S0). Read in this order:

1. [repository-audit.md](repository-audit.md) — starting point and environment
2. [product-spec.md](product-spec.md) — vision, principles, scope, assumptions
3. [decision-log.md](decision-log.md) — every decision; links to [ADRs](adr/)
4. [architecture.md](architecture.md) — system shape, layering, contracts, risks
5. [architecture/MUSIC_SOURCE_ARCHITECTURE.md](architecture/MUSIC_SOURCE_ARCHITECTURE.md) — provider-independent source model (facets, capabilities, matcher, resolver, health)
   - [architecture/PLAYBACK_TARGETS.md](architecture/PLAYBACK_TARGETS.md) — direct streams vs remote providers vs embedded players
   - [architecture/SOURCE_CAPABILITY_MATRIX.md](architecture/SOURCE_CAPABILITY_MATRIX.md) — what each provider officially allows, and Podium's positions
   - [architecture/YOUTUBE_MUSIC_TRANSITION.md](architecture/YOUTUBE_MUSIC_TRANSITION.md) — proposal: replacing Online with YouTube Music while Offline stays frozen (2026-10-06)
   - [architecture/YOUTUBE_MUSIC_ARCHITECTURE.md](architecture/YOUTUBE_MUSIC_ARCHITECTURE.md) — YouTube Music as built: catalogue, account, delegated playback, owner-aware controller (D-38)
   - [research/YOUTUBE_MUSIC_IMPLEMENTATION_NOTES.md](research/YOUTUBE_MUSIC_IMPLEMENTATION_NOTES.md) — what was observed live, what is assumed, what needs the phone
   - [testing/YOUTUBE_MUSIC_DEVICE_ACCEPTANCE.md](testing/YOUTUBE_MUSIC_DEVICE_ACCEPTANCE.md) — the device run (not yet performed)
   - [implementation/YOUTUBE_MUSIC_FINAL_REPORT.md](implementation/YOUTUBE_MUSIC_FINAL_REPORT.md) — final report: what was built, tests, limits, build and APK
   - [architecture/LYRICS_ARCHITECTURE.md](architecture/LYRICS_ARCHITECTURE.md) — lyrics provider, matching, LRC sync, cache, the full-screen lyric
   - [architecture/PODIUM_CUSTOMIZATION.md](architecture/PODIUM_CUSTOMIZATION.md) — body glitter, display fonts, the Custom theme, backgrounds, contrast, persistence
   - [testing/UI_DEVICE_ACCEPTANCE.md](testing/UI_DEVICE_ACCEPTANCE.md) — on-device steps for focus geometry, customization and lyrics
   - [music-source-analysis.md](music-source-analysis.md) — original source analysis (technical vs. authorised vs. distributable)
6. [design-system.md](design-system.md) — tokens, glass materials, components, copy
7. [interaction-model.md](interaction-model.md) — the Wheel, contexts, inputs, haptics
8. [navigation-map.md](navigation-map.md) — hierarchy, stack rules, transitions
9. [screen-inventory.md](screen-inventory.md) — every screen with states
10. [vertical-slice-plan.md](vertical-slice-plan.md) — the first build (the benchmark)
11. [implementation-plan.md](implementation-plan.md) — phases 0–12

Subsystems: [audio-architecture.md](audio-architecture.md) · [playback-state-machine.md](playback-state-machine.md) · [queue-and-autoplay.md](queue-and-autoplay.md) · [data-model.md](data-model.md) · [offline-architecture.md](offline-architecture.md) · [download-system.md](download-system.md) · [animation-system.md](animation-system.md)

Quality: [accessibility.md](accessibility.md) · [performance.md](performance.md) · [security.md](security.md) (incl. privacy) · [testing-strategy.md](testing-strategy.md) · [deployment.md](deployment.md)

Evidence: [research/](research/) — dated findings with sources, including [BITCHORD_ARCHITECTURE_REVIEW.md](research/BITCHORD_ARCHITECTURE_REVIEW.md) and [provider policies](research/2026-10-02-provider-policies.md).
