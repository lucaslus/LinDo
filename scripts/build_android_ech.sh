#!/usr/bin/env bash
set -euo pipefail

ARM64_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --arm64-only)
      ARM64_ONLY=true
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 1
      ;;
  esac
done

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

OS_NAME="$(uname -s)"
case "$OS_NAME" in
  Darwin)
    HOST_TAG="darwin-x86_64"
    DEFAULT_SDK_DIR="$HOME/Library/Android/sdk"
    ;;
  Linux)
    HOST_TAG="linux-x86_64"
    DEFAULT_SDK_DIR="$HOME/Android/Sdk"
    ;;
  *)
    echo "Unsupported host OS for Android NDK build: $OS_NAME" >&2
    exit 1
    ;;
esac

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$DEFAULT_SDK_DIR}}"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_NDK_LATEST_HOME:-}" ] && [ -d "${ANDROID_NDK_LATEST_HOME}" ]; then
    ANDROID_NDK_HOME="${ANDROID_NDK_LATEST_HOME}"
  elif [ -d "$SDK_DIR/ndk" ]; then
    ANDROID_NDK_HOME="$(ls -1d "$SDK_DIR/ndk"/* 2>/dev/null | sort -V | tail -n1 || true)"
  fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "ERROR: ANDROID_NDK_HOME could not be resolved (checked SDK_DIR=$SDK_DIR)." >&2
  exit 1
fi

export ANDROID_NDK_HOME
export ANDROID_NDK="$ANDROID_NDK_HOME"
export BORING_BSSL_RUST_CPPLIB="c++_shared"
export CMAKE_GENERATOR="Ninja"

TOOLCHAIN_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
SYSROOT_LIB="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib"

if [ ! -d "$TOOLCHAIN_BIN" ]; then
  echo "ERROR: NDK LLVM toolchain directory not found: $TOOLCHAIN_BIN" >&2
  exit 1
fi

if [ -d "$SDK_DIR/cmake" ]; then
  LATEST_CMAKE_BIN="$(ls -1d "$SDK_DIR/cmake"/*/bin 2>/dev/null | sort -V | tail -n1 || true)"
  if [ -n "$LATEST_CMAKE_BIN" ] && [ -d "$LATEST_CMAKE_BIN" ]; then
    export PATH="$LATEST_CMAKE_BIN:$PATH"
  fi
fi
export PATH="$TOOLCHAIN_BIN:$PATH"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/aarch64-linux-android24-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/x86_64-linux-android24-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"

echo "Using ANDROID_NDK_HOME=$ANDROID_NDK_HOME ($HOST_TAG)"

if command -v rustup >/dev/null 2>&1; then
  if [ "$ARM64_ONLY" = "true" ]; then
    rustup target add aarch64-linux-android
  else
    rustup target add aarch64-linux-android x86_64-linux-android
  fi
fi

cargo build --manifest-path "$ROOT_DIR/native-ech/Cargo.toml" --release --target aarch64-linux-android

ARM_OUT="$ROOT_DIR/composeApp/src/androidMain/jniLibs/arm64-v8a"
mkdir -p "$ARM_OUT"
cp "$ROOT_DIR/native-ech/target/aarch64-linux-android/release/liblindo_ech.so" "$ARM_OUT/liblindo_ech.so"
cp "$SYSROOT_LIB/aarch64-linux-android/libc++_shared.so" "$ARM_OUT/libc++_shared.so"
"$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded "$ARM_OUT/libc++_shared.so"

if [ "$ARM64_ONLY" != "true" ]; then
  cargo build --manifest-path "$ROOT_DIR/native-ech/Cargo.toml" --release --target x86_64-linux-android
  X86_OUT="$ROOT_DIR/composeApp/src/androidMain/jniLibs/x86_64"
  mkdir -p "$X86_OUT"
  cp "$ROOT_DIR/native-ech/target/x86_64-linux-android/release/liblindo_ech.so" "$X86_OUT/liblindo_ech.so"
  cp "$SYSROOT_LIB/x86_64-linux-android/libc++_shared.so" "$X86_OUT/libc++_shared.so"
  "$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded "$X86_OUT/libc++_shared.so"
fi

echo "=== Exported JNI symbols (arm64-v8a) ==="
"$TOOLCHAIN_BIN/llvm-nm" -D "$ARM_OUT/liblindo_ech.so" | grep "Java_do_1_linux_lindo_ech_NativeEchBridge"
echo "=== Output sizes ==="
if [ "$ARM64_ONLY" = "true" ]; then
  ls -lh "$ARM_OUT"
else
  ls -lh "$ARM_OUT" "$ROOT_DIR/composeApp/src/androidMain/jniLibs/x86_64"
fi
