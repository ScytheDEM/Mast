package au.signalatlas.core.coverage

import au.signalatlas.engine.AreaClass

/** Rule 5: area class from the number of sites within 3 km of a tile centre. */
object AreaClassifier {
    const val RADIUS_M = 3000.0

    fun classify(siteCountWithin3km: Int): AreaClass = when {
        siteCountWithin3km >= 40 -> AreaClass.DENSE_URBAN
        siteCountWithin3km >= 12 -> AreaClass.URBAN
        siteCountWithin3km >= 3 -> AreaClass.SUBURBAN
        else -> AreaClass.RURAL
    }
}
