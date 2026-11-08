# Changelog

All notable changes to this fork are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the module's `versionName`.

## [Unreleased]

### Added

- Contributor, testing and security guides (`CONTRIBUTING.md`, `TESTING.md`, `SECURITY.md`).
- Documentation consistency checks (`tools/docs/check_docs.py`).

### Changed

- Rewrote the README for this fork: accurate badges, requirements, build/test commands and supported API summary.
- Removed the stale development-plan list from the README; ongoing work is tracked in issues and this changelog.

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
