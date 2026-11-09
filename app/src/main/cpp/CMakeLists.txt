cmake_minimum_required(VERSION 3.18.1)
project(suixing_terminal LANGUAGES CXX)

add_library(suixing_terminal SHARED terminal_pty.cpp)
target_compile_features(suixing_terminal PRIVATE cxx_std_17)
target_compile_options(suixing_terminal PRIVATE -Wall -Wextra -Werror)

# Keep the PTY implementation independent. Only the read-only file-search kernel uses Rust.
set(SX_RUST_TOOLCHAIN "1.98.0")
get_filename_component(SX_RUST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../rust/suixing-search" ABSOLUTE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(SX_RUST_TARGET "aarch64-linux-android")
    set(SX_CLANG_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(SX_RUST_TARGET "armv7-linux-androideabi")
    set(SX_CLANG_TARGET "armv7a-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86")
    set(SX_RUST_TARGET "i686-linux-android")
    set(SX_CLANG_TARGET "i686-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(SX_RUST_TARGET "x86_64-linux-android")
    set(SX_CLANG_TARGET "x86_64-linux-android")
else()
    message(FATAL_ERROR "File search does not support Android ABI '${ANDROID_ABI}'.")
endif()

find_program(SX_RUSTUP rustup HINTS "$ENV{USERPROFILE}/.cargo/bin" "$ENV{HOME}/.cargo/bin")
if(NOT SX_RUSTUP)
    message(FATAL_ERROR "Rust file search requires rustup and Rust ${SX_RUST_TOOLCHAIN}. Install them before building; the Android build never downloads Rust.")
endif()
execute_process(
    COMMAND "${SX_RUSTUP}" which cargo --toolchain "${SX_RUST_TOOLCHAIN}"
    RESULT_VARIABLE SX_CARGO_RESULT OUTPUT_VARIABLE SX_CARGO OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET)
execute_process(
    COMMAND "${SX_RUSTUP}" which rustc --toolchain "${SX_RUST_TOOLCHAIN}"
    RESULT_VARIABLE SX_RUSTC_RESULT OUTPUT_VARIABLE SX_RUSTC OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET)
if(NOT SX_CARGO_RESULT EQUAL 0 OR NOT SX_RUSTC_RESULT EQUAL 0)
    message(FATAL_ERROR "Rust ${SX_RUST_TOOLCHAIN} is not installed. Run 'rustup toolchain install ${SX_RUST_TOOLCHAIN}' before building; Gradle will not install it.")
endif()
execute_process(
    COMMAND "${SX_RUSTUP}" target list --installed --toolchain "${SX_RUST_TOOLCHAIN}"
    RESULT_VARIABLE SX_TARGET_RESULT OUTPUT_VARIABLE SX_INSTALLED_TARGETS
    ERROR_QUIET)
string(REPLACE "\r" "" SX_INSTALLED_TARGETS "${SX_INSTALLED_TARGETS}")
string(REPLACE "\n" ";" SX_INSTALLED_TARGETS "${SX_INSTALLED_TARGETS}")
list(FIND SX_INSTALLED_TARGETS "${SX_RUST_TARGET}" SX_TARGET_INDEX)
if(NOT SX_TARGET_RESULT EQUAL 0 OR SX_TARGET_INDEX EQUAL -1)
    message(FATAL_ERROR "Rust target ${SX_RUST_TARGET} is missing for ${ANDROID_ABI}. Run 'rustup target add --toolchain ${SX_RUST_TOOLCHAIN} ${SX_RUST_TARGET}' before building; Gradle will not download targets.")
endif()

if(NOT EXISTS "${SX_RUST_DIR}/Cargo.toml" OR NOT EXISTS "${SX_RUST_DIR}/Cargo.lock")
    message(FATAL_ERROR "The locked suixing-search Rust sources are missing from ${SX_RUST_DIR}.")
endif()
get_filename_component(SX_COMPILER_BIN "${CMAKE_CXX_COMPILER}" DIRECTORY)
if(NOT ANDROID_PLATFORM_LEVEL OR ANDROID_PLATFORM_LEVEL LESS 26)
    message(FATAL_ERROR "Rust file search requires Android API 26 or newer; check minSdk and ANDROID_PLATFORM.")
endif()
set(SX_CLANG "${SX_COMPILER_BIN}/${SX_CLANG_TARGET}${ANDROID_PLATFORM_LEVEL}-clang")
if(CMAKE_HOST_WIN32)
    string(APPEND SX_CLANG ".cmd")
endif()
if(NOT EXISTS "${SX_CLANG}")
    message(FATAL_ERROR "The NDK compiler for ${SX_RUST_TARGET} / Android ${ANDROID_PLATFORM_LEVEL} is missing: ${SX_CLANG}")
endif()
string(TOUPPER "${SX_RUST_TARGET}" SX_TARGET_ENV)
string(REPLACE "-" "_" SX_TARGET_ENV "${SX_TARGET_ENV}")
set(SX_CARGO_TARGET_DIR "${CMAKE_CURRENT_BINARY_DIR}/cargo")
set(SX_RUST_ARCHIVE "${SX_CARGO_TARGET_DIR}/${SX_RUST_TARGET}/release/libsuixing_search.a")
file(GLOB_RECURSE SX_RUST_SOURCES CONFIGURE_DEPENDS "${SX_RUST_DIR}/src/*.rs")
add_custom_command(
    OUTPUT "${SX_RUST_ARCHIVE}"
    COMMAND "${CMAKE_COMMAND}" -E env
        "RUSTC=${SX_RUSTC}"
        "CARGO_TARGET_DIR=${SX_CARGO_TARGET_DIR}"
        "CARGO_TARGET_${SX_TARGET_ENV}_LINKER=${SX_CLANG}"
        "RUSTFLAGS=-C relocation-model=pic"
        "${SX_CARGO}" build --manifest-path "${SX_RUST_DIR}/Cargo.toml"
        --release --locked --offline --target "${SX_RUST_TARGET}"
    WORKING_DIRECTORY "${SX_RUST_DIR}"
    DEPENDS ${SX_RUST_SOURCES} "${SX_RUST_DIR}/Cargo.toml" "${SX_RUST_DIR}/Cargo.lock"
        "${SX_RUST_DIR}/rust-toolchain.toml"
    COMMENT "Building offline Rust file search for ${ANDROID_ABI}"
    VERBATIM)
add_custom_target(suixing_search_rust_build DEPENDS "${SX_RUST_ARCHIVE}")
add_library(suixing_search_rust STATIC IMPORTED)
set_target_properties(suixing_search_rust PROPERTIES IMPORTED_LOCATION "${SX_RUST_ARCHIVE}")
add_dependencies(suixing_search_rust suixing_search_rust_build)

add_library(suixing_search SHARED search_jni.cpp)
target_compile_features(suixing_search PRIVATE cxx_std_17)
target_compile_options(suixing_search PRIVATE -Wall -Wextra -Werror)
target_link_libraries(suixing_search PRIVATE suixing_search_rust android log ${CMAKE_DL_LIBS} m)
target_link_options(suixing_search PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
