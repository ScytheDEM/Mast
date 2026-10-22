package au.signalatlas.core.acma

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Imports the real ACMA export named by `ACMA_FULL_ZIP` and checks the counts against the Python
 * reference (tools/acma/README.md, export 2026-10-22). Skipped when the variable is unset.
 */
class FullExportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun fullExportMatchesReference() {
        val path = System.getenv("ACMA_FULL_ZIP")
        assumeTrue("ACMA_FULL_ZIP not set", !path.isNullOrBlank())
        val zip = File(path!!)
        val out = File(tmp.root, "towers.db")

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

        val start = System.nanoTime()
        val report = try {
            AcmaImporter().import(zip, out, "202610221934", "full-export-test", progress = { _, _ -> sample() })
        } finally {
            running.set(false)
            sampler.join()
        }
        val elapsedS = (System.nanoTime() - start) / 1e9
        val peakMb = peakHeap.get() / 1e6
        println(
            "FullExportTest: %.1f s, DB %.1f MB, peak heap used %.1f MB (baseline %.1f MB), %s".format(
                elapsedS, out.length() / 1e6, peakMb, baseline / 1e6, report,
            ),
        )

        assertThat(report).isEqualTo(
            ImportReport(
                sites = 25359,
                sectors = 537540,
                carriers = 852634,
                antennas = 1803,
                skippedUnknownBand = 304,
                skippedNoPower = 10,
                skippedBadSite = 0,
                skippedMalformed = 0,
                carriersByNetwork = mapOf(
                    "NBN" to 204154,
                    "OPTUS" to 190082,
                    "OPTUS_TPG_JV" to 23815,
                    "TELSTRA" to 231490,
                    "TPG" to 203093,
                ),
            ),
        )
        assertThat(peakMb).isLessThan(160.0)
    }
}
