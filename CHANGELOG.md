# Changelog

All notable changes to this fork are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the module's `versionName`.

## [Unreleased]

### Added

- Contributor, testing and security guides (`CONTRIBUTING.md`, `TESTING.md`, `SECURITY.md`).
- Documentation consistency checks (`tools/docs/check_docs.py`).
- Release verification (`scripts/verify-release.sh`) and APK signing (`scripts/sign-apk.sh`) scripts.
- Issue templates, pull-request checklist and `CODEOWNERS`.

### Changed

- Rewrote the README for this fork: accurate badges, requirements, build/test commands and supported API summary.
- Removed the stale development-plan list from the README; ongoing work is tracked in issues and this changelog.
- Replaced the push-triggered workflow with a single manual `ci.yml`: `validate` must pass before `package` builds the arm64-v8a APK; signing is an optional, separately approved job.
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
