package au.signalatlas.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import au.signalatlas.core.acma.AcmaImporter
import au.signalatlas.core.model.TowerFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TowerRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture = File(javaClass.getResource("/acma/fixture.zip")!!.toURI())
    private val dir by lazy { tmp.newFolder("towers") }
    private val installer by lazy { DbInstaller(dir, DbValidator(minSites = 1)) }
    private var repo: TowerRepository? = null

    @After fun tearDown() { repo?.close() }

    /** Installs the fixture as the live DB with [stamp], without telling any repository. */
    private fun install(stamp: String, schemaVersion: Int? = null) {
        val building = File(dir, "cand.building")
        AcmaImporter().import(fixture, building, stamp, "etag")
        if (schemaVersion != null) {
            BundledSQLiteDriver().open(building.path).use {
                it.execSQL("UPDATE meta SET value = '$schemaVersion' WHERE key = 'schema_version'")
            }
            // DbInstaller.install would reject it; put it in place directly.
            installer.live.delete()
            check(building.renameTo(installer.live))
        } else {
            check(installer.install(building) == Validation.Ok)
        }
    }

    private fun repository(scope: kotlinx.coroutines.CoroutineScope) = TowerRepository(installer, scope).also { repo = it }

    private fun TowerStore.isOpen() = siteDetail(allSites(TowerFilter()).first().id) != null

    @Test fun withStoreIsNullWithoutADb() = runTest {
        val r = repository(backgroundScope)
        r.start()
        assertThat(r.withStore { 1 }).isNull()
    }

    @Test fun withStoreKeepsARetiredStoreOpenUntilTheBlockReturns() = runTest {
        install("202610010000")
        val r = repository(backgroundScope)
        r.start()
        val old = checkNotNull(r.store.value)

        val result = r.withStore { pinned ->
            assertThat(pinned).isSameInstanceAs(old)
            install("202610221934")
            r.reload()
            assertThat(r.store.value).isNotSameInstanceAs(old)
            assertThat(old.isOpen()).isTrue() // retired, but still pinned
            "done"
        }

        assertThat(result).isEqualTo("done")
        assertThat(old.isOpen()).isFalse() // closed when the last user returned
        assertThat(r.store.value!!.isOpen()).isTrue()
    }

    @Test fun aRetiredStoreWithNoUsersClosesImmediately() = runTest {
        install("202610010000")
        val r = repository(backgroundScope)
        r.start()
        val old = checkNotNull(r.store.value)
        install("202610221934")
        r.reload()
        assertThat(old.isOpen()).isFalse()
        assertThat(r.store.value!!.meta.extractStamp).isEqualTo("202610221934")
    }

    @Test fun concurrentUsersEachSeeOneConsistentStoreAndTheOldOneClosesAfterBoth() = runTest {
        install("202610010000")
        val r = repository(backgroundScope)
        r.start()
        val old = checkNotNull(r.store.value)

        val pinnedBoth = CompletableDeferred<Unit>()
        val release = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val seen = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
        var pinned = 0
        val users = release.mapIndexed { i, gate ->
            async {
                r.withStore { s ->
                    if (++pinned == 2) pinnedBoth.complete(Unit)
                    pinnedBoth.await()
                    val before = s.meta.extractStamp
                    gate.await()
                    seen += before to s.meta.extractStamp // same store object throughout
                    assertThat(s.isOpen()).isTrue()
                    i
                }
            }
        }
        runCurrent()
        pinnedBoth.await()

        install("202610221934")
        r.reload()
        assertThat(old.isOpen()).isTrue()
        // A new user gets the new store while the old one is still pinned.
        assertThat(r.withStore { it.meta.extractStamp }).isEqualTo("202610221934")

        release[0].complete(Unit)
        runCurrent()
        assertThat(old.isOpen()).isTrue() // one user left
        release[1].complete(Unit)
        assertThat(users.awaitAll()).containsExactly(0, 1).inOrder()

        assertThat(seen.toList()).containsExactly("202610010000" to "202610010000", "202610010000" to "202610010000")
        assertThat(old.isOpen()).isFalse()
    }

    @Test fun closeWhilePinnedDefersTheCloseToTheLastUser() = runTest {
        install("202610010000")
        val r = repository(backgroundScope)
        r.start()
        val store = checkNotNull(r.store.value)
        r.withStore {
            r.close()
            assertThat(r.store.value).isNull()
            assertThat(store.isOpen()).isTrue()
        }
        assertThat(store.isOpen()).isFalse()
    }

    @Test fun closeWithNoUsersClosesImmediately() = runTest {
        install("202610010000")
        val r = repository(backgroundScope)
        r.start()
        val store = checkNotNull(r.store.value)
        r.close()
        assertThat(store.isOpen()).isFalse()
    }

    @Test fun aBlockThatThrowsStillReleasesItsPin() = runTest {
        install("202610010000")
        val r = repository(backgroundScope)
        r.start()
        val old = checkNotNull(r.store.value)
        try {
            r.withStore {
                install("202610221934")
                r.reload()
                error("boom")
            }
            error("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().isEqualTo("boom")
        }
        assertThat(old.isOpen()).isFalse()
    }

    @Test fun startTreatsASchemaMismatchAsNoDb() = runTest {
        install("202610010000", schemaVersion = 3)
        val r = repository(backgroundScope)
        r.start()
        assertThat(r.store.value).isNull()
        r.refresh() // same file: must not retry opening on every refresh
        assertThat(r.store.value).isNull()
    }

    @Test fun refreshReloadsOnlyWhenTheInstalledFileChanged() = runTest {
        install("202610010000")
        val r = repository(backgroundScope)
        r.start()
        val first = checkNotNull(r.store.value)
        r.refresh()
        assertThat(r.store.value).isSameInstanceAs(first)

        install("202610221934") // installed, but nobody reloaded
        r.refresh()
        assertThat(r.store.value!!.meta.extractStamp).isEqualTo("202610221934")
    }

    @Test fun refreshInAFreshProcessOpensTheInstalledDb() = runTest {
        install("202610221934")
        val r = repository(backgroundScope)
        r.refresh()
        assertThat(r.store.value!!.meta.extractStamp).isEqualTo("202610221934")
    }
}
