#!/usr/bin/env bash
# Install the debug APK on a connected device/emulator and follow the Rust log.
#
# Usage: ./scripts/run-on-device.sh [extra logcat filters]
set -euo pipefail

SCAFFOLD_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$SCAFFOLD_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

adb wait-for-device
adb install -r "$APK"
adb logcat -c
# Rust logs land under the "horizon" tag (android_logger, see lib.rs).
adb logcat -s horizon RustStdoutStderr DEBUG "$@"
