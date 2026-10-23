package au.signalatlas.engine

import au.signalatlas.core.data.CarrierBatch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Area class of a tile. The ordinals are the C++ `sa::Area` values (checked by a JVM test). */
enum class AreaClass { DENSE_URBAN, URBAN, SUBURBAN, RURAL }

/**
 * One rendered 256x256 tile, row-major, row 0 = north edge. [rsrp], [sinr] and [prob] hold the unsigned codes of
 * rule 9 (read each byte with `toInt() and 0xFF`). [best] holds, per pixel, the index into the [PreparedCarriers]
 * that served it, as an unsigned 16-bit value (`toInt() and 0xFFFF`); 0xFFFF means no carrier (RSRP code 0).
 */
class RenderedTile(
    val z: Int,
    val x: Int,
    val y: Int,
    val rsrp: ByteArray,
    val sinr: ByteArray,
    val prob: ByteArray,
    val best: ShortArray,
) {
    /** Index into the prepared carriers of the server at [pixel] (0 until 65536), or -1 for none. */
    fun bestCarrier(pixel: Int): Int = (best[pixel].toInt() and 0xFFFF).let { if (it == NO_BEST) -1 else it }

    companion object {
        const val PIXELS = 256 * 256
        const val NO_BEST = 0xFFFF
    }
}

/**
 * Cooperative cancellation of a render. Backed by a direct `IntBuffer(1)` whose address the native renderer polls
 * once per grid row; [cancel] is permanent and may be called from any thread. Use one token per render.
 */
class CancelToken {
    private val bytes: ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    internal val flag: IntBuffer = bytes.asIntBuffer()

    /** The buffer whose address is handed to native code. */
    internal val buffer: ByteBuffer get() = bytes

    val isCancelled: Boolean get() = flag.get(0) != 0

    fun cancel() {
        flag.put(0, 1)
    }
}

/** The native carrier arrays, in the order [NativeEngine.nativeRender] receives them (= ordinal). */
internal enum class CarrierField(val elemBytes: Int) {
    LAT(8), LON(8), H_TX(4), AZ(4), TILT(4), EIRP_RE(4), FREQ_MHZ(4), H_BW(4), FB(4), GAIN(4), R_MAX_KM(4),
    TECH(1), SECTOR_KEY(4), FREQ_KEY(4), HRP_ID(4), HRP_PEAK(2),
}

/** The pattern table uploaded for a [PatternStore][au.signalatlas.core.data.PatternStore]: see [CoverageEngine]. */
internal class PatternUpload(
    val buffer: ByteBuffer,
    val count: Int,
    /** `PatternStore.lookup`. */
    val lookup: (sddIds: LongArray, outPatternId: IntArray, outPeakBearing: ShortArray) -> Unit,
)

/** The per-carrier math done natively in one call (see [NativeEngine.nativeDerive]). */
internal fun interface Derive {
    fun derive(
        n: Int, eirpDbm: FloatArray, bwHz: IntArray, freqMhz: ByteBuffer, hTx: ByteBuffer, tech: ByteBuffer,
        outEirpRe: ByteBuffer, outRMaxKm: ByteBuffer,
    )
}

/**
 * Carriers marshalled for the native renderer: one direct, native-order buffer per [CarrierField], built once per
 * (data version, selection, region). The buffers are read-only after construction, so [CoverageEngine.render] may
 * run concurrently on the same instance from several threads.
 *
 * Carriers the engine could not use (non-finite position, EIRP or frequency, or bandwidth <= 0) are dropped, so
 * indices into this object are not batch rows: [batchIndex] maps them back. Nothing native is allocated (the buffers
 * are garbage-collected direct buffers), so [close] only marks the object unusable; it is idempotent.
 */
class PreparedCarriers internal constructor(
    /** Number of carriers kept. */
    val size: Int,
    /** Rule 7: whether renders add the O2I loss. */
    val indoor: Boolean,
    private val batchRows: IntArray,
    internal val fields: Array<ByteBuffer>,
    internal val patterns: PatternUpload?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    /** The row of the source [CarrierBatch] that carrier [i] (an index in `best`) came from. */
    fun batchIndex(i: Int): Int {
        require(i in 0 until size) { "carrier index $i out of 0 until $size" }
        return batchRows[i]
    }

    internal fun eirpReAt(i: Int): Float = fields[CarrierField.EIRP_RE.ordinal].getFloat(i * 4)
    internal fun rMaxKmAt(i: Int): Float = fields[CarrierField.R_MAX_KM.ordinal].getFloat(i * 4)
    internal fun freqKeyAt(i: Int): Int = fields[CarrierField.FREQ_KEY.ordinal].getInt(i * 4)
    internal fun sectorKeyAt(i: Int): Int = fields[CarrierField.SECTOR_KEY.ordinal].getInt(i * 4)
    internal fun hrpIdAt(i: Int): Int = fields[CarrierField.HRP_ID.ordinal].getInt(i * 4)
    internal fun hrpPeakAt(i: Int): Short = fields[CarrierField.HRP_PEAK.ordinal].getShort(i * 2)

    override fun close() {
        closed.set(true)
    }

    companion object {
        /** The renderer indexes carriers with a uint16 (0xFFFF = none). */
        const val MAX_CARRIERS = 65535

        /** True when the engine can use row [i] of [b]. */
        internal fun isUsable(b: CarrierBatch, i: Int): Boolean =
            b.siteLat[i].isFinite() && b.siteLon[i].isFinite() && b.eirpDbm[i].isFinite() &&
                b.freqMhz[i].isFinite() && b.bandwidthHz[i] > 0

        /**
         * Stable key of (network, frequency to 0.1 MHz): carriers of different sectors with equal keys interfere
         * (rule 8). Pure arithmetic, so it is the same across runs and processes (no hashCode).
         */
        internal fun freqKey(network: Int, freqMhz: Float): Int {
            val tenths = (freqMhz.toDouble() * 10.0).roundToInt().coerceIn(0, FREQ_KEY_MASK)
            return ((network and 0xFF) shl FREQ_KEY_BITS) or tenths
        }

        private const val FREQ_KEY_BITS = 21
        private const val FREQ_KEY_MASK = (1 shl FREQ_KEY_BITS) - 1

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        internal fun build(batch: CarrierBatch, patterns: PatternUpload?, indoor: Boolean, derive: Derive): PreparedCarriers {
            val total = batch.size
            require(total <= MAX_CARRIERS) {
                "batch of $total carriers exceeds the engine's limit of $MAX_CARRIERS; prepare per region"
            }
            var m = 0
            val rows = IntArray(total)
            for (i in 0 until total) if (isUsable(batch, i)) rows[m++] = i
            val kept = if (m == total) rows else rows.copyOf(m)

            val f = Array(CarrierField.entries.size) { direct(m * CarrierField.entries[it].elemBytes) }
            fun buf(c: CarrierField) = f[c.ordinal]
            val lat = buf(CarrierField.LAT).asDoubleBuffer()
            val lon = buf(CarrierField.LON).asDoubleBuffer()
            val hTx = buf(CarrierField.H_TX).asFloatBuffer()
            val az = buf(CarrierField.AZ).asFloatBuffer()
            val tilt = buf(CarrierField.TILT).asFloatBuffer()
            val freq = buf(CarrierField.FREQ_MHZ).asFloatBuffer()
            val hBw = buf(CarrierField.H_BW).asFloatBuffer()
            val fb = buf(CarrierField.FB).asFloatBuffer()
            val gain = buf(CarrierField.GAIN).asFloatBuffer()
            val tech = buf(CarrierField.TECH)
            val sector = buf(CarrierField.SECTOR_KEY).asIntBuffer()
            val fKey = buf(CarrierField.FREQ_KEY).asIntBuffer()
            val hrpIdBuf = buf(CarrierField.HRP_ID).asIntBuffer()
            val hrpPeakBuf = buf(CarrierField.HRP_PEAK).asShortBuffer()

            val eirpDbm = FloatArray(m)
            val bwHz = IntArray(m)
            val sdd = LongArray(m)
            for (k in 0 until m) {
                val r = kept[k]
                lat.put(k, batch.siteLat[r])
                lon.put(k, batch.siteLon[r])
                hTx.put(k, batch.heightM[r])
                az.put(k, batch.azimuthDeg[r])
                tilt.put(k, batch.tiltDeg[r])
                freq.put(k, batch.freqMhz[r])
                hBw.put(k, batch.hBeamwidthDeg[r])
                fb.put(k, batch.frontToBackDb[r])
                gain.put(k, batch.gainDbi[r])
                tech.put(k, batch.tech[r])
                sector.put(k, batch.sectorId[r])
                fKey.put(k, freqKey(batch.network[r].toInt(), batch.freqMhz[r]))
                eirpDbm[k] = batch.eirpDbm[r]
                bwHz[k] = batch.bandwidthHz[r]
                sdd[k] = batch.sddId[r]
            }
            if (patterns != null && m > 0) {
                val ids = IntArray(m)
                val peaks = ShortArray(m)
                patterns.lookup(sdd, ids, peaks)
                hrpIdBuf.put(ids)
                hrpPeakBuf.put(peaks)
            }
            if (m > 0) {
                derive.derive(m, eirpDbm, bwHz, buf(CarrierField.FREQ_MHZ), buf(CarrierField.H_TX), tech,
                    buf(CarrierField.EIRP_RE), buf(CarrierField.R_MAX_KM))
            }
            return PreparedCarriers(m, indoor, kept, f, patterns)
        }
    }
}
