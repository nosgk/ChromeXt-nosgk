# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 3.9.x | ✅ |
| < 3.9 | ❌ |

## Reporting a vulnerability

Please use GitHub's **private vulnerability reporting** on this repository:

1. Open the **Security** tab.
2. Choose **Report a vulnerability**.
3. Include affected version, browser/ROM, reproduction steps, and impact.

Do not open a public issue, and do not include exploit details in a pull request.

You can expect an initial response within 7 days. Please allow time for a fix and a release before public disclosure.

## Security model

ChromeXt runs inside the browser process through Xposed. It can execute JavaScript in pages, read/write UserScript storage, and interact with browser internals. The design assumes:

- The user explicitly scopes the module to trusted browsers.
- Installed UserScripts are trusted code. A UserScript can do anything the page can do, plus the granted `GM_*` APIs.
- `GM.ChromeXt` is opt-in per script via `@grant GM.ChromeXt` and is locked behind a per-process token.
- The module does not expose a network service in release builds. The local extension server is debug-only and must not be enabled in production builds.
- `@require` resources are fetched from the URLs declared by the script author; ChromeXt does not provide integrity guarantees beyond HTTPS.

## Hardening guidelines for script authors

- Do not request `GM.ChromeXt` unless strictly necessary.
- Do not load `@require` resources from untrusted hosts.
- Treat `GM_storage` as sensitive: it is readable by the script and by the manager UI.
- Avoid injecting untrusted strings into `evaluateJavascript`; ChromeXt encodes payloads, but script code should still validate its inputs.

## For contributors

- Do not add exported components without a caller check.
- Do not log full URLs, script bodies, or storage values at default log level.
- Do not weaken Android lint security checks without a documented reason.
- Security-relevant changes need a test or a device verification note.
