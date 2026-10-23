package au.signalatlas.engine

import java.nio.ByteBuffer

/**
 * JNI entry points of the native coverage engine (`libsaengine.so`). Only [CoverageEngine] and
 * [PreparedCarriers] call these; every buffer is a direct, native-order [ByteBuffer] and is validated
 * (capacity, alignment) on the native side, which reports a bad argument as -2 and never throws.
 */
object NativeEngine {
    init {
        System.loadLibrary("saengine")
    }

    /** The engine's C ABI version (`sa_engine_version`). */
    @JvmStatic
    external fun version(): Int

    /**
     * Fills [outEirpRe] (rule 2) and [outRMaxKm] (rule 9, maximum over the four area classes) for the first
     * [n] carriers. [eirpDbm] and [bwHz] are plain arrays (read in a critical section); the rest are direct
     * buffers of floats / bytes. Returns 0, or -2 on a bad argument.
     */
    @JvmStatic
    external fun nativeDerive(
        n: Int, eirpDbm: FloatArray, bwHz: IntArray, freqMhz: ByteBuffer, hTx: ByteBuffer, tech: ByteBuffer,
        outEirpRe: ByteBuffer, outRMaxKm: ByteBuffer,
    ): Int

    /**
     * Renders one tile from [fields] (one buffer per [CarrierField], in ordinal order). [patterns] is null or
     * `patternCount * 360` bytes; [cancel] is null or a 4-byte buffer the engine polls. The outputs are direct
     * buffers of 65536 bytes (rsrp, sinr, prob) and 131072 bytes (best, uint16). Returns the number of
     * carriers evaluated (>= 0), -1 when cancelled, or -2 for bad arguments.
     */
    @JvmStatic
    external fun nativeRender(
        fields: Array<ByteBuffer>, n: Int, patterns: ByteBuffer?, patternCount: Int, z: Int, x: Int, y: Int,
        grid: Int, area: Int, indoor: Boolean, thrDbm: Float, cancel: ByteBuffer?,
        outRsrp: ByteBuffer, outSinr: ByteBuffer, outProb: ByteBuffer, outBest: ByteBuffer,
    ): Int
}
