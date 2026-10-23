// Signal Atlas coverage engine: pure computation over caller-supplied arrays.
// No Android headers here; no file or network access anywhere in the engine.
#ifndef SIGNAL_ATLAS_ENGINE_H
#define SIGNAL_ATLAS_ENGINE_H

#ifdef __cplusplus
extern "C" {
#endif

// Engine ABI version. Bumped when the C ABI changes.
int sa_engine_version(void);

#ifdef __cplusplus
}
#endif

#endif  // SIGNAL_ATLAS_ENGINE_H
