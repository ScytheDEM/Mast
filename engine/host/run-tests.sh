#!/usr/bin/env bash
# Configures, builds and runs the engine's host (macOS) GoogleTest suite. Idempotent; works from any cwd.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
CMAKE_BIN_DIR="$SDK/cmake/4.1.2/bin"
BUILD="$HERE/build"

for tool in cmake ctest ninja; do
    if [[ ! -x "$CMAKE_BIN_DIR/$tool" ]]; then
        echo "error: $CMAKE_BIN_DIR/$tool not found; install with: sdkmanager \"cmake;4.1.2\"" >&2
        exit 1
    fi
done

"$CMAKE_BIN_DIR/cmake" -S "$HERE" -B "$BUILD" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN_DIR/ninja" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=/usr/bin/clang \
    -DCMAKE_CXX_COMPILER=/usr/bin/clang++
"$CMAKE_BIN_DIR/cmake" --build "$BUILD"
"$CMAKE_BIN_DIR/ctest" --test-dir "$BUILD" --output-on-failure
