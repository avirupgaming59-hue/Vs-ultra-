# Security Summary

This walkthrough documents the reliability fixes and security protections implemented across VS Shell to ensure AI code generation completes cleanly on the localhost code-server and to safeguard application credentials, workspace files, network traffic, and WebView interactions.

## Reviewed Files

| Area | Files Reviewed & Updated |
| :--- | :--- |
| AI & App Check Startup | `VsShellApplication.kt`, `MainActivity.kt`, `GeminiChatService.kt`, `ChatViewModel.kt`, `LocalhostRuntimeHost.kt`, `WorkspaceViewModel.kt` |
| Credential & Data Protection | `SecureVault.kt`, `SettingsRepository.kt`, `ServerProfileRepository.kt`, `AndroidManifest.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `StartupDiagnostics.kt` |
| Localhost Server & Storage Isolation | `LocalhostServerEngine.kt`, `PhoneFileRepository.kt` |
| Network & WebView Hardening | `network_security_config.xml`, `ChromeCustomTabsManager.kt`, `InAppWebWorkspaceController.kt` |
| User-Facing App Protection | `MainViewModel.kt`, `SettingsScreen.kt`, `MainActivity.kt` |

## Issues Resolved

| Category | Vulnerability / Bug Fixed | Mitigation Implemented |
| :--- | :--- | :--- |
| AI App Check Attestation | Initializing Firebase App Check in `Application.onCreate()` without the launch `Intent` caused debug tokens to be missed, rejecting AI API requests with HTTP 403. | Removed `configureAppCheck` from `VsShellApplication.onCreate()` so `MainActivity.onCreate()` configures App Check first with the launch `Intent`. |
| AI Completion & Timeout | Tight 20s timeouts and missing fallback model handling interrupted multi-file AI workspace builds and chat replies. | Increased generation timeouts to 60s, enabled multi-file structured JSON output, installed a persistent AI handler in `LocalhostRuntimeHost`, and added automatic compatibility fallback on `404 / NOT_FOUND` while keeping `gemini-4-hydrogen` primary. |
| Plaintext Credential Storage | Master passwords/tokens in DataStore and server password hints in Room were stored in plaintext. | Created `SecureVault` using hardware-backed Android Keystore `AES/GCM/NoPadding` (256-bit, random 12-byte IV) with `vsshell:enc:v1:` envelope encryption for DataStore and Room credentials. |
| Unauthenticated Localhost API | Any local process or malicious web page could access `http://127.0.0.1:<port>/api/*` via CSRF or DNS rebinding to read/write workspace files or trigger AI calls. | Enforced per-session 256-bit cryptographic `X-VSShell-Token` verification on `/api/*`, strict loopback `Host` and `Origin` checks, 2 MB body size caps, 24-connection concurrency limits, and canonical path confinement. |
| Stored HTML Injection & Preview Isolation | Unescaped workspace names and unisolated preview frames could execute arbitrary scripts in the local IDE origin. | Applied HTML entity escaping to generated starter pages, isolated live previews on `/preview/<file>` with a strict CSP and `<iframe sandbox="allow-scripts allow-forms">` (no `allow-same-origin`). |
| File Import Path Traversal | Untrusted document display names could write outside the app's `phone_workspace` directory. | Stripped path separators, control characters, and `..` segments in `PhoneFileRepository` and enforced canonical directory confinement on all reads, writes, and deletes. |
| Insecure WebView Settings & Autofill | `allowFileAccess`, `allowContentAccess`, mixed content compatibility, unsafe URI schemes, and unpinned password autofill exposed local files and credentials. | Disabled file/content access and mixed content, enabled Safe Browsing, blocked unsafe schemes (`javascript:`, `file:`, `data:`, `vbscript:`, `content:`), and pinned autofill to the configured profile host over HTTPS or loopback HTTP. |
| Global Cleartext Network Traffic & Backups | `usesCleartextTraffic="true"` allowed unencrypted remote connections and `allowBackup="true"` included credential stores. | Replaced global cleartext with `network_security_config.xml` enforcing HTTPS globally (HTTP restricted to `127.0.0.1` and `localhost`), disabled backups (`android:allowBackup="false"`), excluded sensitive directories, and added log redaction in `StartupDiagnostics`. |
| Physical Device & Screen Privacy | Shoulder-surfing, screenshots, and background task snapshots could expose sensitive workspace sessions. | Added opt-in **Screen Privacy Shield** (`FLAG_SECURE`) and salted **PBKDF2-SHA256 App Lock PIN** with automatic relock and brute-force cooldown. |

## Safeguards Verified

| Safeguard | Mechanism | Verification Method |
| :--- | :--- | :--- |
| Keystore Credential Encryption | AES-256-GCM authenticated encryption & tamper detection | `ExampleUnitTest.secureVault_aesGcmRoundTripTamperCheckAndPinVerification` |
| Salted PIN Hashing & Lockout | 120,000-iteration PBKDF2-SHA256 + `MessageDigest.isEqual` + 5-attempt cooldown | `ExampleUnitTest.secureVault_aesGcmRoundTripTamperCheckAndPinVerification` & `MainActivity.AppPinLockOverlay` |
| Localhost API Access Control | Cryptographic `X-VSShell-Token`, loopback `Host`/`Origin` filter, path confinement | `ExampleUnitTest.localhostServerEngine_enforcesSessionTokenHostOriginAndPathConfinement` |
| URL & WebView Scheme Filtering | Allowlist strictly permitting `http` / `https` and safe `about:blank` | `ExampleUnitTest.urlAndWebViewAutofillGuards_blockDangerousSchemesAndCrossHostAutofill` |
| Host-Pinned HTTPS Autofill | Credential injection permitted only on matching target host over HTTPS or loopback | `ExampleUnitTest.urlAndWebViewAutofillGuards_blockDangerousSchemesAndCrossHostAutofill` |

---

## PoC Verification

### 1. Localhost API Token, Host/Origin Guard, and Path Traversal Blocking
- **Exploit Scenario**: A rogue local app or external website attempts to read workspace files via `GET http://127.0.0.1:<port>/api/files` without the IDE session token, spoof `Host: evil.attacker.example` (DNS rebinding), or pass `Origin: https://evil.attacker.example` (cross-origin attack), or write `../outside.txt`.
- **Verification**: Executed via `ExampleUnitTest.localhostServerEngine_enforcesSessionTokenHostOriginAndPathConfinement`:
  1. Unauthenticated `GET /api/files` immediately returns `HTTP/1.1 403 Forbidden`.
  2. Spoofed `Host: evil.attacker.example` immediately returns `HTTP/1.1 403 Forbidden`.
  3. Cross-origin `Origin: https://evil.attacker.example` immediately returns `HTTP/1.1 403 Forbidden`.
  4. `resolveConfinedFile(tempDir, "../outside.txt")` and `resolveConfinedFile(tempDir, "..\\outside.txt")` both return `null`.
  5. Fetching `GET /` provides the sandboxed preview frame (`sandbox="allow-scripts allow-forms"`) and the embedded `SESSION_TOKEN`, after which `DELETE /api/file?name=nonexistent.txt` with `X-VSShell-Token` returns `HTTP/1.1 200 OK` (with `X-Content-Type-Options: nosniff`) and `GET /preview/index.html` returns `Content-Security-Policy` (`connect-src 'none'`).

### 2. AES-256-GCM Tamper Detection & Salted PIN Verification
- **Exploit Scenario**: Stored DataStore/Room ciphertexts or PIN hashes are modified on disk or tested with incorrect guesses.
- **Verification**: Executed via `ExampleUnitTest.secureVault_aesGcmRoundTripTamperCheckAndPinVerification`:
  1. Plaintext encrypted with `encryptAesGcmBase64` decrypts identically with `decryptAesGcmBase64`.
  2. Flipping a single character in the Base64 ciphertext triggers `AEADBadTagException` during decryption, preventing silent corruption or padding oracle attacks.
  3. `hashPin("482910")` produces a salted `pbkdf2:sha256:120000:...` digest; `verifyPin` accepts `"482910"` and rejects `"000000"` and `"48291"`.
  4. `sanitizeUrlForLogs` and `redactSensitiveText` strip query parameters, bearer tokens, and `password=` values from crash/diagnostic logs.

### 3. Dangerous URL Scheme & Cross-Host Credential Autofill Blocking
- **Exploit Scenario**: A workspace link attempts to execute `javascript:alert(1)`, access app files via `file:///data/data/...`, or redirect an active WebView with pending `autoLoginPassword` to a third-party/unencrypted host.
- **Verification**: Executed via `ExampleUnitTest.urlAndWebViewAutofillGuards_blockDangerousSchemesAndCrossHostAutofill`:
  1. `ChromeCustomTabsManager.normalizeUrl` returns `""` for `javascript:`, `file:`, `data:`, and `intent:` URLs while preserving `localhost:8080`.
  2. `InAppWebWorkspaceController.isBlockedUnsafeScheme` blocks `javascript:` and `file:` navigations before WebView loads them or triggers external prompts.
  3. `InAppWebWorkspaceController.isAllowedAutofillTarget` permits credential injection on `https://coder.example.com/login` (matching expected host over HTTPS) and `http://127.0.0.1:8080/`, while rejecting `https://evil.example.com/login` (wrong host) and `http://coder.example.com/login` (unencrypted remote HTTP).
