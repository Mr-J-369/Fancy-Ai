set(FANCY_RECALL_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}")
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

# The BART summarizer (CTranslate2) and the QuickJS relationship parser were
# removed with the multilingual MiniLM memory. Only the Rust tokenizer remains;
# it is linked into fancy_llama (defined by the including CMakeLists) so the
# shared object keeps packaging into the APK for the :memory process to load.
find_program(FANCY_MEMORY_CARGO cargo HINTS "$ENV{HOME}/.cargo/bin" REQUIRED)
set(FANCY_TOKENIZER_TARGET "${CMAKE_CURRENT_BINARY_DIR}/memory-tokenizer")
set(FANCY_TOKENIZER_LIBRARY "${FANCY_TOKENIZER_TARGET}/aarch64-linux-android/release/libfancy_memory_tokenizer.so")
add_custom_command(OUTPUT "${FANCY_TOKENIZER_LIBRARY}"
    COMMAND "${CMAKE_COMMAND}" -E env
        "CC_aarch64_linux_android=${CMAKE_C_COMPILER}"
        "AR_aarch64_linux_android=${CMAKE_AR}"
        "CFLAGS_aarch64_linux_android=--target=${CMAKE_C_COMPILER_TARGET}"
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=${ANDROID_TOOLCHAIN_ROOT}/bin/aarch64-linux-android33-clang"
        "RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-soname,libfancy_memory_tokenizer.so"
        "${FANCY_MEMORY_CARGO}" build --locked --release --target aarch64-linux-android
        --manifest-path "${FANCY_RECALL_SOURCE_DIR}/tokenizer/Cargo.toml"
        --target-dir "${FANCY_TOKENIZER_TARGET}"
    DEPENDS "${FANCY_RECALL_SOURCE_DIR}/CMakeLists.txt"
        "${FANCY_RECALL_SOURCE_DIR}/tokenizer/Cargo.toml"
        "${FANCY_RECALL_SOURCE_DIR}/tokenizer/Cargo.lock"
        "${FANCY_RECALL_SOURCE_DIR}/tokenizer/src/lib.rs"
    VERBATIM)
add_custom_target(build_memory_tokenizer DEPENDS "${FANCY_TOKENIZER_LIBRARY}")
add_library(fancy_memory_tokenizer SHARED IMPORTED GLOBAL)
set_target_properties(fancy_memory_tokenizer PROPERTIES IMPORTED_LOCATION "${FANCY_TOKENIZER_LIBRARY}")
add_dependencies(fancy_memory_tokenizer build_memory_tokenizer)
target_link_libraries(fancy_llama PRIVATE fancy_memory_tokenizer)
