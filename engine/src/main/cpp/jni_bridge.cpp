// The only translation unit that includes jni.h. Compiled with -fno-exceptions (see CMakeLists.txt),
// so no C++ exception can cross the JNI boundary.
#include <jni.h>

#include "engine.h"

extern "C" JNIEXPORT jint JNICALL
Java_au_signalatlas_engine_NativeEngine_version(JNIEnv* /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(sa_engine_version());
}
