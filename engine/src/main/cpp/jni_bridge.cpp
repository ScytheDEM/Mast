// The only translation unit that includes jni.h. Compiled with -fno-exceptions (see CMakeLists.txt),
// so no C++ exception can cross the JNI boundary; errors are return codes (Ruling AA).
//
// All bulk data crosses as direct ByteBuffers (native byte order, GC-managed on the Kotlin side). The bridge
// validates every buffer's capacity and alignment before use and never retains a pointer past the call.
#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <new>

#include "engine.h"
#include "model/eirp.h"
#include "model/pathloss.h"
#include "render/tile.h"
#include "terrain/dem_grid.h"
#include "terrain/polar_table.h"

namespace {

constexpr jint kOk = 0;
constexpr jint kInvalid = -2;  // same code as sa::kRenderInvalid

// Order and element sizes of the carrier buffers passed to nativeRender: CarrierFields in EngineTypes.kt.
constexpr int kFieldCount = 17;
constexpr size_t kFieldSize[kFieldCount] = {8, 8, 4, 4, 4, 4, 4, 4, 4, 4, 4, 1, 4, 4, 4, 2, 4};
constexpr int kTerrainSlotField = 16;  // int32 per carrier: 0 = no terrain table, k = pool entry k - 1

// Returns the buffer's address when it is a direct buffer with at least `count * elem` bytes aligned to `elem`.
// A zero-length request is always satisfied (`*ok` stays true and nullptr is returned).
template <typename T>
const T* inPtr(JNIEnv* env, jobject buf, int64_t count, bool* ok) {
    if (count == 0) return nullptr;
    if (buf == nullptr) { *ok = false; return nullptr; }
    void* p = env->GetDirectBufferAddress(buf);
    const jlong cap = env->GetDirectBufferCapacity(buf);
    if (p == nullptr || cap < 0 || static_cast<int64_t>(cap) < count * static_cast<int64_t>(sizeof(T)) ||
        reinterpret_cast<uintptr_t>(p) % alignof(T) != 0) {
        *ok = false;
        return nullptr;
    }
    return static_cast<const T*>(p);
}

template <typename T>
T* outPtr(JNIEnv* env, jobject buf, int64_t count, bool* ok) {
    return const_cast<T*>(inPtr<T>(env, buf, count, ok));
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_au_signalatlas_engine_NativeEngine_version(JNIEnv* /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(sa_engine_version());
}

// Per-carrier derived values: eirpRe (rule 2) and rMaxKm (rule 9, maximum over the four area classes).
JNIEXPORT jint JNICALL Java_au_signalatlas_engine_NativeEngine_nativeDerive(
    JNIEnv* env, jclass /*clazz*/, jint n, jfloatArray eirpDbm, jintArray bwHz, jobject freqMhz, jobject hTx,
    jobject tech, jobject outEirpRe, jobject outRMaxKm) {
    if (n < 0) return kInvalid;
    if (n == 0) return kOk;
    if (eirpDbm == nullptr || bwHz == nullptr || env->GetArrayLength(eirpDbm) < n || env->GetArrayLength(bwHz) < n) {
        return kInvalid;
    }
    bool ok = true;
    const float* f = inPtr<float>(env, freqMhz, n, &ok);
    const float* h = inPtr<float>(env, hTx, n, &ok);
    const uint8_t* t = inPtr<uint8_t>(env, tech, n, &ok);
    float* eRe = outPtr<float>(env, outEirpRe, n, &ok);
    float* rMax = outPtr<float>(env, outRMaxKm, n, &ok);
    if (!ok) return kInvalid;
    jfloat* e = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(eirpDbm, nullptr));
    if (e == nullptr) return kInvalid;
    jint* b = static_cast<jint*>(env->GetPrimitiveArrayCritical(bwHz, nullptr));
    if (b == nullptr) {
        env->ReleasePrimitiveArrayCritical(eirpDbm, e, JNI_ABORT);
        return kInvalid;
    }
    // No JNI calls between Get and Release (critical section).
    for (jint i = 0; i < n; ++i) {
        const sa::Tech tc = t[i] == 1 ? sa::Tech::NR : sa::Tech::LTE;
        eRe[i] = sa::eirpPerRe(e[i], tc, f[i], b[i]);
        rMax[i] = sa::cullingRangeKm(eRe[i], f[i], h[i]);
    }
    env->ReleasePrimitiveArrayCritical(bwHz, b, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(eirpDbm, e, JNI_ABORT);
    return kOk;
}

// Renders one tile. Returns the number of carriers evaluated (>= 0), -1 when cancelled, -2 for bad arguments
// (including a buffer that is too small or misaligned). The output buffers are 256*256 elements each.
//
// Terrain: `tables` / `masks` are the distinct polar tables (kPolarTableCells bytes) and coverage masks
// (kPolarMaskBytes) of the prepared carriers, same length; the carrier field kTerrainSlotField holds per carrier 0
// (no terrain) or k (pool entry k - 1). The pool is resolved to pointers here on every call (a handful of JNI
// lookups); the Kotlin side keeps the buffers strongly reachable for the life of the prepared carriers.
// `outStats` (nullable) receives two int32: pixels covered (RSRP code != 0) and, among them, terrain-incomplete.
JNIEXPORT jint JNICALL Java_au_signalatlas_engine_NativeEngine_nativeRender(
    JNIEnv* env, jclass /*clazz*/, jobjectArray fields, jint n, jobjectArray tables, jobjectArray masks,
    jobject patterns, jint patternCount, jint z, jint x, jint y, jint grid, jint area, jboolean indoor, jfloat thrDbm,
    jobject cancel, jobject outRsrp, jobject outSinr, jobject outProb, jobject outBest, jobject outStats) {
    if (n < 0 || n > 65535 || patternCount < 0 || area < 0 || area > 3 || fields == nullptr ||
        env->GetArrayLength(fields) != kFieldCount || tables == nullptr || masks == nullptr) {
        return kInvalid;
    }
    const jsize poolSize = env->GetArrayLength(tables);
    if (poolSize < 0 || poolSize != env->GetArrayLength(masks)) return kInvalid;
    const void* ptr[kFieldCount] = {};
    bool ok = true;
    for (int k = 0; k < kFieldCount; ++k) {
        jobject buf = env->GetObjectArrayElement(fields, k);
        const int64_t count = n;
        // Alignment and size by the field's element size.
        switch (kFieldSize[k]) {
            case 8: ptr[k] = inPtr<double>(env, buf, count, &ok); break;
            case 4: ptr[k] = inPtr<float>(env, buf, count, &ok); break;
            case 2: ptr[k] = inPtr<int16_t>(env, buf, count, &ok); break;
            default: ptr[k] = inPtr<uint8_t>(env, buf, count, &ok); break;
        }
        env->DeleteLocalRef(buf);
    }
    const uint8_t* att = inPtr<uint8_t>(env, patterns, static_cast<int64_t>(patternCount) * 360, &ok);
    const int32_t* cancelFlag = inPtr<int32_t>(env, cancel, cancel == nullptr ? 0 : 1, &ok);
    int32_t* stats = outPtr<int32_t>(env, outStats, outStats == nullptr ? 0 : 2, &ok);
    constexpr int64_t px = sa::kTilePixels;
    uint8_t* rsrp = outPtr<uint8_t>(env, outRsrp, px, &ok);
    uint8_t* sinr = outPtr<uint8_t>(env, outSinr, px, &ok);
    uint8_t* prob = outPtr<uint8_t>(env, outProb, px, &ok);
    uint16_t* best = outPtr<uint16_t>(env, outBest, px, &ok);
    if (!ok) return kInvalid;

    // Terrain pool -> per-carrier pointers (only when some carrier has a slot).
    std::unique_ptr<const uint8_t*[]> poolTable, poolMask, carTable, carMask;  // NOLINT: new[] below is nothrow
    const int32_t* slot = static_cast<const int32_t*>(ptr[kTerrainSlotField]);
    bool anySlot = false;
    for (jint i = 0; i < n && slot != nullptr; ++i) {
        if (slot[i] < 0 || slot[i] > poolSize) return kInvalid;
        anySlot = anySlot || slot[i] != 0;
    }
    if (anySlot) {
        poolTable.reset(new (std::nothrow) const uint8_t*[static_cast<size_t>(poolSize)]);
        poolMask.reset(new (std::nothrow) const uint8_t*[static_cast<size_t>(poolSize)]);
        carTable.reset(new (std::nothrow) const uint8_t*[static_cast<size_t>(n)]);
        carMask.reset(new (std::nothrow) const uint8_t*[static_cast<size_t>(n)]);
        if (!poolTable || !poolMask || !carTable || !carMask) return kInvalid;
        for (jsize k = 0; k < poolSize; ++k) {
            jobject tb = env->GetObjectArrayElement(tables, k);
            jobject mb = env->GetObjectArrayElement(masks, k);
            bool tok = true;
            poolTable[k] = inPtr<uint8_t>(env, tb, sa::kPolarTableCells, &tok);
            poolMask[k] = inPtr<uint8_t>(env, mb, sa::kPolarMaskBytes, &tok);
            env->DeleteLocalRef(tb);
            env->DeleteLocalRef(mb);
            if (!tok) return kInvalid;
        }
        for (jint i = 0; i < n; ++i) {
            carTable[i] = slot[i] == 0 ? nullptr : poolTable[slot[i] - 1];
            carMask[i] = slot[i] == 0 ? nullptr : poolMask[slot[i] - 1];
        }
    }

    sa::CarrierArrays c{};
    c.n = n;
    c.lat = static_cast<const double*>(ptr[0]);
    c.lon = static_cast<const double*>(ptr[1]);
    c.hTx = static_cast<const float*>(ptr[2]);
    c.az = static_cast<const float*>(ptr[3]);
    c.tilt = static_cast<const float*>(ptr[4]);
    c.eirpRe = static_cast<const float*>(ptr[5]);
    c.freqMhz = static_cast<const float*>(ptr[6]);
    c.hBw = static_cast<const float*>(ptr[7]);
    c.fb = static_cast<const float*>(ptr[8]);
    c.gain = static_cast<const float*>(ptr[9]);
    c.rMaxKm = static_cast<const float*>(ptr[10]);
    c.tech = static_cast<const uint8_t*>(ptr[11]);
    c.sectorKey = static_cast<const int32_t*>(ptr[12]);
    c.freqKey = static_cast<const int32_t*>(ptr[13]);
    c.hrpId = static_cast<const int32_t*>(ptr[14]);
    c.hrpPeak = static_cast<const int16_t*>(ptr[15]);
    c.terrainTable = carTable.get();
    c.terrainMask = carMask.get();
    const sa::PatternTable table{patternCount, att};
    sa::RenderParams p{};
    p.z = z;
    p.x = x;
    p.y = y;
    p.grid = grid;
    p.area = static_cast<sa::Area>(area);
    p.indoor = indoor != JNI_FALSE;
    p.thrDbm = thrDbm;
    sa::TileOut out{rsrp, sinr, prob, best};
    if (stats != nullptr) {
        out.terrainCoveredPx = &stats[0];
        out.terrainIncompletePx = &stats[1];
    }
    return static_cast<jint>(sa::renderTile(c, table, p, out, cancelFlag));
}

// Builds one polar terrain table (terrain/polar_table.h) from a DEM mosaic. `dem` holds rows * cols int16 (native
// order, row 0 = south edge); node (r, c) lies at latitude (southIdx + r) / 1200 and longitude (westIdx + c) / 1200
// degrees. `outTable` / `outMask` are kPolarTableCells / kPolarMaskBytes bytes. Returns 0, -1 when `cancel` (nullable
// 4-byte buffer, polled once per radial) became non-zero (outputs zeroed), or -2 for bad arguments (outputs untouched).
JNIEXPORT jint JNICALL Java_au_signalatlas_engine_NativeEngine_nativeBuildTerrain(
    JNIEnv* env, jclass /*clazz*/, jobject dem, jint southIdx, jint westIdx, jint rows, jint cols, jdouble lat,
    jdouble lon, jfloat hTxAgl, jfloat freqMhz, jobject outTable, jobject outMask, jobject cancel) {
    if (rows < 2 || cols < 2) return kInvalid;
    bool ok = true;
    const int16_t* h = inPtr<int16_t>(env, dem, static_cast<int64_t>(rows) * cols, &ok);
    uint8_t* table = outPtr<uint8_t>(env, outTable, sa::kPolarTableCells, &ok);
    uint8_t* mask = outPtr<uint8_t>(env, outMask, sa::kPolarMaskBytes, &ok);
    const int32_t* cancelFlag = inPtr<int32_t>(env, cancel, cancel == nullptr ? 0 : 1, &ok);
    if (!ok || h == nullptr) return kInvalid;
    const sa::DemGrid grid{southIdx / 1200.0, westIdx / 1200.0, 1.0 / 1200.0, rows, cols, h};
    return static_cast<jint>(sa::buildPolarTable(grid, lat, lon, hTxAgl, freqMhz, table, mask, cancelFlag));
}

}  // extern "C"
