package au.signalatlas.app.ui.map

import au.signalatlas.app.data.BasemapKeys
import au.signalatlas.app.data.Coverage
import au.signalatlas.app.data.LocationSource
import au.signalatlas.app.data.TowerData
import au.signalatlas.app.data.TowerSnapshot
import au.signalatlas.app.data.Updates
import au.signalatlas.core.acma.ImportReport
import au.signalatlas.core.coverage.Accuracy
import au.signalatlas.core.coverage.CoverageSample
import au.signalatlas.core.coverage.CoverageSelection
import au.signalatlas.core.coverage.FetchProgress
import au.signalatlas.core.data.DataMeta
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Site
import au.signalatlas.core.model.SiteDetail
import au.signalatlas.core.model.SiteSummary
import au.signalatlas.core.model.Tech
import au.signalatlas.core.model.TowerFilter
import au.signalatlas.core.update.HrpStatus
import au.signalatlas.core.update.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    override val patternStatus = MutableStateFlow(HrpStatus(null, 0, null))
    var patternDownloads = 0
    override fun downloadPatternsNow() { patternDownloads++ }
}

/** Mirrors the real template's shape: `.../<cacheId>/<view>/...`, so a view switch changes it but not the cache id. */
internal class FakeCoverage : Coverage {
    private val template = MutableStateFlow<String?>(null)
    /** Every selection ever set, in order (null = off). */
    val selections = mutableListOf<CoverageSelection?>()
    var lastVisible: Set<Triple<Int, Int, Int>>? = null
    /** Answers for [valueAt]; null = no tile yet. */
    var sample: CoverageSample? = null
    var valueAtCalls = 0

    override var selection: CoverageSelection? = null
        set(value) {
            field = value
            selections += value
            template.value = value?.let { "https://coverage.test/v1/${it.cacheId()}/${it.view.segment}/{z}/{x}/{y}.png" }
        }
    override val urlTemplate = template
    override fun setVisible(tiles: Set<Triple<Int, Int, Int>>) { lastVisible = tiles }
    override fun valueAt(lat: Double, lon: Double): CoverageSample? { valueAtCalls++; return sample }

    override val terrainEpoch = MutableStateFlow(0L)
    /** What [accuracyFor] flows hold; tests drive the chip through it. */
    val accuracy = MutableStateFlow(Accuracy.QUICK)
    val accuracyQueries = mutableListOf<Set<Triple<Int, Int, Int>>>()
    override fun accuracyFor(visible: Set<Triple<Int, Int, Int>>): StateFlow<Accuracy> { accuracyQueries += visible; return accuracy }
    var online = true
    override fun isOnline() = online
    /** Answer of [estimateIncreaseBytes]; null = offline / zoomed out. */
    var estimate: Long? = 12_000_000L
    override suspend fun estimateIncreaseBytes() = estimate
    /** The progress script of the next [increaseResolution]: one per call, in order (the last one repeats). */
    val scripts = mutableListOf<Flow<FetchProgress>>()
    val increaseCalls = mutableListOf<Boolean>()
    override fun increaseResolution(allowMetered: Boolean): Flow<FetchProgress> {
        increaseCalls += allowMetered
        return scripts.getOrNull(increaseCalls.size - 1) ?: scripts.last()
    }
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
