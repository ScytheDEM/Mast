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

# Catch UB and memory errors in the engine and its tests.
add_compile_options(-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
add_link_options(-fsanitize=address,undefined)

# Shared engine sources (no Android headers). jni_bridge.cpp is Android-only and excluded here.
set(ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../src/main/cpp")
add_library(saengine_core STATIC
    "${ENGINE_DIR}/engine.cpp"
    "${ENGINE_DIR}/model/eirp.cpp"
    "${ENGINE_DIR}/model/antenna.cpp"
    "${ENGINE_DIR}/model/pathloss.cpp"
    "${ENGINE_DIR}/render/tile.cpp"
    "${ENGINE_DIR}/terrain/bullington.cpp"
    "${ENGINE_DIR}/terrain/polar_table.cpp")
target_include_directories(saengine_core PUBLIC "${ENGINE_DIR}")
target_compile_options(saengine_core PRIVATE -O3 -Wall -Wextra -Werror -fno-exceptions)  # Ruling AA

enable_testing()
include(GoogleTest)
add_executable(saengine_tests
    test/smoke_test.cpp
    test/eirp_test.cpp
    test/antenna_test.cpp
    test/pathloss_test.cpp
    test/tile_test.cpp
    test/robustness_test.cpp
    test/terrain_test.cpp)
target_compile_definitions(saengine_tests PRIVATE
    SA_VECTOR_DIR="${CMAKE_CURRENT_SOURCE_DIR}/test/vectors")
target_link_libraries(saengine_tests PRIVATE saengine_core GTest::gtest_main)
target_compile_options(saengine_tests PRIVATE -Wall -Wextra -Werror)
gtest_discover_tests(saengine_tests)

# Micro-benchmark (Release, no sanitizers; engine sources compiled directly so they are not instrumented).
# Run: engine/host/build/saengine_bench
add_executable(saengine_bench
    bench/bench.cpp
    "${ENGINE_DIR}/model/antenna.cpp"
    "${ENGINE_DIR}/model/pathloss.cpp")
target_include_directories(saengine_bench PRIVATE "${ENGINE_DIR}")
target_compile_options(saengine_bench PRIVATE -O3 -fno-exceptions -fno-sanitize=address,undefined)
target_link_options(saengine_bench PRIVATE -fno-sanitize=address,undefined)

# Tile renderer benchmark (Release, no sanitizers). Run: engine/host/build/saengine_tile_bench
add_executable(saengine_tile_bench
    bench/tile_bench.cpp
    "${ENGINE_DIR}/model/eirp.cpp"
    "${ENGINE_DIR}/model/antenna.cpp"
    "${ENGINE_DIR}/model/pathloss.cpp"
    "${ENGINE_DIR}/render/tile.cpp")
target_include_directories(saengine_tile_bench PRIVATE "${ENGINE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/test")
target_compile_definitions(saengine_tile_bench PRIVATE
    SA_VECTOR_DIR="${CMAKE_CURRENT_SOURCE_DIR}/test/vectors")
target_compile_options(saengine_tile_bench PRIVATE -O3 -fno-exceptions -fno-sanitize=address,undefined)
target_link_options(saengine_tile_bench PRIVATE -fno-sanitize=address,undefined)

# Terrain polar-table benchmark (Release, no sanitizers). Run: engine/host/build/saengine_terrain_bench
add_executable(saengine_terrain_bench
    bench/terrain_bench.cpp
    "${ENGINE_DIR}/terrain/bullington.cpp"
    "${ENGINE_DIR}/terrain/polar_table.cpp")
target_include_directories(saengine_terrain_bench PRIVATE "${ENGINE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/test")
target_compile_definitions(saengine_terrain_bench PRIVATE
    SA_VECTOR_DIR="${CMAKE_CURRENT_SOURCE_DIR}/test/vectors")
target_compile_options(saengine_terrain_bench PRIVATE -O3 -fno-exceptions -fno-sanitize=address,undefined)
target_link_options(saengine_terrain_bench PRIVATE -fno-sanitize=address,undefined)
