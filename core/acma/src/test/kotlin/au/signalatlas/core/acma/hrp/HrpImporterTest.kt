package au.signalatlas.core.acma.hrp

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.acma.ImportCancelledException
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HrpImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rrlFixture = File(javaClass.getResource("/acma/fixture.zip")!!.toURI())
    private val hrpFixture = File(javaClass.getResource("/acma/hrp_fixture.zip")!!.toURI())
    private val expected = Json.parseToJsonElement(javaClass.getResource("/acma/hrp_expected.json")!!.readText()).jsonObject

    private val towersDb: File by lazy {
        File(tmp.root, "towers.db").also { AcmaImporter().import(rrlFixture, it, "202610221934", "\"rrl\"") }
    }

    private fun <T> withDb(file: File, block: (SQLiteConnection) -> T): T {
        val conn = BundledSQLiteDriver().open(file.path)
        try {
            return block(conn)
        } finally {
            conn.close()
        }
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { check(it.step()); it.getLong(0) }
    private fun SQLiteConnection.text(sql: String): String = prepare(sql).use { check(it.step()); it.getText(0) }

    private fun import(zip: File = hrpFixture, out: File = File(tmp.root, "patterns.db.building")): Pair<HrpReport, File> =
        HrpImporter().import(zip, towersDb, out, "\"hrp-etag\"") to out

    @Test fun fixtureMatchesTheReferenceExactly() {
        val (report, out) = import()
        assertThat(report).isEqualTo(
            HrpReport(
                devices = expected["devices"]!!.jsonPrimitive.int,
                patterns = expected["patterns"]!!.jsonPrimitive.int,
                skippedIncomplete = expected["skippedIncomplete"]!!.jsonPrimitive.int,
                skippedUnknown = expected["skippedUnknown"]!!.jsonPrimitive.int,
                rows = expected["rows"]!!.jsonPrimitive.long,
            ),
        )
        withDb(out) { db ->
            assertThat(db.long("SELECT COUNT(*) FROM device_pattern")).isEqualTo(report.devices.toLong())
            assertThat(db.long("SELECT COUNT(*) FROM pattern")).isEqualTo(report.patterns.toLong())
            assertThat(db.long("SELECT MIN(id) FROM pattern")).isEqualTo(1L)
            assertThat(db.long("SELECT MAX(id) FROM pattern")).isEqualTo(report.patterns.toLong())
            assertThat(db.long("SELECT COUNT(*) FROM pattern WHERE length(att) <> 360")).isEqualTo(0L)

            val samples = expected["samples"]!!.jsonArray
            assertThat(samples).isNotEmpty()
            for (s in samples.map { it.jsonObject }) {
                val sdd = s["sdd"]!!.jsonPrimitive.long
                db.prepare(
                    "SELECT d.pattern_id, d.peak_bearing, d.peak_dbw, p.att FROM device_pattern d " +
                        "JOIN pattern p ON p.id = d.pattern_id WHERE d.sdd_id = ?",
                ).use { st ->
                    st.bindLong(1, sdd)
                    assertThat(st.step()).isTrue()
                    assertThat(st.getLong(0)).isEqualTo(s["patternId"]!!.jsonPrimitive.long)
                    assertThat(st.getLong(1)).isEqualTo(s["peakBearing"]!!.jsonPrimitive.long)
                    assertThat(st.getDouble(2)).isEqualTo(s["peakDbw"]!!.jsonPrimitive.double)
                    val att = st.getBlob(3).map { it.toInt() and 0xFF }
                    assertThat(att).containsExactlyElementsIn(s["att"]!!.jsonArray.map { it.jsonPrimitive.int }).inOrder()
                    assertThat(att.take(11))
                        .containsExactlyElementsIn(s["att0_10"]!!.jsonArray.map { it.jsonPrimitive.int }).inOrder()
                }
            }
        }
    }

    @Test fun metaRecordsSchemaEtagTowerStampAndReport() {
        val (report, out) = import()
        withDb(out) { db ->
            assertThat(db.text("SELECT value FROM meta WHERE key = 'schema_version'")).isEqualTo("1")
            assertThat(db.text("SELECT value FROM meta WHERE key = 'hrp_etag'")).isEqualTo("\"hrp-etag\"")
            assertThat(db.text("SELECT value FROM meta WHERE key = 'tower_stamp'")).isEqualTo("202610221934")
            assertThat(db.text("SELECT value FROM meta WHERE key = 'imported_at'")).isNotEmpty()
            assertThat(Json.decodeFromString(HrpReport.serializer(), db.text("SELECT value FROM meta WHERE key = 'report'")))
                .isEqualTo(report)
        }
    }

    @Test fun cancelMidStreamThrowsAndDeletesOut() {
        val out = File(tmp.root, "patterns.db.building")
        var polls = 0
        assertThrows(ImportCancelledException::class.java) {
            // Polls: before streaming, then rows 0, 10 000, ...: the third is inside the stream.
            HrpImporter().import(hrpFixture, towersDb, out, "e", cancel = { ++polls >= 3 })
        }
        assertThat(polls).isEqualTo(3)
        assertThat(out.exists()).isFalse()
        assertThat(File(out.path + "-journal").exists()).isFalse()
    }

    @Test fun progressRisesToOne() {
        val seen = mutableListOf<Float>()
        HrpImporter().import(hrpFixture, towersDb, File(tmp.root, "p.db"), "e", progress = { seen += it })
        assertThat(seen).isNotEmpty()
        assertThat(seen).isInOrder()
        assertThat(seen.last()).isEqualTo(1f)
    }

    // Ruling BB: java.util.zip.ZipFile's entry stream does NOT check the CRC (verified on JDK 17), so the
    // importer checks CRC-32 and size itself. A corrupted CRC must fail the import and leave no output.
    @Test fun crcMismatchIsZipExceptionAndDeletesOut() {
        val bad = File(tmp.root, "bad-crc.zip").apply { writeBytes(flipCrc(hrpFixture.readBytes())) }
        // Plain ZipFile reads the corrupted entry without complaint ...
        ZipFile(bad).use { zf -> zf.getInputStream(zf.getEntry("LICENCE_.csv")).use { it.readBytes() } }
        // ... the importer does not.
        val out = File(tmp.root, "patterns.db.building")
        val e = assertThrows(ZipException::class.java) { import(bad, out) }
        assertThat(e.message).contains("CRC")
        assertThat(out.exists()).isFalse()
    }

    @Test fun wrongHeaderIsFormatException() {
        val zip = zipOf("SDD_ID,START_ANGLE,POWER\r\n1,0,1.0\r\n")
        val out = File(tmp.root, "x.db")
        assertThrows(HrpFormatException::class.java) { import(zip, out) }
        assertThat(out.exists()).isFalse()
    }

    @Test fun missingEntryIsFormatException() {
        val zip = File(tmp.root, "other.zip")
        ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("other.csv")); it.write(HEADER.toByteArray()) }
        assertThrows(HrpFormatException::class.java) { import(zip) }
    }

    @Test fun malformedRowsMarkTheDeviceIncompleteAndNeverAbort() {
        val known = knownSdd()
        val good = deviceRows(known, peakAt = 10)
        val csv = StringBuilder(HEADER).append("\r\n")
        // Same known SDD: one row non-numeric, one row with an extra field, each in its own run.
        csv.append(good.replace("$known,R,5,6,", "$known,R,5,6,abc,"))
        csv.append("999,R,0,1,1.0\r\n") // separates the runs (unknown SDD)
        csv.append(good.replace("$known,R,7,8,", "$known,R,7,8,x"))
        csv.append("999,R,0,1,1.0\r\n")
        csv.append(good) // a complete run of the same SDD is still stored
        val (report, _) = import(zipOf(csv.toString()))
        assertThat(report).isEqualTo(HrpReport(devices = 1, patterns = 1, skippedIncomplete = 2, skippedUnknown = 2, rows = 360L * 3 + 2))
    }

    @Test fun repeatedCompleteRunKeepsTheFirstAndIsNotCounted() {
        val known = knownSdd()
        val csv = HEADER + "\n" + deviceRows(known, peakAt = 10, eol = "\n") + "1,R,0,1,0\n" +
            deviceRows(known, peakAt = 99, eol = "\n")
        val (report, out) = import(zipOf(csv))
        assertThat(report).isEqualTo(HrpReport(devices = 1, patterns = 1, skippedIncomplete = 0, skippedUnknown = 1, rows = 721))
        withDb(out) { assertThat(it.long("SELECT peak_bearing FROM device_pattern")).isEqualTo(10L) }
    }

    @Test fun parserReadsFieldsByPosition() {
        val p = HrpStreamParser(ByteArrayInputStream("$HEADER\r\n2909149471,9001,265,266,-33.6\r\n,x,360,0,7\r\n5,R,1,2\r\n".toByteArray()))
        assertThat(p.next()).isTrue()
        assertThat(p.sddId).isEqualTo(2909149471L)
        assertThat(p.startAngle).isEqualTo(265)
        assertThat(p.powerDbw).isEqualTo(-33.6f)
        assertThat(p.power).isEqualTo(-33.6)
        assertThat(p.valid).isTrue()
        assertThat(p.next()).isTrue()
        assertThat(p.sddId).isEqualTo(HrpStreamParser.INVALID_SDD)
        assertThat(p.valid).isFalse() // angle 360 is out of range
        assertThat(p.next()).isTrue()
        assertThat(p.sddId).isEqualTo(5L)
        assertThat(p.valid).isFalse() // four fields
        assertThat(p.next()).isFalse()
    }

    @Test fun parserReachesAMillionRowsPerSecond() {
        val block = generatedRows(rows = 100_000, seed = 42)
        fun run(repeats: Int): Pair<Long, Long> {
            val p = HrpStreamParser(RepeatingStream(HEADER_BYTES, block, repeats))
            var n = 0L
            var sum = 0L
            val start = System.nanoTime()
            while (p.next()) {
                n++
                sum += p.startAngle
            }
            check(sum > 0)
            return n to System.nanoTime() - start
        }
        repeat(3) { run(10) } // warm-up
        val (rows, nanos) = run(50)
        assertThat(rows).isEqualTo(5_000_000L)
        val perSecond = rows / (nanos / 1e9)
        println("HrpStreamParser: %.2f M rows/s".format(perSecond / 1e6))
        assertThat(perSecond).isAtLeast(1_000_000.0)
    }

    // --- helpers ---

    private fun knownSdd(): Long = withDb(towersDb) { it.long("SELECT MIN(sdd_id) FROM carrier") }

    private fun deviceRows(sdd: Long, peakAt: Int, eol: String = "\r\n"): String = buildString {
        for (a in 0 until 360) {
            val power = if (a == peakAt) "30.0" else String.format(Locale.ROOT, "%.1f", 30.0 - 1 - (a % 7) * 0.5)
            append("$sdd,R,$a,${a + 1},$power$eol")
        }
    }

    private fun zipOf(csv: String): File {
        val f = File(tmp.root, "gen-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("LICENCE_.csv"))
            z.write(csv.toByteArray(Charsets.ISO_8859_1))
            z.closeEntry()
        }
        return f
    }

    /** Flips one bit of the entry CRC in both the local header and the central directory. */
    private fun flipCrc(zip: ByteArray): ByteArray {
        val b = zip.copyOf()
        check(b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte())
        b[14] = (b[14].toInt() xor 1).toByte()
        val cd = (0 until b.size - 4).first { b[it] == 0x50.toByte() && b[it + 1] == 0x4b.toByte() && b[it + 2] == 1.toByte() && b[it + 3] == 2.toByte() }
        b[cd + 16] = (b[cd + 16].toInt() xor 1).toByte()
        return b
    }

    private fun generatedRows(rows: Int, seed: Int): ByteArray {
        val r = Random(seed)
        val sb = StringBuilder(rows * 32)
        var sdd = 2_900_000_000L
        for (i in 0 until rows) {
            val a = i % 360
            if (a == 0) sdd += 1 + r.nextInt(5)
            val power = r.nextInt(-400, 400) / 10.0
            sb.append(sdd).append(',').append(r.nextInt(1, 999_999)).append(',').append(a).append(',').append(a + 1)
                .append(',').append(String.format(Locale.ROOT, "%.1f", power)).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** [head] once, then [block] [repeats] times, without materialising the whole stream. */
    private class RepeatingStream(private val head: ByteArray, private val block: ByteArray, private var repeats: Int) : InputStream() {
        private var current = head
        private var pos = 0

        private fun advance(): Boolean {
            while (pos >= current.size) {
                if (repeats == 0) return false
                repeats--
                current = block
                pos = 0
            }
            return true
        }

        override fun read(): Int = if (advance()) current[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!advance()) return -1
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }
    }

    private companion object {
        const val HEADER = "SDD_ID,DEVICE_REGISTRATION_IDENTIFIER,START_ANGLE,STOP_ANGLE,POWER"
        val HEADER_BYTES = "$HEADER\r\n".toByteArray()
    }
}
