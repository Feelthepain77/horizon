#!/usr/bin/env bash
# Full pipeline: Rust libraries -> APK/AAB.
#
# Usage:
#   ./scripts/build-apk.sh [--variant debug|release] [--bundle] [--skip-rust]
#
# --bundle    build an .aab instead of an .apk (assembleRelease/bundleRelease)
# --skip-rust package the jniLibs that are already in place
set -euo pipefail

SCAFFOLD_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$SCAFFOLD_ROOT/android"

VARIANT="debug"
BUNDLE=false
SKIP_RUST=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --variant) VARIANT="$2"; shift 2 ;;
    --bundle) BUNDLE=true; shift ;;
    --skip-rust) SKIP_RUST=true; shift ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

if [[ "$SKIP_RUST" == false ]]; then
  PROFILE=dev
  [[ "$VARIANT" == "release" ]] && PROFILE=release
  "$SCAFFOLD_ROOT/scripts/build-android-libs.sh" --profile "$PROFILE"
fi

cd "$ANDROID_DIR"
# Bootstrap the wrapper if this is a fresh checkout without gradlew.
if [[ ! -x ./gradlew ]]; then
  echo ">> No gradlew found — run 'gradle wrapper --gradle-version 9.8.2' once"
  exit 1
fi

TASK="assemble$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"
[[ "$BUNDLE" == true ]] && TASK="bundleRelease"

./gradlew "$TASK"
echo ">> Built: $TASK"
