# Testing ChromeXt

## Layers

| Layer | Location | Runs on |
|---|---|---|
| JVM unit tests | `app/src/test/kotlin` | JVM, no Android device |
| Robolectric tests | `app/src/test/kotlin` | JVM with Android framework shadows |
| JavaScript asset tests | `tools/js-tests` | Node.js `node:test` |
| Documentation checks | `tools/docs/check_docs.py` | Python 3 |
| Device smoke tests | manual checklist below | Real device / emulator |

## Commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew ktfmtCheck
node --test tools/js-tests/*.test.js
python3 tools/docs/check_docs.py
```

Coverage reports are written to `app/build/reports/jacoco/`.

## What to test

Pure logic must have unit tests:

- UserScript header parsing and metadata extraction (`script/Parser.kt`)
- URL matching and origin parsing (`utils/Url.kt`)
- R8 obfuscated-name ranking (`utils/Reflect.kt`)
- Script id generation and storage handling
- Menu entry policy (front end / `.user.js` / regular page)
- Database schema migrations (Robolectric)
- JSON bridge encoding for IPC payloads

Hook code that depends on browser internals cannot be unit-tested meaningfully; cover it with the device checklist and small, focused regression notes.

## JavaScript tests

`tools/js-tests` uses Node's built-in test runner. Do not add npm dependencies; provide minimal DOM/window stubs inside the test file.

## Device smoke checklist

Test on at least one Chromium browser and one WebView browser:

- [ ] Install a `.user.js` from a URL.
- [ ] Script runs on a matching page and `GM_setValue` persists across reload.
- [ ] Disable the script; it no longer runs.
- [ ] Page menu shows the expected entries.
- [ ] Eruda opens and closes.
- [ ] Developer options page opens; toggles persist.
- [ ] Upgrade/install over an existing script preserves storage when configured.
- [ ] Reset data removes scripts and settings.

For the native manager (once available):

- [ ] Create, edit, enable, disable, delete, sort.
- [ ] Import/export a ZIP round-trip.
- [ ] Install confirmation shows permissions and target domains.
- [ ] Provider fallback works when the module process is stopped.

## Adding fixtures

Put UserScript samples under `app/src/test/resources/userscripts/`:

- `valid/` normal scripts
- `invalid/` malformed headers
- `edge/` unicode, very large scripts, multiple metadata blocks
- `malicious/` scripts that attempt `GM.ChromeXt` abuse or path traversal

Each fixture should have a one-line comment describing the expected parse result.
