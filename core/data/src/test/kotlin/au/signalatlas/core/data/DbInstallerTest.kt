package au.signalatlas.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import au.signalatlas.core.acma.AcmaImporter
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DbInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture = File(javaClass.getResource("/acma/fixture.zip")!!.toURI())
    private lateinit var dir: File
    private var seq = 0

    @Before fun setUp() { dir = tmp.newFolder("data") }

    private fun installer(minSites: Int = 1) = DbInstaller(dir, DbValidator(minSites))

    private fun build(stamp: String = "stamp${seq++}"): File {
        val out = File(dir, "cand$seq.building")
        AcmaImporter().import(fixture, out, stamp, "etag")
        return out
    }

    private fun meta(db: File, key: String): String =
        BundledSQLiteDriver().open(db.path).use { c ->
            c.prepare("SELECT value FROM meta WHERE key = '$key'").use { it.step(); it.getText(0) }
        }

    @Test fun firstInstallMakesLiveAndNoPrevious() {
        val inst = installer()
        assertThat(inst.install(build())).isEqualTo(Validation.Ok)
        assertThat(inst.live.exists()).isTrue()
        assertThat(inst.previous.exists()).isFalse()
        assertThat(File(dir, "towers.prev.db.tmp").exists()).isFalse()
    }

    @Test fun secondInstallKeepsFirstAsPrevious() {
        val inst = installer()
        inst.install(build())
        val firstStamp = meta(inst.live, "extract_stamp")
        assertThat(inst.install(build())).isEqualTo(Validation.Ok)
        assertThat(meta(inst.previous, "extract_stamp")).isEqualTo(firstStamp)
        assertThat(meta(inst.live, "extract_stamp")).isNotEqualTo(firstStamp)
    }

    @Test fun truncatedCandidateIsRejectedAndLiveUntouched() {
        val inst = installer()
        inst.install(build())
        val before = inst.live.readBytes()
        val cand = build()
        val bytes = cand.readBytes()
        cand.writeBytes(bytes.copyOf(bytes.size / 2))
        assertThat(inst.install(cand)).isInstanceOf(Validation.Invalid::class.java)
        assertThat(inst.live.readBytes()).isEqualTo(before)
        assertThat(cand.exists()).isFalse()
        assertThat(inst.previous.exists()).isFalse()
    }

    @Test fun garbageFileIsInvalidNotThrown() {
        val f = File(dir, "junk.building").apply { writeText("this is not sqlite at all, just text".repeat(200)) }
        assertThat(DbValidator(1).validate(f)).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test fun missingFileIsInvalidAndNotCreated() {
        val f = File(dir, "nope.building")
        assertThat(DbValidator(1).validate(f)).isEqualTo(Validation.Invalid("missing file"))
        assertThat(f.exists()).isFalse()
    }

    @Test fun orphanSectorIsRejected() {
        val cand = build()
        BundledSQLiteDriver().open(cand.path).use {
            it.execSQL("INSERT INTO sector(id, site_id, network, antenna_id, azimuth_deg, height_m) VALUES (999999, 123456789, 0, NULL, 0, 10)")
        }
        val r = DbValidator(1).validate(cand)
        assertThat(r).isInstanceOf(Validation.Invalid::class.java)
        assertThat((r as Validation.Invalid).reason).startsWith("orphan sector")
    }

    @Test fun orphanCarrierIsRejected() {
        val cand = build()
        BundledSQLiteDriver().open(cand.path).use {
            it.execSQL("INSERT INTO carrier VALUES (999999999, 888888, 1, 1, 1.0, 0.0, 0, 'B1', 'L', 'D')")
        }
        val r = DbValidator(1).validate(cand) as Validation.Invalid
        assertThat(r.reason).startsWith("orphan carrier")
    }

    @Test fun outOfBoundsSiteIsRejected() {
        val cand = build()
        BundledSQLiteDriver().open(cand.path).use {
            it.execSQL("INSERT INTO site(id, lat, lon, name) VALUES (777777, 10.0, 100.0, 'x')")
        }
        val r = DbValidator(1).validate(cand) as Validation.Invalid
        assertThat(r.reason).contains("bounds")
    }

    @Test fun wrongSchemaVersionIsRejected() {
        val cand = build()
        BundledSQLiteDriver().open(cand.path).use {
            it.execSQL("UPDATE meta SET value = '2' WHERE key = 'schema_version'")
        }
        val r = DbValidator(1).validate(cand) as Validation.Invalid
        assertThat(r.reason).contains("schema version")
    }

    @Test fun tooFewSitesMentionsSiteCount() {
        val inst = installer(minSites = 10)
        val r = inst.install(build()) as Validation.Invalid
        assertThat(r.reason).contains("site count")
        assertThat(inst.live.exists()).isFalse()
    }

    @Test fun cleanupStaleRemovesBuildingAndTmpOnly() {
        val inst = installer()
        inst.install(build())
        inst.install(build())
        val stale = listOf("x.building", "x.building-journal", "y.tmp", "y.tmp-journal").map { File(dir, it).apply { writeText("z") } }
        inst.cleanupStale()
        stale.forEach { assertThat(it.exists()).isFalse() }
        assertThat(inst.live.exists()).isTrue()
        assertThat(inst.previous.exists()).isTrue()
    }

    @Test fun rollbackRestoresPrevious() {
        val inst = installer()
        inst.install(build())
        val firstStamp = meta(inst.live, "extract_stamp")
        inst.install(build())
        assertThat(inst.rollback()).isTrue()
        assertThat(meta(inst.live, "extract_stamp")).isEqualTo(firstStamp)
        assertThat(inst.previous.exists()).isFalse()
    }

    @Test fun rollbackWithoutPreviousReturnsFalse() {
        assertThat(installer().rollback()).isFalse()
    }

    @Test fun crashBetweenMovesIsRecoveredByCleanupStale() {
        val inst = installer()
        inst.install(build())
        val oldStamp = meta(inst.live, "extract_stamp")
        inst.install(build())
        // Simulate the old crash window: live gone, previous = last good, candidate still .building.
        inst.live.delete()
        File(dir, "x.building").writeText("z")
        val prevStamp = meta(inst.previous, "extract_stamp")
        inst.cleanupStale()
        assertThat(inst.live.exists()).isTrue()
        assertThat(meta(inst.live, "extract_stamp")).isEqualTo(prevStamp)
        assertThat(prevStamp).isEqualTo(oldStamp)
        assertThat(File(dir, "x.building").exists()).isFalse()
    }

    @Test fun failingFinalMovePropagatesAndLiveIsIntact() {
        val real = installer()
        real.install(build())
        val oldStamp = meta(real.live, "extract_stamp")
        val before = real.live.readBytes()
        val failing = DbInstaller(dir, DbValidator(1)) { from, to ->
            if (to == real.live.toPath()) throw java.io.IOException("boom")
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val cand = build()
        val ex = runCatching { failing.install(cand) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(java.io.IOException::class.java)
        assertThat(real.live.readBytes()).isEqualTo(before)
        assertThat(meta(real.previous, "extract_stamp")).isEqualTo(oldStamp)
        assertThat(cand.exists()).isFalse()
    }

    @Test fun previousKeepsOldDbAfterSuccessfulInstall() {
        val inst = installer()
        inst.install(build())
        val oldBytes = inst.live.readBytes()
        inst.install(build())
        assertThat(inst.previous.readBytes()).isEqualTo(oldBytes)
        assertThat(File(dir, "towers.prev.db.tmp").exists()).isFalse()
    }
}
