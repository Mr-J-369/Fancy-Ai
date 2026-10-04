cmake_minimum_required(VERSION 3.22.1)

project(dit_engine LANGUAGES C CXX ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT DEFINED SDCPP_DIR)
    set(SDCPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src/stable-diffusion.cpp")
endif()

if (NOT EXISTS "${SDCPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "stable-diffusion.cpp not found at ${SDCPP_DIR}. Please run build.sh to fetch dependencies.")
endif()

set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Prebuilt FastRPC lib for Android aarch64 host side
set(PREBUILT_LIB_DIR "android_aarch64" CACHE STRING "" FORCE)

add_subdirectory("${SDCPP_DIR}" sdcpp EXCLUDE_FROM_ALL)

add_library(dit_engine SHARED DitEngine.cpp)

target_include_directories(dit_engine PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../image/src/main/cpp/sd"
    "${SDCPP_DIR}/include"
)

target_link_libraries(dit_engine PRIVATE stable-diffusion log)

target_compile_options(dit_engine PRIVATE -fvisibility=hidden)
target_link_options(dit_engine PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/dit_engine.map"
    "-Wl,-s"
)

set_target_properties(dit_engine PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib/${CMAKE_ANDROID_ARCH_ABI}"
)

foreach(htp_version v79 v81 v85)
    if(TARGET htp-${htp_version})
        add_dependencies(dit_engine htp-${htp_version})
    endif()
endforeach()
