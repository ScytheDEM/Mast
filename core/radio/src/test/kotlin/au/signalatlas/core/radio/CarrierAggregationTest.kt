package au.signalatlas.core.radio

import android.telephony.TelephonyDisplayInfo as T
import au.signalatlas.core.model.Tech
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CarrierAggregationTest {
    @Test fun threeCarriersSumToSixtyMhz() {
        val ca = CarrierAggregation.from(intArrayOf(20000, 20000, 20000))!!
        assertThat(ca.count).isEqualTo(3)
        assertThat(ca.totalMhz).isEqualTo(60)
        assertThat(ca.text).isEqualTo("CA ×3 · 60 MHz")
        assertThat(ca.isAggregated).isTrue()
    }

    @Test fun nonPositiveAndUnavailableEntriesAreDropped() {
        val ca = CarrierAggregation.from(intArrayOf(10000, 0, -1, Int.MAX_VALUE, 5000))!!
        assertThat(ca.bandwidthsKhz).containsExactly(10000, 5000).inOrder()
        assertThat(ca.totalMhz).isEqualTo(15)
    }

    @Test fun emptyOrNullIsNoAggregation() {
        assertThat(CarrierAggregation.from(null)).isNull()
        assertThat(CarrierAggregation.from(intArrayOf())).isNull()
        assertThat(CarrierAggregation.from(intArrayOf(0, -1))).isNull()
    }

    @Test fun singleCarrierIsNotAggregated() {
        val ca = CarrierAggregation.from(intArrayOf(20000))!!
        assertThat(ca.isAggregated).isFalse()
        assertThat(ca.text).isEqualTo("CA ×1 · 20 MHz")
    }

    @Test fun fractionalMhzRounds() {
        assertThat(CarrierAggregation(listOf(1400, 3000)).totalMhz).isEqualTo(4)
    }

    private fun label(tech: Tech?, o: Int, ca: CarrierAggregation? = null) = DisplayLabel.of(tech, o, ca)

    @Test fun labels() {
        val none = T.OVERRIDE_NETWORK_TYPE_NONE
        assertThat(label(null, none)).isNull()
        assertThat(label(Tech.LTE, none)).isEqualTo(DisplayLabel.LTE)
        assertThat(label(Tech.LTE, T.OVERRIDE_NETWORK_TYPE_LTE_CA)).isEqualTo(DisplayLabel.LTE_A)
        assertThat(label(Tech.LTE, T.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO)).isEqualTo(DisplayLabel.LTE_A)
        assertThat(label(Tech.LTE, none, CarrierAggregation(listOf(20000, 10000)))).isEqualTo(DisplayLabel.LTE_A)
        assertThat(label(Tech.LTE, none, CarrierAggregation(listOf(20000)))).isEqualTo(DisplayLabel.LTE)
        assertThat(label(Tech.LTE, T.OVERRIDE_NETWORK_TYPE_NR_NSA)).isEqualTo(DisplayLabel.FIVE_G)
        assertThat(label(Tech.LTE, T.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)).isEqualTo(DisplayLabel.FIVE_G_PLUS)
        assertThat(label(Tech.NR, none)).isEqualTo(DisplayLabel.FIVE_G)
        assertThat(label(Tech.NR, T.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)).isEqualTo(DisplayLabel.FIVE_G_PLUS)
        assertThat(DisplayLabel.FIVE_G_PLUS.text).isEqualTo("5G+")
    }
}
