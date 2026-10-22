package au.signalatlas.app.ui.map

import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.TowerFilter
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
    val query: String = "",
    val searchResults: List<SiteSummary> = emptyList(),
    val update: UpdateStatus = UpdateStatus.Unknown,
    val basemapKey: String? = null,
    /** True once the basemap key is known or the lookup has taken too long; the map may show. */
    val basemapResolved: Boolean = false,
    val locationGranted: Boolean = false,
    val location: Pair<Double, Double>? = null,
    /** Nearest filtered sites with distance in metres, nearest first; empty without permission. */
    val nearby: List<Pair<SiteSummary, Double>> = emptyList(),
    val data: DataSummary? = null,
)

/** What the installed DB contains, for the updates screen. */
data class DataSummary(
    /** ACMA extract time, `yyyyMMddHHmm` (Australia/Sydney local time). */
    val extractStamp: String,
    val importedAt: Instant,
    val sites: Int,
    val sectors: Int,
    val carriers: Int,
)
