package au.signalatlas.feature.trip

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripDeliveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun stats(ahead: List<Float>, covered: Boolean = true, nextDead: Double? = 1_800.0) = TripStats(
        0, 0.0, "Telstra", "4G", TripStatus(covered, nextDead, 1_000.0, if (covered) null else 1_500.0, ahead, 2_250.0),
    )

    @Test fun stripMergesRunsAndColoursThem() {
        val ahead = List(4) { 0.95f } + List(2) { 0.6f } + List(3) { 0.1f } + List(1) { 0.0f } + List(2) { 0.9f }
        val s = TripContent.strip(ahead)
        assertThat(s).containsExactly(
            StripSegment(200, CoverageLevel.COVERED), StripSegment(100, CoverageLevel.WEAK),
            StripSegment(200, CoverageLevel.DEAD), StripSegment(100, CoverageLevel.COVERED),
        ).inOrder()
        assertThat(CoverageLevel.COVERED.color).isEqualTo(0xFF2A9D8F.toInt())
        assertThat(CoverageLevel.WEAK.color).isEqualTo(0xFFF4A261.toInt())
        assertThat(CoverageLevel.DEAD.color).isEqualTo(0xFF9E9E9E.toInt())
        assertThat(TripContent.strip(List(500) { 0.9f }).sumOf { it.lengthM }).isEqualTo(10_000)
    }

    @Test fun contentTextsAndChip() {
        val c = TripContent.of(stats(List(40) { 0.9f }))
        assertThat(c.title).isEqualTo("Trip · Telstra · 4G")
        assertThat(c.text).isEqualTo("Next dead zone in 1.8 km")
        assertThat(c.shortCritical).isEqualTo("1.8 km")
        assertThat(c.progress).isEqualTo(0)
        val dead = TripContent.of(stats(List(10) { 0f }, covered = false))
        assertThat(dead.text).isEqualTo("Coverage returns in ~1.5 km")
        val start = TripContent.of(null)
        assertThat(start.title).isEqualTo("Trip")
        assertThat(start.text).isEqualTo(TripController.WAITING_GPS)
        assertThat(start.segments).isEmpty()
    }

    @Test fun oldAndroidGetsAPlainOngoingNotificationWithTheSameText() {
        var applied = false
        val n = TripNotifications(context, sdkInt = 35, liveUpdate = { _, _ -> applied = true })
        val notif = n.ongoing(stats(List(40) { 0.9f }))
        assertThat(applied).isFalse()
        assertThat(notif.channelId).isEqualTo("trip_status")
        assertThat(notif.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notif.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Trip · Telstra · 4G")
        assertThat(notif.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Next dead zone in 1.8 km")
        assertThat(notif.actions.map { it.title.toString() }).containsExactly("Stop trip")
    }

    @Test fun android16AppliesTheLiveUpdateWithSegmentsAndChipText() {
        var seen: TripContent? = null
        val n = TripNotifications(context, sdkInt = 36, liveUpdate = { _, c -> seen = c })
        n.ongoing(stats(List(4) { 0.95f } + List(6) { 0.1f }))
        assertThat(seen!!.segments).containsExactly(StripSegment(200, CoverageLevel.COVERED), StripSegment(300, CoverageLevel.DEAD)).inOrder()
        assertThat(seen!!.shortCritical).isEqualTo("1.8 km")
    }

    @Test fun channelsAreSeparateAndAlertsAreHighImportance() {
        TripNotifications(context, sdkInt = 35).ensureChannels()
        val nm = context.getSystemService(NotificationManager::class.java)
        assertThat(nm.getNotificationChannel("trip_status").importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(nm.getNotificationChannel("coverage_alerts").importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        val alertNotif = Notification.Builder(context, "coverage_alerts").build()
        assertThat(alertNotif.channelId).isEqualTo("coverage_alerts")
    }

    @Test fun voicePhrases() {
        assertThat(TripVoice.phrase(Alert(AlertKind.LEAVING, "x", 1_800.0, 6_000.0))).isEqualTo("Coverage ends in 1.8 kilometres")
        assertThat(TripVoice.phrase(Alert(AlertKind.LEAVING, "x", 800.0, 6_000.0))).isEqualTo("Coverage ends in 800 metres")
        assertThat(TripVoice.phrase(Alert(AlertKind.BACK, "Back in coverage"))).isEqualTo("Back in coverage")
        assertThat(TripVoice.phrase(Alert(AlertKind.EARLY, "Signal dropping fast"))).isEqualTo("Signal dropping fast")
    }

    @Test fun voiceIsSilentWhenOffAndSpeaksWhenOn() {
        var created = 0
        var on = false
        val voice = TripVoice(context, { on }, { _, _ -> created++; throw IllegalStateException("no engine in tests") })
        voice.speak(Alert(AlertKind.BACK, "Back in coverage"))
        assertThat(created).isEqualTo(0)
        on = true
        try { voice.speak(Alert(AlertKind.BACK, "Back in coverage")) } catch (e: IllegalStateException) { }
        assertThat(created).isEqualTo(1)
    }

    @Test fun tileIsActiveOnlyWhileRunning() {
        assertThat(TileModel.of(true).state).isEqualTo(Tile.STATE_ACTIVE)
        assertThat(TileModel.of(false).state).isEqualTo(Tile.STATE_INACTIVE)
        assertThat(TileModel.of(true).subtitleRes).isEqualTo(R.string.trip_tile_running)
    }
}
