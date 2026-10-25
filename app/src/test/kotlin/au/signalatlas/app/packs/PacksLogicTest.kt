package au.signalatlas.app.packs

import au.signalatlas.core.geo.packs.PackArea
import au.signalatlas.core.geo.packs.PackCatalogue
import au.signalatlas.core.geo.packs.PackContentSource
import au.signalatlas.core.model.GeoBounds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PacksLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSource(override val id: String, val estimate: Long) : PackContentSource {
        var failAt: Float? = null
        val downloads = ArrayList<Long>() // generations
        val removed = ArrayList<String>()
        var held = 0L
        override suspend fun estimateBytes(area: PackArea) = estimate
        override suspend fun download(packId: String, generation: Long, area: PackArea, allowMetered: Boolean, progress: (Float) -> Unit) {
            downloads += generation
            for (f in listOf(0.25f, 0.5f, 1f)) {
                if (failAt != null && f >= failAt!!) throw IOException("network lost")
                progress(f)
                held = (estimate * f).toLong()
            }
        }
        override suspend fun sizeBytes(packId: String, area: PackArea) = held
        override suspend fun remove(packId: String, area: PackArea) { removed += packId; held = 0 }
    }

    private class FakeScheduler : PackScheduler {
        val queued = ArrayList<Pair<String, Boolean>>()
        val cancelled = ArrayList<String>()
        override fun enqueue(packId: String, allowMetered: Boolean) { queued += packId to allowMetered }
        override fun cancel(packId: String) { cancelled += packId }
    }

    private var now = 1000L
    private val basemap = FakeSource("basemap", 300)
    private val dem = FakeSource("dem", 100)
    private val scheduler = FakeScheduler()

    private fun repo() = PackRepository(File(tmp.root, "packs.json"))
    private fun manager(repo: PackRepository = repo()) =
        PackManager(repo, mapOf(PackManager.BASEMAP to basemap, PackManager.ELEVATION to dem), scheduler) { now++ }

    private val sydney = PackCatalogue.byId("metro-sydney")!!

    @Test fun `the size is the sum of the sources and is known before anything is queued`() = runBlocking<Unit> {
        val m = manager()
        val e = m.estimate(sydney.area)
        assertThat(e).isEqualTo(PackEstimate(300, 100))
        assertThat(e.totalBytes).isEqualTo(400)
        assertThat(m.repo.packs.value).isEmpty()
        assertThat(scheduler.queued).isEmpty()
    }

    @Test fun `download queues the pack with its mobile data choice and ignores a second request`() {
        val m = manager()
        m.download(sydney, allowMetered = true)
        m.download(sydney, allowMetered = false)
        assertThat(scheduler.queued).containsExactly("metro-sydney" to true)
        val p = m.repo.get("metro-sydney")!!
        assertThat(p.state).isEqualTo(PackState.QUEUED)
        assertThat(p.allowMetered).isTrue()
    }

    @Test fun `a download weights progress by bytes, ends ready and records the size`() = runBlocking<Unit> {
        val m = manager()
        m.download(sydney, false)
        val seen = ArrayList<Float>()
        PackDownloader(m.repo, listOf(basemap, dem)) { now++ }.run("metro-sydney", false) { seen += it }
        assertThat(seen).isInOrder()
        assertThat(seen.first()).isWithin(0.001f).of(0.75f * 0.25f) // basemap is 3/4 of the bytes
        assertThat(seen.last()).isEqualTo(1f)
        val p = m.repo.get("metro-sydney")!!
        assertThat(p.state).isEqualTo(PackState.READY)
        assertThat(p.bytes).isEqualTo(400)
        assertThat(p.updatedAt).isGreaterThan(0)
    }

    @Test fun `a failed download is queued again and resumes with the same generation`() = runBlocking<Unit> {
        val m = manager()
        m.download(sydney, false)
        val gen = m.repo.get("metro-sydney")!!.generation
        val downloader = PackDownloader(m.repo, listOf(basemap, dem)) { now++ }
        dem.failAt = 0.5f
        assertThrows(IOException::class.java) { runBlocking { downloader.run("metro-sydney", false) } }
        val mid = m.repo.get("metro-sydney")!!
        assertThat(mid.state).isEqualTo(PackState.QUEUED)
        assertThat(mid.error).isEqualTo("network lost")
        assertThat(mid.progress).isGreaterThan(0.75f) // the base map was finished

        dem.failAt = null
        downloader.run("metro-sydney", false)
        val done = m.repo.get("metro-sydney")!!
        assertThat(done.state).isEqualTo(PackState.READY)
        assertThat(done.error).isNull()
        assertThat(done.generation).isEqualTo(gen)
        assertThat(dem.downloads).containsExactly(gen, gen)
    }

    @Test fun `update starts a new generation and requeues, also for a failed pack`() {
        val m = manager()
        m.download(sydney, false)
        val first = m.repo.get("metro-sydney")!!
        m.repo.update("metro-sydney") { it.copy(state = PackState.FAILED, error = "x") }
        m.update("metro-sydney", allowMetered = true)
        val p = m.repo.get("metro-sydney")!!
        assertThat(p.state).isEqualTo(PackState.QUEUED)
        assertThat(p.error).isNull()
        assertThat(p.generation).isGreaterThan(first.generation)
        assertThat(scheduler.queued.last()).isEqualTo("metro-sydney" to true)
    }

    @Test fun `delete cancels the work, releases every source and forgets the pack`() = runBlocking<Unit> {
        val m = manager()
        m.download(sydney, false)
        m.delete("metro-sydney")
        assertThat(scheduler.cancelled).containsExactly("metro-sydney")
        assertThat(basemap.removed).containsExactly("metro-sydney")
        assertThat(dem.removed).containsExactly("metro-sydney")
        assertThat(m.repo.packs.value).isEmpty()
    }

    @Test fun `a custom box becomes a pack of its own`() {
        val m = manager()
        val def = m.downloadCustom("My box", PackArea.Box(GeoBounds(-34.0, 150.0, -33.0, 151.0)), false)
        assertThat(def.id).startsWith("custom-")
        assertThat(m.repo.get(def.id)!!.name).isEqualTo("My box")
    }

    @Test fun `packs survive a restart`() {
        val first = repo()
        manager(first).download(PackCatalogue.byId("road-hume")!!, false)
        first.update("road-hume") { it.copy(state = PackState.READY, bytes = 42, progress = 1f) }
        val reopened = repo()
        val p = reopened.get("road-hume")!!
        assertThat(p.state).isEqualTo(PackState.READY)
        assertThat(p.bytes).isEqualTo(42)
        assertThat(p.area).isEqualTo(PackCatalogue.byId("road-hume")!!.area)
    }
}
