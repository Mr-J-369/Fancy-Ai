cmake_minimum_required(VERSION 3.18.1)
project(fancy_ai)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp build options — disable everything we don't need
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# ARM NEON is available on all arm64-v8a devices — enable for performance
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NEON ON CACHE BOOL "" FORCE)
    # Hexagon NPU acceleration: enabled when HEXAGON_SDK_ROOT is passed.
    # Builds 6 skel libs (v68–v81) using the Hexagon LLVM toolchain.
    if(DEFINED HEXAGON_SDK_ROOT AND IS_DIRECTORY "${HEXAGON_SDK_ROOT}")
        message(STATUS "Hexagon SDK: ${HEXAGON_SDK_ROOT} — enabling NPU backend")
        set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
        if(NOT DEFINED HEXAGON_TOOLS_ROOT)
            set(HEXAGON_TOOLS_ROOT "${HEXAGON_SDK_ROOT}/tools/HEXAGON_Tools/19.0.07")
        endif()
        # Disable llama.cpp's automatic skel installation so our POST_BUILD copy can handle it
        set(GGML_HEXAGON_SKIP_INSTALL ON CACHE BOOL "" FORCE)
        # Workaround for Hexagon SDK 6.6.0.0: hexagon_fun.cmake references
        # PREBUILT_LIB_DIR at top-level before ExternalProject defines it.
        set(PREBUILT_LIB_DIR "placeholder" CACHE INTERNAL "")
    else()
        message(STATUS "HEXAGON_SDK_ROOT not set — CPU-only build")
    endif()
endif()

# ── Performance: force optimized native math even in *debug* APK builds ─────────
# Android Gradle compiles the native libs with CMAKE_BUILD_TYPE=Debug for any
# debuggable variant (`installDebug`, Android Studio "Run"). At the implied -O0,
# ggml's NEON matmul kernels run 10-50x slower: an 8B model appears to "hang"
# (all compute threads pegged at 100% for minutes, no token ever emitted).
# Force -O3 so debug installs are actually usable. Release builds (RelWithDebInfo,
# already -O2) are left untouched.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    message(STATUS "fancy_ai: debug native build — forcing -O3 for ggml/llama math")
    add_compile_options(-O3)
endif()

# ── Vulkan GPU backend (Adreno) ─────────────────────────────────────────────────
# Reliable accelerator path (the experimental Hexagon NPU backend stalls with a
# FastRPC 0xc DSP timeout on k-quant models). Enabled when SPIRV-Headers is present
# under third_party/. Shaders are compiled at build time by the NDK's bundled glslc;
# libvulkan comes from the NDK sysroot.
set(_spirv_headers_cfg "${CMAKE_CURRENT_SOURCE_DIR}/third_party/spirv-headers-install/share/cmake/SPIRV-Headers")
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${_spirv_headers_cfg}")
    message(STATUS "fancy_ai: enabling Vulkan GPU backend")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    # NDK-bundled host glslc (compiles compute shaders on the build machine).
    file(GLOB _ndk_glslc
        "${CMAKE_ANDROID_NDK}/shader-tools/*/glslc"
        "${ANDROID_NDK}/shader-tools/*/glslc"
        "$ENV{ANDROID_NDK_ROOT}/shader-tools/*/glslc")
    if(_ndk_glslc)
        list(GET _ndk_glslc 0 _glslc)
        set(Vulkan_GLSLC_EXECUTABLE "${_glslc}" CACHE FILEPATH "" FORCE)
        message(STATUS "fancy_ai: glslc = ${_glslc}")
    else()
        message(WARNING "fancy_ai: glslc not found in NDK shader-tools — Vulkan shaders will fail")
    endif()

    # The NDK ships only the Vulkan *C* headers; ggml-vulkan needs the C++ bindings
    # (vulkan/vulkan.hpp). Point the include dir at our vendored Vulkan-Headers
    # (full set: C + C++). libvulkan itself is still resolved from the NDK sysroot.
    set(_vulkan_headers_inc "${CMAKE_CURRENT_SOURCE_DIR}/third_party/Vulkan-Headers/include")
    if(EXISTS "${_vulkan_headers_inc}/vulkan/vulkan.hpp")
        set(Vulkan_INCLUDE_DIR "${_vulkan_headers_inc}" CACHE PATH "" FORCE)
        message(STATUS "fancy_ai: Vulkan headers = ${_vulkan_headers_inc}")
    endif()

    # SPIRV-Headers is a *host* package; let find_package look beyond the Android
    # sysroot find-root so the config we installed under third_party/ is visible.
    set(SPIRV-Headers_DIR "${_spirv_headers_cfg}" CACHE PATH "" FORCE)
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
endif()

# ── OpenCL GPU backend (Adreno — Qualcomm's first-class GPU path) ────────────────
# Preferred GPU path on Adreno (Vulkan-on-Adreno drivers throw during compute).
# Headers from vendored OpenCL-Headers; we link the OpenCL-ICD-Loader libOpenCL.so
# (bundled in jniLibs) which dispatches to the device's Qualcomm OpenCL driver at
# runtime. Kernels are embedded (no runtime files); Adreno-optimized kernels on.
set(_opencl_headers "${CMAKE_CURRENT_SOURCE_DIR}/third_party/OpenCL-Headers")
set(_opencl_lib "${CMAKE_CURRENT_SOURCE_DIR}/third_party/OpenCL-ICD-Loader/build-android/libOpenCL.so")
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${_opencl_headers}/CL/cl.h" AND EXISTS "${_opencl_lib}")
    message(STATUS "fancy_ai: enabling OpenCL GPU backend (Adreno)")
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(OpenCL_INCLUDE_DIR "${_opencl_headers}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY "${_opencl_lib}" CACHE FILEPATH "" FORCE)
endif()

add_subdirectory(llama.cpp)

# ggml-vulkan.cpp #includes <spirv/unified1/spirv.hpp>. Upstream resolves these via
# the Vulkan SDK's combined include dir, which co-locates spirv/ next to vulkan/.
# Our split setup (NDK Vulkan loader + vendored Vulkan-Headers) doesn't, so add the
# SPIRV-Headers include to the target explicitly.
if(GGML_VULKAN AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/third_party/spirv-headers-install/include")
endif()

add_library(fancy_ai SHARED jni.cpp)

target_include_directories(fancy_ai PRIVATE
    ${CMAKE_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(fancy_ai
    llama
    ggml
    log
    android
)

# [COMPATIBILITY] Align to 16 KB pages for Android 15+ (S25 Ultra, etc.)
# This fixes the "LOAD segment not aligned" error during app install/load.
target_link_options(fancy_ai PRIVATE "-Wl,-z,max-page-size=16384")
