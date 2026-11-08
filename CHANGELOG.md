# Changelog

All notable changes to this fork are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the module's `versionName`.

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

[Unreleased]: https://github.com/nosgk/ChromeXt-nosgk/compare/v3.9.1...HEAD
[3.9.1]: https://github.com/nosgk/ChromeXt-nosgk/compare/v3.9.0...v3.9.1
[3.9.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.9.0
