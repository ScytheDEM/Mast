package au.signalatlas.core.geo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the real `dataset-links.csv` (about 7 MB) and the Canberra quadkey 311230302 (about 13 MB gz), then checks the
 * rasterised Civic cell. Run with `GEO_LIVE=1 ./gradlew :core:geo:testDebugUnitTest --tests '*BuildingFetcherLiveTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
class BuildingFetcherLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the Canberra quadkey is listed and Civic rasterises as built up`() {
        assumeTrue("set GEO_LIVE=1 to run against the real dataset", System.getenv("GEO_LIVE") == "1")
        val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
        val store = BuildingChunkStore(File(tmp.root, "geo/bld"), { Long.MAX_VALUE })
        val fetcher = BuildingFetcher(http, store)
        runBlocking {
            val parts = fetcher.partsOf("311230302")
            assertThat(parts).isNotEmpty()
            assertThat(parts.first().url).contains("quadkey=311230302")
            assertThat(parts.first().url).endsWith(".csv.gz")
            val civic = ChunkMath.idFor(-35.28, 149.13)
            println("live: ${Quadkey.forChunk(civic)} ~${fetcher.estimateBytes(setOf(civic)) / 1_000_000} MB")
            fetcher.fetch(setOf(civic), allowMetered = true) {}
            val c = store.get(civic)!!
            val row = ((-35.2795 + 90.0) * 100).toInt() - civic.latIdx * 25 // City / Civic
            val col = ((149.1305 + 180.0) * 100).toInt() - civic.lonIdx * 25
            println("live: Civic cover ${c.coverAt(row, col)} height ${c.heightAt(row, col)}")
            assertThat(c.coverAt(row, col)!!).isGreaterThan(0.15f)
            assertThat(c.heightAt(row, col)).isGreaterThan(8f)
        }
    }
}
