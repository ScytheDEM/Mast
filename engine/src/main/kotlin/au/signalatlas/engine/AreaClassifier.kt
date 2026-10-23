package au.signalatlas.engine

/** Rule 5: area class from the number of sites within 3 km of a tile centre (or of a calibration fix). */
object AreaClassifier {
    const val RADIUS_M = 3000.0

    fun classify(siteCountWithin3km: Int): AreaClass = when {
        siteCountWithin3km >= 40 -> AreaClass.DENSE_URBAN
        siteCountWithin3km >= 12 -> AreaClass.URBAN
        siteCountWithin3km >= 3 -> AreaClass.SUBURBAN
        else -> AreaClass.RURAL
    }
}
