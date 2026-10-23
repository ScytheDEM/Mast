# Engine host build

`run-tests.sh` configures, builds and runs the GoogleTest suite on macOS (ASan + UBSan, `-fno-exceptions` engine).
It also builds two Release benchmarks without sanitizers:

- `build/saengine_bench`: per-carrier antenna and path-loss micro-benchmark (Task 5).
- `build/saengine_tile_bench`: tile renderer (Task 6). z13 tile 7489/4954, grid 128, single thread, the Canberra vector's
  carriers replicated with small position offsets. Median of 15 runs.

## Tile renderer benchmark (Apple M1 Max, Apple clang 17, `-O3`, 2026-10-23)

| Case | Median |
|---|---|
| 109 carriers (the vector as is) | 11.2 ms |
| 300 carriers, replicas shifted as whole sites (9 sites) | 31.6 ms |
| 300 carriers, every carrier its own site (worst case for site sharing) | 35.2 ms |
| 300 carriers, replicas, indoor | 32.0 ms |

Target: < 60 ms for 300 carriers. For comparison, evaluating every carrier at every point (no bounds) took 109 ms.

## Terrain polar-table benchmark (Apple M1 Max, Apple clang 17, `-O3`, 2026-10-23)

`build/saengine_terrain_bench`: one 360 × 256 table, single thread, median of 15 runs.

| Case | Median |
|---|---|
| synthetic rough DEM, every radial reaches 80 km, 30 m / 1800 MHz | 21.8 ms |
| same, 3 m / 700 MHz (mostly obstructed) | 20.9 ms |
| same, 45 m / 3500 MHz | 21.1 ms |
| real Canberra GLO-90 crop (radials leave it at 40-50 km), 27 m / 2130 MHz | 13.8 ms |
| `terrainLookup` | 17.6 ns per call |

Target: < 150 ms per 80 km table.
