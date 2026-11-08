# Contributing to ChromeXt

## Before you start

- Read the architecture notes in the source comments under `hook/`, `proxy/` and `utils/`.
- Search existing issues before opening a new one.
- For behavior changes, describe the user-visible effect and the affected browsers.

## Development environment

- JDK 21
- Android SDK Platform 35 and Build Tools
- Node.js 20+ (for the JavaScript asset tests)
- Python 3.10+ (for documentation/browser-list checks)

The canonical build runs on GitHub Actions and is triggered manually. Local builds are optional but useful for fast iteration:

```bash
./gradlew format
./gradlew ktfmtCheck lintDebug testDebugUnitTest assembleDebug
node --test tools/js-tests/*.test.js
python3 tools/docs/check_docs.py
```

## Code style

- Kotlin is formatted with ktfmt 0.27 and the project's no-trailing-comma style.
- Run `./gradlew format` before committing.
- Keep comments that explain *why* (especially reflection anchors and R8 heuristics); do not add comments that restate the code.
- Prefer small, named functions over long `when` blocks.
- Do not introduce new dependencies without a clear size/compatibility justification.

## Commit messages

```
<type>(<scope>): <summary>

<why / what / risk / rollback>

Refs: <issue or problem id>
```

Types: `feat`, `fix`, `refactor`, `test`, `docs`, `ci`, `build`, `chore`.

Rules:

- Keep behavior-preserving refactors and behavior changes in separate commits.
- Mark behavior changes explicitly in the body (`Behavior: changed`) and update `CHANGELOG.md`.
- Do not commit internal design documents, audit reports or security analysis. Those are maintained locally.

## Pull requests

- One logical change per pull request.
- Include tests for pure logic (parsers, URL matching, repositories, policies).
- Include a device/emulator verification note for hook or UI changes: browser, version, Android version, and what you checked.
- Make sure the manual CI run is green before merging.

## Reporting bugs

Include:

1. Browser package name and version.
2. Android version and ROM.
3. Xposed/LSPosed/LSPatch version.
4. Steps to reproduce and the relevant log lines (`ChromeXt` tag or Xposed log).

## Security issues

Follow [SECURITY.md](SECURITY.md); do not use public issues.
