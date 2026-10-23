cmake_minimum_required(VERSION 3.28)
project(saengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(saengine SHARED engine.cpp jni_bridge.cpp)
target_include_directories(saengine PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(saengine PRIVATE -O3 -fvisibility=hidden -Wall -Wextra)
# No exceptions across JNI.
set_source_files_properties(jni_bridge.cpp PROPERTIES COMPILE_OPTIONS "-fno-exceptions")
# 16 KB page-size compatible.
target_link_options(saengine PRIVATE "-Wl,-z,max-page-size=16384")
