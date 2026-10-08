cmake_minimum_required(VERSION 3.22.1)
project(vextor LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp: scaricato alla versione fissata LLAMA_TAG (oppure da una cartella
# locale passando -DLLAMA_SOURCE_DIR=/percorso/llama.cpp).
# ---------------------------------------------------------------------------
set(LLAMA_TAG "b11400" CACHE STRING "llama.cpp release tag")
set(LLAMA_SOURCE_DIR "" CACHE PATH "Local llama.cpp checkout (optional)")

# Solo la libreria: niente tool, server, test.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# Backend CPU con tutte le varianti ARM (dotprod, i8mm, SVE...): a runtime
# ggml carica quella migliore per il processore (Snapdragon 8 Elite su S25).
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)

if(LLAMA_SOURCE_DIR)
    add_subdirectory(${LLAMA_SOURCE_DIR} build-llama)
else()
    include(FetchContent)
    FetchContent_Declare(
        llama
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_TAG}.tar.gz
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    )
    FetchContent_MakeAvailable(llama)
endif()

add_library(vextor SHARED vextor_jni.cpp)
target_link_libraries(vextor PRIVATE llama ggml android log)
target_compile_options(vextor PRIVATE -O3)
