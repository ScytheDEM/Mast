package au.signalatlas.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.model.GeoBounds
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
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

    @Test fun siteKindIsReadIntoSummariesDetailAndTheDasFilter() {
        assertThat(store.allSites(TowerFilter()).all { it.kind == au.signalatlas.core.model.SiteKind.MACRO }).isTrue()
        assertThat(store.allSites(TowerFilter(dasOnly = true))).isEmpty()
        val copy = File(tmp.newFolder(), "towers.db").also { db.copyTo(it) }
        BundledSQLiteDriver().open(copy.path).use { c ->
            c.execSQL("UPDATE site SET site_kind = 2 WHERE id = 101160")
            c.execSQL("UPDATE site SET site_kind = 1 WHERE id = 41428")
        }
        TowerStore.open(copy).use { s ->
            assertThat(s.summary(101160)!!.kind).isEqualTo(au.signalatlas.core.model.SiteKind.DAS_TUNNEL)
            assertThat(s.summary(41428)!!.kind).isEqualTo(au.signalatlas.core.model.SiteKind.DAS_BUILDING)
            assertThat(s.summary(9002575)!!.kind).isEqualTo(au.signalatlas.core.model.SiteKind.MACRO)
            assertThat(s.summary(-5)).isNull()
            assertThat(s.siteDetail(101160)!!.site.kind).isEqualTo(au.signalatlas.core.model.SiteKind.DAS_TUNNEL)
            assertThat(s.allSites(TowerFilter(dasOnly = true)).map { it.id }).containsExactly(41428, 101160)
            assertThat(s.nearest(-35.30, 149.13, TowerFilter(dasOnly = true), 5).map { it.first.id }).containsExactly(41428, 101160)
        }
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

    @Test fun sectorIdentitiesRoundTripAndIgnoreRowIds() {
        val rows = sql("SELECT id, site_id, network, antenna_id, azimuth_deg, height_m FROM sector") {
            listOf(it.getLong(0).toInt(), it.getLong(1).toInt(), it.getLong(2).toInt(), if (it.isNull(3)) null else it.getLong(3).toInt(),
                if (it.isNull(4)) null else it.getDouble(4), it.getDouble(5))
        }
        assertThat(rows).isNotEmpty()
        val ids = rows.map { it[0] as Int }
        val idents = store.sectorIdentities(ids)
        assertThat(idents.keys).containsExactlyElementsIn(ids)
        for (r in rows) {
            assertThat(idents[r[0] as Int]).isEqualTo(
                au.signalatlas.core.model.SectorIdentity.of(r[1] as Int, Network.entries[r[2] as Int], r[3] as Int?, r[4] as Double?, r[5] as Double),
            )
        }
        val back = store.sectorIdsByIdentity(idents.values)
        for ((id, ident) in idents) assertThat(back[ident]).isAtMost(id)  // lowest id on a (never expected) tie
        assertThat(back.keys).containsExactlyElementsIn(idents.values.toSet())
        assertThat(store.sectorIdsByIdentity(listOf("1|0|-|-|0", "garbage"))).isEmpty()
        assertThat(store.sectorIdentities(listOf(-5))).isEmpty()
    }

    // Independent textbook haversine (R = 6371008.8 m).
    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_008.8
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
        val a = Math.pow(Math.sin((p2 - p1) / 2), 2.0) +
            Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(lo2 - lo1) / 2), 2.0)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    @Test fun openRejectsAnotherSchemaVersion() {
        val copy = File(tmp.newFolder(), "towers.db").also { db.copyTo(it) }
        BundledSQLiteDriver().open(copy.path).use { c ->
            c.execSQL("UPDATE meta SET value = '3' WHERE key = 'schema_version'")
        }
        try {
            TowerStore.open(copy)
            error("expected SchemaMismatchException")
        } catch (e: SchemaMismatchException) {
            assertThat(e.found).isEqualTo(3)
            assertThat(e.expected).isEqualTo(2)
        }
    }

    @Test fun carriersInCountMatchesSqlForTelstra() {
        val telstra = Network.TELSTRA.ordinal
        val expected = sql(
            """SELECT COUNT(*) FROM carrier c JOIN sector s ON s.id = c.sector_id JOIN site t ON t.id = s.site_id
               WHERE s.network = $telstra AND t.lat BETWEEN -35.4 AND -35.2 AND t.lon BETWEEN 149.0 AND 149.3""",
        ) { it.getLong(0).toInt() }[0]
        assertThat(expected).isGreaterThan(0)

        val batch = store.carriersIn(canberra, setOf(Network.TELSTRA), Tech.entries.toSet(), null)
        assertThat(batch.size).isEqualTo(expected)
        assertThat(batch.network.all { it.toInt() == telstra }).isTrue()
        assertThat(batch.siteLat.size).isEqualTo(expected)
        assertThat(batch.sddId.size).isEqualTo(expected)
        assertThat(batch.sddId.toSet()).hasSize(expected)
        for (i in 0 until batch.size) assertThat(canberra.contains(batch.siteLat[i], batch.siteLon[i])).isTrue()
    }

    @Test fun carriersInFiltersByTechAndBand() {
        val all = Network.entries.toSet()
        val everything = store.carriersIn(canberra, all, Tech.entries.toSet(), null)
        val nr = store.carriersIn(canberra, all, setOf(Tech.NR), null)
        val lte = store.carriersIn(canberra, all, setOf(Tech.LTE), null)
        assertThat(nr.size + lte.size).isEqualTo(everything.size)
        assertThat(nr.tech.all { it.toInt() == Tech.NR.ordinal }).isTrue()

        val band = everything.bandLabels.first()
        val one = store.carriersIn(canberra, all, Tech.entries.toSet(), setOf(band))
        val expected = (0 until everything.size).count { everything.bandLabels[everything.bandIdx[it].toInt()] == band }
        assertThat(one.size).isEqualTo(expected)
        assertThat(one.bandIdx.all { one.bandLabels[it.toInt()] == band }).isTrue()

        assertThat(store.carriersIn(canberra, emptySet(), Tech.entries.toSet(), null).size).isEqualTo(0)
        assertThat(store.carriersIn(canberra, all, Tech.entries.toSet(), emptySet()).size).isEqualTo(0)
        assertThat(store.carriersIn(GeoBounds(10.0, 100.0, 11.0, 101.0), all, Tech.entries.toSet(), null).size).isEqualTo(0)
    }

    @Test fun carriersInBandLabelsAreAllBandsSortedByFrequency() {
        val batch = store.carriersIn(canberra, Network.entries.toSet(), Tech.entries.toSet(), null)
        assertThat(batch.bandLabels).isEqualTo(store.allBands)
    }

    @Test fun carriersInArraysMatchSqlForSdd999999304() {
        val row = sql(
            """SELECT t.lat, t.lon, s.height_m, s.azimuth_deg, c.tilt_deg, c.eirp_dbm, c.freq_hz, c.bw_hz, c.tech,
                      s.network, a.h_bw_deg, a.f2b_db, a.gain_dbi, c.sector_id, c.band
               FROM carrier c JOIN sector s ON s.id = c.sector_id JOIN site t ON t.id = s.site_id
               LEFT JOIN antenna a ON a.id = s.antenna_id WHERE c.sdd_id = 999999304""",
        ) { st ->
            fun d(i: Int) = if (st.isNull(i)) Double.NaN else st.getDouble(i)
            listOf(
                st.getDouble(0), st.getDouble(1), st.getDouble(2), d(3), st.getDouble(4), st.getDouble(5),
                st.getLong(6) / 1e6, st.getLong(7).toDouble(), st.getLong(8).toDouble(), st.getLong(9).toDouble(),
                d(10), d(11), d(12), st.getLong(13).toDouble(), st.getText(14),
            )
        }.single()
        val b = store.carriersIn(
            GeoBounds(-90.0, -180.0, 90.0, 180.0), Network.entries.toSet(), Tech.entries.toSet(), null,
        )
        val i = b.sddId.indexOf(999999304L)
        assertThat(i).isAtLeast(0)
        assertThat(b.siteLat[i]).isEqualTo(row[0])
        assertThat(b.siteLon[i]).isEqualTo(row[1])
        assertThat(b.heightM[i]).isEqualTo((row[2] as Double).toFloat())
        assertThat(b.azimuthDeg[i]).isEqualTo((row[3] as Double).toFloat())
        assertThat(b.tiltDeg[i]).isEqualTo((row[4] as Double).toFloat())
        assertThat(b.eirpDbm[i]).isEqualTo((row[5] as Double).toFloat())
        assertThat(b.freqMhz[i]).isEqualTo((row[6] as Double).toFloat())
        assertThat(b.bandwidthHz[i]).isEqualTo((row[7] as Double).toInt())
        assertThat(b.tech[i].toInt()).isEqualTo((row[8] as Double).toInt())
        assertThat(b.network[i].toInt()).isEqualTo((row[9] as Double).toInt())
        assertThat(b.hBeamwidthDeg[i]).isEqualTo((row[10] as Double).toFloat())
        assertThat(b.frontToBackDb[i]).isEqualTo((row[11] as Double).toFloat())
        assertThat(b.gainDbi[i]).isEqualTo((row[12] as Double).toFloat())
        assertThat(b.sectorId[i]).isEqualTo((row[13] as Double).toInt())
        assertThat(b.bandLabels[b.bandIdx[i].toInt()]).isEqualTo(row[14])
    }

    @Test fun carriersInUsesNaNForOmniAndMissingAntennaFields() {
        val omni = sql("SELECT COUNT(*) FROM sector WHERE azimuth_deg IS NULL AND id IN (SELECT sector_id FROM carrier)") {
            it.getLong(0).toInt()
        }[0]
        val noAntenna = sql(
            "SELECT COUNT(*) FROM carrier c JOIN sector s ON s.id = c.sector_id WHERE s.antenna_id IS NULL",
        ) { it.getLong(0).toInt() }[0]
        val b = store.carriersIn(
            GeoBounds(-90.0, -180.0, 90.0, 180.0), Network.entries.toSet(), Tech.entries.toSet(), null,
        )
        val sectorsOmni = (0 until b.size).filter { b.azimuthDeg[it].isNaN() }.map { b.sectorId[it] }.toSet()
        assertThat(sectorsOmni).hasSize(omni)
        assertThat((0 until b.size).count { b.gainDbi[it].isNaN() && b.hBeamwidthDeg[it].isNaN() && b.frontToBackDb[it].isNaN() })
            .isAtLeast(noAntenna)
    }

    @Test fun carriersInGivesTheSameRowsWhateverTheChunkSize() {
        val everywhere = GeoBounds(-90.0, -180.0, 90.0, 180.0)
        val nets = Network.entries.toSet()
        val techs = Tech.entries.toSet()
        val expected = sql("SELECT COUNT(*) FROM carrier") { it.getLong(0).toInt() }[0]
        val whole = store.carriersIn(everywhere, nets, techs, null)
        assertThat(whole.size).isEqualTo(expected)
        for (chunk in listOf(1, 2, 7)) {
            val chunked = store.carriersIn(everywhere, nets, techs, null, chunk)
            assertThat(chunked.sddId).isEqualTo(whole.sddId)
            assertThat(chunked.siteLat).isEqualTo(whole.siteLat)
        }
    }

    @Test fun carriersInOnAClosedStoreThrows() {
        val copy = TowerStore.open(db)
        copy.close()
        try {
            copy.carriersIn(canberra, Network.entries.toSet(), Tech.entries.toSet(), null)
            error("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().contains("closed")
        }
    }

    @Test fun siteCountWithinMatchesBruteForce() {
        val all = store.allSites(TowerFilter())
        for ((lat, lon, r) in listOf(
            Triple(-35.30, 149.13, 5_000.0), Triple(-35.30, 149.13, 50_000.0),
            Triple(-35.30, 149.13, 1_000_000.0), Triple(-33.87, 151.21, 20_000.0), Triple(-12.0, 130.0, 100.0),
        )) {
            val expected = all.count { haversine(lat, lon, it.lat, it.lon) <= r }
            assertThat(store.siteCountWithin(lat, lon, r)).isEqualTo(expected)
        }
    }

    @Test fun siteCountWithinCountsSitesNotCarriers() {
        val s = store.allSites(TowerFilter()).first { store.siteDetail(it.id)!!.sectors.sumOf { s -> s.carriers.size } > 1 }
        assertThat(store.siteCountWithin(s.lat, s.lon, 0.5)).isAtLeast(1)
        val n = store.allSites(TowerFilter()).count { it.lat == s.lat && it.lon == s.lon }
        assertThat(store.siteCountWithin(s.lat, s.lon, 0.5)).isEqualTo(n)
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
