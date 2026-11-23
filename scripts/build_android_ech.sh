#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/28.2.13676358}"
export ANDROID_NDK="$ANDROID_NDK_HOME"
export BORING_BSSL_RUST_CPPLIB="c++_shared"
export CMAKE_GENERATOR="Ninja"

HOST_TAG="darwin-x86_64"
TOOLCHAIN_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
SYSROOT_LIB="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib"
CMAKE_BIN="$SDK_DIR/cmake/3.22.1/bin"
export PATH="$CMAKE_BIN:$TOOLCHAIN_BIN:$PATH"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/aarch64-linux-android24-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/x86_64-linux-android24-clang"

rustup target add aarch64-linux-android x86_64-linux-android

cargo build --manifest-path "$ROOT_DIR/native-ech/Cargo.toml" --release --target aarch64-linux-android
cargo build --manifest-path "$ROOT_DIR/native-ech/Cargo.toml" --release --target x86_64-linux-android

ARM_OUT="$ROOT_DIR/composeApp/src/androidMain/jniLibs/arm64-v8a"
X86_OUT="$ROOT_DIR/composeApp/src/androidMain/jniLibs/x86_64"
mkdir -p "$ARM_OUT" "$X86_OUT"

cp "$ROOT_DIR/native-ech/target/aarch64-linux-android/release/liblindo_ech.so" "$ARM_OUT/liblindo_ech.so"
cp "$SYSROOT_LIB/aarch64-linux-android/libc++_shared.so" "$ARM_OUT/libc++_shared.so"

cp "$ROOT_DIR/native-ech/target/x86_64-linux-android/release/liblindo_ech.so" "$X86_OUT/liblindo_ech.so"
cp "$SYSROOT_LIB/x86_64-linux-android/libc++_shared.so" "$X86_OUT/libc++_shared.so"

"$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded "$ARM_OUT/libc++_shared.so" "$X86_OUT/libc++_shared.so"

echo "=== Exported JNI symbols (arm64-v8a) ==="
"$TOOLCHAIN_BIN/llvm-nm" -D "$ARM_OUT/liblindo_ech.so" | grep "Java_do_1_linux_lindo_ech_NativeEchBridge"
echo "=== Output sizes ==="
ls -lh "$ARM_OUT" "$X86_OUT"
