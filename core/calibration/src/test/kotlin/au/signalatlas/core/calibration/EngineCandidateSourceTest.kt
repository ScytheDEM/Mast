package au.signalatlas.core.calibration

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import au.signalatlas.engine.AreaClass
import au.signalatlas.engine.TerrainTable
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer

class EngineCandidateSourceTest {
    private fun table() = TerrainTable(ByteBuffer.allocateDirect(TerrainTable.CELLS), ByteBuffer.allocateDirect(TerrainTable.MASK_BYTES))

    /** Three 758 MHz carriers 1-3 km north of the default fix. */
    private val batch = CarrierBatch(
        3, DoubleArray(3) { -35.28 + (it + 1) / 111.195 }, DoubleArray(3) { 149.13 }, FloatArray(3) { 30f }, FloatArray(3), FloatArray(3),
        FloatArray(3) { 60f }, FloatArray(3) { 758f }, IntArray(3) { 10_000_000 }, ByteArray(3), ByteArray(3) { Network.TELSTRA.ordinal.toByte() },
        FloatArray(3) { 65f }, FloatArray(3) { 25f }, FloatArray(3) { 15f }, IntArray(3) { 10 + it }, LongArray(3) { 100L + it },
        ByteArray(3), listOf("B28"),
    )

    private val towers = object : TowerQuery {
        override fun carriersIn(b: GeoBounds, networks: Set<Network>, techs: Set<Tech>) = batch
        // 45 sites around the carriers' sites (1+ km north), 5 around the fix.
        override fun siteCountWithin(lat: Double, lon: Double, radiusM: Double) = if (lat > -35.275) 45 else 5
        override fun identitiesOf(sectorIds: Collection<Int>) = sectorIds.associateWith { "s$it" }
        override fun sectorIdsOf(idents: Collection<String>) = emptyMap<String, Int>()
    }

    private class Recorder : PointPredictor {
        var terrainSeen: ((Int) -> TerrainTable?)? = null
        var called = false
        var area: AreaClass? = null
        var carrierAreas: List<AreaClass?> = emptyList()
        override fun predict(
            batch: CarrierBatch, terrain: ((Int) -> TerrainTable?)?, lat: Double, lon: Double, area: AreaClass,
            carrierAreas: Array<AreaClass?>,
        ): List<PointPrediction> {
            called = true
            terrainSeen = terrain
            this.area = area
            this.carrierAreas = carrierAreas.toList()
            return (0 until batch.size).map { PointPrediction(it, -90f - it, 20f) }
        }
    }

    @Test fun predictionsUseTheTerrainTablesAroundTheFixWhereTheCoverageSideHasThem() {
        val t = table()
        var askedAt: Pair<Double, Double>? = null
        val rec = Recorder()
        val src = EngineCandidateSource(towers, rec, TerrainTablesAt { lat, lon ->
            askedAt = lat to lon
            { b, row -> if (b.sectorId[row] == 11) t else null }
        })
        val set = src.candidates(Network.TELSTRA, Tech.LTE, 758.0, -35.28, 149.13)!!
        assertThat(askedAt).isEqualTo(-35.28 to 149.13)
        val lookup = rec.terrainSeen!!
        assertThat(lookup(0)).isNull()
        assertThat(lookup(1)).isSameInstanceAs(t) // rows are the matcher's candidate rows
        assertThat(lookup(2)).isNull()
        assertThat(set.candidates.map { it.sectorId }).containsExactly(10, 11, 12).inOrder()
        assertThat(set.candidates.map { it.predictedDbm }).containsExactly(-90.0, -91.0, -92.0).inOrder()
        assertThat(set.area).isEqualTo(rec.area)
        assertThat(rec.area).isEqualTo(AreaClass.SUBURBAN)  // the fix's class (recorded with the sample)
    }

    /** Rule 5 as amended 2026-10-24: predictions use each carrier's class from its own site, like the map tiles. */
    @Test fun predictionsUseEachCarriersOwnAreaClass() {
        val rec = Recorder()
        EngineCandidateSource(towers, rec, null).candidates(Network.TELSTRA, Tech.LTE, 758.0, -35.28, 149.13)!!
        assertThat(rec.carrierAreas).containsExactly(AreaClass.DENSE_URBAN, AreaClass.DENSE_URBAN, AreaClass.DENSE_URBAN)
    }

    @Test fun withoutTablesTheQuickModelIsUsed() {
        for (src in listOf<(Recorder) -> EngineCandidateSource>(
            { EngineCandidateSource(towers, it, null) },
            { EngineCandidateSource(towers, it, TerrainTablesAt { _, _ -> null }) },
        )) {
            val rec = Recorder()
            src(rec).candidates(Network.TELSTRA, Tech.LTE, 758.0, -35.28, 149.13)!!
            assertThat(rec.called).isTrue()
            assertThat(rec.terrainSeen).isNull()
        }
    }
}
