package au.signalatlas.app.ui.map

import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.core.update.HrpStatus
import au.signalatlas.core.update.UpdateStatus
import java.time.Instant

data class MapUiState(
    /** True until the repository has looked for an installed DB (avoids a first-run flash). */
    val loading: Boolean = true,
    val hasData: Boolean = false,
    /** Every site matching [filter]; the same list instance until the filter or the DB changes. */
    val sites: List<SiteSummary> = emptyList(),
    val filter: TowerFilter = TowerFilter(),
    val availableBands: List<String> = emptyList(),
    val selectedId: Int? = null,
    val selected: SiteDetail? = null,
    val search: SearchUiState = SearchUiState(),
    val update: UpdateStatus = UpdateStatus.Unknown,
    val basemapKey: String? = null,
    /** True once the basemap key is known or the lookup has taken too long; the map may show. */
    val basemapResolved: Boolean = false,
    val locationGranted: Boolean = false,
    /** False while the device's location services are off (then no fix will arrive). */
    val locationEnabled: Boolean = true,
    val location: Pair<Double, Double>? = null,
    /** Nearest filtered sites with distance in metres, nearest first; empty without permission. */
    val nearby: List<Pair<SiteSummary, Double>> = emptyList(),
    val data: DataSummary? = null,
    val coverage: CoverageUiState = CoverageUiState(),
    /** The settled map zoom; null until the map has reported one. */
    val zoom: Double? = null,
    /** The antenna-pattern import (Updates screen). */
    val patterns: HrpStatus = HrpStatus(null, 0, null),
)

/** The coverage controls and what they produced. */
data class CoverageUiState(
    val on: Boolean = false,
    val network: Network = Network.TELSTRA,
    val fiveGOnly: Boolean = false,
    val indoor: Boolean = false,
    val view: CoverageSelection.View = CoverageSelection.View.RSRP,
    /** The overlay's tile template (null while off). */
    val urlTemplate: String? = null,
    /** The long-press result card, if one is showing. */
    val readout: Readout? = null,
) {
    /** Coverage is on but the map is too far out to show it. */
    fun needsZoom(zoom: Double?): Boolean = on && zoom != null && zoom < MIN_ZOOM

    companion object {
        /** `CoverageLayer.MIN_ZOOM`. */
        const val MIN_ZOOM = 8
    }
}

/** A long-press on the map and what is known about that point. */
data class Readout(val lat: Double, val lon: Double, val result: ReadoutResult)

sealed interface ReadoutResult {
    /** No rendered tile covers the point yet; the view model keeps asking. */
    data object Calculating : ReadoutResult
    /** Still nothing after the retry window. */
    data object NoPrediction : ReadoutResult
    /** A tile exists, but there is no signal above the coverage threshold at the point. */
    data object NoCoverage : ReadoutResult
    data class Predicted(
        val rsrpDbm: Int,
        val quality: SignalQuality,
        /** Whole percent, or null if unknown. */
        val reliablePercent: Int?,
        val best: BestSector?,
    ) : ReadoutResult
}

data class BestSector(val network: Network, val band: String, val distanceM: Double)

/** The legend's four bands (rule 11), judged on the RSRP as displayed. */
enum class SignalQuality {
    EXCELLENT, GOOD, FAIR, WEAK;

    companion object {
        /** Null below −118 dBm, where the layer is transparent. */
        fun of(rsrpDbm: Int): SignalQuality? = when {
            rsrpDbm >= -85 -> EXCELLENT
            rsrpDbm >= -100 -> GOOD
            rsrpDbm >= -110 -> FAIR
            rsrpDbm >= -118 -> WEAK
            else -> null
        }
    }
}

/**
 * Search box state. [results] always belong to [resultsFor] (a trimmed query), which can lag
 * behind [query] while the debounce or the lookup runs.
 */
data class SearchUiState(
    /** What the user typed last. */
    val query: String = "",
    val results: List<SiteSummary> = emptyList(),
    val resultsFor: String = "",
) {
    /** A lookup for the current text has not finished yet. */
    val pending: Boolean get() = query.trim() != resultsFor

    /** Results for [text] (the field's current text), or null while they are still being computed. */
    fun resultsFor(text: String): List<SiteSummary>? = if (text.trim() == resultsFor) results else null
}

/** What the installed DB contains, for the updates screen. */
data class DataSummary(
    /** ACMA extract time, `yyyyMMddHHmm` (Australia/Sydney local time). */
    val extractStamp: String,
    val importedAt: Instant,
    val sites: Int,
    val sectors: Int,
    val carriers: Int,
)
