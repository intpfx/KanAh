#!/usr/bin/env bash
# Reproducible JNI build; --bootstrap explicitly installs tools in a task-only cache.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${LIVE_ATV_TOOLCHAIN_CACHE:-$HOME/.cache/live-atv-toolchain}"
RUST_VERSION=1.90.0
NDK_VERSION=28.2.13676358
export RUSTUP_HOME="$CACHE_DIR/rustup"
export CARGO_HOME="$CACHE_DIR/cargo"
export PATH="$CARGO_HOME/bin:$PATH"
export CARGO_TARGET_DIR="$ROOT_DIR/native/atv/target"
NDK_DIR="${ANDROID_NDK_HOME:-$CACHE_DIR/android-ndk-r28c}"
if [[ "${1:-}" == "--bootstrap" ]]; then
    mkdir -p "$CACHE_DIR"
    if [[ ! -x "$CARGO_HOME/bin/rustup" ]]; then
        case "$(uname -s):$(uname -m)" in
            Darwin:arm64) RUST_HOST=aarch64-apple-darwin ;;
            Darwin:x86_64) RUST_HOST=x86_64-apple-darwin ;;
            Linux:x86_64) RUST_HOST=x86_64-unknown-linux-gnu ;;
            Linux:aarch64) RUST_HOST=aarch64-unknown-linux-gnu ;;
            *) echo "Unsupported bootstrap host; install Rust $RUST_VERSION manually." >&2; exit 1 ;;
        esac
        URL="https://static.rust-lang.org/rustup/archive/1.28.2/$RUST_HOST/rustup-init"
        curl --fail --location "$URL" -o "$CACHE_DIR/rustup-init"
        curl --fail --location "$URL.sha256" -o "$CACHE_DIR/rustup-init.sha256"
        (cd "$CACHE_DIR"; shasum -a 256 -c rustup-init.sha256)
        chmod +x "$CACHE_DIR/rustup-init"
        "$CACHE_DIR/rustup-init" -y --no-modify-path --profile minimal --default-toolchain "$RUST_VERSION"
    fi
    rustup toolchain install "$RUST_VERSION" --profile minimal
    rustup target add --toolchain "$RUST_VERSION" aarch64-linux-android armv7-linux-androideabi
    if [[ ! -f "$NDK_DIR/source.properties" ]]; then
        if [[ "$(uname -s)" != Darwin ]]; then
            echo "Install Android NDK $NDK_VERSION and set ANDROID_NDK_HOME, then rerun." >&2; exit 1
        fi
        IMAGE="$CACHE_DIR/android-ndk-r28c-darwin.dmg"
        [[ -f "$IMAGE" ]] || curl --fail --location https://dl.google.com/android/repository/android-ndk-r28c-darwin.dmg -o "$IMAGE"
        echo "a7a2513d4c229f814e1a3bc528fe04ed0807b63f57a6d21f09d75e8362e44a20  $IMAGE" | shasum -a 256 -c -
        MOUNT_DIR="$CACHE_DIR/ndk-image"
        mkdir -p "$MOUNT_DIR"
        hdiutil attach -readonly -nobrowse -mountpoint "$MOUNT_DIR" "$IMAGE"
        trap 'hdiutil detach "$MOUNT_DIR" >/dev/null || true' EXIT
        cp -R "$MOUNT_DIR/AndroidNDK13676358.app/Contents/NDK" "$NDK_DIR"
        hdiutil detach "$MOUNT_DIR"
        trap - EXIT
    fi
    echo "Native tools are ready in $CACHE_DIR. Run scripts/build-atv-native.sh to build."
    exit 0
fi
if [[ ! -x "$CARGO_HOME/bin/cargo" || ! -f "$NDK_DIR/source.properties" ]]; then
    echo "Native tools missing. Explicitly run: scripts/build-atv-native.sh --bootstrap" >&2
    exit 1
fi
if ! grep -Fq "Pkg.Revision = $NDK_VERSION" "$NDK_DIR/source.properties"; then
    echo "Android NDK $NDK_VERSION is required (ANDROID_NDK_HOME=$NDK_DIR)." >&2; exit 1
fi
case "$(uname -s)" in Darwin) NDK_HOST=darwin-x86_64 ;; Linux) NDK_HOST=linux-x86_64 ;; *) echo "Unsupported host" >&2; exit 1 ;; esac
NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$NDK_HOST/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android21-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$NDK_BIN/armv7a-linux-androideabi21-clang"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
for TARGET in aarch64-linux-android armv7-linux-androideabi; do
    case "$TARGET" in aarch64-linux-android) ABI=arm64-v8a ;; *) ABI=armeabi-v7a ;; esac
    cargo +"$RUST_VERSION" build --locked --manifest-path "$ROOT_DIR/native/atv/Cargo.toml" --release -p atv-android --target "$TARGET"
    mkdir -p "$ROOT_DIR/app/src/main/jniLibs/$ABI"
    cp "$CARGO_TARGET_DIR/$TARGET/release/libatv_android.so" "$ROOT_DIR/app/src/main/jniLibs/$ABI/libatv_android.so"
    "$NDK_BIN/llvm-strip" --strip-unneeded "$ROOT_DIR/app/src/main/jniLibs/$ABI/libatv_android.so"
done
