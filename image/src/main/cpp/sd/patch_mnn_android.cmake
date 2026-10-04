# Applies FancyAI's local MNN patches to the FetchContent checkout and regenerates
# the OpenCL kernel table so it matches the patched conv_2d_int_buf.cl.
# Required inputs: -DMNN_SOURCE_DIR=<fetchcontent source> -DPATCH_FILE=<mnn_local.patch>.
if(NOT DEFINED MNN_SOURCE_DIR OR NOT DEFINED PATCH_FILE)
    message(FATAL_ERROR "patch_mnn_android.cmake needs MNN_SOURCE_DIR and PATCH_FILE")
endif()

find_program(GIT_EXECUTABLE git)
if(NOT GIT_EXECUTABLE)
    message(FATAL_ERROR "git is required to apply the MNN patch set")
endif()

# Idempotent: skip when the patch is already applied, fail loudly when neither
# the forward nor the reverse direction applies (upstream layout changed).
execute_process(
    COMMAND ${GIT_EXECUTABLE} apply --reverse --check ${PATCH_FILE}
    WORKING_DIRECTORY ${MNN_SOURCE_DIR}
    RESULT_VARIABLE REVERSE_CHECK
    OUTPUT_QUIET ERROR_QUIET
)
if(REVERSE_CHECK EQUAL 0)
    message(STATUS "MNN local patches already applied, skipping")
else()
    execute_process(
        COMMAND ${GIT_EXECUTABLE} apply --check ${PATCH_FILE}
        WORKING_DIRECTORY ${MNN_SOURCE_DIR}
        RESULT_VARIABLE APPLY_CHECK
        OUTPUT_VARIABLE APPLY_OUT ERROR_VARIABLE APPLY_OUT
    )
    if(NOT APPLY_CHECK EQUAL 0)
        message(FATAL_ERROR "MNN upstream layout changed; cannot apply local patches:\n${APPLY_OUT}")
    endif()
    execute_process(
        COMMAND ${GIT_EXECUTABLE} apply ${PATCH_FILE}
        WORKING_DIRECTORY ${MNN_SOURCE_DIR}
        RESULT_VARIABLE APPLY_RESULT
        OUTPUT_VARIABLE APPLY_OUT ERROR_VARIABLE APPLY_OUT
    )
    if(NOT APPLY_RESULT EQUAL 0)
        message(FATAL_ERROR "Failed to apply MNN local patches:\n${APPLY_OUT}")
    endif()
    message(STATUS "Applied FancyAI MNN local patches")
endif()

find_program(PYTHON_EXECUTABLE python3)
if(NOT PYTHON_EXECUTABLE)
    message(FATAL_ERROR "python3 is required to regenerate MNN OpenCL kernels")
endif()
execute_process(
    COMMAND ${PYTHON_EXECUTABLE} opencl_codegen.py .
    WORKING_DIRECTORY ${MNN_SOURCE_DIR}/source/backend/opencl/execution/cl
    RESULT_VARIABLE CODEGEN_RESULT
    OUTPUT_QUIET ERROR_VARIABLE CODEGEN_ERR
)
if(NOT CODEGEN_RESULT EQUAL 0)
    message(FATAL_ERROR "MNN OpenCL codegen failed:\n${CODEGEN_ERR}")
endif()
message(STATUS "Regenerated MNN OpenCL kernels")
