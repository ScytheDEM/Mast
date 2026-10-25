package au.signalatlas.car

import kotlin.math.roundToInt

/** The strings of the signal panel and the Signal screen; pure, shared by both flavours. */
object PanelText {
    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun header(sim: CarSim): String = "SIM ${sim.slot} · ${sim.carrier}"

    /** "5G+ · n78" or "LTE-A · B28"; null when neither is known. */
    fun tech(sim: CarSim): String? =
        listOfNotNull(sim.label?.text, sim.band).joinToString(" · ").ifEmpty { null }

    /** "CA ×3 · 60 MHz"; null without aggregation (one carrier or nothing reported). */
    fun ca(sim: CarSim): String? = sim.ca?.takeIf { it.isAggregated }?.text

    fun signal(sim: CarSim): String? = sim.rsrpDbm?.let { "$it dBm" }

    /** "1.2 km NE"; null when the serving tower is not known. */
    fun tower(sim: CarSim): String? {
        val d = sim.towerDistanceM ?: return null
        val b = sim.towerBearingDeg ?: return distance(d)
        return "${distance(d)} ${compass(b)}"
    }

    fun distance(m: Double): String = when {
        m < 1000 -> "${(m / 10.0).roundToInt() * 10} m"
        m < 10_000 -> "%.1f km".format(java.util.Locale.US, m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    fun compass(deg: Double): String = COMPASS[(((deg % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8]

    /** 0 to 4 bars from the serving RSRP. */
    fun bars(rsrpDbm: Int?): Int = when {
        rsrpDbm == null -> 0
        rsrpDbm >= -85 -> 4
        rsrpDbm >= -95 -> 3
        rsrpDbm >= -105 -> 2
        rsrpDbm >= -115 -> 1
        else -> 0
    }

    /** The second panel line: CA, signal and tower joined with a separator; falls back to the note. */
    fun detail(sim: CarSim): String {
        val parts = listOfNotNull(ca(sim), signal(sim), tower(sim))
        return if (parts.isEmpty()) sim.note ?: "Searching…" else parts.joinToString("  ·  ")
    }

    /** The Signal screen's measured-versus-predicted line. */
    fun predicted(sim: CarSim): String? {
        val measured = sim.rsrpDbm ?: return null
        val predicted = sim.predictedDbm ?: return null
        val diff = measured - predicted
        val sign = if (diff > 0) "+" else ""
        return "Measured $measured · predicted $predicted dBm ($sign$diff)"
    }
}
