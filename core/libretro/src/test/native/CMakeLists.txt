# Linux-only test harness for the libretro host. Built and run by the Gradle
# task :core:libretro:nativeHostTest (and used by the JVM JNI tests).
cmake_minimum_required(VERSION 3.22)
project(retrohost_tests LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp retrohost)

# A fake libretro core that reports what it receives through its video output.
add_library(testcore SHARED testcore.cpp)
target_include_directories(testcore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
set_target_properties(testcore PROPERTIES PREFIX "")

add_executable(host_tests host_tests.cpp)
target_link_libraries(host_tests PRIVATE retrohost_host pthread)
target_compile_options(host_tests PRIVATE -Wall -Wextra -Werror)
