cmake_minimum_required(VERSION 3.22)
project(flownotes_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

# On-device speech recognition: whisper.cpp (MIT), pinned to v1.9.5 by commit.
include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        d1be6fde11ac6e0407606b4e42fe72d34add8037 # v1.9.5
    GIT_SHALLOW    FALSE
)

set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
# Cross-compiling: never tune for the build machine. The arm64 baseline (NEON) runs on every
# 64-bit Android phone; newer-only extensions (fp16, dotprod) could crash older/budget phones.
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE              OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whisper)

add_library(flownotes_whisper SHARED whisper_jni.cpp)
target_compile_options(flownotes_whisper PRIVATE -O3)
target_link_libraries(flownotes_whisper PRIVATE whisper log android)
