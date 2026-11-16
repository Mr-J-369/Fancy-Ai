cmake_minimum_required(VERSION 3.31.6)

project(fancy_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_CPP_DIR)
    set(LLAMA_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/src/llama.cpp-Android")
endif()

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp-Android not found at ${LLAMA_CPP_DIR}. The local engine checkout is required.")
endif()

# llama.cpp's Android OpenCL build needs Khronos headers and a link-time ICD
# loader. The loader is deliberately excluded from the APK so Android resolves
# the device manufacturer's implementation declared in the app manifest.
include(FetchContent)
FetchContent_Declare(
    opencl_headers
    URL https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/v2026.05.29.tar.gz
    URL_HASH SHA256=d9e6c48357de5002da11ce45de600e0c3ffe6ab4f628a3b9fe2b38603161658a
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_Declare(
    opencl_loader
    URL https://github.com/KhronosGroup/OpenCL-ICD-Loader/archive/refs/tags/v2026.05.29.tar.gz
    URL_HASH SHA256=48fd0c5181db7cd046f4f731d5955694892e10998d49d09ee0d997e7e04fd939
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(OPENCL_ICD_LOADER_HEADERS_DIR "" CACHE PATH "" FORCE)
set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENCL_LOADER_MANAGED_DISPATCH OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(opencl_headers)
set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
FetchContent_MakeAvailable(opencl_loader)

# Android vendor libraries use the unversioned SONAME libOpenCL.so.
set_target_properties(OpenCL PROPERTIES VERSION "" SOVERSION "")
set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
endif()
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_BIN_KERNELS OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
set(PREBUILT_LIB_DIR android_aarch64 CACHE STRING "" FORCE)

set(LLAMA_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/llama")
add_subdirectory("${LLAMA_CPP_DIR}" "${LLAMA_BINARY_DIR}" EXCLUDE_FROM_ALL)

add_library(fancy_llama SHARED llama_jni.cpp)
target_include_directories(fancy_llama PRIVATE
    "${LLAMA_CPP_DIR}"
    "${LLAMA_CPP_DIR}/common"
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
)
target_link_libraries(fancy_llama PRIVATE llama llama-common android dl log)
target_compile_options(fancy_llama PRIVATE -fvisibility=hidden)

# Qualcomm's HTP skels are DSP binaries, not Android CPU libraries. Model each
# one as a CMake library output so AGP packages it, then replace the placeholder
# with llama.cpp's official Hexagon build artifact. keepDebugSymbols prevents
# Android's ARM strip step from touching the DSP ELF files.
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/htp_package_stub.c" "void fancy_htp_package_stub(void) {}\n")
foreach(FANCY_HTP_VERSION v73 v75 v79 v81 v85)
    set(FANCY_HTP_TARGET "fancy-htp-${FANCY_HTP_VERSION}")
    add_library(${FANCY_HTP_TARGET} SHARED "${CMAKE_CURRENT_BINARY_DIR}/htp_package_stub.c")
    set_target_properties(${FANCY_HTP_TARGET} PROPERTIES OUTPUT_NAME "ggml-htp-${FANCY_HTP_VERSION}")
    add_dependencies(${FANCY_HTP_TARGET} "htp-${FANCY_HTP_VERSION}")
    set(FANCY_HTP_STAGE_TARGET "stage-${FANCY_HTP_TARGET}")
    add_custom_target(
        ${FANCY_HTP_STAGE_TARGET}
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${LLAMA_BINARY_DIR}/ggml/src/ggml-hexagon/libggml-htp-${FANCY_HTP_VERSION}.so"
            "$<TARGET_FILE:${FANCY_HTP_TARGET}>"
        DEPENDS ${FANCY_HTP_TARGET} "htp-${FANCY_HTP_VERSION}"
        VERBATIM
    )
    add_dependencies(fancy_llama ${FANCY_HTP_STAGE_TARGET})
endforeach()
