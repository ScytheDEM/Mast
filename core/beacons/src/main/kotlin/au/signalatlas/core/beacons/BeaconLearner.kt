package au.signalatlas.core.beacons

import au.signalatlas.core.model.LatLon
import kotlin.math.max
import kotlin.math.pow

/**
 * Learns where beacons are from good GPS: each sighting made while the fix accuracy is <= [MAX_FIX_ACCURACY_M] and the RSSI
 * >= [MIN_RSSI] adds its fix to the beacon's centroid, weighted by received power relative to [MIN_RSSI] (10^(dB/10)), so
 * the position converges on where the signal peaked. Imported (CSV) positions are authoritative and never moved.
 */
class BeaconLearner(private val store: BeaconRepository) {
    /** Applies one sighting made at [fix]; returns whether it was used. */
    fun learn(sighting: BeaconSighting, fix: LatLon, fixAccuracyM: Double): Boolean {
        if (!(fixAccuracyM <= MAX_FIX_ACCURACY_M) || sighting.rssi < MIN_RSSI) return false
        val known = store.get(sighting.id)
        if (known?.source == KnownBeacon.Source.IMPORTED) return false
        val w = weight(sighting.rssi)
        val updated = if (known == null) {
            KnownBeacon(sighting.id, fix, KnownBeacon.Source.LEARNED, w, 1, sighting.rssi)
        } else {
            val total = known.weightSum + w
            KnownBeacon(
                sighting.id,
                LatLon(
                    (known.pos.lat * known.weightSum + fix.lat * w) / total,
                    (known.pos.lon * known.weightSum + fix.lon * w) / total,
                ),
                KnownBeacon.Source.LEARNED, total, known.n + 1, max(known.peakRssi, sighting.rssi),
            )
        }
        store.put(updated)
        return true
    }

    companion object {
        const val MAX_FIX_ACCURACY_M = 15.0
        const val MIN_RSSI = -75

        /** Linear power above the [MIN_RSSI] floor: 1 at -75 dBm, 10 at -65, 100 at -55. */
        fun weight(rssi: Int): Double = 10.0.pow((rssi - MIN_RSSI) / 10.0)
    }
}

/** Where to pull the position to: the strongest known beacon, projected onto the road. */
data class SnapTarget(val beaconId: String, val rssi: Int, val position: LatLon, val strength: Double)

/**
 * Positioning from beacons: among the sightings in [windowMs] the strongest with RSSI >= [MIN_RSSI] whose position is known,
 * its position projected onto the road by [project] (null leaves the beacon's own position). [strength] in 0.5..1 grows with
 * the RSSI (0.5 at -70 dBm, 1 at -60 and above) and is how far the dead-reckoned position moves toward the target.
 */
class BeaconSnapper(private val positions: BeaconPositions, private val windowMs: Long = 5_000L) {
    private val seen = HashMap<String, BeaconSighting>()

    fun onSighting(s: BeaconSighting) {
        synchronized(seen) { seen[s.id] = s }
    }

    fun clear() = synchronized(seen) { seen.clear() }

    fun target(nowMs: Long, project: (LatLon) -> LatLon?): SnapTarget? {
        val best = synchronized(seen) {
            seen.values.removeAll { nowMs - it.timeMs > windowMs }
            seen.values.filter { it.rssi >= MIN_RSSI && positions.get(it.id) != null }.maxByOrNull { it.rssi }
        } ?: return null
        val pos = positions.get(best.id)!!.pos
        return SnapTarget(best.id, best.rssi, project(pos) ?: pos, strength(best.rssi))
    }

    companion object {
        const val MIN_RSSI = -70

        fun strength(rssi: Int): Double = ((rssi + 80) / 20.0).coerceIn(0.5, 1.0)
    }
}
