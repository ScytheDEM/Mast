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
import au.signalatlas.engine.PreparedCarriers
import au.signalatlas.engine.RenderedTile
import kotlinx.coroutines.runBlocking

/** Carriers prepared for rendering; maps render-time carrier indices back to rows of the source batch. */
interface PreparedRegion {
    fun batchIndex(i: Int): Int
}

/** The part of the engine the tile service uses; lets tests run without native code. */
interface TileRenderer {
    fun prepare(batch: CarrierBatch, patterns: PatternStore?, indoor: Boolean): PreparedRegion

    /** Null if [cancel] fired. */
    fun render(
        prepared: PreparedRegion, z: Int, x: Int, y: Int, area: AreaClass, thrDbm: Float, cancel: CancelToken,
    ): RenderedTile?
}

/** [TileRenderer] backed by the native [CoverageEngine]. */
class EngineTileRenderer(private val engine: CoverageEngine) : TileRenderer {
    private class Prepared(val carriers: PreparedCarriers) : PreparedRegion {
        override fun batchIndex(i: Int) = carriers.batchIndex(i)
    }

    override fun prepare(batch: CarrierBatch, patterns: PatternStore?, indoor: Boolean): PreparedRegion =
        Prepared(engine.prepare(batch, patterns, indoor))

    override fun render(
        prepared: PreparedRegion, z: Int, x: Int, y: Int, area: AreaClass, thrDbm: Float, cancel: CancelToken,
    ): RenderedTile? = engine.render((prepared as Prepared).carriers, z, x, y, area, thrDbm, cancel)
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
