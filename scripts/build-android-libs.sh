#!/usr/bin/env bash
# Build the Rust game library (libhorizon_android.so) for all Android ABIs
# into android/app/src/main/jniLibs/ using cargo-ndk.
#
# Usage:
#   ./scripts/build-android-libs.sh [--profile dev|release|fast] [--abis a,b,..]
#
# Requirements: rustup + the four Android targets, Android NDK (ANDROID_HOME
# or ANDROID_NDK_HOME), cargo-ndk (`cargo install cargo-ndk`).
set -euo pipefail

SCAFFOLD_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE_DIR="$SCAFFOLD_ROOT/crates/horizon-android"
JNILIBS_DIR="$SCAFFOLD_ROOT/android/app/src/main/jniLibs"

PROFILE="dev"
ABIS="arm64-v8a,armeabi-v7a,x86,x86_64"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --abis) ABIS="$2"; shift 2 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

# --- environment checks -----------------------------------------------------
command -v cargo >/dev/null || { echo "cargo not found — install rustup" >&2; exit 1; }
command -v cargo-ndk >/dev/null || { echo "cargo-ndk not found — cargo install cargo-ndk" >&2; exit 1; }

NDK_DIR="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK_DIR" && -n "${ANDROID_HOME:-}" ]]; then
  # Use the newest NDK installed under $ANDROID_HOME.
  NDK_DIR=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
  echo "Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
  exit 1
fi

# ring (via ureq/rustls in the client tree) and any other cc-based build
# script need the NDK's clang wrappers on PATH; cargo-ndk only configures
# the linkers. Pick the prebuilt directory matching the host.
TOOLCHAIN_BIN=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  if [[ -d "$NDK_DIR/toolchains/llvm/prebuilt/$host/bin" ]]; then
    TOOLCHAIN_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$host/bin"
    break
  fi
done
if [[ -n "$TOOLCHAIN_BIN" ]]; then
  export PATH="$TOOLCHAIN_BIN:$PATH"
else
  echo "warning: no NDK prebuilt toolchain bin found under $NDK_DIR" >&2
fi

# cc-rs (ring and friends, via ureq/rustls in the client tree) checks these
# before probing PATH. NDK r30 ships only API-suffixed wrappers (no bare
# <triple>-clang); armv7a keeps its v7a in the NDK's naming. minSdk is 35.
export CC_aarch64_linux_android="$TOOLCHAIN_BIN/aarch64-linux-android35-clang"
export AR_aarch64_linux_android="$TOOLCHAIN_BIN/llvm-ar"
export RANLIB_aarch64_linux_android="$TOOLCHAIN_BIN/llvm-ranlib"
export CC_armv7_linux_androideabi="$TOOLCHAIN_BIN/armv7a-linux-androideabi35-clang"
export AR_armv7_linux_androideabi="$TOOLCHAIN_BIN/llvm-ar"
export RANLIB_armv7_linux_androideabi="$TOOLCHAIN_BIN/llvm-ranlib"
export CC_x86_64_linux_android="$TOOLCHAIN_BIN/x86_64-linux-android35-clang"
export AR_x86_64_linux_android="$TOOLCHAIN_BIN/llvm-ar"
export RANLIB_x86_64_linux_android="$TOOLCHAIN_BIN/llvm-ranlib"
export CC_i686_linux_android="$TOOLCHAIN_BIN/i686-linux-android35-clang"
export AR_i686_linux_android="$TOOLCHAIN_BIN/llvm-ar"
export RANLIB_i686_linux_android="$TOOLCHAIN_BIN/llvm-ranlib"

for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
  rustup target list --installed | grep -q "^$target$" || rustup target add "$target"
done

# Map cargo profile to cargo-ndk profile flag.
case "$PROFILE" in
  dev) CARGO_PROFILE_FLAG="" ;;           # debug build (default)
  release) CARGO_PROFILE_FLAG="--release" ;;
  fast) CARGO_PROFILE_FLAG="--profile fast" ;;  # Horizon workspace's fast profile
  *) echo "unknown profile: $PROFILE (dev|release|fast)" >&2; exit 2 ;;
esac

echo ">> Building horizon-android for: $ABIS (profile: $PROFILE)"
echo ">> NDK: $NDK_DIR"

# cargo-ndk selects the right linker per target and copies libc++_shared if
# needed (we link none — pure Rust + wgpu, so the .so is self-contained).
cargo ndk \
  --manifest-path "$CRATE_DIR/Cargo.toml" \
  $(for abi in ${ABIS//,/ }; do echo -n "-t $abi "; done) \
  -o "$JNILIBS_DIR" \
  build $CARGO_PROFILE_FLAG

echo ">> Done. Libraries:"
find "$JNILIBS_DIR" -name "libhorizon_android.so" -exec ls -la {} \;
