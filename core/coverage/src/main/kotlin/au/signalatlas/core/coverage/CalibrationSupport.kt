package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.model.CalibrationRules
import au.signalatlas.core.model.CalibrationSnapshot
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.ResidualCell
import au.signalatlas.core.model.SectorCorrection
import au.signalatlas.engine.ResidualRaster
import au.signalatlas.engine.SectorTerms
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * What one [CalibrationSnapshot] means for tiles (M4 Calibration rules 4-6, Review Focus 5).
 *
 * Calibration is versioned per z8 region (the level-0 prepared region: its z8 tile, carriers from 80 km around): a
 * region's [regionVersion] hashes the applied sector estimates (n >= 3) of the sites the region's carriers can come
 * from and the applied residual cells (n >= 3) touching its tile, and is 0 when none apply. A snapshot that changes
 * nothing in a region leaves its version, and so its tile keys, alone: only affected regions re-render.
 *
 * Instances are immutable views of one snapshot; [CalibrationSupport.viewOf] caches the current one.
 */
internal class CalibrationView(val snapshot: CalibrationSnapshot) {
    private val sectorsByRegion = HashMap<Long, MutableList<SectorCorrection>>()
    private val cellsByRegion = HashMap<Long, MutableList<ResidualCell>>()
    private val versions = ConcurrentHashMap<Long, Long>()

    init {
        for (s in snapshot.sectors.values) {
            if (!s.isApplied || !s.siteLat.isFinite() || !s.siteLon.isFinite()) continue
            // Every z8 tile whose region bounds (tile + margin) contain the site.
            val reach = TileMath.expand(GeoBounds(s.siteLat, s.siteLon, s.siteLat, s.siteLon), REGION_MARGIN_M)
            forZ8TilesIn(reach) { rx, ry ->
                if (TileMath.expand(TileMath.bounds(REGION_ZOOM, rx, ry), REGION_MARGIN_M).contains(s.siteLat, s.siteLon)) {
                    sectorsByRegion.getOrPut(regionId(rx, ry)) { ArrayList() } += s
                }
            }
        }
        for (c in snapshot.residuals) {
            if (!c.isApplied) continue
            forZ8TilesIn(cellBounds(c)) { rx, ry -> cellsByRegion.getOrPut(regionId(rx, ry)) { ArrayList() } += c }
        }
    }

    /** The calibration version of the z8 region containing tile (z, x, y); 0 below z8 (never calibrated). */
    fun regionVersion(z: Int, x: Int, y: Int): Long {
        if (z < REGION_ZOOM) return 0L
        val d = z - REGION_ZOOM
        val id = regionId(x shr d, y shr d)
        return versions.getOrPut(id) { hash(sectorsByRegion[id].orEmpty(), cellsByRegion[id].orEmpty()) }
    }

    /**
     * Rule 4 terms per batch row for the applied sectors of [batch]; null when none applies (uncalibrated prepare). An
     * estimate applies only to a carrier at the site it was learned at: sector ids are tower-DB row ids, so after a DB
     * update an id may name another sector, which must not inherit the estimate.
     */
    fun termsFor(batch: CarrierBatch): ((Int) -> SectorTerms?)? {
        val applied = snapshot.sectors.filterValues { it.isApplied }
        fun at(row: Int): SectorCorrection? = applied[batch.sectorId[row]]?.takeIf {
            abs(it.siteLat - batch.siteLat[row]) < SAME_SITE_DEG && abs(it.siteLon - batch.siteLon[row]) < SAME_SITE_DEG
        }
        if (applied.isEmpty() || (0 until batch.size).none { at(it) != null }) return null
        return { row -> at(row)?.let(::terms) }
    }

    /** Applied sector estimate of [sectorId], if any. */
    fun appliedSector(sectorId: Int): SectorCorrection? = snapshot.sectors[sectorId]?.takeIf { it.isApplied }

    /**
     * Rule 5 rasters for tile (z, x, y): one per network with applied cells under the tile, each pixel holding
     * `n / (n + 5) * mean` of the cell containing its centre (0.25 dB codes, clamped to int8). Empty when none.
     */
    fun rasters(z: Int, x: Int, y: Int): List<ResidualRaster> {
        if (z < REGION_ZOOM) return emptyList()
        val d = z - REGION_ZOOM
        val cells = cellsByRegion[regionId(x shr d, y shr d)] ?: return emptyList()
        val tile = TileMath.bounds(z, x, y)
        val byNetwork = HashMap<Int, HashMap<Long, Double>>()
        for (c in cells) {
            val b = cellBounds(c)
            if (b.north < tile.south || b.south > tile.north || b.east < tile.west || b.west > tile.east) continue
            byNetwork.getOrPut(c.network) { HashMap() }[cellKey(c.latIdx, c.lonIdx)] = c.appliedDb
        }
        if (byNetwork.isEmpty()) return emptyList()
        val rowLat = IntArray(256) { CalibrationRules.cellIndex(TileMath.latAt(z, y + (it + 0.5) / 256.0)) }
        val colLon = IntArray(256) { CalibrationRules.cellIndex(TileMath.lonAt(z, x + (it + 0.5) / 256.0)) }
        return byNetwork.entries.sortedBy { it.key }.take(ResidualRaster.MAX_PER_TILE).mapNotNull { (network, values) ->
            if (network !in 0 until 32) return@mapNotNull null
            val codes = ByteArray(256 * 256)
            var any = false
            for (py in 0 until 256) {
                for (px in 0 until 256) {
                    val v = values[cellKey(rowLat[py], colLon[px])] ?: continue
                    val code = ResidualRaster.code(v)
                    codes[py * 256 + px] = code
                    any = any || code.toInt() != 0
                }
            }
            if (any) ResidualRaster(1 shl network, codes) else null
        }
    }

    private companion object {
        const val REGION_ZOOM = CoverageTileService.MIN_ZOOM
        const val REGION_MARGIN_M = CoverageTileService.REGION_MARGIN_M
        const val SAME_SITE_DEG = 1e-5  // about 1 m

        fun regionId(rx: Int, ry: Int): Long = (rx.toLong() shl 32) or (ry.toLong() and 0xFFFFFFFFL)
        fun cellKey(latIdx: Int, lonIdx: Int): Long = (latIdx.toLong() shl 32) or (lonIdx.toLong() and 0xFFFFFFFFL)

        fun cellBounds(c: ResidualCell) = GeoBounds(
            c.latIdx * CalibrationRules.CELL_DEG, c.lonIdx * CalibrationRules.CELL_DEG,
            (c.latIdx + 1) * CalibrationRules.CELL_DEG, (c.lonIdx + 1) * CalibrationRules.CELL_DEG,
        )

        fun terms(s: SectorCorrection) = SectorTerms(
            s.offsetDb.toFloat(), s.slopeDbPerDecade.toFloat(), s.dMedianKm.toFloat(), s.sigmaScale.toFloat(),
        )

        /** Calls [f] for every z8 tile intersecting [b]. */
        inline fun forZ8TilesIn(b: GeoBounds, f: (Int, Int) -> Unit) {
            val nw = TileMath.locate(b.north.coerceAtMost(85.0), b.west, REGION_ZOOM) ?: return
            val se = TileMath.locate(b.south.coerceAtLeast(-85.0), b.east, REGION_ZOOM) ?: return
            for (rx in nw.x..se.x) for (ry in nw.y..se.y) f(rx, ry)
        }

        /** FNV-1a 64 over the region's applied terms in a fixed order; 0 only when nothing applies. */
        fun hash(sectors: List<SectorCorrection>, cells: List<ResidualCell>): Long {
            if (sectors.isEmpty() && cells.isEmpty()) return 0L
            var h = -0x340d631b7bdddcdbL
            fun mix(v: Long) {
                var x = v
                repeat(8) {
                    h = (h xor (x and 0xFF)) * 0x100000001b3L
                    x = x ushr 8
                }
            }
            for (s in sectors.sortedBy { it.sectorId }) {
                mix(s.sectorId.toLong()); mix(s.n.toLong()); mix(s.offsetDb.toRawBits())
                mix(s.slopeDbPerDecade.toRawBits()); mix(s.dMedianKm.toRawBits())
            }
            mix(-1L)
            for (c in cells.sortedWith(compareBy({ it.network }, { it.latIdx }, { it.lonIdx }))) {
                mix(c.network.toLong()); mix(cellKey(c.latIdx, c.lonIdx)); mix(c.n.toLong()); mix(c.meanDb.toRawBits())
            }
            return if (h == 0L) 1L else h
        }
    }
}

/** The current [CalibrationView] of a snapshot flow, rebuilt once per snapshot version. */
internal class CalibrationSupport(private val source: () -> CalibrationSnapshot) {
    @Volatile private var view = CalibrationView(CalibrationSnapshot.EMPTY)

    fun current(): CalibrationView {
        val snap = source()
        val v = view
        if (v.snapshot === snap) return v
        return synchronized(this) {
            view.takeIf { it.snapshot === snap } ?: CalibrationView(snap).also { view = it }
        }
    }
}

/** Fractional-tile Web-Mercator coordinates (the pixel centres of a residual raster). */
internal fun TileMath.latAt(z: Int, yFrac: Double): Double =
    Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1.0 - 2.0 * yFrac / (1L shl z)))))

internal fun TileMath.lonAt(z: Int, xFrac: Double): Double = xFrac / (1L shl z) * 360.0 - 180.0
