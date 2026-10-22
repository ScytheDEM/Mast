package au.signalatlas.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BandPlanTest {
    @Test fun classifiesKnownFrequencies() {
        assertThat(BandPlan.classify(778_000_000, Network.TELSTRA)).isEqualTo(BandInfo("B28", Tech.LTE))
        assertThat(BandPlan.classify(872_500_000, Network.TELSTRA)).isEqualTo(BandInfo("n5", Tech.NR))
        assertThat(BandPlan.classify(872_500_000, Network.TPG)).isEqualTo(BandInfo("B5", Tech.LTE))
        assertThat(BandPlan.classify(948_000_000, Network.OPTUS)).isEqualTo(BandInfo("B8", Tech.LTE))
        assertThat(BandPlan.classify(2_352_000_000, Network.NBN)).isEqualTo(BandInfo("B40", Tech.LTE))
        assertThat(BandPlan.classify(3_410_000_000, Network.NBN)).isEqualTo(BandInfo("n78", Tech.NR))
        assertThat(BandPlan.classify(27_499_000_000, Network.TELSTRA)!!.band).isEqualTo("n258")
        assertThat(BandPlan.classify(28_900_000_000, Network.NBN)!!.band).isEqualTo("n257")
    }

    @Test fun classifiesB26N26AndN77() {
        assertThat(BandPlan.classify(862_000_000, Network.TELSTRA)).isEqualTo(BandInfo("n26", Tech.NR))
        assertThat(BandPlan.classify(862_000_000, Network.OPTUS)).isEqualTo(BandInfo("B26", Tech.LTE))
        assertThat(BandPlan.classify(3_920_000_000, Network.NBN)).isEqualTo(BandInfo("n77", Tech.NR))
        assertThat(BandPlan.classify(3_800_000_000, Network.NBN)!!.band).isEqualTo("n78")
    }

    @Test fun boundariesAreInclusive() {
        assertThat(BandPlan.classify(758_000_000, Network.OPTUS)!!.band).isEqualTo("B28")
        assertThat(BandPlan.classify(803_000_000, Network.OPTUS)!!.band).isEqualTo("B28")
        assertThat(BandPlan.classify(27_500_000_000, Network.OPTUS)!!.band).isEqualTo("n257")
        assertThat(BandPlan.classify(29_500_000_000, Network.OPTUS)!!.band).isEqualTo("n257")
        assertThat(BandPlan.classify(29_500_000_001, Network.OPTUS)).isNull()
    }

    @Test fun unknownBandIsNull() {
        assertThat(BandPlan.classify(450_000_000, Network.TELSTRA)).isNull()
    }
}
