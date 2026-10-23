package au.signalatlas.core.coverage

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.data.PatternStore
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.core.data.TowerRepository
import au.signalatlas.engine.AreaClass
import au.signalatlas.engine.CancelToken
import au.signalatlas.engine.CoverageEngine
import au.signalatlas.engine.DemGridHandle
import au.signalatlas.engine.PreparedCarriers
import au.signalatlas.engine.RenderedTile
import au.signalatlas.engine.ResidualRaster
import au.signalatlas.engine.SectorTerms
import au.signalatlas.engine.TerrainTable
import kotlinx.coroutines.runBlocking

/** Carriers prepared for rendering; maps render-time carrier indices back to rows of the source batch. */
interface PreparedRegion {
    fun batchIndex(i: Int): Int
}

/** The part of the engine the tile service uses; lets tests run without native code. */
interface TileRenderer {
    /**
     * [terrain] is asked once per kept carrier, with its row in [batch], for its terrain table (null = quick model);
     * [calibration] likewise for its sector's terms (null = uncalibrated).
     */
    fun prepare(
        batch: CarrierBatch, patterns: PatternStore?, indoor: Boolean, terrain: ((batchRow: Int) -> TerrainTable?)? = null,
        calibration: ((batchRow: Int) -> SectorTerms?)? = null,
    ): PreparedRegion

    /** Null if [cancel] fired. [residuals] are the tile's residual rasters (Calibration rule 5). */
    fun render(
        prepared: PreparedRegion, z: Int, x: Int, y: Int, area: AreaClass, thrDbm: Float, cancel: CancelToken,
        residuals: List<ResidualRaster> = emptyList(),
    ): RenderedTile?

    /**
     * The polar terrain table of one site (see [CoverageEngine.buildTerrainTable]); [hTxAgl] and [freqMhz] are the
     * table-key values. Null if [cancel] fired.
     */
    fun buildTerrainTable(
        dem: DemGridHandle, siteLat: Double, siteLon: Double, hTxAgl: Float, freqMhz: Float, cancel: CancelToken,
    ): TerrainTable?
}

/** [TileRenderer] backed by the native [CoverageEngine]. */
class EngineTileRenderer(private val engine: CoverageEngine) : TileRenderer {
    private class Prepared(val carriers: PreparedCarriers) : PreparedRegion {
        override fun batchIndex(i: Int) = carriers.batchIndex(i)
    }

    override fun prepare(
        batch: CarrierBatch, patterns: PatternStore?, indoor: Boolean, terrain: ((batchRow: Int) -> TerrainTable?)?,
        calibration: ((batchRow: Int) -> SectorTerms?)?,
    ): PreparedRegion = Prepared(engine.prepare(batch, patterns, indoor, terrain, calibration))

    override fun buildTerrainTable(
        dem: DemGridHandle, siteLat: Double, siteLon: Double, hTxAgl: Float, freqMhz: Float, cancel: CancelToken,
    ): TerrainTable? = engine.buildTerrainTable(dem, siteLat, siteLon, hTxAgl, freqMhz, cancel)

    override fun render(
        prepared: PreparedRegion, z: Int, x: Int, y: Int, area: AreaClass, thrDbm: Float, cancel: CancelToken,
        residuals: List<ResidualRaster>,
    ): RenderedTile? = engine.render((prepared as Prepared).carriers, z, x, y, area, thrDbm, cancel, residuals)
}

/** A pinned tower store, as far as the tile service needs it. */
internal interface Towers {
    val stamp: String
    fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>, bands: Set<String>?): CarrierBatch
    fun siteCountWithin(lat: Double, lon: Double, radiusM: Double): Int
}

internal interface TowerData {
    /** The extract stamp of the installed tower DB; null when none is installed. */
    fun stamp(): String?

    /** Runs [block] (blocking) with the current store pinned; null when no DB is installed. */
    fun <T> withTowers(block: (Towers) -> T): T?
}

internal class RepositoryTowerData(private val repo: TowerRepository) : TowerData {
    override fun stamp(): String? = repo.store.value?.meta?.extractStamp

    override fun <T> withTowers(block: (Towers) -> T): T? = runBlocking {
        repo.withStore { store ->
            block(object : Towers {
                override val stamp = store.meta.extractStamp
                override fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>, bands: Set<String>?) =
                    store.carriersIn(b, networks, techs, bands)
                override fun siteCountWithin(lat: Double, lon: Double, radiusM: Double) =
                    store.siteCountWithin(lat, lon, radiusM)
            })
        }
    }
}
