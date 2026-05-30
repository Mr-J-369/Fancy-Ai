cmake_minimum_required(VERSION 3.18.1)
project(fancy_ai CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ──────────────────────────────────────────────────────────────────────────────
# Fancy AI native engine.
#
# Builds llama.cpp (latest upstream) + a thin JNI bridge (jni.cpp → libfancy_ai.so)
# with every accelerator the Snapdragon platform offers, selected at *runtime* via
# nativeSetEngineParams(backend): CPU (NEON+i8mm), Vulkan (Adreno), OpenCL (Adreno),
# and the Hexagon NPU (HTP). Backends are compiled in statically; the host picks one
# device per load (see jni.cpp device pinning).
# ──────────────────────────────────────────────────────────────────────────────

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)   # don't probe build-host CPU; we target the device
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)   # ggml's own threadpool; avoids an extra libomp dep
set(GGML_METAL   OFF CACHE BOOL "" FORCE)
set(GGML_CUDA    OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Master switch for the GPU/NPU backends (Vulkan + OpenCL + Hexagon). OFF = CPU-only:
# a simpler, smaller, rock-stable build that doesn't depend on the Hexagon SDK or the
# Adreno drivers. Flip to ON to re-enable the accelerators (also needs the android-28
# native platform in build.gradle.kts for Vulkan's 1.1 symbols).
option(FANCYAI_ENABLE_ACCELERATORS "Build Vulkan/OpenCL/Hexagon GPU+NPU backends" OFF)

set(_cpp_dir "${CMAKE_CURRENT_SOURCE_DIR}")

# ── CPU backend (arm64-v8a) ─────────────────────────────────────────────────────
# Compile the CPU kernels for armv8.2 + dotprod + i8mm + fp16 so ggml's fast Q4/Q8
# repacked matmul kernels (CPU_REPACK) are built. Without i8mm/dotprod ~290 tensors
# fall back to baseline NEON and CPU inference is several times slower. This HARD-
# compiles i8mm: the APK will SIGILL on pre-~2021 SoCs that lack it (fine for the
# 8-Elite / modern Snapdragons; for broad distribution use GGML_CPU_ALL_VARIANTS).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NEON ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
endif()

# ── Debug performance: force -O3 for the native math ────────────────────────────
# AGP compiles native libs with CMAKE_BUILD_TYPE=Debug for any debuggable variant
# (installDebug / Studio Run). At the implied -O0, ggml's NEON kernels run 10-50x
# slower and an 8B model appears to hang (threads pegged, no token for minutes).
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    message(STATUS "fancy_ai: debug native build — forcing -O3 for ggml/llama math")
    add_compile_options(-O3)
endif()

# ── Hexagon NPU (HTP) backend ───────────────────────────────────────────────────
# Enabled when HEXAGON_SDK_ROOT is passed (see app/build.gradle.kts). Builds the
# HTP skel libs (libggml-htp-v68..v81.so) with the Hexagon LLVM toolchain; the
# gradle `signHexagonSkeletons` task collects them from intermediates/cmake into
# jniLibs. The QNN host/stub/skel prebuilts already live in src/main/jniLibs.
if(FANCYAI_ENABLE_ACCELERATORS AND ANDROID_ABI STREQUAL "arm64-v8a" AND DEFINED HEXAGON_SDK_ROOT AND IS_DIRECTORY "${HEXAGON_SDK_ROOT}")
    message(STATUS "fancy_ai: enabling Hexagon NPU backend (${HEXAGON_SDK_ROOT})")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    if(NOT DEFINED HEXAGON_TOOLS_ROOT)
        set(HEXAGON_TOOLS_ROOT "${HEXAGON_SDK_ROOT}/tools/HEXAGON_Tools/19.0.07")
    endif()
    # Hexagon SDK 6.6.0.0 quirk: hexagon_fun.cmake dereferences PREBUILT_LIB_DIR at
    # include time, before the per-skel ExternalProject sets it. Seed a placeholder.
    set(PREBUILT_LIB_DIR "placeholder" CACHE INTERNAL "")
else()
    message(STATUS "fancy_ai: HEXAGON_SDK_ROOT not set — NPU backend disabled")
endif()

# ── Vulkan GPU backend (Adreno) ─────────────────────────────────────────────────
# Enabled when the vendored SPIRV-Headers install is present. Compute shaders are
# compiled at build time by the NDK's bundled glslc; libvulkan resolves from the
# NDK sysroot. The NDK ships only the Vulkan C headers, so point Vulkan_INCLUDE_DIR
# at our vendored Vulkan-Headers (which also carry the C++ bindings ggml needs).
set(_spirv_cfg "${_cpp_dir}/third_party/spirv-headers-install/share/cmake/SPIRV-Headers")
if(FANCYAI_ENABLE_ACCELERATORS AND ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${_spirv_cfg}")
    message(STATUS "fancy_ai: enabling Vulkan GPU backend")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    # ggml-vulkan builds its `vulkan-shaders-gen` host tool via ExternalProject,
    # whose nested CMake doesn't inherit the ninja that AGP passes as
    # CMAKE_MAKE_PROGRAM — so it fails with "CMAKE_MAKE_PROGRAM is not set". Feed
    # ggml a host toolchain file (its supported override) that points at the same
    # ninja; host compilers are auto-detected by the sub-build.
    if(CMAKE_MAKE_PROGRAM)
        set(_host_tc "${CMAKE_BINARY_DIR}/fancyai-host-toolchain.cmake")
        file(WRITE "${_host_tc}"
            "set(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"ninja\" FORCE)\n")
        set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${_host_tc}" CACHE FILEPATH "" FORCE)
        message(STATUS "fancy_ai: vulkan-shaders-gen ninja = ${CMAKE_MAKE_PROGRAM}")
    endif()

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

    set(_vulkan_inc "${_cpp_dir}/third_party/Vulkan-Headers/include")
    if(EXISTS "${_vulkan_inc}/vulkan/vulkan.hpp")
        set(Vulkan_INCLUDE_DIR "${_vulkan_inc}" CACHE PATH "" FORCE)
    endif()

    # SPIRV-Headers is a host package; let find_package look beyond the Android
    # sysroot find-root so our vendored config is visible.
    set(SPIRV-Headers_DIR "${_spirv_cfg}" CACHE PATH "" FORCE)
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
endif()

# ── OpenCL GPU backend (Adreno — Qualcomm's first-class GPU path) ────────────────
# Headers from vendored OpenCL-Headers; link the OpenCL-ICD-Loader libOpenCL.so,
# which dispatches to the device's Qualcomm OpenCL driver at runtime. Adreno-tuned
# kernels + embedded kernels (no runtime kernel files needed).
set(_ocl_headers "${_cpp_dir}/third_party/OpenCL-Headers")
set(_ocl_lib "${_cpp_dir}/third_party/OpenCL-ICD-Loader/build-android/libOpenCL.so")
if(FANCYAI_ENABLE_ACCELERATORS AND ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${_ocl_headers}/CL/cl.h" AND EXISTS "${_ocl_lib}")
    message(STATUS "fancy_ai: enabling OpenCL GPU backend (Adreno)")
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(OpenCL_INCLUDE_DIR "${_ocl_headers}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY "${_ocl_lib}" CACHE FILEPATH "" FORCE)
endif()

add_subdirectory(llama.cpp)

# ggml-vulkan.cpp #includes <spirv/unified1/spirv.hpp>; our split header setup
# (NDK Vulkan loader + vendored Vulkan-Headers) doesn't co-locate spirv/ next to
# vulkan/, so add the SPIRV-Headers include to the target explicitly.
if(GGML_VULKAN AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE
        "${_cpp_dir}/third_party/spirv-headers-install/include")
endif()

add_library(fancy_ai SHARED jni.cpp)

target_include_directories(fancy_ai PRIVATE
    ${_cpp_dir}/llama.cpp/include
    ${_cpp_dir}/llama.cpp/ggml/include)

target_link_libraries(fancy_ai llama ggml log android)

# Align to 16 KB pages for Android 15+ (S25 Ultra etc.) — avoids the
# "LOAD segment not aligned" loader error.
target_link_options(fancy_ai PRIVATE "-Wl,-z,common-page-size=16384" "-Wl,-z,max-page-size=16384")
