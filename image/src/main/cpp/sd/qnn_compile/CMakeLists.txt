cmake_minimum_required(VERSION 3.22.2)
project(fancy_qnn_compile CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED SD_DIR)
    message(FATAL_ERROR "SD_DIR must point to image/src/main/cpp/sd")
endif()

include(FetchContent)
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED   OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC   ON  CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_CONTRIB  OFF CACHE BOOL "" FORCE)
set(ZSTD_LEGACY_SUPPORT OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    zstd
    GIT_REPOSITORY https://github.com/facebook/zstd.git
    GIT_TAG v1.5.6
    GIT_SHALLOW TRUE
    SOURCE_SUBDIR build/cmake
)
FetchContent_MakeAvailable(zstd)

add_executable(fancy_qnn_compile
    ${SD_DIR}/qnn_convert.cpp
    ${SD_DIR}/qnn_sd_graphs.cpp)

set_target_properties(fancy_qnn_compile PROPERTIES
    OUTPUT_NAME "libfancy_qnn_compile.so"
    PREFIX ""
    SUFFIX "")

target_include_directories(fancy_qnn_compile PRIVATE
    ${SD_DIR}
    ${SD_DIR}/qnn-sdk/include/QNN
    ${SD_DIR}/safetensor2mnn
    ${SD_DIR}/third_party/json
    ${zstd_SOURCE_DIR}/lib)

target_compile_options(fancy_qnn_compile PRIVATE
    -O3
    -ffp-contract=off
    -Wno-deprecated-declarations)

target_link_options(fancy_qnn_compile PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384)

target_link_libraries(fancy_qnn_compile PRIVATE libzstd_static log dl)
