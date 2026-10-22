package au.signalatlas.core.map

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class BasemapLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy {
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.root.resolve("basemap.preferences_pb") })
    }
    private var now = 1_800_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun locator(url: String = server.url("/builds.json").toString()) =
        BasemapLocator(OkHttpClient(), prefs, buildsUrl = url, clock = { now })

    private fun enqueueBuilds(vararg keys: String) {
        val body = keys.joinToString(",", "[", "]") {
            """{"key":"$it","size":139077432987,"version":"4.15.2","uploaded":"2026-10-22T05:00:00Z"}"""
        }
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build())
    }

    /** A URL nothing listens on: connection refused, as when offline. */
    private fun offlineUrl(): String {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/builds.json").toString()
        dead.close()
        return url
    }

    @After fun tearDown() {
        server.close()
        scope.cancel()
    }

    @Test
    fun picksTheLastKey() = runTest {
        enqueueBuilds("20261020.pmtiles", "20261021.pmtiles", "20261022.pmtiles")
        assertThat(locator().currentKey()).isEqualTo("20261022.pmtiles")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun cachedWithinSevenDaysMakesNoRequest() = runTest {
        enqueueBuilds("20261021.pmtiles", "20261022.pmtiles")
        assertThat(locator().currentKey()).isEqualTo("20261022.pmtiles")
        now += 6 * day
        enqueueBuilds("20261028.pmtiles")
        assertThat(locator().currentKey()).isEqualTo("20261022.pmtiles")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun refreshesAfterSevenDays() = runTest {
        enqueueBuilds("20261022.pmtiles")
        locator().currentKey()
        now += 7 * day + 1
        enqueueBuilds("20261022.pmtiles", "20261029.pmtiles")
        assertThat(locator().currentKey()).isEqualTo("20261029.pmtiles")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun offlineWithACacheReturnsTheCachedKey() = runTest {
        enqueueBuilds("20261022.pmtiles")
        locator().currentKey()
        now += 30 * day
        assertThat(locator(offlineUrl()).currentKey()).isEqualTo("20261022.pmtiles")
    }

    @Test
    fun offlineWithoutACacheReturnsNull() = runTest {
        assertThat(locator(offlineUrl()).currentKey()).isNull()
    }

    @Test
    fun aBadResponseKeepsTheCachedKey() = runTest {
        enqueueBuilds("20261022.pmtiles")
        locator().currentKey()
        now += 8 * day
        server.enqueue(MockResponse.Builder().code(500).body("oops").build())
        assertThat(locator().currentKey()).isEqualTo("20261022.pmtiles")
        server.enqueue(MockResponse.Builder().body("""[{"key":"../evil?x=1"}]""").build())
        assertThat(locator().currentKey()).isEqualTo("20261022.pmtiles")
    }
}
