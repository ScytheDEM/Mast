package au.signalatlas.core.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TowerRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val closer = TestScope(StandardTestDispatcher())
    private lateinit var env: UpdateTestEnv

    @After fun tearDown() = env.close()

    @Test fun reloadKeepsTheOldStoreReadableForTheGracePeriod() {
        env = UpdateTestEnv(tmp.root, repoScope = closer)
        env.seed("202610010000")
        val old = checkNotNull(env.repo.store.value)
        val site = old.allSites(au.signalatlas.core.model.TowerFilter()).first().id

        env.seed("202610221934") // installs a newer live DB
        runBlocking { env.repo.reload() }
        assertThat(env.repo.store.value).isNotSameInstanceAs(old)

        closer.advanceTimeBy(9.seconds)
        closer.runCurrent()
        assertThat(old.siteDetail(site)).isNotNull()

        closer.advanceTimeBy(2.seconds)
        closer.runCurrent()
        assertThat(old.siteDetail(site)).isNull() // closed, and does not throw
        assertThat(env.repo.store.value!!.siteDetail(site)).isNotNull()
    }
}
