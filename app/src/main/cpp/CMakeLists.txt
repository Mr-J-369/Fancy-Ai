cmake_minimum_required(VERSION 3.18.1)
project(fancy_ai_mnn)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to pre-built MNN libraries in jniLibs/
set(MNN_LIB_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# Add the JNI wrapper
add_library(fancy_ai_mnn SHARED
    jni.cpp
)

# Add library search path
link_directories(${MNN_LIB_DIR})

# Add pre-built MNN libraries as imported libraries
add_library(mnncore SHARED IMPORTED)
set_target_properties(mnncore PROPERTIES IMPORTED_LOCATION "${MNN_LIB_DIR}/libmnncore.so")

add_library(llm SHARED IMPORTED)
set_target_properties(llm PROPERTIES IMPORTED_LOCATION "${MNN_LIB_DIR}/libllm.so")

# Link to MNN libraries
target_link_libraries(fancy_ai_mnn
    mnncore
    llm
    log
)
