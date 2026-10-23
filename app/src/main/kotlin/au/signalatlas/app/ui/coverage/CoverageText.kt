package au.signalatlas.app.ui.coverage

import android.content.res.Resources
import au.signalatlas.app.R
import au.signalatlas.app.ui.map.BestSector
import au.signalatlas.app.ui.map.ReadoutResult
import au.signalatlas.app.ui.map.SignalQuality
import au.signalatlas.app.ui.map.networkNameRes
import kotlin.math.roundToInt

/*
 * The long-press card's strings, built from Resources (not composables) so they can be checked without a UI.
 */

private const val MINUS = '−'

/** "−97" (a true minus sign, as in the legend). */
fun dbmLabel(dbm: Int): String = if (dbm < 0) "$MINUS${-dbm}" else dbm.toString()

fun SignalQuality.label(res: Resources): String = res.getString(
    when (this) {
        SignalQuality.EXCELLENT -> R.string.quality_excellent
        SignalQuality.GOOD -> R.string.quality_good
        SignalQuality.FAIR -> R.string.quality_fair
        SignalQuality.WEAK -> R.string.quality_weak
    },
)

/** "Predicted −97 dBm · Good · 82% reliable". */
fun ReadoutResult.Predicted.headline(res: Resources): String =
    if (reliablePercent == null) {
        res.getString(R.string.readout_predicted_no_reliability, dbmLabel(rsrpDbm), quality.label(res))
    } else {
        res.getString(R.string.readout_predicted, dbmLabel(rsrpDbm), quality.label(res), reliablePercent)
    }

/** "Best sector: Telstra n78 · 1.2 km". */
fun BestSector.line(res: Resources): String =
    res.getString(R.string.readout_best_sector, res.getString(networkNameRes(network)), band, distanceLabel(res, distanceM))

/** Under 1 km in whole metres ("850 m"), otherwise km to one decimal ("1.2 km"). */
fun distanceLabel(res: Resources, metres: Double): String {
    val m = metres.roundToInt()
    return if (m < 1000) res.getString(R.string.distance_m, m) else res.getString(R.string.distance_km_1dp, m / 1000.0)
}

/** Every line the card shows for [result], top to bottom. */
fun ReadoutResult.lines(res: Resources): List<String> = when (this) {
    ReadoutResult.Calculating -> listOf(res.getString(R.string.readout_calculating))
    ReadoutResult.NoPrediction -> listOf(res.getString(R.string.readout_no_prediction))
    ReadoutResult.NoCoverage -> listOf(res.getString(R.string.readout_no_coverage))
    is ReadoutResult.Predicted -> listOfNotNull(headline(res), best?.line(res))
}
