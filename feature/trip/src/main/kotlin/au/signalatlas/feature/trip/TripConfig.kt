package au.signalatlas.feature.trip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What counts as losing coverage. NR carriers only for [LOSE_5G]; 3G is gone in Australia, so 4G+ and all data predict alike. */
enum class TechFloor { LOSE_5G, LOSE_4G, LOSE_ALL }

/** The Settings that shape the alerts. */
data class TripConfig(
    /** Warning window in seconds of travel (60, 90 or 120). */
    val windowSeconds: Int = 90,
    /** Dead stretches shorter than this are ignored (300, 500 or 1000 m). */
    val minDeadZoneM: Int = 300,
    val floor: TechFloor = TechFloor.LOSE_ALL,
    val voice: Boolean = false,
) {
    companion object {
        val WINDOWS = listOf(60, 90, 120)
        val MIN_DEAD_ZONES = listOf(300, 500, 1000)
    }
}

interface TripSettings {
    val config: StateFlow<TripConfig>
    fun update(transform: (TripConfig) -> TripConfig)
}

/** In-memory settings (tests, and the default until preferences are wired). */
class MemoryTripSettings(initial: TripConfig = TripConfig()) : TripSettings {
    private val _config = MutableStateFlow(initial)
    override val config: StateFlow<TripConfig> = _config
    override fun update(transform: (TripConfig) -> TripConfig) {
        _config.value = transform(_config.value)
    }
}
