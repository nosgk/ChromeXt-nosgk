## Summary

<!-- What changed and why. Link the issue. -->

## Behavior

- [ ] Behavior-preserving refactor
- [ ] Bug fix (user-visible change; describe it)
- [ ] New feature (describe it)
- [ ] Documentation / CI only

## Verification

- [ ] `./gradlew ktfmtCheck lintDebug testDebugUnitTest assembleDebug`
- [ ] `node --test tools/js-tests` (if assets changed)
- [ ] `python3 tools/docs/check_docs.py`
- [ ] Manual CI run is green
- [ ] Device/emulator check (browser, version, Android version)

## Checklist

- [ ] Tests added or updated for pure logic
- [ ] `CHANGELOG.md` updated for user-visible changes
- [ ] No internal design documents, audit reports or security analysis included
- [ ] No new dependency without a size/compatibility note
