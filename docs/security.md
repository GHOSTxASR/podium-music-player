# Security & Privacy

**Date:** 2026-10-02 · Scope: client app only (Podium has no backend).

## 1. Hard rules
1. **No secrets in the client or the repo.** Launch sources need none. Signing keys and any future tokens come from environment variables / local files that are gitignored (`.gitignore` covers `*.jks`, `*.keystore`, `keystore.properties`, `.env*`, `local.properties`).
2. **No circumvention.** Podium never implements DRM removal, signature/cipher deobfuscation, bot-detection/attestation spoofing (e.g., BotGuard/PoToken), client impersonation, or paywall bypass (`music-source-analysis.md` §2).
3. **No analytics or crash SDKs** (D-12). No data leaves the device except requests to sources the user configured, artwork hosts those sources reference, and LRCLIB after consent (D-11).

## 2. Threat model (STRIDE, condensed)
| Asset | Threat | Mitigation |
|---|---|---|
| Server credentials | Theft from storage/backup | AES-256-GCM with an Android Keystore key (non-exportable); ciphertext in a dedicated DataStore file excluded from backup and device transfer; never logged |
| Server credentials | Interception on network | HTTPS required except LAN hosts with explicit per-server consent (D-09); Subsonic token+salt (no plaintext password on the wire); prefer OpenSubsonic API keys |
| Session/API tokens in URLs | Leakage via logs | `Redactor` strips `t`, `s`, `p`, `apiKey`, `u` query params from every logged URL |
| Media session | Malicious controller app | `onConnectAsync` allow-list; unknown controllers read-only (ADR-003) |
| Exported components | Intent abuse | Only launcher activity and `PlaybackService` (required) exported; download job service not exported; deep links (P1) validated against an allow-list of key types and id formats |
| Downloads | Path traversal / overwrite | Paths built from `sha1(trackId)` + known extension; never from metadata; files in app-private storage |
| Downloaded files | Tampered/corrupt content | Verification (size, checksum, decode probe) before use; sha256 recorded |
| External URLs (artist links, attributions) | Phishing/intent injection | Only `https:` (and `http:` LAN for servers) opened via Custom Tabs; no `intent:`/`javascript:` schemes; artwork URLs must be http(s) |
| Malicious server responses | Parser abuse, huge payloads | kotlinx.serialization with `ignoreUnknownKeys`, response size caps (5 MB JSON), timeouts (connect 10 s, read 20 s), no reflection-based deserialisation |
| Cleartext | MITM on LAN | Warning at setup; badge on the source ("Not encrypted"); never for non-private hosts |
| Local DB | Data exfiltration via backup | Backup includes DB (user value) but no credentials; documented in privacy notice; user can disable backup at OS level |
| WebView | — | **Not used** anywhere |

## 3. Network configuration
- `network_security_config.xml`: base config trusts system CAs only (no user CAs in release), `cleartextTrafficPermitted="true"` at platform level **only because** app-level `NetworkPolicy` enforces the LAN rule (D-09); unit-tested with hostnames/IPs (RFC 1918, 169.254/16, fc00::/7, fe80::/10, `.local`, `.lan`, `.home.arpa`, public IPs, IDN tricks).
- Debug builds may trust user CAs for proxy debugging.
- Certificate pinning: not used (self-hosted servers have arbitrary certs). Self-signed certs: user may trust a specific certificate fingerprint for a server after an explicit warning (P1), stored per source.

## 4. Permissions (minimum set)
| Permission | Why | When requested |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Sources | install-time |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback | install-time |
| `FOREGROUND_SERVICE_DATA_SYNC` | Downloads on API 29–33 | install-time |
| `RUN_USER_INITIATED_JOBS` | Downloads on API 34+ | install-time |
| `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (≤ 32) | On This Device source | When the user enables that source |
| `POST_NOTIFICATIONS` (33+) | Download progress/completion | First download, with rationale |
| `WAKE_LOCK` | Media3 wake mode | install-time |
No location, contacts, microphone, or phone permissions.

## 5. Privacy
**Data Podium stores (all on device):** library metadata, likes, playlists, play history (with formats), queue, search history (can be disabled), lyrics cache, downloads, preferences, encrypted credentials, diagnostic log (rolling, no content in release).
**Data that leaves the device:** requests to configured sources (which receive what any client sends them: queries, play/scrobble events if enabled, stars); artwork fetches; LRCLIB lookups (title, artist, album, duration) only after consent; Audius requests include `app_name=Podium`.
**User controls:** clear history, clear search history, disable search history, revoke LRCLIB consent, remove sources (and their cached metadata), export library (JSON: likes, playlists, history), delete all data.
**Privacy notice** (Phase 12) mirrors this section in plain language and is reachable from About and onboarding.

## 6. Dependency & supply-chain hygiene
- Versions pinned in the catalog; Gradle dependency verification (`verification-metadata.xml` with SHA-256) enabled in Phase 3.
- Repositories limited to Google Maven and Maven Central (no JitPack).
- License audit (Phase 12): every dependency's license, attribution, and redistribution terms recorded in `docs/licenses.md` and generated into About.
- Renovate/Dependabot (PR-only, no auto-merge) once the repo has a remote.

## 7. Logging rules
Release: ids, states, error codes, durations. Never: titles, artist names, queries, URLs with credentials, file paths containing user names, full stack traces in UI. Debug builds may log titles for development.
