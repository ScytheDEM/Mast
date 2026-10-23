package au.signalatlas.engine

/** JNI entry points of the native coverage engine (`libsaengine.so`). */
object NativeEngine {
    init {
        System.loadLibrary("saengine")
    }

    /** The engine's C ABI version (`sa_engine_version`). */
    @JvmStatic
    external fun version(): Int
}
