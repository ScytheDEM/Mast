package au.signalatlas.core.data

import au.signalatlas.core.model.GeoBounds
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal const val EARTH_RADIUS_M = 6_371_008.8

internal fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

/**
 * Uniform lat/lon cell index over a fixed set of points (immutable after construction, safe for
 * concurrent readers). Points are identified by their row index in the arrays given to the constructor.
 * No antimeridian handling (Australia only).
 */
class GridIndex(
    private val lats: DoubleArray,
    private val lons: DoubleArray,
    private val cellDeg: Double = 0.1,
) {
    private val n = lats.size
    private val minLat: Double
    private val minLon: Double
    private val rows: Int
    private val cols: Int
    private val maxAbsLat: Double

    /** CSR layout: points of cell c are cellPoints[cellStart[c] until cellStart[c + 1]]. */
    private val cellStart: IntArray
    private val cellPoints: IntArray

    init {
        require(lats.size == lons.size) { "lats and lons differ in size" }
        require(cellDeg > 0) { "cellDeg must be positive" }
        var loLat = Double.MAX_VALUE; var hiLat = -Double.MAX_VALUE
        var loLon = Double.MAX_VALUE; var hiLon = -Double.MAX_VALUE
        for (i in 0 until n) {
            loLat = min(loLat, lats[i]); hiLat = max(hiLat, lats[i])
            loLon = min(loLon, lons[i]); hiLon = max(hiLon, lons[i])
        }
        if (n == 0) { loLat = 0.0; hiLat = 0.0; loLon = 0.0; hiLon = 0.0 }
        minLat = loLat
        minLon = loLon
        maxAbsLat = max(abs(loLat), abs(hiLat))
        rows = floor((hiLat - loLat) / cellDeg).toInt() + 1
        cols = floor((hiLon - loLon) / cellDeg).toInt() + 1
        val counts = IntArray(rows * cols + 1)
        val cellOf = IntArray(n)
        for (i in 0 until n) {
            val c = row(lats[i]) * cols + col(lons[i])
            cellOf[i] = c
            counts[c + 1]++
        }
        for (c in 0 until rows * cols) counts[c + 1] += counts[c]
        cellStart = counts
        cellPoints = IntArray(n)
        val fill = counts.copyOf(rows * cols)
        for (i in 0 until n) cellPoints[fill[cellOf[i]]++] = i
    }

    private fun row(lat: Double) = floor((lat - minLat) / cellDeg).toInt().coerceIn(0, rows - 1)
    private fun col(lon: Double) = floor((lon - minLon) / cellDeg).toInt().coerceIn(0, cols - 1)

    /**
     * Writes the row indices of all points inside [b] (edges inclusive) into [out], ascending, and
     * returns how many. [out] must hold at least as many entries as there are points.
     */
    fun query(b: GeoBounds, out: IntArray): Int {
        if (n == 0 || b.south > b.north || b.west > b.east) return 0
        if (b.north < minLat || b.south > minLat + rows * cellDeg) return 0
        if (b.east < minLon || b.west > minLon + cols * cellDeg) return 0
        val r0 = row(b.south); val r1 = row(b.north)
        val c0 = col(b.west); val c1 = col(b.east)
        var count = 0
        for (r in r0..r1) {
            val base = r * cols
            for (c in c0..c1) {
                for (k in cellStart[base + c] until cellStart[base + c + 1]) {
                    val i = cellPoints[k]
                    if (b.contains(lats[i], lons[i])) out[count++] = i
                }
            }
        }
        Arrays.sort(out, 0, count)
        return count
    }

    /**
     * Up to [k] accepted points nearest to (lat, lon) by haversine distance, nearest first, found by
     * scanning rings of cells outward and stopping once no unscanned ring can beat the k-th best.
     */
    fun nearest(lat: Double, lon: Double, k: Int, accept: (Int) -> Boolean): IntArray {
        if (n == 0 || k <= 0) return IntArray(0)
        val qr = row(lat)
        val qc = col(lon)
        // Max-heap of the best k (distance, index) found so far.
        val heapD = DoubleArray(k)
        val heapI = IntArray(k)
        var size = 0

        fun offer(i: Int) {
            if (!accept(i)) return
            val d = haversineM(lat, lon, lats[i], lons[i])
            if (size < k) {
                var pos = size++
                while (pos > 0) {
                    val parent = (pos - 1) / 2
                    if (heapD[parent] >= d) break
                    heapD[pos] = heapD[parent]; heapI[pos] = heapI[parent]; pos = parent
                }
                heapD[pos] = d; heapI[pos] = i
            } else if (d < heapD[0]) {
                var pos = 0
                while (true) {
                    var child = 2 * pos + 1
                    if (child >= size) break
                    if (child + 1 < size && heapD[child + 1] > heapD[child]) child++
                    if (heapD[child] <= d) break
                    heapD[pos] = heapD[child]; heapI[pos] = heapI[child]; pos = child
                }
                heapD[pos] = d; heapI[pos] = i
            }
        }

        fun scanCell(r: Int, c: Int) {
            if (r < 0 || r >= rows || c < 0 || c >= cols) return
            val cell = r * cols + c
            for (p in cellStart[cell] until cellStart[cell + 1]) offer(cellPoints[p])
        }

        val maxRing = max(max(qr, rows - 1 - qr), max(qc, cols - 1 - qc))
        var ring = 0
        while (ring <= maxRing) {
            if (ring == 0) scanCell(qr, qc) else {
                for (c in qc - ring..qc + ring) { scanCell(qr - ring, c); scanCell(qr + ring, c) }
                for (r in qr - ring + 1..qr + ring - 1) { scanCell(r, qc - ring); scanCell(r, qc + ring) }
            }
            // Any point in a ring beyond this one is at least `ring` whole cells away in lat or lon.
            if (size == k && unscannedLowerBoundM(lat, ring) > heapD[0]) break
            ring++
        }
        val order = (0 until size).sortedWith(compareBy({ heapD[it] }, { heapI[it] }))
        return IntArray(size) { heapI[order[it]] }
    }

    /** Lower bound on the distance to any point not within [ring] cells of the query's cell. */
    private fun unscannedLowerBoundM(qLat: Double, ring: Int): Double {
        val gapRad = Math.toRadians(ring * cellDeg)
        val latBound = EARTH_RADIUS_M * gapRad
        // hav(d) >= cos(lat1) * cos(lat2) * sin^2(dLon / 2) for a pure longitude gap.
        val cc = cos(Math.toRadians(qLat)) * cos(Math.toRadians(maxAbsLat))
        val lonBound = if (cc <= 0.0 || gapRad >= PI) 0.0
        else 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(cc) * sin(gapRad / 2)))
        return min(latBound, lonBound)
    }
}
