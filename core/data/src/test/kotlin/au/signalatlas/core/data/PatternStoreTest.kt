package au.signalatlas.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.acma.hrp.HrpImporter
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatternStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rrl = File(javaClass.getResource("/acma/fixture.zip")!!.toURI())
    private val hrp = File(javaClass.getResource("/acma/hrp_fixture.zip")!!.toURI())
    private val expected = Json.parseToJsonElement(javaClass.getResource("/acma/hrp_expected.json")!!.readText()).jsonObject

    private lateinit var dir: File
    private lateinit var towers: File
    private var seq = 0

    @Before fun setUp() {
        dir = tmp.newFolder("towers")
        towers = File(dir, "towers.db").also { AcmaImporter().import(rrl, it, "202610221934", "\"rrl\"") }
    }

    /** A freshly imported candidate in [dir], as the worker builds it. */
    private fun build(etag: String = "\"hrp-${seq++}\""): File =
        File(dir, "patterns.db.building").also { HrpImporter().import(hrp, towers, it, etag) }

    private fun installed(etag: String = "\"hrp\""): File {
        val inst = PatternInstaller(dir)
        assertThat(inst.install(build(etag))).isEqualTo(Validation.Ok)
        return inst.live
    }

    @Test fun loadsEveryPatternAndTheMeta() {
        PatternStore.open(installed("\"e1\"")).use { s ->
            assertThat(s.patternCount).isEqualTo(expected["patterns"]!!.jsonPrimitive.int)
            assertThat(s.deviceCount).isEqualTo(expected["devices"]!!.jsonPrimitive.int)
            assertThat(s.patternBytes.size).isEqualTo(s.patternCount * 360)
            assertThat(s.etag).isEqualTo("\"e1\"")
            assertThat(s.towerStamp).isEqualTo("202610221934")
            assertThat(s.importedAt).isNotNull()
        }
    }

    @Test fun lookupMatchesTheReference() {
        val samples = expected["samples"]!!.jsonArray.map { it.jsonObject }
        PatternStore.open(installed()).use { s ->
            val sdds = LongArray(samples.size) { samples[it]["sdd"]!!.jsonPrimitive.long }
            val ids = IntArray(sdds.size)
            val bearings = ShortArray(sdds.size)
            s.lookup(sdds, ids, bearings)
            for ((i, sample) in samples.withIndex()) {
                assertThat(ids[i]).isEqualTo(sample["patternId"]!!.jsonPrimitive.int)
                assertThat(bearings[i].toInt()).isEqualTo(sample["peakBearing"]!!.jsonPrimitive.int)
                val att = (0 until 360).map { s.patternBytes[(ids[i] - 1) * 360 + it].toInt() and 0xFF }
                assertThat(att).containsExactlyElementsIn(sample["att"]!!.jsonArray.map { it.jsonPrimitive.int }).inOrder()
            }
        }
    }

    @Test fun lookupReturnsZeroForUnknownSdds() {
        PatternStore.open(installed()).use { s ->
            val known = expected["samples"]!!.jsonArray[0].jsonObject["sdd"]!!.jsonPrimitive.long
            val sdds = longArrayOf(999_999_999L, known, -1L, Long.MAX_VALUE, 0L)
            val ids = IntArray(sdds.size) { 77 }
            val bearings = ShortArray(sdds.size) { 77 }
            s.lookup(sdds, ids, bearings)
            assertThat(ids.toList()).containsExactly(0, 1, 0, 0, 0).inOrder()
            assertThat(bearings[0]).isEqualTo(0.toShort())
            assertThat(bearings[2]).isEqualTo(0.toShort())
        }
    }

    @Test fun missingFileIsFileNotFound() {
        assertThrows(FileNotFoundException::class.java) { PatternStore.open(File(dir, "patterns.db")) }
        assertThat(File(dir, "patterns.db").exists()).isFalse()
    }

    @Test fun installerKeepsTheOldPatternsDbWhenGivenATruncatedCandidate() {
        val inst = PatternInstaller(dir)
        assertThat(inst.install(build("\"old\""))).isEqualTo(Validation.Ok)
        val before = inst.live.readBytes()
        val cand = build("\"new\"")
        cand.writeBytes(cand.readBytes().let { it.copyOf(it.size / 2) })
        assertThat(inst.install(cand)).isInstanceOf(Validation.Invalid::class.java)
        assertThat(inst.live.readBytes()).isEqualTo(before)
        assertThat(cand.exists()).isFalse()
        PatternStore.open(inst.live).use { assertThat(it.etag).isEqualTo("\"old\"") }
    }

    @Test fun validatorRejectsDanglingPatternIdsShortBlobsEmptyAndWrongVersion() {
        val v = PatternDbValidator()
        fun broken(sql: String): Validation {
            val f = build()
            BundledSQLiteDriver().open(f.path).use { it.execSQL(sql) }
            return v.validate(f)
        }
        assertThat(v.validate(build())).isEqualTo(Validation.Ok)
        assertThat((broken("UPDATE device_pattern SET pattern_id = 99 WHERE rowid = (SELECT MIN(rowid) FROM device_pattern)") as Validation.Invalid).reason)
            .contains("pattern")
        assertThat((broken("UPDATE pattern SET att = x'00' WHERE id = 1") as Validation.Invalid).reason).contains("360")
        assertThat((broken("DELETE FROM pattern") as Validation.Invalid).reason).contains("pattern")
        assertThat((broken("UPDATE meta SET value = '2' WHERE key = 'schema_version'") as Validation.Invalid).reason)
            .contains("schema version")
        assertThat(v.validate(File(dir, "nope.db"))).isEqualTo(Validation.Invalid("missing file"))
    }

    @Test fun installsAsPatternsDbAndLeavesTowersAlone() {
        val towersBefore = towers.readBytes()
        val inst = PatternInstaller(dir)
        inst.install(build())
        assertThat(inst.live).isEqualTo(File(dir, "patterns.db"))
        assertThat(File(dir, "patterns.db.building").exists()).isFalse()
        assertThat(towers.readBytes()).isEqualTo(towersBefore)
    }

    @Test fun towerCleanupLeavesAPatternsBuildInProgressAlone() {
        val building = File(dir, "patterns.db.building").apply { writeText("in progress") }
        val stale = File(dir, "towers.db.building").apply { writeText("stale") }
        DbInstaller(dir, DbValidator(1)).cleanupStale()
        assertThat(building.exists()).isTrue()
        assertThat(stale.exists()).isFalse()
        PatternInstaller(dir).cleanupStale()
        assertThat(building.exists()).isFalse()
    }

    @Test fun repositoryStartsReloadsAndCloses() = runBlocking {
        val inst = PatternInstaller(dir)
        val repo = PatternRepository(inst)
        repo.start()
        assertThat(repo.patterns.value).isNull() // nothing installed: standard patterns

        inst.install(build("\"a\""))
        repo.reload()
        val first = repo.patterns.value!!
        assertThat(first.etag).isEqualTo("\"a\"")

        inst.install(build("\"b\""))
        repo.reload()
        assertThat(repo.patterns.value!!.etag).isEqualTo("\"b\"")
        assertThat(first.isClosed).isTrue()

        repo.close()
        assertThat(repo.patterns.value).isNull()
    }

    @Test fun repositoryTreatsAnUnreadablePatternsDbAsNone() = runBlocking {
        File(dir, "patterns.db").writeText("not sqlite".repeat(100))
        val repo = PatternRepository(PatternInstaller(dir))
        repo.start()
        assertThat(repo.patterns.value).isNull()
    }

    @Test fun repositoryNeverOpensABuildingFile() = runBlocking {
        build() // a candidate left by a stopped import
        val repo = PatternRepository(PatternInstaller(dir))
        repo.start()
        assertThat(repo.patterns.value).isNull()
    }
}
