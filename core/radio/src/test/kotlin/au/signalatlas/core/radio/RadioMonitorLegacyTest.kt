package au.signalatlas.core.radio

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Tech
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowTelephonyManager

/**
 * The Android 10 / 11 path of [RadioMonitor]: a PhoneStateListener instead of a TelephonyCallback (31+). The listener
 * is the one the monitor registered with `TelephonyManager.listen`, so the tests drive the platform callbacks directly.
 * API 29 has no TelephonyDisplayInfo (the 5G display override stays NONE there).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class RadioMonitorLegacyTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var tm: ShadowTelephonyManager

    @Before fun setUp() {
        tm = shadowOf(app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
        val sub = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(1).setMcc("505").setMnc("01").setCarrierName("Boost").buildSubscriptionInfo()
        shadowOf(app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager)
            .setActiveSubscriptionInfos(sub)
        tm.setAllCellInfo(listOf(FakeCells.lte(true, pci = 10, earfcn = 1300, ta = 4), FakeCells.lte(false, pci = 20, earfcn = 9410)))
    }

    private fun monitor(scope: TestScope) = RadioMonitor(app, scope.backgroundScope, clock = { 7L })

    private fun listener(): PhoneStateListener = tm.listener ?: error("no PhoneStateListener registered")

    @Test fun `runs on the platform under test and registers a PhoneStateListener, not a TelephonyCallback`() = runTest {
        assertThat(Build.VERSION.SDK_INT).isAnyOf(29, 30)
        val m = monitor(this)
        assertThat(tm.listener).isNull()
        m.start()
        runCurrent()
        assertThat(tm.listener).isNotNull()
        assertThat(tm.lastTelephonyCallback).isNull()
        m.stop()
    }

    @Test fun `cell info from the cache is published with SIM and serving cell`() = runTest {
        val m = monitor(this)
        m.start()
        runCurrent()
        val s = m.state.value
        assertThat(s.permission).isEqualTo(Permission.GRANTED)
        assertThat(s.sim!!.network).isEqualTo(Network.TELSTRA)
        assertThat(s.serving!!.tech).isEqualTo(Tech.LTE)
        assertThat(s.serving!!.pci).isEqualTo(10)
        assertThat(s.serving!!.rsrp).isEqualTo(-95)
        assertThat(s.serving!!.taMeters).isWithin(1e-9).of(312.48)
        assertThat(s.neighbours.map { it.pci }).containsExactly(20)
        assertThat(s.updatedAtElapsedMs).isEqualTo(7L)
        m.stop()
    }

    @Test fun `onCellInfoChanged delivers the list it is given`() = runTest {
        val m = monitor(this)
        m.start()
        runCurrent()
        listener().onCellInfoChanged(mutableListOf(FakeCells.lte(true, pci = 55, rsrp = -80), FakeCells.nr(false)))
        val s = m.state.value
        assertThat(s.serving!!.pci).isEqualTo(55)
        assertThat(s.serving!!.rsrp).isEqualTo(-80)
        assertThat(s.nrSecondaries.single().tech).isEqualTo(Tech.NR)
        listener().onCellInfoChanged(null) // a null list is an empty one, not a crash
        assertThat(m.state.value.serving).isNull()
        m.stop()
    }

    @Test fun `service state changes re-read the cached cell list`() = runTest {
        val m = monitor(this)
        m.start()
        runCurrent()
        assertThat(m.state.value.serving!!.pci).isEqualTo(10)
        tm.setAllCellInfo(listOf(FakeCells.lte(true, pci = 77)))
        listener().onServiceStateChanged(ServiceState())
        assertThat(m.state.value.serving!!.pci).isEqualTo(77)
        listener().onServiceStateChanged(null)
        assertThat(m.state.value.serving!!.pci).isEqualTo(77)
        m.stop()
    }

    @Test fun `display info NR NSA override shows 5G on API 30 and API 29 never sets it`() = runTest {
        val m = monitor(this)
        m.start()
        runCurrent()
        assertThat(m.state.value.displayFiveG).isFalse()
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val info = ShadowTelephonyManager.createTelephonyDisplayInfo(
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
        ) as TelephonyDisplayInfo
        listener().onDisplayInfoChanged(info)
        assertThat(m.state.value.displayFiveG).isTrue()
        m.stop()
    }

    @Test fun `API 29 has no display info so an LTE serving cell never shows 5G`() = runTest {
        assumeTrue(Build.VERSION.SDK_INT == 29)
        val m = monitor(this)
        m.start()
        runCurrent()
        listener().onServiceStateChanged(ServiceState())
        assertThat(m.state.value.serving!!.tech).isEqualTo(Tech.LTE)
        assertThat(m.state.value.displayFiveG).isFalse()
        m.stop()
    }

    @Test fun `missing phone permission gives MISSING_PHONE and listener callbacks stay quiet`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val m = monitor(this)
        m.start()
        runCurrent()
        assertThat(m.state.value.permission).isEqualTo(Permission.MISSING_PHONE)
        assertThat(m.state.value.sim).isNull()
        assertThat(m.state.value.serving).isNull()
        listener().onCellInfoChanged(mutableListOf(FakeCells.lte(true, pci = 5)))
        assertThat(m.state.value.serving).isNull() // cells pushed by the platform are not exposed without the permission
        assertThat(m.subscriptions.value).isEmpty()
        m.stop()
    }

    @Test fun `missing location permission keeps the SIM but hides cells`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val m = monitor(this)
        m.start()
        runCurrent()
        listener().onCellInfoChanged(mutableListOf(FakeCells.lte(true, pci = 5)))
        val s = m.state.value
        assertThat(s.permission).isEqualTo(Permission.MISSING_LOCATION)
        assertThat(s.sim!!.network).isEqualTo(Network.TELSTRA)
        assertThat(s.serving).isNull()
        assertThat(s.neighbours).isEmpty()
        m.stop()
    }

    @Test fun `granting the permission later is picked up by the next callback`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val m = monitor(this)
        m.start()
        runCurrent()
        assertThat(m.state.value.serving).isNull()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        listener().onCellInfoChanged(mutableListOf(FakeCells.lte(true, pci = 9)))
        assertThat(m.state.value.permission).isEqualTo(Permission.GRANTED)
        assertThat(m.state.value.serving!!.pci).isEqualTo(9)
        m.stop()
    }

    @Test fun `airplane mode clears cells even when the listener delivers some`() = runTest {
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val m = monitor(this)
        m.start()
        runCurrent()
        listener().onCellInfoChanged(mutableListOf(FakeCells.lte(true, pci = 5)))
        assertThat(m.state.value.airplane).isTrue()
        assertThat(m.state.value.serving).isNull()
        m.stop()
    }

    @Test fun `polling with requestCellInfoUpdate works on API 29 and 30 and stops after stop`() = runTest {
        val m = monitor(this)
        tm.setCallbackCellInfos(listOf(FakeCells.lte(true, pci = 1)))
        m.start()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(m.state.value.serving!!.pci).isEqualTo(1)
        tm.setCallbackCellInfos(listOf(FakeCells.lte(true, pci = 2)))
        advanceTimeBy(2_001)
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(m.state.value.serving!!.pci).isEqualTo(2)
        m.stop()
        tm.setCallbackCellInfos(listOf(FakeCells.lte(true, pci = 3)))
        advanceTimeBy(10_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(m.state.value.serving!!.pci).isEqualTo(2)
    }
}
