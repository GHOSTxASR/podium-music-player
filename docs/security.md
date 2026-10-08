# Security & Privacy

**Date:** 2026-10-02 · Scope: client app only (Podium has no backend).

## 1. Hard rules
1. **No secrets in the client or the repo.** Launch sources need none. Signing keys and any future tokens come from environment variables / local files that are gitignored (`.gitignore` covers `*.jks`, `*.keystore`, `keystore.properties`, `.env*`, `local.properties`).
2. **Stream resolution & DRM boundaries (D-48).** Under D-48, Podium is a sideloaded personal music player. Direct stream resolution (using client-identity rotation, signature/n-parameter deobfuscation, and PoToken generation comparable to BitChord) is permitted and required for YouTube Music streaming. The former "stream-unlock boundary" is revoked. Podium still strictly avoids Widevine DRM cracking, paid subscription cracking, or arbitrary local filesystem overwriting. Unofficial sources are marked with `UNOFFICIAL_API`.
3. **No analytics or crash SDKs** (D-12). No data leaves the device except requests to sources the user configured, artwork hosts those sources reference, and LRCLIB after consent (D-11).

## 2. Threat model (STRIDE, condensed)
| Asset | Threat | Mitigation |
|---|---|---|
| Server credentials | Theft from storage/backup | AES-256-GCM with a non-exportable Android Keystore key; ciphertext only, in the private prefs file `credentials` (never the database); cloud backup off; a device-to-device copy can't be opened without this phone's key; never logged (D-37) |
| Server credentials | Interception on network | HTTPS required except hosts on the listener's own network, after explicit consent (D-09); Subsonic token + fresh salt per request (the password never on the wire). OpenSubsonic API keys: not yet |
| Session/API tokens in URLs | Leakage via logs, storage, other apps | Tokenised URLs are never logged and never persisted (artwork is stored as source + key, fetched inside the source). Transport errors are reduced to class names. Stream URLs live only in memory, pinned to queue items, and are dropped when their source stops serving (D-37) |
| Media session | Malicious controller app | `onConnectAsync` allow-list; unknown controllers read-only (ADR-003) |
| Exported components | Intent abuse | Only launcher activity and `PlaybackService` (required) exported; download job service not exported; deep links (P1) validated against an allow-list of key types and id formats |
| Downloads | Path traversal / overwrite | Paths built from `sha1(trackId)` + known extension; never from metadata; files in app-private storage |
| Downloaded files | Tampered/corrupt content | Verification (size, checksum, decode probe) before use; sha256 recorded |
| External URLs (artist links, attributions) | Phishing/intent injection | Only `https:` (and `http:` LAN for servers) opened via Custom Tabs; no `intent:`/`javascript:` schemes; artwork URLs must be http(s) |
| Malicious server responses | Parser abuse, huge payloads | kotlinx.serialization with `ignoreUnknownKeys`, response size caps (5 MB JSON), timeouts (connect 10 s, read 20 s), no reflection-based deserialisation |
| Cleartext | MITM on LAN | Warning and consent at setup; never to hosts off the listener's network, checked on every request, redirect hop and stream URL (`NetworkPolicy.permits`). The "Not encrypted" badge on the source is not built yet |
| Local DB | Data exfiltration via backup | `allowBackup="false"`: no cloud backup. Device-to-device transfer can still copy app data; the DB holds no credentials and no tokenised URLs |
| WebView | — | **Not used** anywhere |

## 3. Network configuration
- **As implemented (D-37, updated D-69):** there's no `network_security_config.xml`, and the manifest now sets `usesCleartextTraffic="false"`. The text below describes the earlier LAN-source design, when cleartext was allowed only because a security config can't name address ranges. The app-level `NetworkPolicy` enforces the D-09 rule instead, and it's unit-tested (`NetworkPolicyTest`) with hostnames and IPs: RFC 1918, 169.254/16, fc00::/7, fe80::, loopback, `.local`, `.lan`, `.home.arpa`, public IPs and look-alikes (`nas.local.example.com`, `192.168.1.20@evil.example.com`). It checks:
  - the address typed at setup (`check`);
  - every request the server client makes and every redirect hop it would follow (never https → http, at most 3);
  - every stream URL the player opens, from any source (`permits`).
- System CAs only (platform default). Media3's own HTTP data source follows same-protocol redirects without the policy check (known limit, D-37).
- *Original design:* base config trusting system CAs only, cleartext permitted at platform level only because `NetworkPolicy` enforces the LAN rule.
- Debug builds may trust user CAs for proxy debugging.
- Certificate pinning: not used (self-hosted servers have arbitrary certs). Self-signed certs: user may trust a specific certificate fingerprint for a server after an explicit warning (P1), stored per source.

## 4. Permissions (minimum set)
| Permission | Why | When requested |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Sources | install-time |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback | install-time |
| `FOREGROUND_SERVICE_DATA_SYNC` | Downloads on API 29–33 | install-time |
| `RUN_USER_INITIATED_JOBS` | Downloads on API 34+ | install-time |
| `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (≤ 32) | On This Device source | Once by itself when Podium first switches on without it; then from "Allow music access" on Home or Music. If Android stops showing the question, that row opens Podium's page in the system settings (D-47). |
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
Server code never logs URLs or exception messages (they can contain the URL and its token); the debug `now-playing-source` command logs a source's id and display name, never a URL.

## 8. Configured-source security: O11 audit (2026-10-06)
Scope: the OpenSubsonic source, configured sources, credentials, the network policy and what they touch. Evidence: code review, unit tests, and device acceptance on a Nothing Phone (3a). The device test used local test servers through `adb reverse` with generated credentials, plus a scan of Podium's logcat, the servers' request logs and the app's files.

| Area | Finding | Result |
|---|---|---|
| Keystore / AES-GCM | 256-bit AES key generated in the Keystore (GCM, no padding, random IV from the Keystore); output IV + ciphertext + tag; unreadable value → signed out | OK |
| Passwords at rest | Only sealed, in `credentials.xml`; `source-profiles.xml` holds address and user name; no secret in the database | OK (verified on device) |
| Passwords on the wire | Token + fresh 8-byte `SecureRandom` salt per request; 237 requests logged by the test servers, none with `p=` | OK |
| Logs | Podium-only logcat (5,600 lines over the whole acceptance run): no password, user name, server address, `/rest/` URL, token or salt | OK |
| Cleartext | Setup refuses public `http`, asks before local `http`. **Fixed:** the policy was only checked at setup while the platform allows cleartext app-wide. Now every request, redirect hop and stream URL is checked | Fixed |
| Profile isolation | Secrets keyed by a random per-profile id; signing out of / removing one server left the other's secret, profile and playback untouched (device) | OK |
| Sign out / remove | Secrets deleted, capabilities → sign in, choices forgotten. **Fixed:** stream URLs already pinned to queued items could still be used after sign-out, removal or turning the source off; they're now dropped (the playing item excepted, D-18) | Fixed |
| Temporary auth state | Form values in non-saveable state, cleared on leaving; masked; password keyboard, no autocorrect. `ConnectResult.Connected` and `ServerCredentials` mask secrets in `toString()` (**fixed** for the former) | Fixed |
| Exported components | Launcher activity and `PlaybackService` only; untrusted controllers get default (read-only) commands, Podium's own UI the queue commands; debug intents are no-ops in release | OK |
| Backup | `allowBackup="false"`; a device-to-device copy of `credentials.xml` can't be opened without this phone's Keystore key | OK (documented) |
| Exceptions | Transport and API errors reduced to class names; ExoPlayer's own error logs carried no URLs in the device run | OK |
| Breaker | **Fixed:** failures while a breaker was already open lengthened its backoff per call (to 10 min after a short outage) | Fixed |

## 9. YouTube Music, lyrics and appearance audit (2026-10-06)
Scope: `sources:youtubemusic`, `player:remote`, the sign-in WebView, the online repository, lyrics, the display background picture, and the repository itself. Evidence: code review, unit and Robolectric tests, a secret scan of the tree and its full git history, the merged manifest. **Not** verified on a device (no device reachable from the build container).

| Area | Finding | Result |
|---|---|---|
| Google credentials | Sign-in happens on Google's own page in a WebView; Podium never sees the password or second factor; it keeps only session cookies | OK (design) |
| Session at rest | `{session, account name, account key}` sealed by `KeystoreCredentialStore` (AES-256-GCM, Keystore key); never in the database or plaintext prefs; `WebSession.toString` prints a count | OK |
| Session in transit | https only, host allow-list, no redirects; cookies and SAPISIDHASH sent only to music.youtube.com | OK |
| Expiry / revocation | a refused account call (401/403, `logged_in = 0`) deletes the session and shows "sign-in expired"; sign-out wipes the account's online rows, then the session | OK (tests) |
| Account isolation | online rows keyed by `ytm-<sha-256 prefix>`; likes cache per account with a write counter against stale refreshes | OK (tests) |
| WebView | JavaScript (required by the page) but no file/content access, no mixed content, no geolocation, no new windows, safe browsing, SSL errors cancel, navigation allow-list, all web state wiped before and after; **`FLAG_SECURE` added in this audit** | Fixed |
| Logs | no URLs, headers, cookies, account names or song titles in logs; transport exceptions reduced to class names; debug `remote-probe` prints shapes and counts | OK |
| Exported components | `MainActivity` (launcher; debug commands only in debug builds — release has a no-op); `PlaybackService` (media library service: other apps get read-only commands, custom commands only for Podium's own package); `MediaSessionAccessService` (guarded by `BIND_NOTIFICATION_LISTENER_SERVICE`, handles no notifications); `WebSignInActivity` not exported | OK |
| Cleartext | `usesCleartextTraffic=false` again (the LAN exception left with OpenSubsonic) | OK |
| Backup | `allowBackup=false` | OK |
| Lyrics | title, artist, album and length sent to LRCLIB only after consent (D-11); https only; bounded answers; cache keys are hashes | OK |
| Background picture | system photo picker, no storage permission; only the URI is stored; persisted read access released when replaced; an unreadable picture falls back silently (log: exception class only) | OK |
| Repository | no API keys, tokens, cookies, signing keys, local paths with secrets, personal data or databases in the tree or history; test values are invented (`sapi-secret/123`) | OK |
| Remaining risks | the unofficial basis (D-19) can change without notice; a WebView session grants Podium the account's YouTube Music session (necessary for the library — documented to the listener: "You sign in on the service's own page. Podium never sees your password."); debug builds accept debug intents from any app on the device | Accepted |

