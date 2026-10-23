package au.signalatlas.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AreaClassifierTest {
    @Test fun thresholds() {
        assertThat(AreaClassifier.classify(0)).isEqualTo(AreaClass.RURAL)
        assertThat(AreaClassifier.classify(2)).isEqualTo(AreaClass.RURAL)
        assertThat(AreaClassifier.classify(3)).isEqualTo(AreaClass.SUBURBAN)
        assertThat(AreaClassifier.classify(11)).isEqualTo(AreaClass.SUBURBAN)
        assertThat(AreaClassifier.classify(12)).isEqualTo(AreaClass.URBAN)
        assertThat(AreaClassifier.classify(39)).isEqualTo(AreaClass.URBAN)
        assertThat(AreaClassifier.classify(40)).isEqualTo(AreaClass.DENSE_URBAN)
        assertThat(AreaClassifier.classify(500)).isEqualTo(AreaClass.DENSE_URBAN)
    }
}
