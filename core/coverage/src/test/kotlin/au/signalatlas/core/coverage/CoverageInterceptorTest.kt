package au.signalatlas.core.coverage

import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverageInterceptorTest {
    @get:Rule val tmp = TemporaryFolder()
    private var rig: Rig? = null

    @After fun tearDown() { rig?.close() }

    private fun rig() = Rig(tmp.newFolder()).also { rig = it }

    /** The interceptor in front of a terminal interceptor standing in for the network (code 299 marks a pass-through). */
    private fun client(r: Rig, timeoutMs: Long = 20_000) = OkHttpClient.Builder()
        .addInterceptor(CoverageInterceptor(r.service, timeoutMs))
        .addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(299).message("network")
                .body("net".toResponseBody("text/plain".toMediaType())).build()
        })
        .build()

    private fun get(c: OkHttpClient, url: String): Response = c.newCall(Request.Builder().url(url).build()).execute()

    @Test fun otherHostsPassThrough() {
        val r = rig()
        get(client(r), "https://example.com/v1/x/rsrp/s/none/t0/10/1/1.png").use {
            assertThat(it.code).isEqualTo(299)
        }
        assertThat(r.renderer.renders).isEmpty()
    }

    @Test fun servesPngWithHeaders() {
        val r = rig()
        get(client(r), r.url(10, 900, 600)).use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.header("Content-Type")).isEqualTo("image/png")
            assertThat(it.header("Cache-Control")).isEqualTo("no-store")
            assertThat(it.body.bytes().take(4)).containsExactly(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).inOrder()
        }
    }

    @Test fun zoomsOutsideEightToSixteenAreNoContent() {
        val r = rig()
        val c = client(r)
        for (z in intArrayOf(0, 7, 17, 20)) get(c, r.url(z, 0, 0)).use { assertThat(it.code).isEqualTo(204) }
        assertThat(r.renderer.renders).isEmpty()
        get(c, r.url(8, 1, 1)).use { assertThat(it.code).isEqualTo(200) }
        get(c, r.url(16, 1, 1)).use { assertThat(it.code).isEqualTo(200) }
    }

    @Test fun malformedPathsAreNotFound() {
        val r = rig()
        val c = client(r)
        val id = r.selection.cacheId()
        val base = "https://${CoverageTileService.HOST}"
        for (path in listOf(
            "/", "/v1/$id/rsrp/stamp1/none/t0/10/900/600", "/v1/$id/rsrp/stamp1/none/t0/10/900/600.jpg",
            "/v2/$id/rsrp/stamp1/none/t0/10/900/600.png", "/v1/$id/heat/stamp1/none/t0/10/900/600.png",
            "/v1/$id/rsrp/stamp1/none/t0/ten/900/600.png", "/v1/$id/rsrp/stamp1/none/t0/10/9999/600.png",
            "/v1/$id/rsrp/stamp1/none/t0/10/-1/600.png", "/v1/$id/rsrp/stamp1/none/t0/10/900/600/extra.png",
        )) {
            get(c, base + path).use { assertThat(it.code).isEqualTo(404) }
        }
    }

    @Test fun staleUrlsAreGoneAndCoverageOffIsNotFound() {
        val r = rig()
        val c = client(r)
        get(c, r.url(10, 900, 600, stamp = "old")).use { assertThat(it.code).isEqualTo(410) }
        get(c, r.url(10, 900, 600).replace(r.selection.cacheId(), "telstra_t3_all_in")).use { assertThat(it.code).isEqualTo(410) }
        r.service.selection.value = null
        get(c, r.url(10, 900, 600)).use { assertThat(it.code).isEqualTo(404) }
    }

    @Test fun timesOutWith504() {
        val r = rig()
        r.renderer.hold = true
        get(client(r, timeoutMs = 300), r.url(10, 900, 600)).use { assertThat(it.code).isEqualTo(504) }
    }

    @Test fun aCancelledRenderIsServiceUnavailable() {
        val r = rig()
        r.renderer.hold = true
        val c = client(r)
        val result = java.util.concurrent.CompletableFuture<Int>()
        Thread { result.complete(get(c, r.url(10, 900, 600)).use { it.code }) }.start()
        waitUntil { r.renderer.renders.size == 1 }
        r.service.setVisible(emptySet())
        assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(503)
    }
}
