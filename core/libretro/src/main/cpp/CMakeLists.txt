cmake_minimum_required(VERSION 3.22.1)
project(retrohost LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Portable host: no JNI, no Android. Also built for Linux by src/test/native
# so it can be tested against real libretro cores without a device.
add_library(retrohost_host STATIC
    host/CoreLibrary.cpp
    host/CoreOptions.cpp
    host/FrameBuffer.cpp
    host/RetroHost.cpp
    host/SaveStateFile.cpp)
target_include_directories(retrohost_host PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(retrohost_host PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
set_target_properties(retrohost_host PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(retrohost_host PUBLIC ${CMAKE_DL_LIBS})

add_library(retrohost SHARED jni_bridge.cpp)
target_compile_options(retrohost PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(retrohost PRIVATE retrohost_host)

if (ANDROID)
    target_link_libraries(retrohost_host PUBLIC android log)
    # 16 KB page-size compatibility (required for new Play uploads targeting Android 15+).
    target_link_options(retrohost PRIVATE "-Wl,-z,max-page-size=16384")
else ()
    # Linux host build for JVM unit tests: only jni.h is needed, no libjvm.
    if (NOT DEFINED ENV{JAVA_HOME})
        message(FATAL_ERROR "Set JAVA_HOME to build the JNI library for host tests")
    endif ()
    target_include_directories(retrohost PRIVATE $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/linux)
endif ()
