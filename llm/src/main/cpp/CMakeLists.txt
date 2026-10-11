cmake_minimum_required(VERSION 3.22)
project(flownotes_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

# llama.cpp (MIT), pinned by commit (tag b11559).
include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        f2cc7282ce0042641df55e0e563b75e824e2b647 # b11559
    GIT_SHALLOW    FALSE
)

set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
# Same reasoning as the whisper build: portable arm64 baseline, no OpenMP runtime to package.
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE           OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llama)

add_library(flownotes_llama SHARED llama_jni.cpp)
target_compile_options(flownotes_llama PRIVATE -O3)
target_link_libraries(flownotes_llama PRIVATE llama log android)
# Keep this library's copy of ggml private so it can never clash with whisper.cpp's copy.
target_link_options(flownotes_llama PRIVATE -Wl,--exclude-libs,ALL)
