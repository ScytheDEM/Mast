cmake_minimum_required(VERSION 3.28)
project(saengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(SA_MODEL_SOURCES model/eirp.cpp model/antenna.cpp model/pathloss.cpp)
add_library(saengine SHARED engine.cpp jni_bridge.cpp ${SA_MODEL_SOURCES})
target_include_directories(saengine PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(saengine PRIVATE -O3 -fvisibility=hidden -Wall -Wextra
    -fno-exceptions)  # Ruling AA: every engine translation unit; the engine never throws.
# 16 KB page-size compatible.
target_link_options(saengine PRIVATE "-Wl,-z,max-page-size=16384")
