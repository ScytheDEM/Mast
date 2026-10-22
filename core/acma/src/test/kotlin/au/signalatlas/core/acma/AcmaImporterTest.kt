package au.signalatlas.core.acma

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import au.signalatlas.core.acma.csv.AcmaFormatException
import au.signalatlas.core.acma.csv.CsvReader
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.log10
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AcmaImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture: File = File(javaClass.getResource("/acma/fixture.zip")!!.toURI())

    private fun importFixture(zip: File = fixture): Pair<ImportReport, File> {
        val out = File(tmp.root, "towers.db")
        val report = AcmaImporter().import(zip, out, extractStamp = "202610221934", zipEtag = "\"etag-1\"")
        return report to out
    }

    private fun <T> withDb(file: File, block: (SQLiteConnection) -> T): T {
        val conn = BundledSQLiteDriver().open(file.path)
        try {
            return block(conn)
        } finally {
            conn.close()
        }
    }

    /** Single-row single-column query; null if the value is SQL NULL. */
    private fun SQLiteConnection.scalarText(sql: String): String? = prepare(sql).use { st ->
        check(st.step()) { "no row for $sql" }
        if (st.isNull(0)) null else st.getText(0)
    }

    private fun SQLiteConnection.scalarDouble(sql: String): Double? = prepare(sql).use { st ->
        check(st.step()) { "no row for $sql" }
        if (st.isNull(0)) null else st.getDouble(0)
    }

    private fun SQLiteConnection.carrierRow(sddId: Long): Map<String, String?> = prepare(
        "SELECT c.band, c.tech, c.eirp_dbm, s.azimuth_deg, s.antenna_id FROM carrier c " +
            "JOIN sector s ON s.id = c.sector_id WHERE c.sdd_id = $sddId",
    ).use { st ->
        check(st.step()) { "carrier $sddId missing" }
        val names = listOf("band", "tech", "eirp_dbm", "azimuth_deg", "antenna_id")
        names.indices.associate { i -> names[i] to if (st.isNull(i)) null else st.getText(i) }
    }

    /** Copies [src], passing device_details.csv through [rewrite]. */
    private fun rewriteDevices(src: File, rewrite: (String) -> String): File {
        val dst = File(tmp.root, "rewritten-${System.nanoTime()}.zip")
        ZipFile(src).use { zf ->
            ZipOutputStream(dst.outputStream()).use { zos ->
                for (e in zf.entries()) {
                    var bytes = zf.getInputStream(e).readBytes()
                    if (e.name == "device_details.csv") {
                        bytes = rewrite(String(bytes, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)
                    }
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        return dst
    }

    private fun readRow(entry: String, keyCol: String, key: String): Map<String, String> =
        ZipFile(fixture).use { zf ->
            CsvReader(zf.getInputStream(zf.getEntry(entry))).use { r ->
                val k = r.column(keyCol)
                while (r.next()) {
                    if (r[k].trim() == key) return@use r.header.indices.associate { r.header[it] to r[it] }
                }
                error("$entry row $keyCol=$key not found")
            }
        }

    @Test
    fun fixtureMatchesReference() {
        val (report, _) = importFixture()
        val expected = Json.parseToJsonElement(javaClass.getResource("/acma/expected.json")!!.readText()).jsonObject
        fun int(name: String) = expected.getValue(name).jsonPrimitive.int
        assertThat(report.sites).isEqualTo(int("sites"))
        assertThat(report.sectors).isEqualTo(int("sectors"))
        assertThat(report.carriers).isEqualTo(int("carriers"))
        assertThat(report.antennas).isEqualTo(int("antennas"))
        assertThat(report.skippedUnknownBand).isEqualTo(int("skippedUnknownBand"))
        assertThat(report.skippedNoPower).isEqualTo(int("skippedNoPower"))
        assertThat(report.skippedBadSite).isEqualTo(int("skippedBadSite"))
        assertThat(report.skippedMalformed).isEqualTo(int("skippedMalformed"))
        val byNetwork = expected.getValue("carriersByNetwork").jsonObject.mapValues { it.value.jsonPrimitive.int }
        assertThat(report.carriersByNetwork).isEqualTo(byNetwork)
    }

    @Test
    fun tableCountsMatchReport() {
        val (report, out) = importFixture()
        withDb(out) { db ->
            assertThat(db.scalarText("SELECT COUNT(*) FROM site")!!.toInt()).isEqualTo(report.sites)
            assertThat(db.scalarText("SELECT COUNT(*) FROM sector")!!.toInt()).isEqualTo(report.sectors)
            assertThat(db.scalarText("SELECT COUNT(*) FROM carrier")!!.toInt()).isEqualTo(report.carriers)
            assertThat(db.scalarText("SELECT COUNT(*) FROM antenna")!!.toInt()).isEqualTo(report.antennas)
            assertThat(db.scalarText("SELECT COUNT(*) FROM site_summary")!!.toInt()).isEqualTo(report.sites)
            // Sector IDs are dense 1..N.
            assertThat(db.scalarText("SELECT MAX(id) FROM sector")!!.toInt()).isEqualTo(report.sectors)
            assertThat(db.scalarText("SELECT COUNT(*) FROM sector WHERE id NOT IN (SELECT sector_id FROM carrier)"))
                .isEqualTo("0")
        }
    }

    @Test
    fun siteSummaryAggregatesSectors() {
        val (_, out) = importFixture()
        withDb(out) { db ->
            // Site 9002575 carries the synthetic Telstra n26, Optus B26 and TPG n77 rows among others.
            val bands = db.scalarText("SELECT bands FROM site_summary WHERE site_id = 9002575")!!.split(",")
            assertThat(bands).containsAtLeast("n26", "B26", "n77")
            assertThat(bands).isInOrder()
            val netMask = db.scalarText("SELECT network_mask FROM site_summary WHERE site_id = 9002575")!!.toInt()
            assertThat(netMask and 0b111).isEqualTo(0b111) // TELSTRA, OPTUS, TPG
            val techMask = db.scalarText("SELECT tech_mask FROM site_summary WHERE site_id = 9002575")!!.toInt()
            assertThat(techMask).isEqualTo(0b11)
        }
    }

    @Test
    fun derivedPowerRow() {
        val (_, out) = importFixture()
        val dev = readRow("device_details.csv", "SDD_ID", "999999101")
        assertThat(dev["EIRP"]).isEmpty()
        assertThat(dev["TRANSMITTER_POWER"]).isEqualTo("40")
        assertThat(dev["TRANSMITTER_POWER_UNIT"]).isEqualTo("W")
        val gain = readRow("antenna.csv", "ANTENNA_ID", dev.getValue("ANTENNA_ID")).getValue("GAIN").toDouble()
        val loss = dev.getValue("FEEDER_LOSS").toDouble()
        val expected = 10 * log10(40.0) + 30 + gain - loss
        withDb(out) { db ->
            val eirp = db.scalarDouble("SELECT eirp_dbm FROM carrier WHERE sdd_id = 999999101")!!
            assertThat(eirp).isWithin(0.01).of(expected)
        }
    }

    @Test
    fun syntheticRowValues() {
        val (_, out) = importFixture()
        withDb(out) { db ->
            val nr = "1" // Tech.NR.ordinal
            val lte = "0"
            db.carrierRow(999999301).let {
                assertThat(it["band"]).isEqualTo("n26")
                assertThat(it["tech"]).isEqualTo(nr)
            }
            db.carrierRow(999999302).let {
                assertThat(it["band"]).isEqualTo("B26")
                assertThat(it["tech"]).isEqualTo(lte)
            }
            db.carrierRow(999999303).let {
                assertThat(it["band"]).isEqualTo("n77")
                assertThat(it["tech"]).isEqualTo(nr)
            }
            assertThat(db.carrierRow(999999304)["eirp_dbm"]!!.toDouble()).isWithin(0.01).of(10 * log10(500.0))
            assertThat(db.carrierRow(999999305)["eirp_dbm"]!!.toDouble()).isWithin(1e-9).of(40.0)
            assertThat(db.carrierRow(999999306)["eirp_dbm"]!!.toDouble()).isWithin(1e-9).of(40.0)
            assertThat(db.carrierRow(999999307)["azimuth_deg"]).isNull()
            assertThat(db.carrierRow(999999308)["antenna_id"]).isNull()
            assertThat(db.carrierRow(999999309)["azimuth_deg"]!!.toDouble()).isEqualTo(22.3)
            // Skipped devices are absent.
            for (skipped in listOf(999999102L, 999999103L, 999999201L, 999999202L, 999999310L, 999999311L)) {
                assertThat(db.scalarText("SELECT COUNT(*) FROM carrier WHERE sdd_id = $skipped")).isEqualTo("0")
            }
        }
    }

    @Test
    fun malformedIdentifierRows() {
        val (report, out) = importFixture()
        assertThat(report.skippedMalformed).isEqualTo(1) // SDD_ID "ABC"
        withDb(out) { db ->
            fun sectorOf(sdd: Long) = db.scalarText("SELECT sector_id FROM carrier WHERE sdd_id = $sdd")
            // ANTENNA_ID "N/A" -> null antenna, so it shares 999999308's (blank antenna) sector.
            assertThat(db.carrierRow(999999401)["antenna_id"]).isNull()
            assertThat(sectorOf(999999401)).isEqualTo(sectorOf(999999308))
            // SITE_ID "X1" -> missing site.
            assertThat(db.scalarText("SELECT COUNT(*) FROM carrier WHERE sdd_id = 999999402")).isEqualTo("0")
            // SITE_ID "0101160" -> site 101160.
            assertThat(
                db.scalarText("SELECT s.site_id FROM carrier c JOIN sector s ON s.id = c.sector_id WHERE c.sdd_id = 999999403"),
            ).isEqualTo("101160")
        }
    }

    @Test
    fun identifierParsing() {
        assertThat(parseIdentifier(" 01000 ", Int.MAX_VALUE.toLong())).isEqualTo(1000L)
        assertThat(parseIdentifier("0", Int.MAX_VALUE.toLong())).isEqualTo(0L)
        assertThat(parseIdentifier("000000000000000000000042", Long.MAX_VALUE)).isEqualTo(42L)
        assertThat(parseIdentifier("2147483647", Int.MAX_VALUE.toLong())).isEqualTo(2147483647L)
        assertThat(parseIdentifier("2147483648", Int.MAX_VALUE.toLong())).isNull()
        assertThat(parseIdentifier("9223372036854775807", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE)
        assertThat(parseIdentifier("9223372036854775808", Long.MAX_VALUE)).isNull()
        assertThat(parseIdentifier("99999999999999999999", Long.MAX_VALUE)).isNull()
        for (bad in listOf("", "  ", "ABC", "N/A", "X1", "-1", "+1", "1.0", "1e3", "\u00b2")) {
            assertThat(parseIdentifier(bad, Long.MAX_VALUE)).isNull()
        }
    }

    @Test
    fun omniSectorFromRewrittenFixture() {
        // A real kept TPG device (azimuth 270) with AZIMUTH blanked in-test.
        val sddId = "2909216391"
        val zip = rewriteDevices(fixture) { csv ->
            val lines = csv.split("\n").toMutableList()
            val az = lines[0].trimEnd('\r').split(",").indexOf("AZIMUTH")
            val i = lines.indexOfFirst { it.startsWith("$sddId,") }
            val cells = lines[i].split(",").toMutableList()
            assertThat(cells[az]).isEqualTo("270")
            cells[az] = ""
            lines[i] = cells.joinToString(",")
            lines.joinToString("\n")
        }
        val (_, out) = importFixture()
        withDb(out) { db -> assertThat(db.carrierRow(sddId.toLong())["azimuth_deg"]!!.toDouble()).isEqualTo(270.0) }
        val (_, rewritten) = importFixture(zip)
        withDb(rewritten) { db -> assertThat(db.carrierRow(sddId.toLong())["azimuth_deg"]).isNull() }
    }

    @Test
    fun metaWritten() {
        val (report, out) = importFixture()
        withDb(out) { db ->
            assertThat(db.scalarText("SELECT value FROM meta WHERE key = 'schema_version'")).isEqualTo("1")
            assertThat(db.scalarText("SELECT value FROM meta WHERE key = 'extract_stamp'")).isEqualTo("202610221934")
            assertThat(db.scalarText("SELECT value FROM meta WHERE key = 'zip_etag'")).isEqualTo("\"etag-1\"")
            val importedAt = db.scalarText("SELECT value FROM meta WHERE key = 'imported_at'")!!
            assertThat(importedAt).endsWith("Z")
            java.time.Instant.parse(importedAt)
            val json = db.scalarText("SELECT value FROM meta WHERE key = 'report'")!!
            assertThat(Json.decodeFromString(ImportReport.serializer(), json)).isEqualTo(report)
        }
    }

    @Test
    fun overwritesExistingOutput() {
        val out = File(tmp.root, "towers.db")
        out.writeText("not a database")
        val report = AcmaImporter().import(fixture, out, "s", "e")
        withDb(out) { db ->
            assertThat(db.scalarText("SELECT COUNT(*) FROM carrier")!!.toInt()).isEqualTo(report.carriers)
        }
    }

    @Test
    fun missingColumnFailsClearly() {
        val zip = rewriteDevices(fixture) { csv ->
            val nl = csv.indexOf('\n')
            val header = csv.substring(0, nl).trimEnd('\r').split(",").filter { it != "EIRP" }
            header.joinToString(",") + csv.substring(nl)
        }
        val out = File(tmp.root, "towers.db")
        val e = assertThrows(AcmaFormatException::class.java) { AcmaImporter().import(zip, out, "s", "e") }
        assertThat(e.message).contains("EIRP")
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun cancelStops() {
        val out = File(tmp.root, "towers.db")
        assertThrows(ImportCancelledException::class.java) {
            AcmaImporter().import(fixture, out, "s", "e", cancel = { true })
        }
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun cancelDuringDeviceDetailsStopsAndDeletesTheFile() {
        val out = File(tmp.root, "towers.db")
        var phase = ""
        assertThrows(ImportCancelledException::class.java) {
            AcmaImporter().import(
                fixture, out, "s", "e",
                progress = { p, _ -> phase = p },
                cancel = { phase == "devices" },
            )
        }
        assertThat(phase).isEqualTo("devices")
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun cancelBetweenFinaliseStepsStopsAndDeletesTheFile() {
        val out = File(tmp.root, "towers.db")
        val logged = mutableListOf<String>()
        assertThrows(ImportCancelledException::class.java) {
            AcmaImporter(log = { logged += it }).import(
                fixture, out, "s", "e",
                // Passes the poll at finalise entry; requested once the index step is done.
                cancel = { logged.any { it.startsWith("phase=finalise.indexes ") } },
            )
        }
        assertThat(logged.map { it.substringBefore(" ") })
            .containsExactly("phase=client", "phase=licence", "phase=devices", "phase=sites", "phase=antennas",
                "phase=finalise.eirp", "phase=finalise.indexes").inOrder()
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun logsElapsedTimePerPhaseAndTotal() {
        val logged = mutableListOf<String>()
        AcmaImporter(log = { logged += it }).import(fixture, File(tmp.root, "towers.db"), "s", "e")
        val line = Regex("""^phase=([a-z.]+) ms=(\d+)$""")
        assertThat(logged.all { line.matches(it) }).isTrue()
        assertThat(logged.map { line.matchEntire(it)!!.groupValues[1] }).containsExactly(
            "client", "licence", "devices", "sites", "antennas",
            "finalise.eirp", "finalise.indexes", "finalise.prune", "finalise.renumber", "finalise.summary",
            "finalise.report", "finalise", "total",
        ).inOrder()
    }

    @Test
    fun progressCoversPhasesInOrderAndIsMonotonic() {
        val seen = mutableListOf<Pair<String, Float>>()
        AcmaImporter().import(fixture, File(tmp.root, "towers.db"), "s", "e", progress = { p, f -> seen += p to f })
        val phases = seen.map { it.first }.distinct()
        assertThat(phases).containsExactly("client", "licence", "devices", "sites", "antennas", "finalise").inOrder()
        for (phase in phases) {
            val fractions = seen.filter { it.first == phase }.map { it.second }
            assertThat(fractions).isInOrder()
            assertThat(fractions.first()).isAtLeast(0f)
            assertThat(fractions.last()).isEqualTo(1f)
        }
    }

    @Test
    fun unitConversions() {
        assertThat(UnitConversions.toDbm(1.0, "W")).isEqualTo(30.0)
        assertThat(UnitConversions.toDbm(30.0, "dBm")).isEqualTo(30.0)
        assertThat(UnitConversions.toDbm(0.0, "dBW")).isEqualTo(30.0)
        assertThat(UnitConversions.toDbm(1.0, "kW")).isEqualTo(60.0)
        assertThat(UnitConversions.toDbm(1.0, "mW")).isEqualTo(0.0)
        assertThat(UnitConversions.toDbm(1.0, "furlongs")).isNull()
        assertThat(UnitConversions.toDbm(0.0, "W")).isNull()
        assertThat(UnitConversions.toDbm(-3.0, "mW")).isNull()
    }
}
