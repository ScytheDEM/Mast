package au.signalatlas.engine

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.data.PatternStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/**
 * Kotlin face of the native coverage engine. Stateless apart from the pattern-table upload cache, and thread-safe:
 * [render] may be called concurrently from several threads, including on the same [PreparedCarriers].
 */
class CoverageEngine {
    // One direct buffer per PatternStore instance (identity: PatternStore does not override equals). Prepared
    // carriers keep a strong reference to the buffer they use, so a store dropped from here cannot free a table
    // that is still rendering.
    private val patternBuffers = WeakHashMap<PatternStore, ByteBuffer>()

    /**
     * Marshals [batch] for rendering (rules 1, 2, 9): direct buffers, eirpRe and rMax computed natively, HRP
     * pattern ids and peak bearings from [patterns] (null = parametric patterns only), sector key = the batch's
     * sector id, frequency key from (network, frequency). Unusable carriers are dropped, see [PreparedCarriers].
     *
     * [terrain] (null = the quick model everywhere) is asked once per kept carrier for its terrain table, with the
     * carrier's row in [batch] (not its index among the kept carriers, which the caller cannot know in advance); null
     * means that carrier has none. Carriers may share a [TerrainTable] (same object), which is then uploaded once.
     * The tables are held by the result, so they stay alive for as long as it can render.
     *
     * @throws IllegalArgumentException if the batch has more than [PreparedCarriers.MAX_CARRIERS] carriers (callers
     * prepare per region) or the store's pattern bytes are not `patternCount * 360` long.
     */
    fun prepare(
        batch: CarrierBatch, patterns: PatternStore?, indoor: Boolean,
        terrain: ((batchRow: Int) -> TerrainTable?)? = null,
    ): PreparedCarriers = PreparedCarriers.build(batch, patterns?.let(::upload), indoor, NATIVE_DERIVE, terrain)

    /**
     * Builds the polar terrain table of one site (Terrain rule 6) from [dem]: 360 radials x 256 range bins of
     * `L_terr`, about 15-150 ms of native work on the calling thread (call it off the main thread). [hTxAgl] and
     * [freqMhz] must be the table-key values ([TerrainTable.heightKeyM], [TerrainTable.frequencyKeyMhz]); carriers
     * sharing a key share the table. Cells whose profile leaves [dem] are masked and render with the quick model.
     * Returns null when [cancel] fired.
     *
     * @throws IllegalArgumentException if the site is not finite, [dem] is incomplete (see [DemGridHandle.missing]),
     * or the native stage rejects the grid.
     * @throws IllegalStateException if [dem] is closed.
     */
    fun buildTerrainTable(
        dem: DemGridHandle, siteLat: Double, siteLon: Double, hTxAgl: Float, freqMhz: Float, cancel: CancelToken,
    ): TerrainTable? {
        check(!dem.isClosed) { "DemGridHandle is closed" }
        require(dem.isComplete) { "DEM is missing chunks ${dem.missing}" }
        require(siteLat.isFinite() && siteLon.isFinite()) { "site ($siteLat, $siteLon) is not finite" }
        val out = TerrainTable.allocate()
        val r = NativeEngine.nativeBuildTerrain(
            dem.samples, dem.southIdx, dem.westIdx, dem.rows, dem.cols, siteLat, siteLon, hTxAgl, freqMhz,
            out.table, out.mask, cancel.buffer,
        )
        if (r == CANCELLED) return null
        require(r == 0) { "invalid terrain arguments ($r)" }
        return out
    }

    private fun upload(store: PatternStore): PatternUpload {
        require(store.patternBytes.size.toLong() == store.patternCount.toLong() * 360) {
            "pattern table is ${store.patternBytes.size} bytes, expected ${store.patternCount} * 360"
        }
        val buffer = synchronized(patternBuffers) {
            patternBuffers.getOrPut(store) {
                ByteBuffer.allocateDirect(store.patternBytes.size).order(ByteOrder.nativeOrder()).also {
                    it.put(store.patternBytes)
                    it.rewind()
                }
            }
        }
        return PatternUpload(buffer, store.patternCount, store::lookup)
    }

    /**
     * Renders tile (z, x, y) of [prepared] for [area], with coverage threshold [thrDbm] (rule 8). The grid is 128
     * for z <= 13 and 256 above (rule 9). Returns null when [cancel] fired.
     *
     * Output goes through per-thread direct scratch buffers (320 KiB, reused by every render on that thread) and
     * is then copied into fresh arrays: no JNI critical section is held while rendering, so a long render never
     * stalls the garbage collector, and native memory does not grow with the number of renders.
     *
     * @throws IllegalArgumentException for an invalid tile address or argument.
     * @throws IllegalStateException if [prepared] is closed.
     */
    fun render(
        prepared: PreparedCarriers, z: Int, x: Int, y: Int, area: AreaClass, thrDbm: Float, cancel: CancelToken,
    ): RenderedTile? {
        check(!prepared.isClosed) { "PreparedCarriers is closed" }
        val s = scratch.get()!!
        val patterns = prepared.patterns
        val grid = if (z <= 13) 128 else 256
        val r = NativeEngine.nativeRender(
            prepared.fields, prepared.size, prepared.terrainTables, prepared.terrainMasks, patterns?.buffer,
            patterns?.count ?: 0, z, x, y, grid, area.ordinal, prepared.indoor, thrDbm, cancel.buffer,
            s.rsrp, s.sinr, s.prob, s.best, s.stats,
        )
        if (r == CANCELLED) return null
        require(r >= 0) { "invalid render arguments: z=$z x=$x y=$y area=$area" }
        val rsrp = ByteArray(RenderedTile.PIXELS)
        val sinr = ByteArray(RenderedTile.PIXELS)
        val prob = ByteArray(RenderedTile.PIXELS)
        val best = ShortArray(RenderedTile.PIXELS)
        // Absolute positions are irrelevant to native code; only these Kotlin-side reads use the cursor.
        s.rsrp.rewind(); s.rsrp.get(rsrp)
        s.sinr.rewind(); s.sinr.get(sinr)
        s.prob.rewind(); s.prob.get(prob)
        s.best.rewind(); s.best.asShortBuffer().get(best)
        val stats = s.stats.asIntBuffer()
        return RenderedTile(z, x, y, rsrp, sinr, prob, best, stats.get(0), stats.get(1))
    }

    private class Scratch {
        val rsrp = direct(RenderedTile.PIXELS)
        val sinr = direct(RenderedTile.PIXELS)
        val prob = direct(RenderedTile.PIXELS)
        val best = direct(RenderedTile.PIXELS * 2)
        val stats = direct(8)
    }

    private companion object {
        const val CANCELLED = -1

        fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        val scratch: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

        val NATIVE_DERIVE = Derive { n, eirpDbm, bwHz, freqMhz, hTx, tech, outEirpRe, outRMaxKm ->
            val r = NativeEngine.nativeDerive(n, eirpDbm, bwHz, freqMhz, hTx, tech, outEirpRe, outRMaxKm)
            require(r == 0) { "native prepare rejected its arguments ($r)" }
        }
    }
}
