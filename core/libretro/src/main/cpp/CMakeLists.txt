cmake_minimum_required(VERSION 3.22.1)
project(retrohost LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(WARN -Wall -Wextra -Werror -fvisibility=hidden)

# ---- Portable host: core loading, input, saves, pacing, emulation thread.
# Also built for Linux by src/test/native to test against real cores off-device.
add_library(retrohost_host STATIC
    host/CoreLibrary.cpp
    host/CoreOptions.cpp
    host/EmuRunner.cpp
    host/FrameBuffer.cpp
    host/RetroHost.cpp
    host/SaveStateFile.cpp)
target_include_directories(retrohost_host PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(retrohost_host PRIVATE ${WARN})
set_target_properties(retrohost_host PROPERTIES POSITION_INDEPENDENT_CODE ON)
find_package(Threads REQUIRED)
target_link_libraries(retrohost_host PUBLIC ${CMAKE_DL_LIBS} Threads::Threads)

# ---- OpenGL ES 3.0 renderer (Android always; Linux when Mesa EGL/GLES is installed).
if (ANDROID)
    set(RETROHOST_GL ON)
    set(GL_LIBS EGL GLESv3)
else ()
    find_path(GLES3_INCLUDE GLES3/gl3.h)
    find_library(EGL_LIB EGL)
    find_library(GLES_LIB GLESv2)
    if (GLES3_INCLUDE AND EGL_LIB AND GLES_LIB)
        set(RETROHOST_GL ON)
        set(GL_LIBS ${EGL_LIB} ${GLES_LIB})
    endif ()
endif ()
if (RETROHOST_GL)
    add_library(retrohost_gl STATIC gl/EglContext.cpp gl/GlRenderer.cpp gl/EglVideoOutput.cpp)
    target_compile_options(retrohost_gl PRIVATE ${WARN})
    set_target_properties(retrohost_gl PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_link_libraries(retrohost_gl PUBLIC retrohost_host ${GL_LIBS})
    target_compile_definitions(retrohost_gl PUBLIC RETROHOST_HAS_GL=1)
endif ()

# ---- JNI library
add_library(retrohost SHARED jni_bridge.cpp)
target_compile_options(retrohost PRIVATE ${WARN})
target_link_libraries(retrohost PRIVATE retrohost_host)

if (ANDROID)
    # Oboe comes from the com.google.oboe:oboe AAR via prefab.
    find_package(oboe REQUIRED CONFIG)
    add_library(retrohost_audio STATIC android/OboeAudioOutput.cpp)
    target_compile_options(retrohost_audio PRIVATE ${WARN})
    target_link_libraries(retrohost_audio PUBLIC retrohost_host oboe::oboe)
    target_link_libraries(retrohost_host PUBLIC android log)
    target_link_libraries(retrohost PRIVATE retrohost_gl retrohost_audio)
    # 16 KB page-size compatibility (required for new Play uploads targeting Android 15+).
    target_link_options(retrohost PRIVATE "-Wl,-z,max-page-size=16384")
else ()
    # Linux host build for JVM unit tests: only jni.h is needed, no libjvm.
    if (NOT DEFINED ENV{JAVA_HOME})
        message(FATAL_ERROR "Set JAVA_HOME to build the JNI library for host tests")
    endif ()
    target_include_directories(retrohost PRIVATE $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/linux)
endif ()
