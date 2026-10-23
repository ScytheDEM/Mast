// The only translation unit that includes jni.h. Compiled with -fno-exceptions (see CMakeLists.txt),
// so no C++ exception can cross the JNI boundary; errors are return codes (Ruling AA).
//
// All bulk data crosses as direct ByteBuffers (native byte order, GC-managed on the Kotlin side). The bridge
// validates every buffer's capacity and alignment before use and never retains a pointer past the call.
#include <jni.h>

#include <cstddef>
#include <cstdint>

#include "engine.h"
#include "model/eirp.h"
#include "model/pathloss.h"
#include "render/tile.h"

namespace {

constexpr jint kOk = 0;
constexpr jint kInvalid = -2;  // same code as sa::kRenderInvalid

// Order and element sizes of the carrier buffers passed to nativeRender: CarrierFields in EngineTypes.kt.
constexpr int kFieldCount = 16;
constexpr size_t kFieldSize[kFieldCount] = {8, 8, 4, 4, 4, 4, 4, 4, 4, 4, 4, 1, 4, 4, 4, 2};

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
JNIEXPORT jint JNICALL Java_au_signalatlas_engine_NativeEngine_nativeRender(
    JNIEnv* env, jclass /*clazz*/, jobjectArray fields, jint n, jobject patterns, jint patternCount, jint z, jint x,
    jint y, jint grid, jint area, jboolean indoor, jfloat thrDbm, jobject cancel, jobject outRsrp, jobject outSinr,
    jobject outProb, jobject outBest) {
    if (n < 0 || n > 65535 || patternCount < 0 || area < 0 || area > 3 || fields == nullptr ||
        env->GetArrayLength(fields) != kFieldCount) {
        return kInvalid;
    }
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
    constexpr int64_t px = sa::kTilePixels;
    uint8_t* rsrp = outPtr<uint8_t>(env, outRsrp, px, &ok);
    uint8_t* sinr = outPtr<uint8_t>(env, outSinr, px, &ok);
    uint8_t* prob = outPtr<uint8_t>(env, outProb, px, &ok);
    uint16_t* best = outPtr<uint16_t>(env, outBest, px, &ok);
    if (!ok) return kInvalid;

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
    return static_cast<jint>(sa::renderTile(c, table, p, out, cancelFlag));
}

}  // extern "C"
