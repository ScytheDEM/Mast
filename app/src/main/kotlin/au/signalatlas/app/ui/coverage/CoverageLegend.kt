package au.signalatlas.app.ui.coverage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.coverage.CoverageSelection

/*
 * Legend colours are fixed (shared-context rule 11, the same ramp as CoverageLayer), not Material You: they must
 * match the map layer whatever the wallpaper.
 */
internal val LegendExcellent = Color(0xFF0B5FA5)
internal val LegendGood = Color(0xFF2A9D8F)
internal val LegendFair = Color(0xFFE9C46A)
internal val LegendWeak = Color(0xFFF4A261)

/** Colour key for the coverage layer; the rows follow the view (RSRP bands, or reliability). */
@Composable
fun CoverageLegend(view: CoverageSelection.View, modifier: Modifier = Modifier) {
    val rows = when (view) {
        CoverageSelection.View.RSRP -> listOf(
            LegendExcellent to R.string.legend_excellent,
            LegendGood to R.string.legend_good,
            LegendFair to R.string.legend_fair,
            LegendWeak to R.string.legend_weak,
        )
        CoverageSelection.View.PROBABILITY -> listOf(
            LegendGood to R.string.legend_reliability_high,
            LegendWeak to R.string.legend_reliability_mid,
        )
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 2.dp,
    ) {
        // One slim row: the legend is a key, not a panel.
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { (color, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Surface(
                        color = color,
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(10.dp),
                    ) { }
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
