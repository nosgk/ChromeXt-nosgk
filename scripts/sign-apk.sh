#!/usr/bin/env bash
# Sign the release APK with the keystore supplied through environment variables.
#
# Usage: bash scripts/sign-apk.sh <unsigned.apk> <output.apk>
# Required environment: KEYSTORE_BASE64, KEYSTORE_ALIAS, KEYSTORE_PASSWORD, KEY_PASSWORD
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <unsigned.apk> <output.apk>" >&2
  exit 2
fi

INPUT="$1"
OUTPUT="$2"

for name in KEYSTORE_BASE64 KEYSTORE_ALIAS KEYSTORE_PASSWORD KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "error: $name is not set" >&2
    exit 1
  fi
done

if [[ ! -f "$INPUT" ]]; then
  echo "error: input APK not found: $INPUT" >&2
  exit 1
fi

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

KEYSTORE="$TMPDIR/keystore.jks"
printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$KEYSTORE"

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return 0
  fi
  local sdk
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [[ -n "$sdk" && -d "$sdk/build-tools" ]]; then
      local candidate
      candidate="$(find "$sdk/build-tools" -maxdepth 2 -name apksigner -type f | sort -V | tail -n 1)"
      if [[ -n "$candidate" ]]; then
        echo "$candidate"
        return 0
      fi
    fi
  done
  return 1
}

APKSIGNER="$(find_apksigner || true)"
if [[ -z "$APKSIGNER" ]]; then
  echo "error: apksigner not found" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEYSTORE_ALIAS" \
  --ks-pass "env:KEYSTORE_PASSWORD" \
  --key-pass "env:KEY_PASSWORD" \
  --out "$OUTPUT" \
  "$INPUT"

"$APKSIGNER" verify --print-certs "$OUTPUT"

(
  cd "$(dirname "$OUTPUT")"
  sha256sum "$(basename "$OUTPUT")" > SHA256SUMS
)

echo "Signed APK written to $OUTPUT"
