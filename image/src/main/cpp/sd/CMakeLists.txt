cmake_minimum_required(VERSION 3.22.2)
project(fancyimage CXX C)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SD_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# MNN is fetched directly from upstream at a pinned commit (same pattern as
# llama.cpp in engine/), not vendored. Local patches live in mnn_patches/ and
# are applied by patch_mnn_android.cmake; bump FANCY_MNN_TAG plus the patch
# fingerprint together. tools/mnn-upstream.txt records the pin and history.
include(FetchContent)
set(FANCY_MNN_TAG "2c3f5b21a223734e5db7da4ef7b492bc02cafcf5")
set(FANCY_MNN_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/mnn_patches/mnn_local.patch")
file(SHA256 "${FANCY_MNN_PATCH}" FANCY_MNN_PATCH_SHA256)
FetchContent_Declare(
    mnn
    GIT_REPOSITORY https://github.com/alibaba/MNN.git
    GIT_TAG ${FANCY_MNN_TAG}
    GIT_SHALLOW TRUE
    PATCH_COMMAND ${CMAKE_COMMAND}
        "-DMNN_SOURCE_DIR=<SOURCE_DIR>"
        "-DPATCH_FILE=${FANCY_MNN_PATCH}"
        "-DPATCH_FINGERPRINT=${FANCY_MNN_PATCH_SHA256}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/patch_mnn_android.cmake"
)
if(NOT EXISTS "${SD_DIR}/qnn-sdk/include/QNN/QnnContext.h" OR
   NOT EXISTS "${SD_DIR}/qnn-sdk/include/QNN/QnnSdkBuildId.h")
    message(FATAL_ERROR "QAIRT 2.51.0 headers are required under qnn-sdk/")
endif()

set(MNN_BUILD_SHARED_LIBS ON  CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST        OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO        OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TRAIN       OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_QUANTOOLS   OFF CACHE BOOL "" FORCE)
set(MNN_EVALUATION        OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_PROTOBUFFER OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CODEGEN     OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV      OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM         ON  CACHE BOOL "" FORCE)
set(MNN_BUILD_DIFFUSION   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_AUDIO       OFF CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD         OFF CACHE BOOL "" FORCE)
set(MNN_USE_SYSTEM_LIB    OFF CACHE BOOL "" FORCE)
set(MNN_USE_SSE           OFF CACHE BOOL "" FORCE)
set(MNN_AVX2              OFF CACHE BOOL "" FORCE)
set(MNN_USE_RVV           OFF CACHE BOOL "" FORCE)
set(MNN_ARM82             ON  CACHE BOOL "" FORCE)
set(MNN_KLEIDIAI          ON  CACHE BOOL "" FORCE)
set(MNN_KLEIDIAI_DEFAULT_ON ON CACHE BOOL "" FORCE)
set(MNN_USE_THREAD_POOL   ON  CACHE BOOL "" FORCE)
set(MNN_OPENCL            ON  CACHE BOOL "" FORCE)
set(MNN_METAL             OFF CACHE BOOL "" FORCE)
set(MNN_OPENGL            OFF CACHE BOOL "" FORCE)
set(MNN_CUDA              OFF CACHE BOOL "" FORCE)
set(MNN_MUSA              OFF CACHE BOOL "" FORCE)
set(MNN_MUSA_COMPAT_STUB  OFF CACHE BOOL "" FORCE)
set(MNN_TENSORRT          OFF CACHE BOOL "" FORCE)
set(MNN_COREML            OFF CACHE BOOL "" FORCE)
set(MNN_NNAPI             OFF CACHE BOOL "" FORCE)
set(MNN_QNN               OFF CACHE BOOL "" FORCE)
set(MNN_HEXAGON           OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY        ON  CACHE BOOL "" FORCE)
set(MNN_VULKAN            OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(mnn)

target_compile_options(MNNCore PRIVATE -Wno-format)
target_compile_options(MNNCPU PRIVATE
    -Wno-c99-designator
    -Wno-implicit-const-int-float-conversion)
target_compile_options(MNN_CL PRIVATE -Wno-format)
foreach(mnn_target MNNCore MNNCPU MNN_CL MNN_Arm82 MNN_Express MNNUtils MNNMath MNNTransform MNNCV MNNARM64)
    target_compile_options(${mnn_target} PRIVATE -Wno-deprecated-literal-operator)
endforeach()

file(READ "${SD_DIR}/qnn-sdk/include/QNN/QnnSdkBuildId.h" QNN_SDK_BUILD_ID_HEADER)
if(NOT QNN_SDK_BUILD_ID_HEADER MATCHES "v2\\.51\\.0\\.")
    message(FATAL_ERROR "Image QNN headers must match QAIRT 2.51.0")
endif()

add_library(fancy_qnn_diffusion SHARED
    qnn_diffusion.cpp
    native_diffusion_pipeline.cpp
    clip_tokenizer.cpp
    mnn_clip.cpp
    qnn_backend.cpp
    qnn_context.cpp
    qnn_model.cpp
    sdxl_model.cpp
    zstd/zstddeclib.c)

add_library(fancy_mnn_upscale SHARED mnn_upscaler.cpp)

add_library(fancy_mnn_faceswap SHARED mnn_faceswap.cpp)

add_library(fancy_mnn_convert SHARED sd_convert.cpp)

add_library(fancy_mnn_diffusion SHARED
    mnn_diffusion_jni.cpp
    mnn_sd15_model.cpp
    native_diffusion_pipeline.cpp
    clip_tokenizer.cpp
    mnn_clip.cpp)

add_library(fancy_mnn_sdxl SHARED
    mnn_diffusion_jni.cpp
    mnn_sdxl_model.cpp
    native_diffusion_pipeline.cpp
    clip_tokenizer.cpp
    mnn_clip.cpp)

set(FANCY_QNN_INCLUDES
    ${SD_DIR}/qnn-sdk/include/QNN
    ${SD_DIR}/qnn-sdk/include/QNN/System
    ${mnn_SOURCE_DIR}/include
    ${SD_DIR}/third_party/json
    ${SD_DIR}/zstd)

target_include_directories(fancy_mnn_upscale PRIVATE ${mnn_SOURCE_DIR}/include)
target_include_directories(fancy_mnn_faceswap PRIVATE ${mnn_SOURCE_DIR}/include)
target_include_directories(fancy_mnn_convert PRIVATE ${SD_DIR}/third_party/json)
target_compile_options(fancy_mnn_convert PRIVATE -fexceptions)
target_include_directories(fancy_mnn_diffusion PRIVATE
    ${mnn_SOURCE_DIR}/include
    ${SD_DIR}/third_party/json)
target_include_directories(fancy_mnn_sdxl PRIVATE
    ${mnn_SOURCE_DIR}/include
    ${SD_DIR}/third_party/json)
target_compile_definitions(fancy_mnn_diffusion PRIVATE FANCY_MNN_SDXL=0)
target_compile_definitions(fancy_mnn_sdxl PRIVATE FANCY_MNN_SDXL=1)
target_include_directories(fancy_qnn_diffusion PRIVATE ${FANCY_QNN_INCLUDES})

target_link_libraries(fancy_mnn_upscale PRIVATE MNN log)
target_link_libraries(fancy_mnn_faceswap PRIVATE MNN log jnigraphics)
target_link_libraries(fancy_mnn_convert PRIVATE log)
target_link_libraries(fancy_mnn_diffusion PRIVATE MNN log jnigraphics)
target_link_libraries(fancy_mnn_sdxl PRIVATE MNN log jnigraphics)
target_link_libraries(fancy_qnn_diffusion PRIVATE MNN log dl jnigraphics)

add_library(fancy_dit_diffusion SHARED dit_diffusion_jni.cpp)
target_include_directories(fancy_dit_diffusion PRIVATE ${SD_DIR})
target_link_libraries(fancy_dit_diffusion PRIVATE log dl jnigraphics android)

set(FANCY_SD_TARGETS
    fancy_mnn_convert
    fancy_mnn_upscale
    fancy_mnn_diffusion
    fancy_mnn_sdxl
    fancy_qnn_diffusion
    fancy_dit_diffusion)
foreach(target ${FANCY_SD_TARGETS})
    target_compile_options(${target} PRIVATE -O3 -fvisibility=hidden -Wno-deprecated-declarations)
    target_link_options(${target} PRIVATE -Wl,--exclude-libs,ALL)
endforeach()

# LLM JNI shares Aura's MNN core; it does not load image models.
add_library(fancy_mnn_llm SHARED mnn_llm_jni.cpp)
target_include_directories(fancy_mnn_llm PRIVATE
    ${mnn_SOURCE_DIR}/include
    ${mnn_SOURCE_DIR}/transformers/llm/engine/include
    ${mnn_SOURCE_DIR}/transformers/llm/engine/src
    ${mnn_SOURCE_DIR}/3rd_party)
# Match MNN's RTTI-free ABI when extending its text runtime.
target_compile_options(fancy_mnn_llm PRIVATE -fexceptions -fno-rtti)
target_link_libraries(fancy_mnn_llm PRIVATE MNN log)
target_compile_options(fancy_mnn_llm PRIVATE -O3 -fvisibility=hidden)
target_link_options(fancy_mnn_llm PRIVATE -Wl,--exclude-libs,ALL)
