cmake_minimum_required(VERSION 3.22.1)

project("openvoice_native" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ============================================================
# llama.cpp (upstream, pinned Git submodule at vendor/llama.cpp)
# ============================================================
# Compiled statically and linked into llama_bridge.so so the app ships a
# single JNI library per ABI. When added via add_subdirectory(), llama.cpp
# sets LLAMA_STANDALONE=OFF which disables examples/tests/tools/common.
# GGML_OPENMP / GGML_NATIVE / GGML_LLAMAFILE are disabled for a portable,
# NDK-friendly build (no -march=native, no OpenMP on Android).
#
# Bump procedure (see docs/developer/build-guide.md):
#   cd vendor/llama.cpp && git fetch origin tag bXXXXX && git checkout bXXXXX
#   cd ../.. && git add vendor/llama.cpp && ./gradlew :app:externalNativeBuildDebug
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)          # llama + ggml as static libs
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)                 # static libs -> shared bridge
# Relative to THIS file (app/), the submodule lives at the repo root.
add_subdirectory(../vendor/llama.cpp llama.cpp)

# ============================================================
# Native library: whisper_bridge
# JNI bridge for Whisper.cpp speech recognition
# ============================================================

add_library(whisper_bridge SHARED
    src/main/cpp/whisper_jni.cpp
)
target_include_directories(whisper_bridge PRIVATE src/main/cpp)
target_link_libraries(whisper_bridge android log)
set_target_properties(whisper_bridge PROPERTIES POSITION_INDEPENDENT_CODE TRUE)

# ============================================================
# Native library: llama_bridge
# JNI bridge for llama.cpp GGUF model inference
# ============================================================

add_library(llama_bridge SHARED
    src/main/cpp/llama_jni.cpp
)
target_include_directories(llama_bridge PRIVATE
    src/main/cpp
    ../vendor/llama.cpp/include
    ../vendor/llama.cpp/ggml/include
)
# `llama` (static) pulls in ggml/ggml-base/ggml-cpu transitively.
target_link_libraries(llama_bridge android log llama)
set_target_properties(llama_bridge PROPERTIES POSITION_INDEPENDENT_CODE TRUE)

# ============================================================
# Common compile options for ARM64
# ============================================================
target_compile_options(whisper_bridge PRIVATE -O3 -DNDEBUG)
target_compile_options(llama_bridge PRIVATE -O3 -DNDEBUG)
