#!/usr/bin/env bash
# Verify the release APK before it is published.
#
# Usage: bash scripts/verify-release.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
OUT="$ROOT/app/build/outputs/apk/release/ChromeXt-arm64-v8a-unsigned.apk"
MAX_BYTES=$((8 * 1024 * 1024))

if [[ ! -f "$APK" ]]; then
  echo "error: release APK not found at $APK" >&2
  exit 1
fi

find_apkanalyzer() {
  if command -v apkanalyzer >/dev/null 2>&1; then
    command -v apkanalyzer
    return 0
  fi
  local sdk
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [[ -n "$sdk" && -x "$sdk/cmdline-tools/latest/bin/apkanalyzer" ]]; then
      echo "$sdk/cmdline-tools/latest/bin/apkanalyzer"
      return 0
    fi
  done
  return 1
}

APKANALYZER="$(find_apkanalyzer || true)"
if [[ -z "$APKANALYZER" ]]; then
  echo "error: apkanalyzer not found; cannot verify the Xposed entry point" >&2
  exit 1
fi

SIZE="$(stat -c%s "$APK")"
echo "APK size: $SIZE bytes"
if (( SIZE > MAX_BYTES )); then
  echo "error: APK exceeds the 8 MiB budget ($SIZE > $MAX_BYTES)" >&2
  exit 1
fi

echo "Checking libxposed Modern API metadata..."
JAVA_INIT="$(unzip -p "$APK" META-INF/xposed/java_init.list 2>/dev/null || true)"
if ! printf '%s\n' "$JAVA_INIT" | grep -qx 'org.matrix.chromext.MainHook'; then
  echo "error: META-INF/xposed/java_init.list does not list org.matrix.chromext.MainHook" >&2
  exit 1
fi

MODULE_PROP="$(unzip -p "$APK" META-INF/xposed/module.prop 2>/dev/null || true)"
if ! printf '%s\n' "$MODULE_PROP" | grep -qx 'targetApiVersion=102'; then
  echo "error: module.prop does not target libxposed API 102" >&2
  exit 1
fi
if ! printf '%s\n' "$MODULE_PROP" | grep -qx 'minApiVersion=100'; then
  echo "error: module.prop does not declare minApiVersion=100" >&2
  exit 1
fi

if ! unzip -p "$APK" META-INF/xposed/scope.list | grep -qx 'com.android.chrome'; then
  echo "error: scope.list is missing the default browser scope" >&2
  exit 1
fi

echo "Checking MainHook.<init>()..."
"$APKANALYZER" dex code \
  --class org.matrix.chromext.MainHook \
  --method '<init>()V' \
  "$APK" >/dev/null

echo "Checking application id and version..."
APP_ID="$("$APKANALYZER" manifest application-id "$APK")"
VERSION_NAME="$("$APKANALYZER" manifest version-name "$APK")"
if [[ "$APP_ID" != "org.matrix.chromext" ]]; then
  echo "error: unexpected application id: $APP_ID" >&2
  exit 1
fi
echo "Application id: $APP_ID"
echo "Version name: $VERSION_NAME"

if unzip -l "$APK" | grep -q ' lib/'; then
  OTHER_ABIS="$(unzip -l "$APK" | grep -o 'lib/[^/]*/' | sort -u | grep -v '^lib/arm64-v8a/$' || true)"
  if [[ -n "$OTHER_ABIS" ]]; then
    echo "error: APK contains native libraries for other ABIs:" >&2
    echo "$OTHER_ABIS" >&2
    exit 1
  fi
  echo "Native libraries: arm64-v8a only."
else
  echo "No native libraries; the APK is architecture-independent."
fi

cp "$APK" "$OUT"
echo "Wrote $OUT"
