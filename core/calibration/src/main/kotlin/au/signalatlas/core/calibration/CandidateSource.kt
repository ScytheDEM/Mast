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

/**
 * Maps between the tower DB's renumbered sector ids and the stable [au.signalatlas.core.model.SectorIdentity] keys the
 * calibration data uses. Both return only what exists in the current tower DB (an empty map while none is installed).
 */
interface SectorResolver {
    fun identitiesOf(sectorIds: Collection<Int>): Map<Int, String>
    fun sectorIdsOf(idents: Collection<String>): Map<String, Int>
}

/** The tower queries the matcher needs (a pinned [TowerRepository] store, or a fake in tests). */
interface TowerQuery : SectorResolver {
    fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>): CarrierBatch?
    fun siteCountWithin(lat: Double, lon: Double, radiusM: Double): Int
}

/** [TowerQuery] over the repository's current store (blocking; null while no DB is installed). */
fun TowerRepository.asTowerQuery(): TowerQuery = object : TowerQuery {
    override fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>) =
        runBlocking { withStore { it.carriersIn(b, networks, techs, null) } }

    override fun siteCountWithin(lat: Double, lon: Double, radiusM: Double) =
        runBlocking { withStore { it.siteCountWithin(lat, lon, radiusM) } } ?: 0

    override fun identitiesOf(sectorIds: Collection<Int>) =
        runBlocking { withStore { it.sectorIdentities(sectorIds) } } ?: emptyMap()

    override fun sectorIdsOf(idents: Collection<String>) =
        runBlocking { withStore { it.sectorIdsByIdentity(idents) } } ?: emptyMap()
}

/** A carrier's predicted RSRP (dBm, NaN = unusable) at the fix: [row] indexes the batch handed to [PointPredictor.predict]. */
internal class PointPrediction(val row: Int, val rsrpDbm: Float, val rMaxKm: Float)

/** The engine side of rule 2, behind a seam so the matcher's inputs can be tested without the native library. */
internal fun interface PointPredictor {
    /**
     * Predictions for the usable carriers of [batch] at ([lat], [lon]); [terrain] gives a carrier's table by batch row
     * and [carrierAreas] its area class (rule 5, as the map uses it); [area] is the fallback for a row without one.
     */
    fun predict(
        batch: CarrierBatch, terrain: ((batchRow: Int) -> TerrainTable?)?, lat: Double, lon: Double, area: AreaClass,
        carrierAreas: Array<AreaClass?>,
    ): List<PointPrediction>
}

/**
 * Terrain tables the coverage side has ready around a fix: the carrier lookup (batch, row) -> table for the terrain
 * region of ([lat], [lon]), or null when it has none there (coverage off, no elevation data, no tables built yet).
 */
fun interface TerrainTablesAt {
    fun at(lat: Double, lon: Double): ((batch: CarrierBatch, row: Int) -> TerrainTable?)?
}

/**
 * Rule 1 against the tower DB and rule 2's predicted RSRP from the engine: carriers of the SIM's network (plus the
 * Optus/TPG regional joint venture, which serves both) and the serving technology within +-0.15 MHz of the channel
 * frequency on sites within 40 km, predicted uncalibrated at the fix (outdoor) with each carrier's own area class (rule 5
 * as amended 2026-10-24, the same model the map renders; the fix's class is recorded with the sample). [terrain]
 * supplies the terrain tables the coverage side has ready around the fix, so predictions include L_terr for the
 * carriers that have a table and use the quick model for the rest (and everywhere without [terrain]).
 */
class EngineCandidateSource internal constructor(
    private val towers: TowerQuery,
    private val predictor: PointPredictor,
    private val terrain: TerrainTablesAt?,
) : CandidateSource {
    constructor(
        towers: TowerQuery, engine: CoverageEngine, patterns: () -> PatternStore?, terrain: TerrainTablesAt? = null,
    ) : this(towers, enginePredictor(engine, patterns), terrain)

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
        val lookup = terrain?.at(lat, lon)
        val carrierAreas = AreaClassifier.perSite(sub, towers::siteCountWithin)
        val predictions = predictor.predict(sub, lookup?.let { t -> { row: Int -> t(sub, row) } }, lat, lon, area, carrierAreas)
        val idents = towers.identitiesOf(sub.sectorId.toList())
        val out = predictions.mapNotNull { p ->
            val row = p.row
            val ident = idents[sub.sectorId[row]] ?: return@mapNotNull null
            p.rsrpDbm.takeIf { it.isFinite() }?.let { rsrp ->
                Candidate(
                    sub.sectorId[row], sub.sddId[row], ident, Network.entries[sub.network[row].toInt()], sub.siteLat[row],
                    sub.siteLon[row], sub.freqMhz[row].toDouble(), rsrp.toDouble(), p.rMaxKm.toDouble(),
                    sub.bandwidthHz[row] / 1e6,
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

private fun enginePredictor(engine: CoverageEngine, patterns: () -> PatternStore?) =
    PointPredictor { batch, terrain, lat, lon, area, carrierAreas ->
        val prepared = engine.prepare(batch, patterns(), indoor = false, terrain = terrain, area = carrierAreas::get)
        val rsrp = engine.predictPoint(prepared, lat, lon, area, indoor = false)
        (0 until prepared.size).map { k -> PointPrediction(prepared.batchIndex(k), rsrp[k], prepared.rMaxKm(k)) }
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
