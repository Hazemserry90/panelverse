# Security Policy

## Reporting a vulnerability

If you discover a security issue in PanelVerse, please report it **privately** rather than opening a public issue:

- Open a **private** [GitHub security advisory](https://github.com/Hazemserry90/MangaVerse-updated-version-of-anime-app/security/advisories/new), or
- Email **hazemsery15@gmail.com** with a description, reproduction steps, and the affected version/commit.

Please allow reasonable time for a fix before any public disclosure.

## Supported versions

| Version | Supported |
|---|---|
| `master` (latest) | ✅ |
| Older commits | ❌ |

## Security measures in place

- **No secrets in the repository.** `app/google-services.json`, `local.properties` and keystores are git-ignored.
- **Firebase Realtime Database** requires authentication — anonymous reads are rejected (`401`), and data is scoped per user under `users/{uid}/…`.
- **Hardened WebView** (`ReaderActivity`):
  - Only `http`/`https` URLs are loaded in-app; all other schemes (`intent://`, `market://`, `tel:`, …) are blocked.
  - Incoming URLs are validated before loading.
  - File and content access are disabled (`allowFileAccess = false`, `allowContentAccess = false`).
  - No `addJavascriptInterface` bridge is registered (no JS→Java surface).
- **Component exposure minimized.** Only the launcher `splashscreen` activity is exported; `MainActivity` and `ReaderActivity` are `exported="false"`.
- **HTTPS-only.** Cleartext HTTP traffic is not permitted (no `usesCleartextTraffic`, no network-security-config exceptions).
- **No backup extraction.** `android:allowBackup="false"`.
- **Strong password policy** at sign-up, and generic authentication error messages (no user enumeration).
- **Dependency hygiene.** Unused `Guava` dependency removed; `android-gif-drawable` kept up to date.

## For contributors

- Never commit `google-services.json`, `local.properties`, signing keystores, or API keys.
- Keep dependencies current and prefer HTTPS endpoints.
- Validate any external input (URLs, deep links) before use.
