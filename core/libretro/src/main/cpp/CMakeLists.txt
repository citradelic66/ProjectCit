cmake_minimum_required(VERSION 3.22.1)
project(retrohost LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Phase 1: toolchain smoke test only. Phase 2 adds libretro.h, the dlopen core
# loader, environment callbacks, input bridge and serialization.
add_library(retrohost SHARED jni_bridge.cpp)

target_compile_options(retrohost PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(retrohost PRIVATE android log dl)
# 16 KB page-size compatibility (required for new Play uploads targeting Android 15+).
target_link_options(retrohost PRIVATE "-Wl,-z,max-page-size=16384")
