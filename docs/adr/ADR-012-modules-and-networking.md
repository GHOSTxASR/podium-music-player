# ADR-012 — Module structure, build, and networking stack

**Status:** Accepted · **Date:** 2026-10-02

## Context
Separation of concerns, fast JVM tests, a low-RAM build machine, and a brief that prefers few, purposeful dependencies.

## Decision
**Modules** (Gradle, convention plugins in an included `build-logic` build, versions in `gradle/libs.versions.toml`):
```
app                       Application, MainActivity, AppGraph, NavDisplay host
core/model        [jvm]   ids, domain entities, AudioFormat, Capability types
core/common       [jvm]   Outcome/Result, DispatcherProvider, Clock, Logger facade
core/designsystem         tokens, materials (glass), typography, symbols, motion, primitives, PodWheel visuals
core/interaction          InputRouter, InputTarget, FocusList, WheelGestureDetector, haptics adapter
core/database             Room 3 schema, DAOs, migrations, FTS
core/data                 repositories (Library, Likes, Playlists, History, Settings, Search)
core/network              OkHttp client factory, UA, cleartext policy, JSON
player/api        [jvm]   PlaybackController, PlaybackState, QueueManager, AutoplayEngine (pure logic)
player/service            PlaybackService (Media3), resolver data source, quality inspector, session policy
sources/api       [jvm]   MusicSource, capabilities, SourceRegistry contracts
sources/local             MediaStore adapter + sync
sources/subsonic          OpenSubsonic adapter + sync
sources/audius            Audius adapter
sources/fixture           debug-only generated content
downloads                 DownloadManager, executors (UIDT / WorkManager), verifier
lyrics                    LRC parser [jvm-able], providers, cache
feature/home, feature/library, feature/nowplaying, feature/search, feature/flow, feature/settings
```
Modules are created when their phase begins (the vertical slice starts with ~11).

**Networking:** OkHttp 5 + kotlinx.serialization; per-source thin typed clients. **No Retrofit, no Ktor** — Subsonic/Audius/LRCLIB are simple GET+JSON APIs, and OkHttp is already required by Media3 and Coil (one HTTP stack, one connection pool, one place for UA/cleartext policy).

**Images:** Coil 3 sharing the same OkHttp client.

**Build:** Gradle 9.8 wrapper, AGP 9.4, Kotlin 2.4.20 (K2), KSP 2.3.x (Room only), configuration cache on, `org.gradle.jvmargs=-Xmx2g`.

## Why
Pure-JVM modules make the hardest logic (queue, state machine, autoplay, parsers, wheel math) testable in milliseconds and KMP-ready. One HTTP stack avoids duplicated config and bugs.

## Tradeoffs
More modules = more Gradle configuration time; mitigated by configuration cache and creating modules only when needed.
