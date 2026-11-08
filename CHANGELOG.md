# Changelog

All notable changes to this fork are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the module's `versionName`.

## [3.10.0] - 2026-11-08

### Added

- Migrated the Xposed integration to the libxposed Modern API 102 (`io.github.libxposed:api:102.0.0`). The module now ships `META-INF/xposed/{module.prop,java_init.list,scope.list}` and no longer uses `assets/xposed_init` or the legacy manifest metadata.
- Simplified Chinese UI (`values-zh`) with every Compose string moved into resources.
- Per-app script scope: the `apps` column (schema v11) limits a script to selected packages; an empty list keeps the previous "all hooked apps" behavior.
- Hooked-apps screen with runtime reporting (`hooked_app` table, provider `reportHookedApp`) plus installed-but-inactive supported browsers.
- Manual script update checks against `@updateURL`/`@downloadURL`, version comparison, and one-tap update that preserves local settings and GM storage.
- Theme mode selector (follow system / light / dark) and an About page with the fork and upstream links plus the current version.
- Loading skeletons, empty/error states, adaptive card grid, item animations and app badges in the script list.
- `INTERNET` permission for the manager process so update checks work under LSPosed.

### Changed

- The parser now drops unknown `@run-at` values and non-http(s) update URLs, and deduplicates `@match`/`@grant` entries.
- Search is debounced (180 ms) and uses a precomputed per-script haystack; filter, sort and search state survive configuration changes.
- Script list filtering, sorting and app scoping are applied consistently in the UI and at injection time.
- Manager database writes run off the main thread with optimistic UI updates.

### Fixed

- New scripts receive a unique id instead of overwriting the previous "New Script" row.
- Editor state is keyed per script, so switching scripts no longer reuses stale edits.
- Saving a script keeps its `sortOrder` instead of resetting it to zero.
- Deleting all scripts asks for confirmation.
- "Install from URL" now validates the scheme and caps the download at 4 MiB.

### Removed

- The legacy `de.robv.android.xposed:api:82` dependency, `assets/xposed_init`, the legacy `xposed*` manifest metadata and `res/values/arrays.xml` (the scope now lives in `META-INF/xposed/scope.list`).

## [Unreleased]

### Added

- Contributor, testing and security guides (`CONTRIBUTING.md`, `TESTING.md`, `SECURITY.md`).
- Documentation consistency checks (`tools/docs/check_docs.py`).
- Release verification (`scripts/verify-release.sh`) and APK signing (`scripts/sign-apk.sh`) scripts.
- Issue templates, pull-request checklist and `CODEOWNERS`.
- Database schema v10: `enabled`, `sort_order`, `custom_match`, `custom_include`, `custom_exclude`, `custom_run_at`, `update_url`, `icon_url` and `last_updated`.
- Central script database exposed to browser processes through `ScriptContentProvider`, with a one-time legacy migration and a local fallback when the provider is unreachable.
- UserScript metadata parsing for `@version`, `@author`, `@description`, `@icon`, `@updateURL` and `@run-at`.
- `@run-at` and match/exclude overrides are applied at injection time.
- Native script manager (Jetpack Compose): installed list, search/filter/sort, enable/disable, code editor, per-script overrides, `GM_storage`, install confirmation and ZIP backup/restore.
- `Install UserScript` now opens the native confirmation screen (target sites, permissions, version diff, code preview), with a direct-install fallback when the manager Activity cannot start.
- Browser page menus and Chrome's Developer options now open the native manager instead of the hosted front end.
- JVM unit tests for UserScript parsing, URL matching, R8 name ranking, menu policy and override rules, plus a Robolectric v9 -> v10 migration test.
- Node contract tests for the injected assets, including the IPC anchor and GM API coverage.

### Changed

- The APK now includes AndroidX Compose. Release size is budgeted at 8 MiB, and native libraries are filtered to arm64-v8a to match the artifact name.

### Changed

- Rewrote the README for this fork: accurate badges, requirements, build/test commands and supported API summary.
- Removed the stale development-plan list from the README; ongoing work is tracked in issues and this changelog.
- Replaced the push-triggered workflow with a manual `build.yml` that builds, verifies and signs the arm64-v8a APK.
- The build workflow now only packages and signs; the separate CI/validation stage was removed. Run `ktfmtCheck`, `lintDebug` and the tests locally.
- Release signing uses a locally generated PKCS#12 keystore stored in the `release` environment secrets (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`).
- Pinned GitHub Actions to commit SHAs, added a concurrency group, job timeouts and read-only default permissions.
- Release artifacts are named `ChromeXt-arm64-v8a.apk`; the APK contains no native libraries and remains architecture-independent.
- Dependabot now covers both Gradle and GitHub Actions with grouped weekly updates.

## [3.9.1] - 2026-11-05

### Fixed

- Recognize the new address of the UserScripts manager front end.
- Preserve the `MainHook` constructor in release builds.

## [3.9.0] - 2026-10-20

### Added

- Support for Helium browser.

### Fixed

- Compatibility with current Chromium browsers (menu and page-info hooks).
- Support for the MVC page menu design.
- Loading-state tracking for browsers where `mIsLoading` cannot be located.

[Unreleased]: https://github.com/nosgk/ChromeXt-nosgk/compare/v3.10.0...HEAD
[3.10.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.10.0
[3.9.1]: https://github.com/nosgk/ChromeXt-nosgk/compare/v3.9.0...v3.9.1
[3.9.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.9.0
