package au.signalatlas.core.radio

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowTelephonyManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RadioMonitorTest {
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
        tm.setAllCellInfo(
            listOf(
                FakeCells.lte(true, pci = 10, earfcn = 1300, ta = 4),
                FakeCells.lte(false, pci = 20, earfcn = 9410),
                FakeCells.lte(false, pci = 30, earfcn = FakeCells.NA, rsrp = FakeCells.NA),
                FakeCells.nr(false),
            ),
        )
    }

    private fun monitor(scope: TestScope) = RadioMonitor(app, scope.backgroundScope, clock = { 42L })

    @Test fun `serving neighbours and NR secondary are published with SIM info`() = runTest {
        val m = monitor(this)
        m.start()
        runCurrent()
        val s = m.state.value
        assertThat(s.permission).isEqualTo(Permission.GRANTED)
        assertThat(s.sim!!.network).isEqualTo(Network.TELSTRA)
        assertThat(s.sim!!.carrierName).isEqualTo("Boost")
        assertThat(s.sim!!.mccMnc).isEqualTo("50501")
        assertThat(s.serving!!.pci).isEqualTo(10)
        assertThat(s.serving!!.taMeters).isWithin(1e-9).of(312.48)
        assertThat(s.neighbours.map { it.pci }).containsExactly(20, 30).inOrder()
        assertThat(s.neighbours[1].rsrp).isNull()
        assertThat(s.nrSecondaries.single().tech).isEqualTo(Tech.NR)
        assertThat(s.nrSecondaries.single().pci).isNull()
        assertThat(s.airplane).isFalse()
        assertThat(s.updatedAtElapsedMs).isEqualTo(42L)
        assertThat(m.subscriptions.value.map { it.subId }).containsExactly(1)
        m.stop()
    }

    @Test fun `roaming flag and unknown SIM network`() = runTest {
        tm.setIsNetworkRoaming(true)
        val sub = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(2).setMcc("234").setMnc("10").setCarrierName("O2").buildSubscriptionInfo()
        shadowOf(app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager)
            .setActiveSubscriptionInfos(sub)
        val m = monitor(this)
        m.start()
        runCurrent()
        assertThat(m.state.value.sim!!.network).isNull()
        assertThat(m.state.value.sim!!.carrierName).isEqualTo("O2")
        assertThat(m.state.value.sim!!.roaming).isTrue()
        m.stop()
    }

    @Test fun `LTE serving with NR NSA display override shows 5G`() = runTest {
        val m = monitor(this)
        m.start()
        runCurrent()
        assertThat(m.state.value.displayFiveG).isFalse()
        tm.setTelephonyDisplayInfo(
            ShadowTelephonyManager.createTelephonyDisplayInfo(
                TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertThat(m.state.value.displayFiveG).isTrue()
        m.stop()
    }

    @Test fun `missing phone permission gives MISSING_PHONE and no data without throwing`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val m = monitor(this)
        m.start()
        runCurrent()
        val s = m.state.value
        assertThat(s.permission).isEqualTo(Permission.MISSING_PHONE)
        assertThat(s.sim).isNull()
        assertThat(s.serving).isNull()
        assertThat(m.subscriptions.value).isEmpty()
        m.stop()
    }

    @Test fun `missing location permission keeps the SIM but hides cells`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val m = monitor(this)
        m.start()
        runCurrent()
        val s = m.state.value
        assertThat(s.permission).isEqualTo(Permission.MISSING_LOCATION)
        assertThat(s.sim!!.network).isEqualTo(Network.TELSTRA)
        assertThat(s.serving).isNull()
        assertThat(s.neighbours).isEmpty()
        m.stop()
    }

    @Test fun `airplane mode clears cells and 5G`() = runTest {
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val m = monitor(this)
        m.start()
        runCurrent()
        val s = m.state.value
        assertThat(s.airplane).isTrue()
        assertThat(s.serving).isNull()
        assertThat(s.nrSecondaries).isEmpty()
        assertThat(s.displayFiveG).isFalse()
        m.stop()
    }

    @Test fun `no SIM and no cells is a quiet empty state`() = runTest {
        shadowOf(app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager)
            .setActiveSubscriptionInfoList(emptyList())
        tm.setAllCellInfo(emptyList())
        val m = monitor(this)
        m.start()
        runCurrent()
        assertThat(m.state.value.sim).isNull()
        assertThat(m.state.value.serving).isNull()
        assertThat(m.state.value.permission).isEqualTo(Permission.GRANTED)
        m.stop()
    }

    @Test fun `polls every 2 seconds while started and stops after stop`() = runTest {
        val m = monitor(this)
        tm.setCallbackCellInfos(listOf(FakeCells.lte(true, pci = 1)))
        m.start()
        shadowOf(Looper.getMainLooper()).idle() // registration's initial callbacks (cached list)
        runCurrent() // first poll
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(m.state.value.serving!!.pci).isEqualTo(1)

        tm.setCallbackCellInfos(listOf(FakeCells.lte(true, pci = 2)))
        advanceTimeBy(1_999)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(m.state.value.serving!!.pci).isEqualTo(1)
        advanceTimeBy(2)
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(m.state.value.serving!!.pci).isEqualTo(2)

        m.stop()
        tm.setCallbackCellInfos(listOf(FakeCells.lte(true, pci = 3)))
        advanceTimeBy(10_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(m.state.value.serving!!.pci).isEqualTo(2)
    }

    @Test fun `selecting a subscription re-reads without crashing`() = runTest {
        val m = monitor(this)
        m.start()
        runCurrent()
        m.selectSubscription(1)
        runCurrent()
        assertThat(m.state.value.sim!!.subId).isEqualTo(1)
        m.selectSubscription(null)
        runCurrent()
        assertThat(m.state.value.sim).isNotNull()
        m.stop()
    }

    @Test fun `dual SIM publishes a state per active subscription with the data SIM as primary`() = runTest {
        val subs = listOf(
            ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder().setId(1).setMcc("505").setMnc("01").setCarrierName("Telstra").buildSubscriptionInfo(),
            ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder().setId(2).setMcc("505").setMnc("02").setCarrierName("Optus").buildSubscriptionInfo(),
        )
        shadowOf(app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager).setActiveSubscriptionInfos(*subs.toTypedArray())
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(2)
        val m = monitor(this)
        m.start()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(m.dataSubscriptionId.value).isEqualTo(2)
        assertThat(m.states.value.keys).containsExactly(1, 2).inOrder()
        assertThat(m.states.value.getValue(1).sim!!.network).isEqualTo(Network.TELSTRA)
        assertThat(m.states.value.getValue(2).sim!!.network).isEqualTo(Network.OPTUS)
        assertThat(m.state.value.sim!!.subId).isEqualTo(2) // the selected (data) SIM
        assertThat(m.states.value.getValue(2)).isEqualTo(m.state.value)
        m.stop()
    }
}
