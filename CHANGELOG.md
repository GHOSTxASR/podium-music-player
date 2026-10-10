# Changelog

All notable changes to Podium will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.1] - 2026-10-10

### Added
- Automated unit and regression test suite for `OnlineMusicRepository` initialization and YouTube Music session parsing / paging.
- Comprehensive sign-in flow and cookie-validation tests in `WebSignInActivityTest`.

### Fixed
- **YouTube Music WebView sign-in:** Added resilient multi-trigger and polling cookie detection, manual "Check sign-in" action, support for Google 2-Step Verification, and recognition of regional Google sign-in hosts.
- **Persistent Keystore session:** Safely encrypted and persisted YouTube Music session cookies using Android Keystore, enabling transparent session restoration across app force-stops and phone reboots without repeated sign-in prompts.
- **Cold-start crash on signed-in startup:** Corrected initialization order in `OnlineMusicRepository` where `shelvesCache` and `likesFetchedAt` were accessed before instantiation during early account flow collection, and ensured library refreshes execute off the main thread on `Dispatchers.IO`.
- **Startup deadlock / ANR prevention:** Replaced blocking synchronous `by lazy` WebSettings user-agent retrieval in `AppGraph` with volatile fallback and asynchronous warming on `Dispatchers.IO`, preventing main looper deadlocks with Chromium initialization.

## [0.1.0] - 2026-10-08

### Added
- First public release of Podium, a native Android music player with the classic wheel interface.
- Turn wheel to navigate, press centre button to select, Menu to go back.
- Cover Flow perspective view for album browsing.
- Full-screen, word-by-word time-synced lyrics with customizable typography.
- Offline-first local music library backed by Room database.
- Online streaming integration for external music sources.
- Virtual device chassis with custom finishes, materials, display themes, and physical feedback.
