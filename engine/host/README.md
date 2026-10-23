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
