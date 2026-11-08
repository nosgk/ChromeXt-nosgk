# Changelog

All notable changes to this fork are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the module's `versionName`.

## [3.14.2] - 2026-11-09

### Fixed

- Root shell resolution. `ProcessBuilder("su")` failed with `Cannot run program "su": error=2, No such file or directory` on devices whose root manager does not put `su` on the app's PATH (KernelSU: `/data/adb/ksu/bin/su`, APatch: `/data/adb/ap/bin/su`, Magisk: `/debug_ramdisk/su`). The storage scan, LSPosed scope reader and the root restart button now resolve an absolute path first, then PATH, then `sh -c 'command -v su'`.

## [3.14.1] - 2026-11-08

### Fixed

- Chrome crashing on every page after changing an engine switch and restarting: applying the engine config called `WebView.setWebContentsDebuggingEnabled` inside Chromium browser processes, which initializes the WebView provider there and takes Chrome down. The call is now limited to actual WebView hosts (`WebViewHook.isInit`) and posted to the main thread; the whole config application is also guarded.
- Scripts installed by the upstream ChromeXt disappearing after reinstalling this module: the one-time migration flag lives in the browser's preferences while the central database is wiped with the module, so migration was skipped forever. The flag key moved to `migrated_to_central_v13` and migration now also runs whenever the central database is empty; local-only scripts stay in the injected list until migration succeeds, and the central list is mirrored back to the browser database (preserving local GM storage) so both sides agree.
- Browser database scan timing out on Chrome: it walked the entire app data directory (Chrome's `app_chrome` tree has tens of thousands of entries). It now only probes each app's `databases/` directory, and a non-zero `su` exit code or timeout is reported instead of being shown as "not found".

## [3.14.0] - 2026-11-08

### Fixed

- LSPosed scope reading on Vector 2.x. Its database uses `scope(module_pkg_name, app_pkg_name, user_id)` and `modules(module_pkg_name, apk_path)` with no `mid`, so the old JOIN failed with "no such column" and the apps page reported "cannot query … config_backup". The reader now inspects `PRAGMA table_info` and supports both the Vector and the old LSPosed schemas, prefers the live config over `config_backup`, and keeps the backup path as a fallback.
- Browser local database scan: probe every user profile (`/data/user/<id>`, `/data/user_de/<id>`) with `find` instead of a fixed user-0 path, report "su denied" separately from "file not found", and offer a manual path import as a fallback.

### Added

- Engine switch "global script injection": turns userscript injection off entirely while keeping the ChromeXt menus.
- Engine switch "auto-mount Eruda": opens the Eruda console after a page loads (off by default).
- Engine switch "confirm before installing": skips the install confirmation screen when disabled.
- The WebView debugging switch is now honored by every call site (Eruda install and inspect actions), not only the initial hook.

### Changed

- All engine switches are stored in the central `config` table and re-read by browsers on the next sync; the settings card explains this.

## [3.13.0] - 2026-11-08

### Added

- Script storage card in settings: the central database path/size/count plus every installed browser's own `userscript` database path, size and modification time.
- Root-assisted import from browser-local databases (single or all), so scripts installed by the upstream ChromeXt become visible in this fork immediately instead of waiting for the browser to migrate them.

### Fixed

- The browser-side one-time migration marked itself as done even when the provider was unreachable, which permanently hid upstream-installed scripts; the flag is now only set after a successful import.

## [3.12.0] - 2026-11-08

### Added

- Full-screen code editor: the script editor now shows metadata plus a line/character summary and an "open code editor" button; the editor itself provides monospace input, find/replace, a live line/column indicator and a discard confirmation.
- The app selector in the script editor has a search field and only lists apps that are actually in the LSPosed scope (falling back to apps that reported in when the scope cannot be read).
- Installing a script from a hooked app scopes it to that app by default, both in the native install screen and in the direct-install fallback; re-installing adds the current app to a non-global scope and leaves a global script global.
- The LSPosed scope reader now discovers `modules_config.db` under `/data/adb`, copies the `-wal`/`-shm` sidecars, retries two query shapes, and reports `NO_ROOT` / `NO_CONFIG` / `DB_ERROR` / `EMPTY` instead of silently returning an empty scope.

### Changed

- Navigation: the apps page is now the root. The script list is a second-level screen opened by selecting an app (or the "global scripts" row), with a back arrow; the bottom bar is apps/settings/about and becomes a rail on wide screens.
- The "global scripts" row opens every installed script instead of only scripts without an app scope.
- `@run-at` labels use precise wording: "before document", "DOM parsed", "page loaded".

## [3.11.0] - 2026-11-08

### Added

- Settings rebuilt as a responsive workbench: `NavigationRail` on wide screens, a two-column staggered card grid, and every card fills its cell.
- Live telemetry card: provider reachability with a ping round-trip, schema version, database path/size, script payload counts, GM storage size and hooked-app count.
- Installed-host card driven by `PackageManager` (label, package, version, icon), with a per-app root restart (`su -c am force-stop … ; monkey …`) that falls back to the system app-info page.
- Engine card backed by the new central `config` table: persistent GM storage, CSP rule injection, forced WebView debugging, Eruda menu injection and the privileged `GM.ChromeXt` dispatch.
- Data card: side-by-side export/import, SQLite `VACUUM` with reclaimed bytes, orphan GM storage cleanup, and a danger zone with an explicit script count.
- Apps screen redesigned as a per-app workbench: app icons, package names, total/enabled script counts, a global-scripts entry, search, and an "all apps" filter.
- The apps screen reads the framework scope through `su` when available, so scoped-but-not-yet-hooked apps show up as "scoped, not hooked yet".
- Pull-to-refresh on the scripts and apps screens.

### Changed

- Activation detection is now reliable: every hooked process reports through the system context at `onPackageReady`, and the provider records any authorized caller, so a browser using the central database can no longer show as "not hooked".
- Schema v12 adds the `config` table; `hooked_app` updates preserve a known label/version when a plain provider call only refreshes `last_seen`.
- Creating a script from an app filter pre-scopes it to that app.
- `GM.ChromeXt` is only exposed when the central engine switch allows it (and the script still has to declare the grant).

### Fixed

- An app that was clearly hooked could still be listed as "installed, not hooked yet" because the report was lost when the provider was not running.
- Settings cards no longer collapse to content width or stretch buttons across the whole tablet screen.

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

[Unreleased]: https://github.com/nosgk/ChromeXt-nosgk/compare/v3.14.2...HEAD
[3.14.2]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.14.2
[3.14.1]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.14.1
[3.14.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.14.0
[3.13.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.13.0
[3.12.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.12.0
[3.11.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.11.0
[3.10.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.10.0
[3.9.1]: https://github.com/nosgk/ChromeXt-nosgk/compare/v3.9.0...v3.9.1
[3.9.0]: https://github.com/nosgk/ChromeXt-nosgk/releases/tag/v3.9.0
