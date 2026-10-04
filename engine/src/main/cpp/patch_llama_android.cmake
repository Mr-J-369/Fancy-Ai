function(patch_nested_ninja BUILD_FILE ORIGINAL PATCHED)
    file(READ "${BUILD_FILE}" CONTENTS)

    string(FIND "${CONTENTS}" "${PATCHED}" PATCHED_OFFSET)
    if(NOT PATCHED_OFFSET EQUAL -1)
        return()
    endif()

    string(FIND "${CONTENTS}" "${ORIGINAL}" MATCH_OFFSET)
    if(MATCH_OFFSET EQUAL -1)
        message(FATAL_ERROR "llama.cpp Android ExternalProject layout changed in ${BUILD_FILE}")
    endif()

    string(REPLACE "${ORIGINAL}" "${PATCHED}" CONTENTS "${CONTENTS}")
    file(WRITE "${BUILD_FILE}" "${CONTENTS}")
endfunction()


patch_nested_ninja(
    "${LLAMA_SOURCE_DIR}/ggml/src/ggml-hexagon/CMakeLists.txt"
    "        CMAKE_ARGS\n            -DCMAKE_BUILD_TYPE=Release"
    "        CMAKE_ARGS\n            -DCMAKE_MAKE_PROGRAM=\${CMAKE_MAKE_PROGRAM}\n            -DCMAKE_BUILD_TYPE=Release"
)
