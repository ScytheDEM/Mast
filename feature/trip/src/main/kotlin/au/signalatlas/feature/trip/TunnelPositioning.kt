package au.signalatlas.feature.trip

import au.signalatlas.core.beacons.SnapTarget
import au.signalatlas.core.model.LatLon
import au.signalatlas.core.roads.RoadGraph
import au.signalatlas.core.roads.RoadMatch
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Tunnel positioning (M7): while the vehicle is matched to an `is_tunnel` road and GPS stops (no fix for [LOSS_MS]) or
 * is poor (accuracy over [GOOD_ACCURACY_M]), positions are synthesised along the road geometry at the last smoothed
 * speed: constant for [HOLD_MS] after the last good fix, then decaying (time constant [DECAY_TAU_MS]) so the estimate
 * stops rather than running away, and nothing at all after [GIVE_UP_MS]. A good fix ends it at once. A beacon
 * [SnapTarget] pulls the estimate toward the beacon's position along the road. Not thread-safe: callers synchronise.
 */
class TunnelPositioning {
    private var lastGood: TripFix? = null
    private var lastGoodMatch: RoadMatch? = null
    private var speedMps = 0.0

    private var path: List<LatLon>? = null
    private var pathStartMs = 0L
    private var snapOffsetM = 0.0
    private var drOnTunnel = false

    /** A dead-reckoned estimate is being produced (the UI shows the "estimated" dot). */
    val active: Boolean get() = path != null

    fun reset() {
        lastGood = null; lastGoodMatch = null; speedMps = 0.0
        clearEstimate()
    }

    private fun clearEstimate() {
        path = null; snapOffsetM = 0.0; drOnTunnel = false
    }

    /** Whether the position is inside a tunnel: matched to a tunnel road, GPS lost less than [TUNNEL_LOSS_MS] ago, or estimated along one. */
    fun inTunnel(nowMs: Long): Boolean {
        if (active) return drOnTunnel
        val g = lastGood ?: return false
        return lastGoodMatch?.isTunnel == true && nowMs - g.timeMs < TUNNEL_LOSS_MS
    }

    /**
     * A real fix from the service. Returns true when it should be used as the position; false for a poor fix while in a
     * tunnel context (it is ignored, the estimate carries on). A good fix is matched to the road and ends any estimate.
     */
    fun onFix(fix: TripFix, graph: RoadGraph?, headingDeg: Double, fallbackSpeedMps: Double): Boolean {
        val good = fix.accuracyM <= GOOD_ACCURACY_M
        if (!good) return !(active || lastGoodMatch?.isTunnel == true)
        val s = fix.speedMps ?: fallbackSpeedMps
        speedMps = if (lastGood == null) s else 0.5 * speedMps + 0.5 * s
        lastGood = fix
        lastGoodMatch = graph?.match(fix.lat, fix.lon, headingDeg)
        clearEstimate()
        return true
    }

    /** The estimated fix at [nowMs], or null when GPS is fine, the vehicle is not in a tunnel or the estimate has run out. */
    fun tick(nowMs: Long, graph: RoadGraph?, snap: SnapTarget?): TripFix? {
        val g = lastGood ?: return null
        val m = lastGoodMatch ?: return null
        graph ?: return null
        if (!m.isTunnel) return null
        val gap = nowMs - g.timeMs
        if (gap < LOSS_MS) return null
        if (gap > GIVE_UP_MS) {
            clearEstimate()
            return null
        }
        val p = path ?: buildPath(g, m, graph).also { path = it; pathStartMs = g.timeMs }
        if (p.size < 2) return null
        val travelled = distanceAfter(gap) + snapOffsetM
        if (snap != null) applySnap(p, travelled, snap)
        val along = (distanceAfter(gap) + snapOffsetM).coerceIn(0.0, (p.size - 1) * STEP_M)
        val pos = interpolate(p, along)
        drOnTunnel = graph.nearestOnRoad(pos.lat, pos.lon, 15.0, tunnelOnly = true) != null
        return TripFix(nowMs, pos.lat, pos.lon, min(MAX_ESTIMATE_ACCURACY_M, MIN_ESTIMATE_ACCURACY_M + 0.02 * along), speedAfter(gap))
    }

    /** Metres travelled [elapsedMs] after the last good fix: constant speed for [HOLD_MS], then exponentially decaying. */
    internal fun distanceAfter(elapsedMs: Long): Double {
        val held = min(elapsedMs, HOLD_MS) / 1000.0
        val extra = (elapsedMs - HOLD_MS).coerceAtLeast(0) / 1000.0
        val tau = DECAY_TAU_MS / 1000.0
        return speedMps * held + speedMps * tau * (1 - exp(-extra / tau))
    }

    private fun speedAfter(elapsedMs: Long): Double =
        if (elapsedMs <= HOLD_MS) speedMps else speedMps * exp(-(elapsedMs - HOLD_MS) / DECAY_TAU_MS.toDouble())

    private fun buildPath(g: TripFix, m: RoadMatch, graph: RoadGraph): List<LatLon> =
        listOf(LatLon(g.lat, g.lon)) + graph.follow(m, PATH_M, STEP_M).points

    /** Moves the estimate toward the beacon's projection onto the path by [SnapTarget.strength] of the way. */
    private fun applySnap(p: List<LatLon>, travelled: Double, t: SnapTarget) {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in p.indices) {
            val d = flatDistanceM(p[i], t.position)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0 || bestD > MAX_SNAP_M) return
        val target = best * STEP_M
        snapOffsetM += t.strength * (target - travelled)
    }

    private fun interpolate(p: List<LatLon>, alongM: Double): LatLon {
        val i = min((alongM / STEP_M).toInt(), p.size - 2)
        val f = (alongM - i * STEP_M) / STEP_M
        return LatLon(p[i].lat + (p[i + 1].lat - p[i].lat) * f, p[i].lon + (p[i + 1].lon - p[i].lon) * f)
    }

    private fun flatDistanceM(a: LatLon, b: LatLon): Double {
        val cos = Math.cos(Math.toRadians(a.lat))
        return hypot(Math.toRadians(b.lon - a.lon) * cos * R, Math.toRadians(b.lat - a.lat) * R)
    }

    companion object {
        const val GOOD_ACCURACY_M = 50.0
        const val LOSS_MS = 2_500L
        const val TUNNEL_LOSS_MS = 10_000L
        const val HOLD_MS = 30_000L
        const val DECAY_TAU_MS = 10_000L
        const val GIVE_UP_MS = 120_000L
        const val MIN_ESTIMATE_ACCURACY_M = 60.0
        const val MAX_ESTIMATE_ACCURACY_M = 150.0
        const val STEP_M = 5.0
        const val PATH_M = 3_000.0
        private const val MAX_SNAP_M = 100.0
        private const val R = 6_371_000.0
    }
}
