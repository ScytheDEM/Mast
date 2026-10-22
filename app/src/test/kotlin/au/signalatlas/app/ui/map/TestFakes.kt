package au.signalatlas.app.ui.map

import au.signalatlas.app.data.BasemapKeys
import au.signalatlas.app.data.LocationSource
import au.signalatlas.app.data.TowerData
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.app.data.Updates
import au.signalatlas.core.acma.ImportReport
import au.signalatlas.core.data.DataMeta
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Site
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.core.update.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import kotlin.math.abs

/* Fakes for the MapViewModel seams, shared by the view model and Compose tests. */

internal fun site(id: Int, name: String, lat: Double, lon: Double, vararg nets: Network, techs: Set<Tech> = setOf(Tech.LTE)) =
    SiteSummary(id, lat, lon, name, nets.toSet(), techs, setOf("B28"))

internal val SITES = listOf(
    site(101160, "Telstra Exchange 22 Barton Tce", -35.31, 149.13, Network.TELSTRA, Network.OPTUS),
    site(200, "Optus Rooftop Civic", -35.28, 149.13, Network.OPTUS, techs = setOf(Tech.LTE, Tech.NR)),
    site(300, "TPG Pole Kingston", -35.32, 149.15, Network.TPG),
    site(400, "NBN Tower Belconnen", -35.24, 149.06, Network.NBN),
)

/** Postcodes of [SITES], as in the real DB's site table. */
internal val POSTCODES = mapOf(101160 to "2600", 200 to "2601", 300 to "2600", 400 to "2617")

internal class FakeSnapshot(val all: List<SiteSummary>) : TowerSnapshot {
    var retired = false
    override val meta = DataMeta(
        "202610220500", Instant.parse("2026-10-22T06:00:00Z"),
        ImportReport(all.size, 10, 20, 5, 0, 0, 0, 0, emptyMap()),
    )
    override val allBands = listOf("B28", "n78")
    override fun allSites(f: TowerFilter) = all.filter { f.matches(it) }
    // Deliberately unsorted: the view model must order nearby sites by distance itself.
    override fun nearest(lat: Double, lon: Double, f: TowerFilter, limit: Int) =
        all.filter { f.matches(it) }.map { it to (abs(it.lat - lat) + abs(it.lon - lon)) * 100_000 }.reversed()
    /** Like TowerStore.search: a number matches a site ID or postcode first, then names. */
    override fun search(query: String, limit: Int): List<SiteSummary> {
        val q = query.trim()
        val numeric = if (q.isNotEmpty() && q.all { it.isDigit() }) all.filter { it.id.toString() == q || POSTCODES[it.id] == q } else emptyList()
        return (numeric + all.filter { it.name.contains(q, ignoreCase = true) }).distinct().take(limit)
    }
    override fun siteDetail(siteId: Int): SiteDetail? {
        if (retired) return null
        val s = all.firstOrNull { it.id == siteId } ?: return null
        return SiteDetail(Site(s.id, s.lat, s.lon, s.name, "ACT", POSTCODES[s.id], null), emptyList())
    }
}

internal class FakeData(initial: TowerSnapshot?) : TowerData {
    override val started = MutableStateFlow(true)
    override val changes = MutableStateFlow(initial)
    override fun current() = changes.value
}

internal class FakeUpdates : Updates {
    override val status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    var checks = 0
    var cancels = 0
    val updateCalls = mutableListOf<Boolean>()
    override suspend fun check() { checks++ }
    override fun updateNow(allowMetered: Boolean) { updateCalls += allowMetered }
    override fun cancel() { cancels++ }
}

internal class FakeBasemap(val key: String?, val delayMs: Long = 0, val failure: Exception? = null) : BasemapKeys {
    override suspend fun currentKey(): String? {
        delay(delayMs)
        failure?.let { throw it }
        return key
    }
}

internal class FakeLocation : LocationSource {
    override val last = MutableStateFlow<Pair<Double, Double>?>(null)
    override val enabled = MutableStateFlow(true)
}
