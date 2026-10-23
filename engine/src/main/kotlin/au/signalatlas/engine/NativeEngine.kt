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
     * Renders one tile from [fields] (one buffer per [CarrierField], in ordinal order). [tables] / [masks] are the
     * distinct terrain tables of the carriers (same length, 92160 / 11520 bytes each; empty = none), which the
     * `TERRAIN_SLOT` field indexes (0 = none, k = entry k - 1). [patterns] is null or `patternCount * 360` bytes;
     * [cancel] is null or a 4-byte buffer the engine polls. The outputs are direct buffers of 65536 bytes (rsrp,
     * sinr, prob) and 131072 bytes (best, uint16); [outStats] (null or 8 bytes) receives two int32: pixels covered
     * (RSRP code != 0) and, among them, pixels whose best server had no complete terrain lookup. Returns the
     * number of carriers evaluated (>= 0), -1 when cancelled, or -2 for bad arguments. [residuals] (at most 32
     * direct buffers of 65536 int8 codes) and [residualMasks] (same length) are the tile's residual rasters.
     */
    @JvmStatic
    external fun nativeRender(
        fields: Array<ByteBuffer>, n: Int, tables: Array<ByteBuffer>, masks: Array<ByteBuffer>,
        patterns: ByteBuffer?, patternCount: Int, z: Int, x: Int, y: Int,
        grid: Int, area: Int, indoor: Boolean, thrDbm: Float, residuals: Array<ByteBuffer>, residualMasks: IntArray,
        cancel: ByteBuffer?,
        outRsrp: ByteBuffer, outSinr: ByteBuffer, outProb: ByteBuffer, outBest: ByteBuffer, outStats: ByteBuffer?,
    ): Int

    /**
     * Predicts every prepared carrier's RSRP at (lat, lon) (same model as [nativeRender], no culling, no residual
     * raster) into [outRsrp] (n floats; NaN = unusable carrier). Returns n, or -2 for bad arguments.
     */
    @JvmStatic
    external fun nativePredictPoint(
        fields: Array<ByteBuffer>, n: Int, tables: Array<ByteBuffer>, masks: Array<ByteBuffer>,
        patterns: ByteBuffer?, patternCount: Int, lat: Double, lon: Double, area: Int, indoor: Boolean,
        outRsrp: ByteBuffer,
    ): Int

    /**
     * Builds one polar terrain table (92160 bytes) and its mask (11520 bytes) from the int16 mosaic [dem] (native
     * order, `rows * cols` samples, row 0 = south; node (r, c) at ((southIdx + r) / 1200, (westIdx + c) / 1200)
     * degrees). [hTxAgl] and [freqMhz] are the table-key height and frequency. Returns 0, -1 when [cancel] (null or
     * a 4-byte buffer polled once per radial) fired (outputs zeroed), or -2 for bad arguments.
     */
    @JvmStatic
    external fun nativeBuildTerrain(
        dem: ByteBuffer, southIdx: Int, westIdx: Int, rows: Int, cols: Int, lat: Double, lon: Double,
        hTxAgl: Float, freqMhz: Float, outTable: ByteBuffer, outMask: ByteBuffer, cancel: ByteBuffer?,
    ): Int
}
