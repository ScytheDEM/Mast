package au.signalatlas.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.map.NetworkColors
import kotlin.math.cos
import kotlin.math.sin

/** "Radiation pattern": one polar plot per sector, as drawn on the map while the sheet is open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadiationPatternSection(plots: List<SectorPlot>, modifier: Modifier = Modifier) {
    if (plots.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).testTag("radiation-pattern"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.pattern_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.pattern_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                plots.forEach { SectorPatternPlot(it) }
            }
            Text(
                stringResource(if (plots.any { it.source == PatternSource.ACMA }) R.string.pattern_source_acma else R.string.pattern_source_typical),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** A single sector's polar plot with its azimuth label underneath. */
@Composable
fun SectorPatternPlot(plot: SectorPlot, modifier: Modifier = Modifier, plotSize: androidx.compose.ui.unit.Dp = 132.dp) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val color = Color(NetworkColors.argb(plot.network))
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = axis)
    val points = remember(plot) { plot.points(stepDeg = 2) }
    val name = networkName(plot.network)
    val label = plot.azimuthDeg?.let { stringResource(R.string.pattern_sector_label, name, trim(it, 0)) } ?: stringResource(R.string.pattern_omni_label, name)
    Column(modifier.width(plotSize + 8.dp).testTag("pattern-${plot.sectorId}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(plotSize).semantics { contentDescription = label }) {
            val c = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 12.dp.toPx()
            listOf(1f, 2f / 3f, 1f / 3f).forEach { f -> drawCircle(ring, radius * f, c, style = Stroke(1.dp.toPx())) }
            drawLine(ring, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1.dp.toPx())
            drawLine(ring, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1.dp.toPx())
            val path = Path()
            points.forEachIndexed { i, p ->
                val o = Offset(c.x + p.x * radius, c.y - p.y * radius)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            path.close()
            drawPath(path, color.copy(alpha = 0.28f))
            drawPath(path, color, style = Stroke(2.dp.toPx()))
            plot.azimuthDeg?.let { az ->
                val a = Math.toRadians(az)
                val tip = Offset(c.x + (radius * sin(a)).toFloat(), c.y - (radius * cos(a)).toFloat())
                drawLine(axis, c, tip, 1.5.dp.toPx())
                drawCircle(color, 3.dp.toPx(), tip)
            }
            val n = measurer.measure("N", labelStyle)
            drawText(n, topLeft = Offset(c.x - n.size.width / 2f, 0f))
        }
        Spacer(Modifier.size(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkDot(plot.network, size = 8)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
