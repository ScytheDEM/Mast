package au.signalatlas.app.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DeviceLocationSourceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(LocationManager::class.java)
    private val now = TimeUnit.HOURS.toNanos(5)
    private lateinit var source: DeviceLocationSource

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val shadow = shadowOf(manager)
        shadow.setLocationEnabled(true)
        // Only GPS, so the provider choice is deterministic.
        for (p in manager.allProviders) shadow.setProviderEnabled(p, p == LocationManager.GPS_PROVIDER)
        source = DeviceLocationSource(app, nowNanos = { now })
    }

    @After fun tearDown() = source.stop()

    private fun fix(lat: Double, lon: Double, ageMs: Long) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat
        longitude = lon
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = now - TimeUnit.MILLISECONDS.toNanos(ageMs)
    }

    @Test fun anOldLastKnownFixIsNotUsedForCentring() {
        // The emulator's default position from 18 minutes ago, as seen in verification.
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(37.42, -122.08, ageMs = 18 * 60_000L))
        source.start()
        assertThat(source.last.value).isNull()
        assertThat(source.freshFix()).isNull()
    }

    @Test fun aRecentLastKnownFixIsUsed() {
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(-35.28, 149.13, ageMs = 30_000L))
        source.start()
        assertThat(source.last.value).isEqualTo(-35.28 to 149.13)
        assertThat(source.freshFix()).isEqualTo(-35.28 to 149.13)
    }

    @Test fun currentFixWaitsForANewFixAndPublishesIt() {
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(37.42, -122.08, ageMs = 18 * 60_000L))
        val pending = CoroutineScope(Dispatchers.Unconfined).async { source.currentFix(timeoutMs = 5_000) }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(pending.isCompleted).isFalse() // the stale last-known fix is not an answer

        shadowOf(manager).simulateLocation(fix(-35.28, 149.13, ageMs = 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(pending.isCompleted).isTrue()
        assertThat(pending.getCompleted()).isEqualTo(-35.28 to 149.13)
        assertThat(source.last.value).isEqualTo(-35.28 to 149.13)
    }

    @Test fun staleUpdatesAreIgnored() {
        source.start()
        shadowOf(manager).simulateLocation(fix(37.42, -122.08, ageMs = 10 * 60_000L))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(source.last.value).isNull()
        shadowOf(manager).simulateLocation(fix(-35.28, 149.13, ageMs = 1_000L))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(source.last.value).isEqualTo(-35.28 to 149.13)
    }
}
