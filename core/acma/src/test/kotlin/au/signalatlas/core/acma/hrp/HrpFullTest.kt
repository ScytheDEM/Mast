package au.signalatlas.core.acma.hrp

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import au.signalatlas.core.acma.AcmaImporter
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Imports the real HRP zip (`ACMA_HRP_ZIP`) against a towers.db built from the real RRL export
 * (`ACMA_FULL_ZIP`) and checks the counts against `tools/acma/hrp_reference.py` on the same files
 * (tools/acma/README.md). Skipped unless both variables are set.
 */
class HrpFullTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fullHrpMatchesReference() {
        val hrp = System.getenv("ACMA_HRP_ZIP")
        val rrl = System.getenv("ACMA_FULL_ZIP")
        assumeTrue("ACMA_HRP_ZIP and ACMA_FULL_ZIP not set", !hrp.isNullOrBlank() && !rrl.isNullOrBlank())
        val towers = File(tmp.root, "towers.db")
        AcmaImporter().import(File(rrl!!), towers, "202610221934", "full-export")
        val out = File(tmp.root, "patterns.db.building")

        val runtime = Runtime.getRuntime()
        val peakHeap = AtomicLong(0)
        fun sample() = peakHeap.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
        System.gc()
        val baseline = runtime.totalMemory() - runtime.freeMemory()
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                sample()
                Thread.sleep(5)
            }
        }.apply { isDaemon = true; start() }

        val log = mutableListOf<String>()
        val start = System.nanoTime()
        val report = try {
            HrpImporter(log = { log += it }).import(File(hrp!!), towers, out, "full-hrp-test", progress = { sample() })
        } finally {
            running.set(false)
            sampler.join()
        }
        val elapsedS = (System.nanoTime() - start) / 1e9
        val peakMb = peakHeap.get() / 1e6
        println(
            "HrpFullTest: %.1f s (%s), patterns.db %.2f MB, peak heap used %.1f MB (baseline %.1f MB), %s".format(
                elapsedS, log.joinToString(", "), out.length() / 1e6, peakMb, baseline / 1e6, report,
            ),
        )

        // hrp_reference.py on the same files (zip ETag 0x8DF2EDC716D83D0, entry 2026-10-21, export 2026-10-22).
        assertThat(report).isEqualTo(
            HrpReport(devices = 95319, patterns = 3390, skippedIncomplete = 741534, skippedUnknown = 17857, rows = 280553129),
        )
        BundledSQLiteDriver().open(out.path).use { db ->
            db.prepare("SELECT d.pattern_id, d.peak_bearing, d.peak_dbw, p.att FROM device_pattern d " +
                "JOIN pattern p ON p.id = d.pattern_id WHERE d.sdd_id = 2909149471").use { st ->
                assertThat(st.step()).isTrue()
                assertThat(st.getLong(0)).isEqualTo(1L)
                assertThat(st.getLong(1)).isEqualTo(265L)
                assertThat(st.getDouble(2)).isEqualTo(33.6)
                // Shared-context fact: floor of exactly peak - 25.0 dB = code 100.
                assertThat(st.getBlob(3).maxOf { it.toInt() and 0xFF }).isEqualTo(100)
            }
        }
        assertThat(peakMb).isLessThan(160.0)
    }
}
