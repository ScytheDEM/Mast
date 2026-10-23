package au.signalatlas.core.coverage

import au.signalatlas.core.model.CalibrationRules
import au.signalatlas.core.model.CalibrationSnapshot
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.ResidualCell
import au.signalatlas.core.model.SectorCorrection
import au.signalatlas.engine.SectorTerms
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M4 Task 4: sector terms in prepare, residual rasters per tile, per-region calibration versions, valueAt. */
@RunWith(RobolectricTestRunner::class)
class CalibratedCoverageTest {
    @get:Rule val tmp = TemporaryFolder()
    private var rig: Rig? = null

    private fun rig() = Rig(tmp.newFolder()).also { rig = it }

    @After fun tearDown() { rig?.close() }

    private fun <T> CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)

    // fakeBatch(): row 0 = sector 1, Telstra, at (-33.9, 151.2); row 1 = sector 2, Optus, at (-33.8, 151.3).
    private val sydney = TileMath.locate(-33.9, 151.2, 12)!!
    private val perth = TileMath.locate(-31.95, 115.86, 12)!!

    private fun sector(id: Int = 1, n: Int = 5, lat: Double = -33.9, lon: Double = 151.2, offset: Double = -4.5) =
        SectorCorrection(id, Network.TELSTRA.ordinal, lat, lon, n, offset, 6.0, 1.2)

    private fun snapshot(version: Long, vararg sectors: SectorCorrection, cells: List<ResidualCell> = emptyList()) =
        CalibrationSnapshot(version, sectors.associateBy { it.sectorId }, cells)

    private fun key(r: Rig, at: TileMath.Located, z: Int = 12) = r.service.currentKey(z, at.x, at.y)!!

    @Test fun uncalibratedTilesKeepVersionZeroAndTheM3Url() {
        val r = rig()
        assertThat(key(r, sydney).calibrationVersion).isEqualTo(0L)
        assertThat(r.service.tileUrlTemplate()).endsWith("/none/t0/{z}/{x}/{y}.png")
        r.tile(12, sydney.x, sydney.y).await()
        assertThat(r.renderer.preparedTerms.single()).isEmpty()
        assertThat(r.renderer.renders.single().residuals).isEmpty()
        // Below 3 samples nothing applies either, though the snapshot version moves the URL.
        r.calibration.value = snapshot(1, sector(n = 2))
        assertThat(key(r, sydney).calibrationVersion).isEqualTo(0L)
        assertThat(r.service.tileUrlTemplate()).endsWith("/none/t0c1/{z}/{x}/{y}.png")
    }

    @Test fun appliedSectorTermsReachPrepareForTheirCarriersOnly() {
        val r = rig()
        r.calibration.value = snapshot(1, sector(n = 5))
        assertThat(key(r, sydney).calibrationVersion).isNotEqualTo(0L)
        r.tile(12, sydney.x, sydney.y).await()
        val terms = r.renderer.preparedTerms.single()
        assertThat(terms.keys).containsExactly(0)
        assertThat(terms[0]).isEqualTo(SectorTerms(-4.5f, 6f, 1.2f, sqrt(10.0 / 15.0).toFloat()))
    }

    @Test fun anEstimateLearnedAtAnotherSiteDoesNotApply() {
        val r = rig()
        // Same sector id, but learned at a site 1 km away (the id was reused by a tower DB update).
        r.calibration.value = snapshot(1, sector(lat = -33.909))
        r.tile(12, sydney.x, sydney.y).await()
        assertThat(r.renderer.preparedTerms.single()).isEmpty()
    }

    @Test fun onlyAffectedRegionsGetNewVersionsAndReRender() {
        val r = rig()
        r.calibration.value = snapshot(1, sector(n = 5))
        val sydneyV1 = key(r, sydney).calibrationVersion
        assertThat(key(r, perth).calibrationVersion).isEqualTo(0L)
        r.tile(12, sydney.x, sydney.y).await()
        r.tile(12, perth.x, perth.y).await()
        assertThat(r.renderer.renders).hasSize(2)

        // A Perth sector changes: Perth's version moves, Sydney's does not, and Sydney is served from the cache.
        r.calibration.value = snapshot(2, sector(n = 5), sector(id = 99, n = 4, lat = -31.95, lon = 115.86))
        assertThat(key(r, sydney).calibrationVersion).isEqualTo(sydneyV1)
        assertThat(key(r, perth).calibrationVersion).isNotEqualTo(0L)
        r.tile(12, sydney.x, sydney.y).await()
        assertThat(r.renderer.renders).hasSize(2)
        r.tile(12, perth.x, perth.y).await()
        assertThat(r.renderer.renders).hasSize(3)

        // Sydney's own estimate changes: a new version and a fresh prepare and render.
        val prepares = r.renderer.prepares.get()
        r.calibration.value = snapshot(3, sector(n = 6, offset = -3.0), sector(id = 99, n = 4, lat = -31.95, lon = 115.86))
        assertThat(key(r, sydney).calibrationVersion).isNotEqualTo(sydneyV1)
        r.tile(12, sydney.x, sydney.y).await()
        assertThat(r.renderer.renders).hasSize(4)
        assertThat(r.renderer.prepares.get()).isEqualTo(prepares + 1)
        assertThat(r.renderer.preparedTerms.last()[0]!!.offsetDb).isEqualTo(-3f)
    }

    @Test fun anOldUrlIsStaleOnceTheSnapshotMoves() {
        val r = rig()
        r.calibration.value = snapshot(1, sector())
        val old = TileUrl.parse(r.url(12, sydney.x, sydney.y).toHttpUrl())!!
        r.calibration.value = snapshot(2, sector(offset = 1.0))
        val e = try { r.service.tileFuture(old).await(); null } catch (x: ExecutionException) { x.cause }
        assertThat(e).isInstanceOf(StaleTileException::class.java)
    }

    @Test fun residualCellsBecomeRastersForTheirNetwork() {
        val r = rig()
        val bounds = TileMath.bounds(12, sydney.x, sydney.y)
        val latIdx = CalibrationRules.cellIndex((bounds.north + bounds.south) / 2)
        val lonIdx = CalibrationRules.cellIndex((bounds.west + bounds.east) / 2)
        val cells = listOf(
            ResidualCell(Network.OPTUS.ordinal, latIdx, lonIdx, 5, 4.0),        // applied: 5/10 * 4 = 2 dB = code 8
            ResidualCell(Network.OPTUS.ordinal, latIdx, lonIdx + 3, 2, 9.0),    // n < 3: ignored
            ResidualCell(Network.TELSTRA.ordinal, latIdx + 2, lonIdx, 3, -2.0), // 3/8 * -2 = -0.75 dB = code -3
        )
        r.calibration.value = snapshot(1, cells = cells)
        assertThat(key(r, sydney).calibrationVersion).isNotEqualTo(0L)
        r.tile(12, sydney.x, sydney.y).await()
        val rasters = r.renderer.renders.single().residuals
        assertThat(rasters.map { it.networkMask }).containsExactly(1 shl Network.TELSTRA.ordinal, 1 shl Network.OPTUS.ordinal).inOrder()
        fun codes(mask: Int) = rasters.single { it.networkMask == mask }.let { r -> ByteArray(65536) { r.codeAt(it) } }
        val optus = codes(1 shl Network.OPTUS.ordinal)
        val telstra = codes(1 shl Network.TELSTRA.ordinal)
        assertThat(optus.count { it.toInt() == 8 }).isAtLeast(4)  // a 100 m cell is ~3 x 3 pixels at z12
        assertThat(optus.count { it.toInt() != 0 && it.toInt() != 8 }).isEqualTo(0)
        assertThat(telstra.count { it.toInt() == -3 }).isAtLeast(4)
        assertThat(telstra.count { it.toInt() != 0 && it.toInt() != -3 }).isEqualTo(0)
        // Each painted pixel's centre lies in its cell.
        for (py in 0 until 256) for (px in 0 until 256) {
            if (optus[py * 256 + px].toInt() == 0) continue
            assertThat(CalibrationRules.cellIndex(TileMath.latAt(12, sydney.y + (py + 0.5) / 256))).isEqualTo(latIdx)
            assertThat(CalibrationRules.cellIndex(TileMath.lonAt(12, sydney.x + (px + 0.5) / 256))).isEqualTo(lonIdx)
        }
        // A tile with no applied cell under it gets none.
        r.tile(12, perth.x, perth.y).await()
        assertThat(r.renderer.renders.last().residuals).isEmpty()
    }

    @Test fun valueAtReportsTheBestServersCalibratedSamples() {
        val r = rig()
        r.calibration.value = snapshot(1, sector(n = 7))
        r.tile(12, sydney.x, sydney.y).await()
        // FakeRenderer serves carrier 1 everywhere = batch row 0 = sector 1.
        val bounds = TileMath.bounds(12, sydney.x, sydney.y)
        val sample = r.service.valueAt((bounds.north + bounds.south) / 2, (bounds.west + bounds.east) / 2)!!
        assertThat(sample.best!!.network).isEqualTo(Network.TELSTRA)
        assertThat(sample.calibratedSamples).isEqualTo(7)
        r.renderer.bestIndex = 0  // row 1 = sector 2: no estimate
        r.calibration.value = snapshot(2, sector(n = 8))
        r.tile(12, sydney.x, sydney.y).await()
        assertThat(r.service.valueAt((bounds.north + bounds.south) / 2, (bounds.west + bounds.east) / 2)!!.calibratedSamples).isNull()
    }

    @Test fun cacheFileNamesChangeOnlyForCalibratedKeys() {
        val k = TileKey(12, 1, 2, "sel", "stamp", "none", 0L)
        assertThat(TileCache.fileName(k)).isEqualTo(TileCache.fileName(k.copy(calibrationVersion = 0L)))
        assertThat(TileCache.fileName(k.copy(calibrationVersion = 42L))).isNotEqualTo(TileCache.fileName(k))
        assertThat(TileCache.fileName(k.copy(calibrationVersion = 42L))).isNotEqualTo(TileCache.fileName(k.copy(calibrationVersion = 43L)))
    }
}
