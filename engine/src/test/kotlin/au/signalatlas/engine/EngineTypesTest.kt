package au.signalatlas.engine

import au.signalatlas.core.data.CarrierBatch
import au.signalatlas.core.model.Tech
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

/** JVM-only: the marshalling in [PreparedCarriers.build] with a fake native derive, plus the ABI-shape checks. */
class EngineTypesTest {
    private fun cpp(path: String) = File("src/main/cpp/$path").readText()

    /** Parses `enum class Name : uint8_t { A = 0, B = 1 }` from a C++ header into name -> value. */
    private fun cppEnum(header: String, name: String): Map<String, Int> {
        val body = Regex("""enum class $name\s*:\s*uint8_t\s*\{([^}]*)}""").find(cpp(header))!!.groupValues[1]
        return body.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=').map(String::trim)
            k to v.toInt()
        }
    }

    private fun camel(s: String) = s.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }

    @Test fun areaClassOrdinalsMatchTheCppEnum() {
        val cppArea = cppEnum("model/pathloss.h", "Area")
        assertThat(cppArea).containsExactly("DenseUrban", 0, "Urban", 1, "Suburban", 2, "Rural", 3)
        for (a in AreaClass.entries) assertThat(cppArea[camel(a.name)]).isEqualTo(a.ordinal)
        assertThat(AreaClass.entries).hasSize(cppArea.size)
    }

    @Test fun techOrdinalsMatchTheCppEnum() {
        val cppTech = cppEnum("model/eirp.h", "Tech")
        for (t in Tech.entries) assertThat(cppTech[camelTech(t)]).isEqualTo(t.ordinal)
        assertThat(Tech.entries).hasSize(cppTech.size)
    }

    private fun camelTech(t: Tech) = t.name  // the C++ enumerators are LTE and NR

    @Test fun carrierFieldSizesMatchTheBridge() {
        val sizes = Regex("""kFieldSize\[kFieldCount]\s*=\s*\{([^}]*)}""").find(cpp("jni_bridge.cpp"))!!
            .groupValues[1].split(',').map { it.trim().toInt() }
        assertThat(CarrierField.entries.map { it.elemBytes }).isEqualTo(sizes)
    }

    @Test fun freqKeyIsStableAndSeparatesNetworksAndFrequencies() {
        assertThat(PreparedCarriers.freqKey(0, 2127.5f)).isEqualTo(21275)
        assertThat(PreparedCarriers.freqKey(1, 2127.5f)).isEqualTo((1 shl 21) + 21275)
        assertThat(PreparedCarriers.freqKey(0, 2127.54f)).isEqualTo(PreparedCarriers.freqKey(0, 2127.5f))
        assertThat(PreparedCarriers.freqKey(0, 2127.6f)).isNotEqualTo(PreparedCarriers.freqKey(0, 2127.5f))
        // Highest band in the data (100 GHz, rule 6 clamp) stays inside the frequency field.
        assertThat(PreparedCarriers.freqKey(0, 100000f)).isEqualTo(1_000_000)
        assertThat(PreparedCarriers.freqKey(0, 100000f)).isLessThan(PreparedCarriers.freqKey(1, 30f))
    }

    // Row 0 ok; 1 NaN lat; 2 ok; 3 bandwidth 0; 4 NaN eirp; 5 ok (NaN azimuth = omni stays); 6 infinite lon; 7 NaN freq.
    private fun batch(n: Int = 8): CarrierBatch {
        fun f(v: Float) = FloatArray(n) { v }
        fun at(i: Int) = i < n
        val lat = DoubleArray(n) { -35.0 - it }
        val lon = DoubleArray(n) { 149.0 + it }
        val eirp = FloatArray(n) { 50f + it }
        val bw = IntArray(n) { 10_000_000 }
        val freq = FloatArray(n) { 1800f + it * 100f }
        val az = FloatArray(n) { it * 10f }
        if (at(1)) lat[1] = Double.NaN
        if (at(6)) lon[6] = Double.POSITIVE_INFINITY
        if (at(4)) eirp[4] = Float.NaN
        if (at(3)) bw[3] = 0
        if (at(7)) freq[7] = Float.NaN
        if (at(5)) az[5] = Float.NaN
        return CarrierBatch(
            size = n, siteLat = lat, siteLon = lon, heightM = FloatArray(n) { 20f + it },
            azimuthDeg = az, tiltDeg = FloatArray(n) { it * 0.5f }, eirpDbm = eirp, freqMhz = freq,
            bandwidthHz = bw, tech = ByteArray(n) { (it % 2).toByte() }, network = ByteArray(n) { (it % 3).toByte() },
            hBeamwidthDeg = f(65f), frontToBackDb = f(25f), gainDbi = f(16.5f),
            sectorId = IntArray(n) { 100 + it }, sddId = LongArray(n) { 2_909_000_000L + it },
            bandIdx = ByteArray(n), bandLabels = listOf("B3"),
        )
    }

    private class FakeDerive : Derive {
        var calls = 0
        var n = 0
        var eirp = FloatArray(0)
        var bw = IntArray(0)
        override fun derive(
            n: Int, eirpDbm: FloatArray, bwHz: IntArray, freqMhz: ByteBuffer, hTx: ByteBuffer, tech: ByteBuffer,
            outEirpRe: ByteBuffer, outRMaxKm: ByteBuffer,
        ) {
            calls++
            this.n = n
            eirp = eirpDbm.copyOf(n)
            bw = bwHz.copyOf(n)
            for (i in 0 until n) {
                outEirpRe.putFloat(i * 4, eirpDbm[i] - 30f)
                outRMaxKm.putFloat(i * 4, freqMhz.getFloat(i * 4) / 100f)
            }
        }
    }

    @Test fun dropsUnusableCarriersAndMapsIndicesBack() {
        val d = FakeDerive()
        val p = PreparedCarriers.build(batch(), null, indoor = true, derive = d)
        assertThat(p.size).isEqualTo(3)
        assertThat(p.indoor).isTrue()
        assertThat((0 until p.size).map(p::batchIndex)).containsExactly(0, 2, 5).inOrder()
        assertThat(d.calls).isEqualTo(1)
        assertThat(d.n).isEqualTo(3)
        assertThat(d.eirp).isEqualTo(floatArrayOf(50f, 52f, 55f))
        assertThat(d.bw).isEqualTo(intArrayOf(10_000_000, 10_000_000, 10_000_000))
        assertThrows(IllegalArgumentException::class.java) { p.batchIndex(3) }
        assertThrows(IllegalArgumentException::class.java) { p.batchIndex(-1) }
    }

    @Test fun buffersAreDirectNativeOrderAndHoldTheCompactedRows() {
        val b = batch()
        val p = PreparedCarriers.build(b, null, false, FakeDerive())
        assertThat(p.fields.size).isEqualTo(CarrierField.entries.size)
        for (f in CarrierField.entries) {
            val buf = p.fields[f.ordinal]
            assertThat(buf.isDirect).isTrue()
            assertThat(buf.order()).isEqualTo(ByteOrder.nativeOrder())
            assertThat(buf.capacity()).isEqualTo(p.size * f.elemBytes)
        }
        fun d(f: CarrierField, i: Int) = p.fields[f.ordinal].getDouble(i * 8)
        fun fl(f: CarrierField, i: Int) = p.fields[f.ordinal].getFloat(i * 4)
        assertThat(d(CarrierField.LAT, 1)).isEqualTo(b.siteLat[2])
        assertThat(d(CarrierField.LON, 2)).isEqualTo(b.siteLon[5])
        assertThat(fl(CarrierField.H_TX, 1)).isEqualTo(b.heightM[2])
        assertThat(fl(CarrierField.AZ, 1)).isEqualTo(b.azimuthDeg[2])
        assertThat(fl(CarrierField.AZ, 2).isNaN()).isTrue()  // omni stays NaN
        assertThat(fl(CarrierField.TILT, 2)).isEqualTo(b.tiltDeg[5])
        assertThat(fl(CarrierField.FREQ_MHZ, 2)).isEqualTo(b.freqMhz[5])
        assertThat(fl(CarrierField.H_BW, 0)).isEqualTo(65f)
        assertThat(fl(CarrierField.FB, 0)).isEqualTo(25f)
        assertThat(fl(CarrierField.GAIN, 0)).isEqualTo(16.5f)
        assertThat(p.eirpReAt(1)).isEqualTo(22f)
        assertThat(p.rMaxKmAt(2)).isEqualTo(b.freqMhz[5] / 100f)
        assertThat(p.fields[CarrierField.TECH.ordinal].get(2)).isEqualTo(b.tech[5])
        assertThat(p.sectorKeyAt(1)).isEqualTo(102)
        assertThat(p.freqKeyAt(1)).isEqualTo(PreparedCarriers.freqKey(2, b.freqMhz[2]))
        assertThat(p.hrpIdAt(0)).isEqualTo(0)
        assertThat(p.hrpPeakAt(0)).isEqualTo(0.toShort())
    }

    @Test fun hrpIdsAndPeaksComeFromTheLookupOfTheKeptRows() {
        val seen = ArrayList<Long>()
        val buffer = ByteBuffer.allocateDirect(720).order(ByteOrder.nativeOrder())
        val upload = PatternUpload(buffer, 2) { sdd, ids, peaks ->
            seen += sdd.toList()
            for (i in sdd.indices) {
                ids[i] = if (sdd[i] % 2 == 0L) 2 else 0
                peaks[i] = (sdd[i] % 360).toShort()
            }
        }
        val p = PreparedCarriers.build(batch(), upload, false, FakeDerive())
        assertThat(seen).containsExactly(2_909_000_000L, 2_909_000_002L, 2_909_000_005L).inOrder()
        assertThat(p.hrpIdAt(0)).isEqualTo(2)
        assertThat(p.hrpIdAt(1)).isEqualTo(2)
        assertThat(p.hrpIdAt(2)).isEqualTo(0)
        assertThat(p.hrpPeakAt(1)).isEqualTo((2_909_000_002L % 360).toShort())
        assertThat(p.patterns).isSameInstanceAs(upload)
    }

    @Test fun emptyAndOversizedBatches() {
        val d = FakeDerive()
        val none = PreparedCarriers.build(batch(0), null, false, d)
        assertThat(none.size).isEqualTo(0)
        assertThat(d.calls).isEqualTo(0)
        val big = batch(PreparedCarriers.MAX_CARRIERS + 1)
        val e = assertThrows(IllegalArgumentException::class.java) { PreparedCarriers.build(big, null, false, d) }
        assertThat(e).hasMessageThat().contains("65535")
        assertThat(PreparedCarriers.build(batch(PreparedCarriers.MAX_CARRIERS), null, false, FakeDerive()).size)
            .isGreaterThan(0)
    }

    @Test fun closeIsIdempotent() {
        val p = PreparedCarriers.build(batch(), null, false, FakeDerive())
        assertThat(p.isClosed).isFalse()
        p.close()
        p.close()
        assertThat(p.isClosed).isTrue()
    }

    @Test fun cancelTokenIsBackedByADirectIntBuffer() {
        val t = CancelToken()
        assertThat(t.buffer.isDirect).isTrue()
        assertThat(t.buffer.capacity()).isEqualTo(4)
        assertThat(t.isCancelled).isFalse()
        t.cancel()
        t.cancel()
        assertThat(t.isCancelled).isTrue()
        assertThat(t.buffer.getInt(0)).isEqualTo(1)
    }

    @Test fun renderedTileBestDecodesUnsigned() {
        val best = ShortArray(RenderedTile.PIXELS) { 0xFFFF.toShort() }
        best[1] = 0xFFFE.toShort()
        best[2] = 7
        val t = RenderedTile(13, 1, 2, ByteArray(0), ByteArray(0), ByteArray(0), best)
        assertThat(t.bestCarrier(0)).isEqualTo(-1)
        assertThat(t.bestCarrier(1)).isEqualTo(0xFFFE)
        assertThat(t.bestCarrier(2)).isEqualTo(7)
    }

    // ---- terrain marshalling

    private fun table() = TerrainTable.allocate()

    @Test fun terrainProviderGetsBatchRowsAndSharedTablesAreUploadedOnce() {
        val a = table()
        val b = table()
        val asked = mutableListOf<Int>()
        // Kept rows are 0, 2, 5 (see dropsUnusableCarriersAndMapsIndicesBack). Row 0 and 5 share a; row 2 has none.
        val p = PreparedCarriers.build(batch(), null, false, FakeDerive()) { row ->
            asked += row
            when (row) { 0 -> a; 5 -> a; else -> null }
        }
        assertThat(asked).containsExactly(0, 2, 5).inOrder()
        assertThat(p.terrainPool).hasSize(1)
        assertThat(p.terrainTableAt(0)).isSameInstanceAs(a)
        assertThat(p.terrainTableAt(1)).isNull()
        assertThat(p.terrainTableAt(2)).isSameInstanceAs(a)
        assertThat(p.terrainTables.size).isEqualTo(1)
        assertThat(p.terrainMasks[0]).isSameInstanceAs(a.mask)
        assertThrows(IllegalArgumentException::class.java) { p.terrainTableAt(3) }

        val two = PreparedCarriers.build(batch(), null, false, FakeDerive()) { row -> if (row == 2) b else a }
        assertThat(two.terrainPool).containsExactly(a, b).inOrder()
        assertThat(two.fields[CarrierField.TERRAIN_SLOT.ordinal].getInt(4)).isEqualTo(2)
    }

    @Test fun noTerrainProviderMeansNoSlotsAndNoPool() {
        val p = PreparedCarriers.build(batch(), null, false, FakeDerive())
        assertThat(p.terrainPool).isEmpty()
        assertThat((0 until p.size).map(p::terrainTableAt)).containsExactly(null, null, null)
        assertThat(p.terrainTables).isEmpty()
    }

    @Test fun terrainTableValidatesItsBuffers() {
        assertThrows(IllegalArgumentException::class.java) {
            TerrainTable(ByteBuffer.allocate(TerrainTable.CELLS), ByteBuffer.allocateDirect(TerrainTable.MASK_BYTES))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerrainTable(ByteBuffer.allocateDirect(TerrainTable.CELLS - 1), ByteBuffer.allocateDirect(TerrainTable.MASK_BYTES))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerrainTable(ByteBuffer.allocateDirect(TerrainTable.CELLS), ByteBuffer.allocateDirect(TerrainTable.MASK_BYTES - 1))
        }
        assertThat(TerrainTable.CELLS).isEqualTo(92_160)
        assertThat(TerrainTable.MASK_BYTES).isEqualTo(11_520)
    }

    @Test fun cellDbReadsCodesAndHonoursTheMask() {
        val t = table()
        val k = 7 * 256 + 100
        t.table.put(k, 41.toByte())
        t.mask.put(k shr 3, (1 shl (k and 7)).toByte())
        assertThat(t.cellDb(7, 100)).isEqualTo(10.25f)
        assertThat(t.cellDb(7, 101)).isNull()
        t.table.put(k, 200.toByte())  // above 127: unsigned
        assertThat(t.cellDb(7, 100)).isEqualTo(50f)
        assertThrows(IllegalArgumentException::class.java) { t.cellDb(360, 0) }
    }

    @Test fun tableKeysMatchTheReferenceRounding() {
        assertThat(TerrainTable.frequencyKeyMhz(2130f)).isEqualTo(2130)
        assertThat(TerrainTable.frequencyKeyMhz(2125f)).isEqualTo(2130)  // half up
        assertThat(TerrainTable.frequencyKeyMhz(2124.9f)).isEqualTo(2120)
        assertThat(TerrainTable.frequencyKeyMhz(879.9f)).isEqualTo(880)
        assertThat(TerrainTable.heightKeyM(27f)).isEqualTo(27)
        assertThat(TerrainTable.heightKeyM(26.5f)).isEqualTo(27)
        assertThat(TerrainTable.heightKeyM(1f)).isEqualTo(3)  // clamped to 3 m first
        assertThat(TerrainTable.heightKeyM(Float.NaN)).isEqualTo(3)
    }

    @Test fun terrainStatisticsDriveTheNinetyFivePercentRule() {
        fun tile(covered: Int, incomplete: Int) =
            RenderedTile(1, 0, 0, ByteArray(0), ByteArray(0), ByteArray(0), ShortArray(0), covered, incomplete)
        assertThat(tile(1000, 50).isTerrainAccurate).isTrue()    // exactly 95%
        assertThat(tile(1000, 51).isTerrainAccurate).isFalse()
        assertThat(tile(1000, 0).isTerrainAccurate).isTrue()
        assertThat(tile(1000, 1000).isTerrainAccurate).isFalse()
        assertThat(tile(0, 0).isTerrainAccurate).isFalse()       // nothing covered: no label to earn
        assertThat(RenderedTile(1, 0, 0, ByteArray(0), ByteArray(0), ByteArray(0), ShortArray(0)).isTerrainAccurate).isFalse()
    }
}
