cmake_minimum_required(VERSION 3.22.1)

project("openvoice_native" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

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
target_include_directories(llama_bridge PRIVATE src/main/cpp)
target_link_libraries(llama_bridge android log)
set_target_properties(llama_bridge PROPERTIES POSITION_INDEPENDENT_CODE TRUE)

# ============================================================
# Common compile options for ARM64
# ============================================================
target_compile_options(whisper_bridge PRIVATE -O3 -DNDEBUG)
target_compile_options(llama_bridge PRIVATE -O3 -DNDEBUG)
