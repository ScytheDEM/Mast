package au.signalatlas.core.acma

import kotlin.math.log10

/** Power-unit conversion for Import rule 5. Units are matched exactly (case-sensitive: `mW` is not `MW`). */
object UnitConversions {
    /** Linear units whose non-positive values are unusable (and, for `EIRP`, count as "not supplied"). */
    val LINEAR_UNITS: Set<String> = setOf("W", "mW", "kW")

    /** [value] in [unit] as dBm; null for an unrecognised unit or a linear value <= 0. */
    fun toDbm(value: Double, unit: String): Double? = when (unit) {
        "W" -> if (value > 0) 10 * log10(value) + 30 else null
        "mW" -> if (value > 0) 10 * log10(value) else null
        "kW" -> if (value > 0) 10 * log10(value) + 60 else null
        "dBW" -> value + 30
        "dBm" -> value
        else -> null
    }
}
