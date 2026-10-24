package au.signalatlas.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.signalatlas.feature.trip.TechFloor
import au.signalatlas.feature.trip.TripConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun defaultsThenPersistsAcrossInstances() {
        context.getSharedPreferences("trip", Context.MODE_PRIVATE).edit().clear().commit()
        val a = TripPrefs(context)
        assertThat(a.config.value).isEqualTo(TripConfig(90, 300, TechFloor.LOSE_ALL, false))
        a.update { it.copy(windowSeconds = 120, minDeadZoneM = 1000, floor = TechFloor.LOSE_5G, voice = true) }
        val b = TripPrefs(context)
        assertThat(b.config.value).isEqualTo(TripConfig(120, 1000, TechFloor.LOSE_5G, true))
    }

    @Test fun invalidValuesFallBackToTheDefaults() {
        context.getSharedPreferences("trip", Context.MODE_PRIVATE).edit().putInt("window_s", 45).putInt("min_dead_m", 7).putString("floor", "x").commit()
        assertThat(TripPrefs(context).config.value).isEqualTo(TripConfig())
        val p = TripPrefs(context)
        p.update { it.copy(windowSeconds = 61) }
        assertThat(p.config.value.windowSeconds).isEqualTo(90)
    }
}
