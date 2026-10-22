package au.signalatlas.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.TowerFilter
import com.google.common.truth.Truth.assertThat
import java.io.File
import androidx.sqlite.execSQL
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TowerStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: File
    private lateinit var store: TowerStore
    private val canberra = GeoBounds(-35.4, 149.0, -35.2, 149.3)

    @Before fun setUp() {
        db = File(tmp.newFolder(), "towers.db")
        val fixture = File(javaClass.getResource("/acma/fixture.zip")!!.toURI())
        AcmaImporter().import(fixture, db, "stamp-1", "etag-1")
        store = TowerStore.open(db)
    }

    @After fun tearDown() { store.close() }

    private fun <T> sql(query: String, read: (androidx.sqlite.SQLiteStatement) -> T): List<T> =
        BundledSQLiteDriver().open(db.path).use { c ->
            c.prepare(query).use { st -> buildList { while (st.step()) add(read(st)) } }
        }

    @Test fun sitesInCanberraFindsFixtureSites() {
        val ids = store.sitesIn(canberra, TowerFilter()).map { it.id }
        assertThat(ids).containsAtLeast(101160, 41428, 9002575)
    }

    @Test fun sitesInMatchesAllSitesBruteForce() {
        val all = store.allSites(TowerFilter())
        assertThat(all).hasSize(store.meta.report.sites)
        assertThat(store.sitesIn(canberra, TowerFilter()))
            .containsExactlyElementsIn(all.filter { canberra.contains(it.lat, it.lon) })
    }

    @Test fun optusFilterKeepsOnlyOptusSites() {
        val f = TowerFilter(networks = setOf(Network.OPTUS))
        val sites = store.sitesIn(canberra, f)
        assertThat(sites).isNotEmpty()
        assertThat(sites.all { Network.OPTUS in it.networks }).isTrue()
        val unfiltered = store.sitesIn(canberra, TowerFilter())
        assertThat(sites.map { it.id })
            .containsExactlyElementsIn(unfiltered.filter { Network.OPTUS in it.networks }.map { it.id })
    }

    @Test fun nearestIsSortedAndFirstDistanceIsHaversine() {
        val res = store.nearest(-35.30, 149.13, TowerFilter(), limit = 5)
        assertThat(res).isNotEmpty()
        assertThat(res.map { it.second }).isInOrder()
        val (first, d) = res[0]
        val ref = haversine(-35.30, 149.13, first.lat, first.lon)
        assertThat(Math.abs(d - ref)).isLessThan(1.0)
        // Brute-force check that no site is closer than the reported nearest.
        val best = store.allSites(TowerFilter()).minOf { haversine(-35.30, 149.13, it.lat, it.lon) }
        assertThat(d).isWithin(1e-6).of(best)
    }

    @Test fun nearestRespectsFilterAndLimit() {
        val f = TowerFilter(networks = setOf(Network.TELSTRA))
        val res = store.nearest(-35.30, 149.13, f, limit = 2)
        assertThat(res.size).isAtMost(2)
        assertThat(res.all { Network.TELSTRA in it.first.networks }).isTrue()
    }

    // Independent textbook haversine (R = 6371008.8 m).
    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_008.8
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
        val a = Math.pow(Math.sin((p2 - p1) / 2), 2.0) +
            Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(lo2 - lo1) / 2), 2.0)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    @Test fun searchByIdPutsExactSiteFirst() {
        val res = store.search("41428")
        assertThat(res.first().id).isEqualTo(41428)
        assertThat(store.search(" 41428 ").first().id).isEqualTo(41428)
    }

    @Test fun searchByPostcodeFindsSitesWithThatPostcode() {
        val (id, pc) = sql("SELECT id, postcode FROM site WHERE postcode IS NOT NULL LIMIT 1") { it.getLong(0).toInt() to it.getText(1) }[0]
        val expected = sql("SELECT id FROM site WHERE postcode = '$pc' ORDER BY id") { it.getLong(0).toInt() }
        assertThat(expected).contains(id)
        assertThat(store.search(pc, limit = 100).map { it.id }).containsExactlyElementsIn(expected)
    }

    @Test fun searchByNameIsCaseInsensitiveContains() {
        assertThat(store.search("barton").map { it.id }).contains(101160)
        assertThat(store.search("BARTON").map { it.id }).contains(101160)
        assertThat(store.search("barton", limit = 1)).hasSize(1)
    }

    @Test fun digitsOnlyQueryFallsBackToNameContains() {
        val expected = sql("SELECT id FROM site WHERE name LIKE '%135%' ORDER BY id") { it.getLong(0).toInt() }
        assertThat(expected).contains(9019760)
        assertThat(store.search("135").map { it.id }).containsExactlyElementsIn(expected)
    }

    @Test fun leadingZeroQueryDoesNotMatchSiteId() {
        assertThat(store.search("9002575").map { it.id }).contains(9002575)
        assertThat(store.search("09002575").map { it.id }).doesNotContain(9002575)
    }

    @Test fun searchGroupsAreOrderedDedupedAndCapped() {
        // Copy the DB and engineer one site per group for the query "41428".
        val copy = File(tmp.newFolder(), "towers.db").also { db.copyTo(it) }
        BundledSQLiteDriver().open(copy.path).use { c ->
            c.execSQL("UPDATE site SET postcode = '41428' WHERE id = 9002575")
            c.execSQL("UPDATE site SET name = 'Depot 41428 North' WHERE id = 101160")
        }
        TowerStore.open(copy).use { s ->
            assertThat(s.search("41428").map { it.id }).containsExactly(41428, 9002575, 101160).inOrder()
            assertThat(s.search("41428", limit = 2).map { it.id }).containsExactly(41428, 9002575).inOrder()
            assertThat(s.search("41428", limit = 1).map { it.id }).containsExactly(41428)
        }
    }

    @Test fun openNonexistentPathThrowsAndCreatesNothing() {
        val missing = File(tmp.newFolder(), "nope.db")
        try {
            TowerStore.open(missing)
            error("expected FileNotFoundException")
        } catch (e: java.io.FileNotFoundException) {
            // expected
        }
        assertThat(missing.exists()).isFalse()
    }

    @Test fun searchBlankOrNoMatchIsEmpty() {
        assertThat(store.search("   ")).isEmpty()
        assertThat(store.search("zzzz-no-such-site")).isEmpty()
    }

    @Test fun siteDetailMatchesDbCounts() {
        val detail = store.siteDetail(101160)!!
        assertThat(detail.site.id).isEqualTo(101160)
        val sectorCount = sql("SELECT COUNT(*) FROM sector WHERE site_id = 101160") { it.getLong(0).toInt() }[0]
        val carrierCount = sql(
            "SELECT COUNT(*) FROM carrier WHERE sector_id IN (SELECT id FROM sector WHERE site_id = 101160)",
        ) { it.getLong(0).toInt() }[0]
        assertThat(sectorCount).isGreaterThan(0)
        assertThat(detail.sectors).hasSize(sectorCount)
        assertThat(detail.sectors.sumOf { it.carriers.size }).isEqualTo(carrierCount)
        assertThat(detail.sectors.all { it.siteId == 101160 }).isTrue()
    }

    @Test fun siteDetailOrdering() {
        for (summary in store.allSites(TowerFilter())) {
            val d = store.siteDetail(summary.id)!!
            val keys = d.sectors.map { Triple(it.network.ordinal, it.azimuthDeg ?: -1.0, it.id) }
            assertThat(keys).isInOrder(compareBy<Triple<Int, Double, Int>>({ it.first }, { it.second }, { it.third }))
            for (s in d.sectors) {
                assertThat(s.carriers.map { it.freqHz }).isInOrder()
            }
        }
    }

    @Test fun siteDetailUnknownIsNull() {
        assertThat(store.siteDetail(-1)).isNull()
    }

    @Test fun summariesAgreeWithDetail() {
        for (s in store.allSites(TowerFilter())) {
            val d = store.siteDetail(s.id)!!
            assertThat(d.sectors.map { it.network }.toSet()).isEqualTo(s.networks)
            assertThat(d.sectors.flatMap { it.carriers }.map { it.band.band }.toSet()).isEqualTo(s.bands)
            assertThat(d.sectors.flatMap { it.carriers }.map { it.band.tech }.toSet()).isEqualTo(s.techs)
        }
    }

    @Test fun allBandsSortedByMinimumFrequency() {
        val expected = sql("SELECT band FROM carrier GROUP BY band ORDER BY MIN(freq_hz), band") { it.getText(0) }
        assertThat(store.allBands).isEqualTo(expected)
        assertThat(store.allBands).isNotEmpty()
    }

    @Test fun metaIsLoaded() {
        assertThat(store.meta.extractStamp).isEqualTo("stamp-1")
        assertThat(store.meta.report.sites).isGreaterThan(0)
        assertThat(store.meta.importedAt.epochSecond).isGreaterThan(0L)
    }

    @Test fun concurrentReadersAreSafe() {
        val ids = store.allSites(TowerFilter()).map { it.id }
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = List(6) { t ->
            Thread {
                try {
                    repeat(100) { i ->
                        assertThat(store.siteDetail(ids[(i + t) % ids.size])).isNotNull()
                        assertThat(store.sitesIn(canberra, TowerFilter())).isNotEmpty()
                        store.nearest(-35.3, 149.1, TowerFilter(), 5)
                    }
                } catch (e: Throwable) { errors.add(e) }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertThat(errors).isEmpty()
    }

    @Test fun closeThenDetailIsNull() {
        assertThat(store.siteDetail(101160)).isNotNull()
        store.close()
        store.close() // idempotent
        // In-flight readers holding a replaced store must not crash; they re-read TowerRepository.store.
        assertThat(store.siteDetail(101160)).isNull()
    }
}
