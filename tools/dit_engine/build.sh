#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SRC_DIR="${SCRIPT_DIR}/src"
SDCPP_DIR="${SRC_DIR}/stable-diffusion.cpp"
BUILD_DIR="${SCRIPT_DIR}/build"

# 1. Parse local.properties
LOCAL_PROPS="${PROJECT_ROOT}/local.properties"
SDK_DIR=""
NDK_DIR=""
HEXAGON_SDK_ROOT=""

if [[ -f "$LOCAL_PROPS" ]]; then
    while IFS='=' read -r key val || [[ -n "$key" ]]; do
        key="$(echo "$key" | tr -d ' \r\t')"
        val="$(echo "$val" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        case "$key" in
            sdk.dir) SDK_DIR="${val//\\/}" ;;
            ndk.dir) NDK_DIR="${val//\\/}" ;;
            hexagon.sdk.dir) HEXAGON_SDK_ROOT="${val//\\/}" ;;
        esac
    done < "$LOCAL_PROPS"
fi

SDK_DIR="${ANDROID_SDK_ROOT:-${SDK_DIR:-/home/j/Android/Sdk}}"
HEXAGON_SDK_ROOT="${HEXAGON_SDK_ROOT:-/local/mnt/workspace/Qualcomm/Hexagon_SDK/6.7.0.0}"

if [[ -z "$NDK_DIR" && -d "${SDK_DIR}/ndk" ]]; then
    NDK_DIR="$(find "${SDK_DIR}/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)"
fi

if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
    echo "ERROR: Android NDK not found. Specify ndk.dir in local.properties or set ANDROID_NDK_ROOT." >&2
    exit 1
fi

if [[ ! -d "$HEXAGON_SDK_ROOT" ]]; then
    echo "ERROR: Hexagon SDK not found at ${HEXAGON_SDK_ROOT}." >&2
    exit 1
fi

echo "=========================================================="
echo " Building FancyAi DiT Engine + Qualcomm Hexagon DSP Skels"
echo "=========================================================="
echo " Project Root    : ${PROJECT_ROOT}"
echo " Android NDK     : ${NDK_DIR}"
echo " Hexagon SDK     : ${HEXAGON_SDK_ROOT}"
echo " Output JNI Libs : ${PROJECT_ROOT}/image/src/main/jniLibs/arm64-v8a"
echo " Output DSP Skels: ${PROJECT_ROOT}/image/src/main/assets/ditlibs"
echo "=========================================================="

# 2. Setup Source Repository and Submodules
mkdir -p "$SRC_DIR"
if [[ ! -d "${SDCPP_DIR}/.git" ]]; then
    echo "Cloning happyyzy/stable-diffusion.cpp (work/qualcomm-hexagon-optimizations)..."
    git clone --branch work/qualcomm-hexagon-optimizations https://github.com/happyyzy/stable-diffusion.cpp.git "$SDCPP_DIR"
fi

cd "$SDCPP_DIR"
git checkout work/qualcomm-hexagon-optimizations
git submodule update --init --recursive ggml

# 3. Apply Custom Patches
echo "Applying Qualcomm optimization and cache patches..."
if [[ -f "${SCRIPT_DIR}/patches/01_dsp_l2_cache.patch" ]]; then
    (cd ggml && git apply --check "${SCRIPT_DIR}/patches/01_dsp_l2_cache.patch" 2>/dev/null && git apply "${SCRIPT_DIR}/patches/01_dsp_l2_cache.patch" && echo "Applied 01_dsp_l2_cache.patch") || echo "Skipped 01_dsp_l2_cache.patch (already applied or mismatch)"
fi

if [[ -f "${SCRIPT_DIR}/patches/02_vae_downsample.patch" ]]; then
    (git apply --check "${SCRIPT_DIR}/patches/02_vae_downsample.patch" 2>/dev/null && git apply "${SCRIPT_DIR}/patches/02_vae_downsample.patch" && echo "Applied 02_vae_downsample.patch") || echo "Skipped 02_vae_downsample.patch (already applied or mismatch)"
fi

if [[ -f "${SCRIPT_DIR}/patches/03_hmx_quant_flatten.patch" ]]; then
    (git apply --check "${SCRIPT_DIR}/patches/03_hmx_quant_flatten.patch" 2>/dev/null && git apply "${SCRIPT_DIR}/patches/03_hmx_quant_flatten.patch" && echo "Applied 03_hmx_quant_flatten.patch") || echo "Skipped 03_hmx_quant_flatten.patch (already applied or mismatch)"
fi

if [[ -f "${SCRIPT_DIR}/patches/04_1d_tensor_shape_equivalence.patch" ]]; then
    (git apply --check "${SCRIPT_DIR}/patches/04_1d_tensor_shape_equivalence.patch" 2>/dev/null && git apply "${SCRIPT_DIR}/patches/04_1d_tensor_shape_equivalence.patch" && echo "Applied 04_1d_tensor_shape_equivalence.patch") || echo "Skipped 04_1d_tensor_shape_equivalence.patch (already applied or mismatch)"
fi

if [[ -f "${SCRIPT_DIR}/patches/05_v85_support.patch" ]]; then
    (cd ggml && git apply --check "${SCRIPT_DIR}/patches/05_v85_support.patch" 2>/dev/null && git apply "${SCRIPT_DIR}/patches/05_v85_support.patch" && echo "Applied 05_v85_support.patch") || echo "Skipped 05_v85_support.patch (already applied or mismatch)"
fi

# 4. Resolve CMake & Ninja from Android SDK
CMAKE_BIN="cmake"
NINJA_BIN="ninja"
if [[ -d "${SDK_DIR}/cmake" ]]; then
    CMAKE_CANDIDATE="$(find "${SDK_DIR}/cmake" -path "*/bin/cmake" -type f | sort -V | tail -n 1)"
    if [[ -n "$CMAKE_CANDIDATE" && -x "$CMAKE_CANDIDATE" ]]; then
        CMAKE_BIN="$CMAKE_CANDIDATE"
        NINJA_BIN="$(dirname "$CMAKE_BIN")/ninja"
        export PATH="$(dirname "$CMAKE_BIN"):$PATH"
    fi
fi

# 5. Configure CMake
mkdir -p "$BUILD_DIR"
"$CMAKE_BIN" -B "$BUILD_DIR" -G Ninja \
    -S "$SCRIPT_DIR" \
    -DCMAKE_MAKE_PROGRAM="${NINJA_BIN}" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-march=armv8.7a+fp16+dotprod+i8mm -D_GNU_SOURCE" \
    -DCMAKE_CXX_FLAGS="-march=armv8.7a+fp16+dotprod+i8mm -D_GNU_SOURCE" \
    -DHEXAGON_SDK_ROOT="${HEXAGON_SDK_ROOT}" \
    -DSDCPP_DIR="${SDCPP_DIR}"

# 6. Build Targets
echo "Compiling DSP skeletons (v79, v81, v85)..."
"$CMAKE_BIN" --build "$BUILD_DIR" --target htp-v79 -j$(nproc)
"$CMAKE_BIN" --build "$BUILD_DIR" --target htp-v81 -j$(nproc)
"$CMAKE_BIN" --build "$BUILD_DIR" --target htp-v85 -j$(nproc)

echo "Compiling host libdit_engine.so..."
"$CMAKE_BIN" --build "$BUILD_DIR" --target dit_engine -j$(nproc)

# 7. Stage Artifacts directly into Android project
JNI_DEST="${PROJECT_ROOT}/image/src/main/jniLibs/arm64-v8a"
SKEL_DEST="${PROJECT_ROOT}/image/src/main/assets/ditlibs"
mkdir -p "$JNI_DEST" "$SKEL_DEST"

cp -v "${BUILD_DIR}/lib/arm64-v8a/libdit_engine.so" "${JNI_DEST}/libdit_engine.so"
cp -v "${BUILD_DIR}/sdcpp/ggml/src/ggml-hexagon/libggml-htp-v79.so" "${SKEL_DEST}/libggml-htp-v79.so"
cp -v "${BUILD_DIR}/sdcpp/ggml/src/ggml-hexagon/libggml-htp-v81.so" "${SKEL_DEST}/libggml-htp-v81.so"
cp -v "${BUILD_DIR}/sdcpp/ggml/src/ggml-hexagon/libggml-htp-v85.so" "${SKEL_DEST}/libggml-htp-v85.so"

echo "=========================================================="
echo " SUCCESS! DiT Engine & Qualcomm Hexagon Skeletons Staged."
echo " JNI:  ${JNI_DEST}/libdit_engine.so"
echo " Skel: ${SKEL_DEST}/libggml-htp-v79.so"
echo " Skel: ${SKEL_DEST}/libggml-htp-v81.so"
echo " Skel: ${SKEL_DEST}/libggml-htp-v85.so"
echo "=========================================================="
