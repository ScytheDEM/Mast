package au.signalatlas.core.calibration

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.data.PatternStore
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.engine.AreaClass
import au.signalatlas.engine.AreaClassifier
import au.signalatlas.engine.CoverageEngine
import au.signalatlas.engine.TerrainTable
import kotlin.math.cos
import kotlinx.coroutines.runBlocking

/** Rule-1 candidates for a serving cell at a fix, with the fix's area class (rule 5 of the coverage model). */
data class CandidateSet(val candidates: List<Candidate>, val area: AreaClass)

fun interface CandidateSource {
    /** Null when no tower data is available. Blocking. */
    fun candidates(network: Network, tech: Tech, freqMhz: Double, lat: Double, lon: Double): CandidateSet?
}

/** The tower queries the matcher needs (a pinned [TowerRepository] store, or a fake in tests). */
interface TowerQuery {
    fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>): CarrierBatch?
    fun siteCountWithin(lat: Double, lon: Double, radiusM: Double): Int
}

/** [TowerQuery] over the repository's current store (blocking; null while no DB is installed). */
fun TowerRepository.asTowerQuery(): TowerQuery = object : TowerQuery {
    override fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>) =
        runBlocking { withStore { it.carriersIn(b, networks, techs, null) } }

    override fun siteCountWithin(lat: Double, lon: Double, radiusM: Double) =
        runBlocking { withStore { it.siteCountWithin(lat, lon, radiusM) } } ?: 0
}

/**
 * Rule 1 against the tower DB and rule 2's predicted RSRP from the engine: carriers of the SIM's network (plus the
 * Optus/TPG regional joint venture, which serves both) and the serving technology within +-0.15 MHz of the channel
 * frequency on sites within 40 km, predicted uncalibrated at the fix (outdoor) for the fix's area class. [terrain]
 * supplies a carrier's terrain table when one is ready (batch, row) -> table; without it the quick model is used.
 */
class EngineCandidateSource(
    private val towers: TowerQuery,
    private val engine: CoverageEngine,
    private val patterns: () -> PatternStore?,
    private val terrain: ((batch: CarrierBatch, row: Int) -> TerrainTable?)? = null,
) : CandidateSource {
    override fun candidates(network: Network, tech: Tech, freqMhz: Double, lat: Double, lon: Double): CandidateSet? {
        val r = CalibrationMath.CANDIDATE_RADIUS_KM * 1000.0
        val dLat = Math.toDegrees(r / 6371008.8)
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        val batch = towers.carriersIn(GeoBounds(lat - dLat, lon - dLon, lat + dLat, lon + dLon), servingNetworks(network), setOf(tech))
            ?: return null
        val area = AreaClassifier.classify(towers.siteCountWithin(lat, lon, AreaClassifier.RADIUS_M))
        val rows = (0 until batch.size).filter {
            CalibrationMath.isCandidate(batch.freqMhz[it].toDouble(), freqMhz,
                CalibrationMath.distanceM(batch.siteLat[it], batch.siteLon[it], lat, lon))
        }
        if (rows.isEmpty()) return CandidateSet(emptyList(), area)
        val sub = batch.rows(rows.toIntArray())
        val prepared = engine.prepare(sub, patterns(), indoor = false, terrain = terrain?.let { t -> { row: Int -> t(batch, rows[row]) } })
        val rsrp = engine.predictPoint(prepared, lat, lon, area, indoor = false)
        val out = (0 until prepared.size).mapNotNull { k ->
            val row = prepared.batchIndex(k)
            rsrp[k].takeIf { it.isFinite() }?.let { p ->
                Candidate(
                    sub.sectorId[row], sub.sddId[row], Network.entries[sub.network[row].toInt()], sub.siteLat[row],
                    sub.siteLon[row], sub.freqMhz[row].toDouble(), p.toDouble(), prepared.rMaxKm(k).toDouble(),
                )
            }
        }
        return CandidateSet(out, area)
    }

    companion object {
        fun servingNetworks(sim: Network): Set<Network> = when (sim) {
            Network.OPTUS, Network.TPG -> setOf(sim, Network.OPTUS_TPG_JV)
            else -> setOf(sim)
        }
    }
}

internal fun CarrierBatch.rows(rows: IntArray): CarrierBatch = CarrierBatch(
    rows.size,
    DoubleArray(rows.size) { siteLat[rows[it]] }, DoubleArray(rows.size) { siteLon[rows[it]] },
    FloatArray(rows.size) { heightM[rows[it]] }, FloatArray(rows.size) { azimuthDeg[rows[it]] },
    FloatArray(rows.size) { tiltDeg[rows[it]] }, FloatArray(rows.size) { eirpDbm[rows[it]] },
    FloatArray(rows.size) { freqMhz[rows[it]] }, IntArray(rows.size) { bandwidthHz[rows[it]] },
    ByteArray(rows.size) { tech[rows[it]] }, ByteArray(rows.size) { network[rows[it]] },
    FloatArray(rows.size) { hBeamwidthDeg[rows[it]] }, FloatArray(rows.size) { frontToBackDb[rows[it]] },
    FloatArray(rows.size) { gainDbi[rows[it]] }, IntArray(rows.size) { sectorId[rows[it]] },
    LongArray(rows.size) { sddId[rows[it]] }, ByteArray(rows.size) { bandIdx[rows[it]] }, bandLabels,
)
