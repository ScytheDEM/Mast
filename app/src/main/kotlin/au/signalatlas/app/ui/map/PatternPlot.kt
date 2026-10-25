package au.signalatlas.app.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.ui.connected.BandChip
import au.signalatlas.core.map.NetworkColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Radiation pattern": a header that shows or hides the polar plots ([expanded], remembered by the caller), one polar
 * plot per lobe, the list of which bands face which way ("Telstra · 45° · B3 · B1 · B7 · n78", always shown) and where
 * the patterns come from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadiationPatternSection(
    plots: List<SectorPlot>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (plots.isEmpty()) return
    val omni = stringResource(R.string.pattern_omni)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).testTag("radiation-pattern"),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            val toggle = stringResource(if (expanded) R.string.pattern_collapse else R.string.pattern_expand)
            val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = toggle) { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("pattern-toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.pattern_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, toggle, Modifier.rotate(turn))
            }
            AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.pattern_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        plots.forEach { SectorPatternPlot(it) }
                    }
                }
            }
            Text(
                stringResource(R.string.pattern_sectors_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            plots.forEach { SectorBandsRow(it, omni) }
            val sources = plots.map { it.source }.distinct().sortedBy { it.ordinal }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                sources.forEach { src ->
                    Text(
                        stringResource(if (src == PatternSource.ACMA) R.string.pattern_source_acma else R.string.pattern_source_typical),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (PatternSource.TYPICAL in sources) {
                    Text(
                        stringResource(R.string.pattern_typical_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "● Telstra · 45°  [B3] [B1] [B7] [n78]", read out as one line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectorBandsRow(plot: SectorPlot, omni: String) {
    val name = networkName(plot.network)
    val line = sectorBandsLine(name, plot, omni)
    FlowRow(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("sector-line-${plot.key}")
            .clearAndSetSemantics { contentDescription = line },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkDot(plot.network, size = 8)
        Text(
            listOf(name, plot.azimuthDeg?.let { "${trim(it, 0)}°" } ?: omni).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 2.dp),
        )
        plot.bands.forEach { BandChip(it) }
    }
}

/** A single lobe's polar plot (north up, rings 10/20/30 dB down), its azimuth at the tip, and its label underneath. */
@Composable
fun SectorPatternPlot(plot: SectorPlot, modifier: Modifier = Modifier, plotSize: Dp = 132.dp) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val color = Color(NetworkColors.argb(plot.network))
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = axis)
    val tipStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val points = remember(plot) { plot.points(stepDeg = 2) }
    val name = networkName(plot.network)
    val label = plot.azimuthDeg?.let { stringResource(R.string.pattern_sector_label, name, trim(it, 0)) } ?: stringResource(R.string.pattern_omni_label, name)
    val source = stringResource(if (plot.source == PatternSource.ACMA) R.string.pattern_source_acma else R.string.pattern_source_typical)
    Column(modifier.width(plotSize + 8.dp).testTag("pattern-${plot.key}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(plotSize).semantics { contentDescription = label; stateDescription = source }) {
            val c = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 14.dp.toPx()
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
            val n = measurer.measure("N", labelStyle)
            drawText(n, topLeft = Offset(c.x - n.size.width / 2f, 0f))
            plot.azimuthDeg?.let { az ->
                val a = Math.toRadians(az)
                val tip = Offset(c.x + (radius * sin(a)).toFloat(), c.y - (radius * cos(a)).toFloat())
                drawLine(axis, c, tip, 1.5.dp.toPx())
                drawCircle(color, 3.dp.toPx(), tip)
                // The azimuth at the tip, pushed outward and kept inside the canvas.
                val t = measurer.measure("${trim(az, 0)}°", tipStyle)
                val out = 10.dp.toPx()
                val x = (tip.x + (out * sin(a)).toFloat() - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
                val y = (tip.y - (out * cos(a)).toFloat() - t.size.height / 2f).coerceIn(0f, size.height - t.size.height)
                drawText(t, topLeft = Offset(x, y))
            }
        }
        Spacer(Modifier.size(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkDot(plot.network, size = 8)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        if (plot.bands.isNotEmpty()) {
            Text(
                plot.bands.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
