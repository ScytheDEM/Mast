package au.signalatlas.core.roads

import au.signalatlas.core.model.LatLon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A position on the graph: [alongM] metres from the edge's first node, travelling [forward] (first to second node) or not. */
class RoadMatch(
    val edge: Int,
    val forward: Boolean,
    val alongM: Double,
    val distanceM: Double,
    val headingDiffDeg: Double,
    /** The matched edge is a tunnel (Protomaps `is_tunnel`). */
    val isTunnel: Boolean = false,
)

/** [points] every `stepM` metres from the matched position, [roadLengthM] being how far the graph itself carried the path (the rest is straight). */
class RoadPath(val points: List<LatLon>, val roadLengthM: Double)

/** Samples of a segment kept in a coarse grid so a point query only looks at nearby segments. */
internal class SegmentIndex(private val cell: Double = 100.0) {
    private val cells = HashMap<Long, MutableList<Int>>()

    private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)

    fun add(id: Int, ax: Double, ay: Double, bx: Double, by: Double) {
        val len = hypot(bx - ax, by - ay)
        val n = max(1, (len / SAMPLE_M).toInt() + 1)
        var last = Long.MIN_VALUE
        for (i in 0..n) {
            val t = i.toDouble() / n
            val k = key(floor((ax + (bx - ax) * t) / cell).toLong(), floor((ay + (by - ay) * t) / cell).toLong())
            if (k != last) { cells.getOrPut(k) { ArrayList() } += id; last = k }
        }
    }

    /** Ids of segments possibly within [r] metres of the point (may repeat none; superset of the true answer). */
    fun query(x: Double, y: Double, r: Double): Set<Int> {
        val out = LinkedHashSet<Int>()
        val reach = r + SAMPLE_M
        for (cx in floor((x - reach) / cell).toLong()..floor((x + reach) / cell).toLong()) {
            for (cy in floor((y - reach) / cell).toLong()..floor((y + reach) / cell).toLong()) cells[key(cx, cy)]?.let { out += it }
        }
        return out
    }

    companion object { const val SAMPLE_M = 20.0 }
}

/**
 * A local road graph: nodes are road vertices (merged within 2 m, which also joins the overlapping buffers of
 * neighbouring tiles), edges the straight pieces between them, with a [RoadClass] rank. Coordinates are metres east and
 * north of ([originLat], [originLon]). Direction of travel (one-way) is not modelled.
 */
class RoadGraph private constructor(
    val originLat: Double,
    val originLon: Double,
    private val nx: DoubleArray,
    private val ny: DoubleArray,
    private val ea: IntArray,
    private val eb: IntArray,
    private val erank: IntArray,
    private val elen: DoubleArray,
    private val adj: Array<IntArray>,
    private val index: SegmentIndex,
    private val etunnel: BooleanArray,
    private val tunnelIndex: SegmentIndex,
) {
    private val cosLat = cos(Math.toRadians(originLat))

    val nodeCount: Int get() = nx.size
    val edgeCount: Int get() = ea.size

    /** Number of edges meeting at [node]. */
    fun degree(node: Int): Int = adj[node].size

    fun distanceFromOriginM(lat: Double, lon: Double): Double {
        val (x, y) = toXY(lat, lon)
        return hypot(x, y)
    }

    private fun toXY(lat: Double, lon: Double): Pair<Double, Double> =
        Math.toRadians(lon - originLon) * cosLat * EARTH_R to Math.toRadians(lat - originLat) * EARTH_R

    private fun toLatLon(x: Double, y: Double) =
        LatLon(originLat + Math.toDegrees(y / EARTH_R), originLon + Math.toDegrees(x / (EARTH_R * cosLat.coerceAtLeast(1e-6))))

    /**
     * The edge closest to the point (within [maxDistM]) that runs within [maxHeadingDiffDeg] of [headingDeg] in one of
     * its two directions; among edges within 3 m of the closest the one best aligned with the heading wins.
     */
    fun match(lat: Double, lon: Double, headingDeg: Double, maxDistM: Double = 30.0, maxHeadingDiffDeg: Double = 45.0): RoadMatch? {
        val (px, py) = toXY(lat, lon)
        val found = ArrayList<RoadMatch>()
        for (e in index.query(px, py, maxDistM)) {
            val ax = nx[ea[e]]; val ay = ny[ea[e]]
            val dx = nx[eb[e]] - ax; val dy = ny[eb[e]] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(px - (ax + dx * t), py - (ay + dy * t))
            if (d > maxDistM) continue
            val bearing = Math.toDegrees(atan2(dx, dy))
            val fwdDiff = angleDiff(bearing, headingDeg)
            val backDiff = angleDiff(bearing + 180.0, headingDeg)
            val forward = fwdDiff <= backDiff
            val diff = min(fwdDiff, backDiff)
            if (diff <= maxHeadingDiffDeg) found += RoadMatch(e, forward, t * elen[e], d, diff, etunnel[e])
        }
        val nearest = found.minOfOrNull { it.distanceM } ?: return null
        return found.filter { it.distanceM <= nearest + TIE_M }.minByOrNull { it.headingDiffDeg }
    }

    /** Distance in metres from the point to the nearest tunnel edge, or null when none lies within [maxM]. */
    fun tunnelDistanceM(lat: Double, lon: Double, maxM: Double): Double? {
        val (px, py) = toXY(lat, lon)
        var best = Double.MAX_VALUE
        for (e in tunnelIndex.query(px, py, maxM)) best = min(best, segmentDistance(e, px, py).first)
        return best.takeIf { it <= maxM }
    }

    /**
     * The point on the graph nearest to the given one within [maxM] (only tunnel edges when [tunnelOnly]), with the
     * match for following the road on from it; null when nothing is that close.
     */
    fun nearestOnRoad(lat: Double, lon: Double, maxM: Double, tunnelOnly: Boolean = false): Pair<LatLon, RoadMatch>? {
        val (px, py) = toXY(lat, lon)
        var best: Triple<Int, Double, Double>? = null // edge, distance, t
        for (e in (if (tunnelOnly) tunnelIndex else index).query(px, py, maxM)) {
            val (d, t) = segmentDistance(e, px, py)
            if (d <= maxM && (best == null || d < best.second)) best = Triple(e, d, t)
        }
        val (e, d, t) = best ?: return null
        val x = nx[ea[e]] + (nx[eb[e]] - nx[ea[e]]) * t
        val y = ny[ea[e]] + (ny[eb[e]] - ny[ea[e]]) * t
        return toLatLon(x, y) to RoadMatch(e, true, t * elen[e], d, 0.0, etunnel[e])
    }

    /** Distance from (px, py) to edge [e] and the fraction along it of the closest point. */
    private fun segmentDistance(e: Int, px: Double, py: Double): Pair<Double, Double> {
        val ax = nx[ea[e]]; val ay = ny[ea[e]]
        val dx = nx[eb[e]] - ax; val dy = ny[eb[e]] - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + dx * t), py - (ay + dy * t)) to t
    }

    /**
     * Follows the road from [m] for [lengthM] metres and samples it every [stepM] metres (the first sample [stepM] on).
     * At a junction the continuations (never straight back) whose turn is within 25 degrees of the smallest turn compete,
     * and the highest road class among them wins, the smaller turn on a tie. A loop, a dead end or the edge of the graph
     * ends the road part; the path then continues straight on the last bearing to [lengthM].
     */
    fun follow(m: RoadMatch, lengthM: Double, stepM: Double = 50.0): RoadPath {
        val px = ArrayList<Double>()
        val py = ArrayList<Double>()
        val cum = ArrayList<Double>()
        fun add(x: Double, y: Double) {
            val d = if (px.isEmpty()) 0.0 else hypot(x - px.last(), y - py.last())
            px += x; py += y; cum += (cum.lastOrNull() ?: 0.0) + d
        }
        var e = m.edge
        var fwd = m.forward
        val a0 = ea[e]; val b0 = eb[e]
        val f0 = if (elen[e] == 0.0) 0.0 else m.alongM / elen[e]
        add(nx[a0] + (nx[b0] - nx[a0]) * f0, ny[a0] + (ny[b0] - ny[a0]) * f0)
        val visited = HashSet<Int>()
        visited += e * 2 + (if (fwd) 1 else 0)
        while (cum.last() < lengthM) {
            val to = if (fwd) eb[e] else ea[e]
            add(nx[to], ny[to])
            if (cum.last() >= lengthM) break
            val incoming = tailBearing(px, py, cum, TURN_PROBE_M)
            val next = chooseNext(to, e, incoming) ?: break
            val nfwd = ea[next] == to
            if (!visited.add(next * 2 + (if (nfwd) 1 else 0))) break
            e = next
            fwd = nfwd
        }
        val total = cum.last()
        val end = tailBearing(px, py, cum, TURN_PROBE_M)
        val out = ArrayList<LatLon>()
        var seg = 0
        var k = 1
        while (k * stepM <= lengthM + 1e-6) {
            val d = k * stepM
            if (d >= total) {
                val over = d - total
                out += toLatLon(px.last() + over * sin(Math.toRadians(end)), py.last() + over * cos(Math.toRadians(end)))
            } else {
                while (cum[seg + 1] < d) seg++
                val span = cum[seg + 1] - cum[seg]
                val t = if (span == 0.0) 0.0 else (d - cum[seg]) / span
                out += toLatLon(px[seg] + (px[seg + 1] - px[seg]) * t, py[seg] + (py[seg + 1] - py[seg]) * t)
            }
            k++
        }
        return RoadPath(out, min(total, lengthM))
    }

    private fun chooseNext(node: Int, arrivedBy: Int, incoming: Double): Int? {
        val options = adj[node].filter { it != arrivedBy }
        if (options.isEmpty()) return null
        if (options.size == 1) return options[0]
        val turns = options.associateWith { abs(signedDiff(outBearing(node, it), incoming)) }
        val smallest = turns.values.min()
        return options.filter { turns.getValue(it) <= smallest + CLASS_WINDOW_DEG }
            .maxWithOrNull(compareBy<Int> { erank[it] }.thenBy { -turns.getValue(it) })
    }

    /** Bearing leaving [node] along [edge], read about [TURN_PROBE_M] on through plain two-way vertices. */
    private fun outBearing(node: Int, edge: Int): Double {
        var cur = node
        var e = edge
        var acc = 0.0
        while (true) {
            val nxt = if (ea[e] == cur) eb[e] else ea[e]
            acc += elen[e]
            cur = nxt
            if (acc >= TURN_PROBE_M || adj[cur].size != 2) break
            e = if (adj[cur][0] == e) adj[cur][1] else adj[cur][0]
        }
        return Math.toDegrees(atan2(nx[cur] - nx[node], ny[cur] - ny[node]))
    }

    private fun tailBearing(px: List<Double>, py: List<Double>, cum: List<Double>, probe: Double): Double {
        val n = px.size - 1
        var i = n - 1
        while (i > 0 && cum[n] - cum[i] < probe) i--
        if (i < 0 || (px[n] == px[i] && py[n] == py[i])) return 0.0
        return Math.toDegrees(atan2(px[n] - px[i], py[n] - py[i]))
    }

    companion object {
        private const val EARTH_R = 6_371_000.0
        private const val MERGE_M = 2.0
        private const val SNAP_M = 2.0
        private const val TIE_M = 3.0
        private const val TURN_PROBE_M = 25.0
        private const val CLASS_WINDOW_DEG = 25.0

        /** Absolute difference of two bearings in degrees, 0..180. */
        internal fun angleDiff(a: Double, b: Double): Double = abs(signedDiff(a, b))

        /** a - b wrapped to -180..180. */
        internal fun signedDiff(a: Double, b: Double): Double {
            var d = (a - b) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }

        fun build(originLat: Double, originLon: Double, ways: List<RoadWay>): RoadGraph {
            val cos0 = cos(Math.toRadians(originLat))
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            val cells = HashMap<Long, MutableList<Int>>()
            fun cellKey(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)
            fun node(x: Double, y: Double): Int {
                val cx = floor(x / MERGE_M).toLong()
                val cy = floor(y / MERGE_M).toLong()
                for (i in -1..1) for (j in -1..1) {
                    cells[cellKey(cx + i, cy + j)]?.forEach { if (hypot(xs[it] - x, ys[it] - y) <= MERGE_M) return it }
                }
                xs += x; ys += y
                cells.getOrPut(cellKey(cx, cy)) { ArrayList() } += xs.size - 1
                return xs.size - 1
            }

            val edges = HashMap<Long, Int>()
            val tunnels = HashMap<Long, Boolean>() // an edge is a tunnel only if every way over it is
            fun key(a: Int, b: Int) = (min(a, b).toLong() shl 32) or max(a, b).toLong()
            for (w in ways) {
                var prev = -1
                for (p in w.points) {
                    val n = node(Math.toRadians(p.lon - originLon) * cos0 * EARTH_R, Math.toRadians(p.lat - originLat) * EARTH_R)
                    if (prev >= 0 && n != prev) {
                        edges.merge(key(prev, n), w.roadClass.rank) { p, q -> max(p, q) }
                        tunnels.merge(key(prev, n), w.isTunnel) { p, q -> p && q }
                    }
                    prev = n
                }
            }

            // Snap loose ends (e.g. a tile's clipped end) onto the edge they lie on, so overlapping tiles join up.
            val a = ArrayList<Int>(edges.size)
            val b = ArrayList<Int>(edges.size)
            val r = ArrayList<Int>(edges.size)
            val tn = ArrayList<Boolean>(edges.size)
            val deg = IntArray(xs.size)
            val idx = SegmentIndex()
            for ((k, rank) in edges) {
                val u = (k ushr 32).toInt(); val v = (k and 0xFFFFFFFFL).toInt()
                idx.add(a.size, xs[u], ys[u], xs[v], ys[v])
                a += u; b += v; r += rank; tn += tunnels.getValue(k)
                deg[u]++; deg[v]++
            }
            for (n in xs.indices) {
                if (deg[n] != 1) continue
                for (e in idx.query(xs[n], ys[n], SNAP_M)) {
                    val u = a[e]; val v = b[e]
                    if (u == n || v == n) continue
                    val dx = xs[v] - xs[u]; val dy = ys[v] - ys[u]
                    val len2 = dx * dx + dy * dy
                    if (len2 == 0.0) continue
                    val t = ((xs[n] - xs[u]) * dx + (ys[n] - ys[u]) * dy) / len2
                    if (t <= 0.0 || t >= 1.0 || hypot(xs[n] - (xs[u] + dx * t), ys[n] - (ys[u] + dy * t)) > SNAP_M) continue
                    b[e] = n
                    idx.add(a.size, xs[n], ys[n], xs[v], ys[v])
                    a += n; b += v; r += r[e]; tn += tn[e]
                    break
                }
            }

            val unique = HashMap<Long, Int>()
            val uniqueTunnel = HashMap<Long, Boolean>()
            for (i in a.indices) if (a[i] != b[i]) {
                unique.merge(key(a[i], b[i]), r[i]) { p, q -> max(p, q) }
                uniqueTunnel.merge(key(a[i], b[i]), tn[i]) { p, q -> p && q }
            }
            val m = unique.size
            val fa = IntArray(m); val fb = IntArray(m); val fr = IntArray(m); val fl = DoubleArray(m)
            val ft = BooleanArray(m)
            val index = SegmentIndex()
            val tunnelIndex = SegmentIndex()
            val counts = IntArray(xs.size)
            var i = 0
            for ((k, rank) in unique) {
                val u = (k ushr 32).toInt(); val v = (k and 0xFFFFFFFFL).toInt()
                fa[i] = u; fb[i] = v; fr[i] = rank
                fl[i] = hypot(xs[v] - xs[u], ys[v] - ys[u])
                ft[i] = uniqueTunnel.getValue(k)
                index.add(i, xs[u], ys[u], xs[v], ys[v])
                if (ft[i]) tunnelIndex.add(i, xs[u], ys[u], xs[v], ys[v])
                counts[u]++; counts[v]++
                i++
            }
            val adj = Array(xs.size) { IntArray(counts[it]) }
            val fill = IntArray(xs.size)
            for (e in 0 until m) { adj[fa[e]][fill[fa[e]]++] = e; adj[fb[e]][fill[fb[e]]++] = e }
            return RoadGraph(originLat, originLon, xs.toDoubleArray(), ys.toDoubleArray(), fa, fb, fr, fl, adj, index, ft, tunnelIndex)
        }
    }
}
