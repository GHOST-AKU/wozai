#!/usr/bin/env bash
# Cross-compile the existing headless core; this does not build or sign an APK.
set -euo pipefail

mesh_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ndk_root="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk_root" ]]; then
    printf 'Set ANDROID_NDK_HOME to an installed Android NDK (verified with r27c).\n' >&2
    exit 1
fi
if [[ "$ndk_root" != /* ]]; then
    ndk_root="$PWD/$ndk_root"
fi
# Resolve caller-relative toolchain homes before changing the working directory.
if [[ -n "${CARGO_HOME:-}" && "$CARGO_HOME" != /* ]]; then
    export CARGO_HOME="$PWD/$CARGO_HOME"
fi
if [[ -n "${RUSTUP_HOME:-}" && "$RUSTUP_HOME" != /* ]]; then
    export RUSTUP_HOME="$PWD/$RUSTUP_HOME"
fi

case "$(uname -s)" in
    Linux) host_tag=linux-x86_64 ;;
    Darwin) host_tag=darwin-x86_64 ;;
    *) printf 'Unsupported NDK build host: %s\n' "$(uname -s)" >&2; exit 1 ;;
esac

llvm_bin="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin"
linker="$llvm_bin/aarch64-linux-android29-clang"
if [[ ! -x "$linker" || ! -x "$llvm_bin/llvm-ar" ]]; then
    printf 'Missing Android API 29 arm64 compiler or archiver in %s\n' "$llvm_bin" >&2
    exit 1
fi

cargo_bin=cargo
if [[ -n "${CARGO_HOME:-}" && -x "$CARGO_HOME/bin/cargo" ]]; then
    cargo_bin="$CARGO_HOME/bin/cargo"
fi
if ! command -v "$cargo_bin" >/dev/null 2>&1; then
    printf 'Cargo is required. Install Rust 1.88+ and rustup target add aarch64-linux-android.\n' >&2
    exit 1
fi

cd -- "$mesh_root"
# Build caller-supplied relative target directories from mesh/ as well.
target_dir="${CARGO_TARGET_DIR:-$mesh_root/target}"
CARGO_TARGET_DIR="$target_dir" \
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker" \
CC_aarch64_linux_android="$linker" \
AR_aarch64_linux_android="$llvm_bin/llvm-ar" \
    "$cargo_bin" rustc --manifest-path "$mesh_root/Cargo.toml" --locked \
    --release --bin mesh-lab --target aarch64-linux-android \
    -- -C link-arg=-Wl,-z,max-page-size=16384

printf 'Android arm64 core: %s/aarch64-linux-android/release/mesh-lab\n' "$target_dir"
