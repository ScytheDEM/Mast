package au.signalatlas.core.coverage

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ConnectivityNetworkMonitorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun callback(): ConnectivityManager.NetworkCallback =
        shadowOf(cm).networkCallbacks.single()

    private fun caps(vararg c: Int) = NetworkCapabilities::class.java.getDeclaredConstructor().let {
        it.isAccessible = true
        it.newInstance().also { nc -> c.forEach { cap -> shadowOf(nc).addCapability(cap) } }
    }

    @Test fun followsTheDefaultNetworkMeteredAndLost() {
        val m = ConnectivityNetworkMonitor(context)
        assertThat(m.isOnline()).isFalse()
        assertThat(m.unmetered.value).isFalse()

        val net = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(1)
        // Connected but not validated (captive portal, no route): not online.
        callback().onCapabilitiesChanged(net, caps(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertThat(m.isOnline()).isFalse()
        callback().onCapabilitiesChanged(net, caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertThat(m.isOnline()).isTrue()
        assertThat(m.isMetered()).isTrue() // no NOT_METERED capability
        assertThat(m.unmetered.value).isFalse()

        callback().onCapabilitiesChanged(net, caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED, NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertThat(m.isMetered()).isFalse()
        assertThat(m.unmetered.value).isTrue()

        callback().onLost(net)
        assertThat(m.isOnline()).isFalse()
        assertThat(m.unmetered.value).isFalse()
    }
}
