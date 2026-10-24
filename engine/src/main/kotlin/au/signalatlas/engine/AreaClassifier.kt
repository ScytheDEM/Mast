package au.signalatlas.engine

import au.signalatlas.core.data.CarrierBatch

/**
 * Rule 5: area class from the number of sites (any network) within 3 km. Amended 2026-10-24 after the tile-seam bug:
 * the count is taken around each carrier's *site* (the site itself included), never around a tile centre, so a
 * carrier keeps one path-loss model on every tile it reaches and adjacent tiles agree along their shared edge.
 */
object AreaClassifier {
    const val RADIUS_M = 3000.0

    fun classify(siteCountWithin3km: Int): AreaClass = when {
        siteCountWithin3km >= 40 -> AreaClass.DENSE_URBAN
        siteCountWithin3km >= 12 -> AreaClass.URBAN
        siteCountWithin3km >= 3 -> AreaClass.SUBURBAN
        else -> AreaClass.RURAL
    }

    /**
     * The class of every row of [batch], by batch row: [siteCountWithin] (lat, lon, radius m) is asked once per distinct
     * site position. Carriers with a non-finite position get none (they are never rendered).
     */
    fun perSite(batch: CarrierBatch, siteCountWithin: (lat: Double, lon: Double, radiusM: Double) -> Int): Array<AreaClass?> {
        val bySite = HashMap<Pair<Double, Double>, AreaClass>()
        return Array(batch.size) { r ->
            val lat = batch.siteLat[r]
            val lon = batch.siteLon[r]
            if (!lat.isFinite() || !lon.isFinite()) return@Array null
            bySite.getOrPut(lat to lon) { classify(siteCountWithin(lat, lon, RADIUS_M)) }
        }
    }
}
