cmake_minimum_required(VERSION 3.22.2)
project(appmodules CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O3)
endif()

add_subdirectory(sd)
