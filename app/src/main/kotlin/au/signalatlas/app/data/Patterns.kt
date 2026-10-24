package au.signalatlas.app.data

import au.signalatlas.core.data.PatternRepository

/** One transmitter's ACMA horizontal pattern: [attenuation] is 360 bytes in 0.25 dB steps, relative to [peakBearing]. */
class HrpPattern(val attenuation: ByteArray, val peakBearing: Int) {
    init { require(attenuation.size == 360) { "pattern needs 360 bytes" } }
}

/** Read-only access to the installed antenna patterns, for the site sheet's radiation plot. */
fun interface PatternAccess {
    /** The pattern of the licensed device [sddId], or null when none is installed or the device has none. */
    fun hrp(sddId: Long): HrpPattern?

    companion object {
        val None = PatternAccess { null }
    }
}

class RepositoryPatternAccess(private val repo: PatternRepository) : PatternAccess {
    override fun hrp(sddId: Long): HrpPattern? {
        val store = repo.patterns.value ?: return null
        val id = IntArray(1)
        val peak = ShortArray(1)
        store.lookup(longArrayOf(sddId), id, peak)
        if (id[0] <= 0) return null
        val from = (id[0] - 1) * 360
        if (from + 360 > store.patternBytes.size) return null
        return HrpPattern(store.patternBytes.copyOfRange(from, from + 360), peak[0].toInt())
    }
}
