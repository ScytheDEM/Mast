package au.signalatlas.core.calibration

import au.signalatlas.core.model.CalibrationRules
import au.signalatlas.core.model.Network
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.log10
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = ArrayList<CalibrationDb>()

    private fun db(file: File = File(tmp.root, "calibration.db")) = CalibrationDb.open(file).also { opened += it }

    @After fun close() = opened.forEach { it.close() }

    private fun TestScope.store(d: CalibrationDb = db()) = CalibrationStore(d, backgroundScope, { testScheduler.currentTime })

    @Test fun firstChangePublishesAtOnceThenAtMostEveryThirtySeconds() = runTest {
        val s = store()
        assertThat(s.snapshot.value.version).isEqualTo(0L)
        s.add(sample())
        runCurrent()
        assertThat(s.snapshot.value.version).isEqualTo(1L)
        assertThat(s.snapshot.value.sectors[7]!!.n).isEqualTo(1)
        repeat(5) { s.add(sample()) }
        advanceTimeBy(29_000)
        runCurrent()
        assertThat(s.snapshot.value.version).isEqualTo(1L)  // coalesced
        advanceTimeBy(1_100)
        runCurrent()
        assertThat(s.snapshot.value.version).isEqualTo(2L)
        assertThat(s.snapshot.value.sectors[7]!!.n).isEqualTo(6)
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(s.snapshot.value.version).isEqualTo(2L)  // nothing new: no publish
        s.add(sample())  // > 30 s since the last publish: at once
        runCurrent()
        assertThat(s.snapshot.value.version).isEqualTo(3L)
    }

    @Test fun offsetFollowsRuleFourAndAppliesFromThreeSamples() = runTest {
        val s = store()
        s.add(sample(rsrp = -90, predicted = -96.0))
        s.add(sample(rsrp = -88, predicted = -96.0))
        runCurrent()
        advanceTimeBy(31_000)
        val two = s.snapshot.value.sectors[7]!!
        assertThat(two.offsetDb).isWithin(1e-9).of(CalibrationMath.offsetOnly(2, 14.0))
        assertThat(two.isApplied).isFalse()
        s.add(sample(rsrp = -91, predicted = -96.0))
        advanceTimeBy(31_000)
        val three = s.snapshot.value.sectors[7]!!
        assertThat(three.n).isEqualTo(3)
        assertThat(three.isApplied).isTrue()
        assertThat(three.offsetDb).isWithin(1e-9).of(CalibrationMath.offsetOnly(3, 19.0))
        assertThat(three.slopeDbPerDecade).isEqualTo(0.0)
        assertThat(three.sigmaScale).isWithin(1e-12).of(CalibrationRules.sigmaScale(3))
        assertThat(three.network).isEqualTo(Network.TELSTRA.ordinal)
        assertThat(three.siteLat).isEqualTo(-35.27)
        // A wildly wrong sector cannot exceed the clamp.
        repeat(5) { s.add(sample(sector = 9, rsrp = -50, predicted = -120.0)) }
        advanceTimeBy(31_000)
        assertThat(s.snapshot.value.sectors[9]!!.offsetDb).isEqualTo(20.0)
    }

    @Test fun slopeIsRefittedFromThirtySamplesWithSpread() = runTest {
        val s = store()
        val r = DoubleArray(36)
        val d = DoubleArray(36)
        for (i in 0 until 36) {
            d[i] = 0.3 * Math.pow(10.0, i / 35.0 * 1.2)   // 0.3 .. 4.75 km
            r[i] = 4.0 - 12.0 * log10(d[i]) + (if (i % 2 == 0) 1.5 else -1.5)
            s.add(sample(rsrp = -100, predicted = -100.0 - r[i], distanceM = d[i] * 1000.0))
        }
        advanceTimeBy(31_000)
        val got = s.snapshot.value.sectors[7]!!
        val want = CalibrationMath.fitSector(r, d)
        assertThat(want.slopeFitted).isTrue()
        assertThat(got.slopeDbPerDecade).isWithin(1e-6).of(want.slopeDbPerDecade)
        assertThat(got.offsetDb).isWithin(1e-6).of(want.offsetDb)
        assertThat(got.dMedianKm).isWithin(1e-9).of(want.dMedianKm)
        assertThat(got.slopeDbPerDecade).isLessThan(-5.0)
    }

    @Test fun residualCellsUseTheCorrectionAndOnlyApplyFromThreeSamples() = runTest {
        val s = store()
        // Sector 7: 3 samples r = +6 in one cell (offset applies), plus one sample in another cell.
        repeat(3) { s.add(sample(rsrp = -90, predicted = -96.0, lat = -35.2805, lon = 149.1305)) }
        s.add(sample(rsrp = -80, predicted = -96.0, lat = -35.2905, lon = 149.1305))
        advanceTimeBy(31_000)
        val snap = s.snapshot.value
        val mu = snap.sectors[7]!!.offsetDb
        val main = snap.residuals.single { it.latIdx == CalibrationRules.cellIndex(-35.2805) }
        assertThat(main.n).isEqualTo(3)
        assertThat(main.meanDb).isWithin(1e-9).of(6.0 - mu)
        assertThat(main.appliedDb).isWithin(1e-9).of(3.0 / 8.0 * (6.0 - mu))
        val lone = snap.residuals.single { it.latIdx == CalibrationRules.cellIndex(-35.2905) }
        assertThat(lone.n).isEqualTo(1)
        assertThat(lone.appliedDb).isEqualTo(0.0)
        assertThat(main.network).isEqualTo(Network.TELSTRA.ordinal)
    }

    @Test fun resetSectorAndDeleteAllForgetAndPublishAtOnce() = runTest {
        val s = store()
        repeat(3) { s.add(sample(sector = 7)) }
        repeat(3) { s.add(sample(sector = 8)) }
        s.putCellMapping(CellMapping("p:0:0:1:2", Network.TELSTRA, 7, 5007L, 5, true))
        s.putCellMapping(CellMapping("p:0:0:3:2", Network.TELSTRA, 8, 5008L, 2, false))
        runCurrent()
        val before = s.snapshot.value.version
        s.resetSector(7)
        assertThat(s.snapshot.value.version).isEqualTo(before + 1)
        assertThat(s.snapshot.value.sectors.keys).containsExactly(8)
        assertThat(s.snapshot.value.residuals.map { it.n }).containsExactly(3)
        assertThat(s.cellMapping("p:0:0:1:2")).isNull()
        assertThat(s.cellMapping("p:0:0:3:2")).isNotNull()
        assertThat(s.sampleCount()).isEqualTo(3)
        s.deleteAll()
        assertThat(s.snapshot.value.version).isEqualTo(before + 2)
        assertThat(s.snapshot.value.sectors).isEmpty()
        assertThat(s.snapshot.value.residuals).isEmpty()
        assertThat(s.cellMapping("p:0:0:3:2")).isNull()
        assertThat(s.sampleCount()).isEqualTo(0)
    }

    @Test fun uncertainSamplesAreRejected() = runTest {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) { s.add(sample().copy(confidence = Confidence.UNCERTAIN)) }
        assertThat(s.sampleCount()).isEqualTo(0)
    }

    @Test fun csvExportHasEveryFieldOfEverySample() = runTest {
        val s = store()
        s.add(sample(rsrp = -90))
        s.add(sample(rsrp = -91).copy(cellId = null, taM = null, confidence = Confidence.CONFIRMED))
        val csv = StringBuilder().also(s::exportCsv).toString().trimEnd().lines()
        assertThat(csv).hasSize(3)
        val header = csv[0].split(',')
        assertThat(header).isEqualTo(CalibrationDb.SAMPLE_COLUMNS)
        assertThat(header).containsAtLeast("time_ms", "lat", "lon", "accuracy_m", "network", "tech", "channel", "pci",
            "cell_id", "ta_m", "rsrp", "rsrq", "sinr", "sdd_id", "sector_id", "predicted_rsrp", "distance_m", "area")
        val first = csv[1].split(',')
        assertThat(first).hasSize(header.size)
        fun col(row: List<String>, name: String) = row[header.indexOf(name)]
        assertThat(col(first, "rsrp")).isEqualTo("-90")
        assertThat(col(first, "network")).isEqualTo("TELSTRA")
        assertThat(col(first, "area")).isEqualTo("URBAN")
        assertThat(col(first, "cell_id")).isEqualTo("123456")
        val second = csv[2].split(',')
        assertThat(col(second, "cell_id")).isEmpty()
        assertThat(col(second, "ta_m")).isEmpty()
        assertThat(col(second, "confidence")).isEqualTo("CONFIRMED")
    }

    @Test fun dataPersistsAcrossReopenAndAForeignSchemaIsDiscarded() = runTest {
        val file = File(tmp.root, "c.db")
        val first = db(file)
        val s = store(first)
        repeat(4) { s.add(sample()) }
        s.putCellMapping(CellMapping("c:0:0:9", Network.TELSTRA, 7, 5007L, 5, true))
        first.close()
        opened.remove(first)
        val again = store(db(file))
        assertThat(again.snapshot.value.version).isEqualTo(1L)  // published from the stored data at start
        assertThat(again.snapshot.value.sectors[7]!!.n).isEqualTo(4)
        assertThat(again.cellMapping("c:0:0:9")!!.confirmed).isTrue()
        opened.forEach { it.close() }
        opened.clear()
        val raw = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(file.path)
        raw.prepare("PRAGMA user_version = 99").use { it.step() }
        raw.close()
        assertThat(store(db(file)).sampleCount()).isEqualTo(0)
    }
}
