cmake_minimum_required(VERSION 3.22.2)
project(fancyimage CXX C)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SD_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# MNN is fetched from the FancyMNN fork (Mr-J-369/MNN) at a pinned commit,
# not vendored. All engine, Hexagon, OpenCL, and KV cache surgery is merged
# directly on the fork's master branch. Releases are pins in FANCY_MNN_TAG.
# tools/mnn-upstream.txt records the fork pin and history.
include(FetchContent)
set(FANCY_MNN_TAG "4439e70f566d95a842f4111678d212ee62a4bed9")
FetchContent_Declare(
    mnn
    GIT_REPOSITORY https://github.com/Mr-J-369/MNN.git
    GIT_TAG ${FANCY_MNN_TAG}
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
set(MNN_BUILD_LLM         OFF CACHE BOOL "" FORCE)
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
set(MNN_HEXAGON           ON  CACHE BOOL "" FORCE)
set(MNN_HEXAGON_APP_POWER_HOLD ON CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY        ON  CACHE BOOL "" FORCE)
set(MNN_VULKAN            OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(mnn)

target_compile_options(MNNCore PRIVATE -Wno-format)
target_compile_options(MNNCPU PRIVATE
    -Wno-c99-designator
    -Wno-implicit-const-int-float-conversion)
target_compile_options(MNN_CL PRIVATE -Wno-format)
foreach(mnn_target MNNCore MNNCPU MNN_CL MNN_Arm82 MNN_Express MNNUtils MNNMath MNNTransform MNNCV MNNARM64 MNN_Hexagon)
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

add_library(fancy_mnn_faceswap SHARED mnn_faceswap.cpp)

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

add_library(fancy_mnn_convert SHARED sd_convert.cpp)

set(FANCY_QNN_INCLUDES
    ${SD_DIR}/qnn-sdk/include/QNN
    ${SD_DIR}/qnn-sdk/include/QNN/System
    ${mnn_SOURCE_DIR}/include
    ${SD_DIR}/third_party/json
    ${SD_DIR}/zstd)

target_include_directories(fancy_mnn_faceswap PRIVATE ${mnn_SOURCE_DIR}/include)
target_include_directories(fancy_mnn_diffusion PRIVATE
    ${mnn_SOURCE_DIR}/include
    ${SD_DIR}/third_party/json)
target_include_directories(fancy_mnn_sdxl PRIVATE
    ${mnn_SOURCE_DIR}/include
    ${SD_DIR}/third_party/json)
target_compile_definitions(fancy_mnn_diffusion PRIVATE FANCY_MNN_SDXL=0)
target_compile_definitions(fancy_mnn_sdxl PRIVATE FANCY_MNN_SDXL=1)
target_include_directories(fancy_mnn_convert PRIVATE
    ${SD_DIR}/safetensor2mnn
    ${SD_DIR}/third_party/json)
target_include_directories(fancy_qnn_diffusion PRIVATE ${FANCY_QNN_INCLUDES})

target_link_libraries(fancy_mnn_faceswap PRIVATE MNN log jnigraphics)
target_link_libraries(fancy_mnn_diffusion PRIVATE MNN log jnigraphics)
target_link_libraries(fancy_mnn_sdxl PRIVATE MNN log jnigraphics)
target_link_libraries(fancy_mnn_convert PRIVATE log)
target_link_libraries(fancy_qnn_diffusion PRIVATE MNN log dl jnigraphics)

add_library(fancy_dit_diffusion SHARED dit_diffusion_jni.cpp)
target_include_directories(fancy_dit_diffusion PRIVATE ${SD_DIR})
target_link_libraries(fancy_dit_diffusion PRIVATE log dl jnigraphics android)

set(FANCY_SD_TARGETS
    fancy_mnn_diffusion
    fancy_mnn_sdxl
    fancy_mnn_convert
    fancy_qnn_diffusion
    fancy_dit_diffusion)
foreach(target ${FANCY_SD_TARGETS})
    target_compile_options(${target} PRIVATE -O3 -fvisibility=hidden -Wno-deprecated-declarations)
    target_link_options(${target} PRIVATE -Wl,--exclude-libs,ALL)
endforeach()

file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/mnn_htp_package_stub.c" "void fancy_mnn_htp_package_stub(void) {}\n")
foreach(FANCY_MNN_HTP_VERSION v73 v75 v79 v81 v85)
    string(TOUPPER "${FANCY_MNN_HTP_VERSION}" FANCY_MNN_HTP_UPPER)
    set(FANCY_MNN_HTP_TARGET "fancy-mnn-htp-${FANCY_MNN_HTP_VERSION}")
    add_library(${FANCY_MNN_HTP_TARGET} SHARED "${CMAKE_CURRENT_BINARY_DIR}/mnn_htp_package_stub.c")
    set_target_properties(${FANCY_MNN_HTP_TARGET} PROPERTIES OUTPUT_NAME "MNN_htpops_skel${FANCY_MNN_HTP_UPPER}")
    add_dependencies(${FANCY_MNN_HTP_TARGET} "mnn-htp-${FANCY_MNN_HTP_VERSION}")
    set(FANCY_MNN_HTP_STAGE_TARGET "stage-${FANCY_MNN_HTP_TARGET}")
    add_custom_target(
        ${FANCY_MNN_HTP_STAGE_TARGET}
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${mnn_BINARY_DIR}/source/backend/hexagon/libMNN_htpops_skel${FANCY_MNN_HTP_UPPER}.so"
            "$<TARGET_FILE:${FANCY_MNN_HTP_TARGET}>"
        DEPENDS ${FANCY_MNN_HTP_TARGET} "mnn-htp-${FANCY_MNN_HTP_VERSION}"
        VERBATIM
    )
    add_dependencies(MNN ${FANCY_MNN_HTP_STAGE_TARGET})
endforeach()
