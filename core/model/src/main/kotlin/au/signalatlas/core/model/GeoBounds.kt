package au.signalatlas.core.model

data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** Inclusive on all edges; no antimeridian handling (Australia only). */
    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east
}
