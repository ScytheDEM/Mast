cmake_minimum_required(VERSION 3.28)
project(saengine_host LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(FetchContent)
FetchContent_Declare(googletest
    GIT_REPOSITORY https://github.com/google/googletest.git
    GIT_TAG v1.17.0)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

# Shared engine sources (no Android headers). jni_bridge.cpp is Android-only and excluded here.
set(ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../src/main/cpp")
add_library(saengine_core STATIC "${ENGINE_DIR}/engine.cpp")
target_include_directories(saengine_core PUBLIC "${ENGINE_DIR}")
target_compile_options(saengine_core PRIVATE -O3 -Wall -Wextra -Werror)

enable_testing()
include(GoogleTest)
add_executable(saengine_tests test/smoke_test.cpp)
target_link_libraries(saengine_tests PRIVATE saengine_core GTest::gtest_main)
target_compile_options(saengine_tests PRIVATE -Wall -Wextra -Werror)
gtest_discover_tests(saengine_tests)
