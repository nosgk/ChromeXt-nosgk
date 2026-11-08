# ChromeXt

[![CI](https://github.com/nosgk/ChromeXt-nosgk/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/nosgk/ChromeXt-nosgk/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/nosgk/ChromeXt-nosgk?color=orange&label=Download)](https://github.com/nosgk/ChromeXt-nosgk/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/nosgk/ChromeXt-nosgk/total?label=Downloads&color=yellow)](https://github.com/nosgk/ChromeXt-nosgk/releases)

Add UserScript and DevTools support to Chromium-based and WebView-based Android browsers using the Xposed framework.

This repository is a fork of [JingMatrix/ChromeXt](https://github.com/JingMatrix/ChromeXt), maintained by [nosgk](https://github.com/nosgk). The upstream project remains the reference implementation; this fork focuses on engineering hardening, a native script manager, and reproducible builds.

## How it works

ChromeXt hooks `onUpdateUrl` in [UserScript.kt](app/src/main/kotlin/org/matrix/chromext/hook/UserScript.kt), compares the current URL with installed UserScripts, and evaluates JavaScript through the `javascript:` scheme or the DevTools protocol when available.

Chromium-based browsers such as Chrome, Brave, Vivaldi, Whale, Bromite, Cromite, Thorium and others are supported. Most WebView-based browsers are supported as well; if yours is not, please open an issue.

> WebView users: enable this module only for the browser application you want to use, not for every WebView host and not for the Android system.

## Requirements

- Android 5.0+ (API 21+), arm64-v8a device recommended.
- Xposed framework:
  - Root users: [Vector](https://github.com/JingMatrix/Vector) or LSPosed.
  - Non-root users: a patched [LSPatch](https://github.com/JingMatrix/LSPatch) build.

The module APK contains no native libraries, so it is architecture-independent. Release artifacts are published as `ChromeXt-arm64-v8a.apk` to match the recommended device class.

## Install

1. Download the latest `ChromeXt-arm64-v8a.apk` from [Releases](https://github.com/nosgk/ChromeXt-nosgk/releases/latest).
2. Install it and enable the module in your Xposed manager.
3. Scope the module to the browsers you use.
4. Reboot the browser process (force stop or reboot).

Non-root LSPatch flow:

1. Download a patched `lspatch.jar` and `ChromeXt-signed.apk`.
2. Patch the target browser: `java -jar lspatch.jar <browser.apk> -d -v -m ChromeXt-signed.apk --force --injectdex`.
3. Install the patched browser (you may need to uninstall the original first).

## Usage

Install UserScripts by opening URLs that end with `.user.js`, or by sharing a JavaScript file to ChromeXt.

### Supported UserScript APIs

ChromeXt implements most commonly used [Tampermonkey APIs](https://www.tampermonkey.net/documentation.php):

- `@name` (colons are not allowed), `@namespace`, `@description`, `@version`, `@author`, `@icon`
- `@match` (Chrome match-pattern syntax, regular expressions supported), `@include`, `@exclude`
- `@run-at`: `document-start`, `document-end`, `document-idle` (default)
- `@grant`: `GM_addStyle`, `GM_addElement`, `GM_xmlhttpRequest`, `GM_openInTab`, `GM_registerMenuCommand`, `GM_unregisterMenuCommand`, `GM_download`, `unsafeWindow`
- `@grant`: `GM_setValue`, `GM_getValue`, `GM_listValues`, `GM_addValueChangeListener`, `GM_removeValueChangeListener`, `GM_setClipboard`, `GM_cookie`, `GM_notification`, `window.close`
- `@require`, `@resource` (without Subresource Integrity)
- `@inject-into`, `@sandbox`: imported scripts may define globals by default; set `@inject-into content` or `@sandbox DOM` to disable
- `@noframes`: frame injection requires a main-page script with `@grant frames`

The implementations live in [Local.kt](app/src/main/kotlin/org/matrix/chromext/script/Local.kt) and [GM.js](app/src/main/assets/GM.js). Behavior intentionally differs from Tampermonkey in a few places; check those files when in doubt.

### `GM.ChromeXt`

`GM.ChromeXt` is a powerful and dangerous API. It must be declared with `@grant GM.ChromeXt` to unlock. It exposes `ChromeXt.dispatch(action, payload)` to page scripts; actions are handled by [Listener.kt](app/src/main/kotlin/org/matrix/chromext/Listener.kt). It is locked by default so that a malicious UserScript cannot take over the browser integration.

### Script management

ChromeXt ships a native manager (Jetpack Compose). Open it from the launcher, from the LSPosed module card (the settings button), from the browser page menu (`Script manager`), or from Chrome's Developer options.

The manager supports:

- installed-script list with search, filters, sorting and one-tap enable/disable;
- a code editor plus per-script settings (`@run-at`, custom `@match`/`@include`/`@exclude`, update URL);
- a `GM_storage` inspector/editor;
- install confirmation with target sites, requested permissions and a code preview;
- ZIP backup and restore (ChromeXt format and plain `.user.js` archives).

Scripts live in a central database inside the module and are migrated once from each browser's legacy database. If the module process is unavailable (for example, an OEM background restriction), browsers fall back to their local database.

### Edit before installing

If you cancel the installation prompt, you can edit the script directly in the page. Use the `Install UserScript` page menu entry afterwards to install the modified version.

### DevTools and Eruda

The three-dot page menu offers:

1. `Developer tools` for the ChromeXt front end;
2. `Script manager` to open the native panel;
3. `Eruda console` for regular pages.

For WebView-based browsers and Chromium forks with their own page menu (for example Herond and Whale), these entries appear in the context menu.

## Build

Builds run on GitHub Actions only; the workflow is **manual** (`workflow_dispatch`) and is never triggered by a push. See [.github/workflows/ci.yml](.github/workflows/ci.yml).

Local development still works if you have JDK 21 and Android SDK 35 installed:

```bash
./gradlew format          # apply ktfmt
./gradlew ktfmtCheck      # verify formatting
./gradlew lintDebug       # Android lint
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The canonical gate is the CI workflow: `validate` (format, lint, unit tests, JS tests, debug build) must pass before `package` (release build, Xposed entry-point check, optional signing, checksums) runs.

## Test

```bash
./gradlew testDebugUnitTest
node --test tools/js-tests
```

See [TESTING.md](TESTING.md) for the test layout, device matrix and how to add fixtures.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md). In short: keep behavior changes explicit, add tests for pure logic, run `./gradlew format`, and make sure the manual CI run is green before merging.

Useful entry points:

1. Tampermonkey API: [Local.kt](app/src/main/kotlin/org/matrix/chromext/script/Local.kt) and [GM.js](app/src/main/assets/GM.js)
2. Eruda configuration: [eruda.js](app/src/main/assets/eruda.js)
3. WebView browser support: [WebView.kt](app/src/main/kotlin/org/matrix/chromext/hook/WebView.kt)
4. Hook compatibility: [proxy/](app/src/main/kotlin/org/matrix/chromext/proxy) and [hook/](app/src/main/kotlin/org/matrix/chromext/hook)

## Security

Please report vulnerabilities privately as described in [SECURITY.md](SECURITY.md). Do not open a public issue for a security problem.

## License

ChromeXt is distributed under the terms in [LICENSE](LICENSE).
