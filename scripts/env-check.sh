#!/usr/bin/env bash
# Sanity-check the local toolchain for the Android port and print a report.
set -uo pipefail

pass() { echo "  [ok]   $1"; }
fail() { echo "  [FAIL] $1"; }
info() { echo "  [--]   $1"; }

echo "== Rust =="
if command -v cargo >/dev/null; then
  pass "cargo $(cargo --version | cut -d' ' -f2)"
  for target in aarch64-linux-android; do
    if rustup target list --installed 2>/dev/null | grep -q "^$target$"; then
      pass "target $target"
    else
      fail "target $target (rustup target add $target)"
    fi
  done
  if command -v cargo-ndk >/dev/null; then
    pass "cargo-ndk"
  else
    fail "cargo-ndk (cargo install cargo-ndk)"
  fi
else
  fail "cargo (install rustup)"
fi

echo "== Android =="
if command -v adb >/dev/null; then pass "adb"; else info "adb (platform-tools) — optional, device testing"; fi

NDK_DIR="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK_DIR" && -n "${ANDROID_HOME:-}" ]]; then
  NDK_DIR=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
if [[ -n "$NDK_DIR" && -d "$NDK_DIR" ]]; then
  pass "NDK: $NDK_DIR"
else
  fail "Android NDK (sdkmanager 'ndk;28.*' or set ANDROID_NDK_HOME)"
fi

if command -v java >/dev/null; then
  JAVA_VER=$(java -version 2>&1 | head -1)
  if javac -version >/dev/null 2>&1; then
    pass "JDK: $JAVA_VER"
  else
    fail "JRE only ($JAVA_VER) — Gradle needs a full JDK 17+"
  fi
fi

echo "== Gradle =="
if [[ -x /dev/null ]] ; then :; fi
G="${GRADLE_BIN:-gradle}"
if command -v "$G" >/dev/null || [[ -x "$G" ]]; then
  pass "gradle on PATH (or set GRADLE_BIN)"
else
  info "gradle not on PATH — the scaffold's ./gradlew handles this once bootstrapped"
fi

echo
echo "Next: ./scripts/build-android-libs.sh && ./scripts/build-apk.sh"
