package au.signalatlas.app.ui.connected

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import au.signalatlas.app.ui.map.SignalQuality
import au.signalatlas.core.map.NetworkColors
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.connected.ConnectedStatus
import au.signalatlas.app.connected.ConnectedUi
import au.signalatlas.core.calibration.Confidence
import au.signalatlas.core.model.Network
import java.util.Locale

@Composable
fun networkName(n: Network): String = stringResource(networkNameRes(n))

private fun networkNameRes(n: Network) = when (n) {
    Network.TELSTRA -> R.string.network_telstra
    Network.OPTUS -> R.string.network_optus
    Network.TPG -> R.string.network_tpg
    Network.OPTUS_TPG_JV -> R.string.network_optus_tpg_jv
    Network.NBN -> R.string.network_nbn
}

/**
 * The info bar's one line, e.g. "Telstra 5G · B28 + n78 · −94 dBm · tower 1.3 km NE · Likely · ≈ 85 Mbps", or the reason
 * there is nothing to show ("Allow phone access to see your tower").
 */
fun connectedText(ui: ConnectedUi, res: Resources): String = when (ui.status) {
    ConnectedStatus.NEEDS_PHONE -> res.getString(R.string.connected_needs_phone)
    ConnectedStatus.NEEDS_LOCATION -> res.getString(R.string.connected_needs_location)
    ConnectedStatus.AIRPLANE -> res.getString(R.string.connected_airplane)
    ConnectedStatus.NO_SIM -> res.getString(R.string.connected_no_sim)
    ConnectedStatus.ROAMING -> res.getString(R.string.connected_roaming, carrierOf(ui, res))
    ConnectedStatus.SEARCHING -> res.getString(R.string.connected_searching)
    ConnectedStatus.UNTRACKED -> res.getString(R.string.connected_untracked, carrierOf(ui, res))
    ConnectedStatus.CONNECTED -> listOfNotNull(
        res.getString(R.string.connected_carrier_tech, carrierOf(ui, res), res.getString(if (ui.fiveG) R.string.tech_5g else R.string.tech_4g)),
        ui.bands,
        ui.rsrpDbm?.let { res.getString(R.string.connected_rsrp, it.toString().replace('-', '−')) },
        ui.tower?.let { res.getString(R.string.connected_tower, distance(it.distanceM), it.compass) },
        ui.confidence?.let { res.getString(confidenceRes(it)) },
        ui.speedMbps?.let { res.getString(R.string.connected_speed, it) },
    ).joinToString(" · ")
}

private fun carrierOf(ui: ConnectedUi, res: Resources): String =
    ui.carrier ?: ui.network?.let { res.getString(networkNameRes(it)) } ?: res.getString(R.string.connected_unknown_carrier)

private fun confidenceRes(c: Confidence) = when (c) {
    Confidence.UNCERTAIN -> R.string.connected_uncertain
    Confidence.LIKELY -> R.string.connected_likely
    Confidence.CONFIRMED -> R.string.connected_confirmed
}

/** "850 m", "1.3 km", "12 km". */
internal fun distance(m: Double): String = when {
    m < 1000 -> String.format(Locale.getDefault(), "%d m", (m / 10).toInt() * 10)
    m < 10_000 -> String.format(Locale.getDefault(), "%.1f km", m / 1000)
    else -> String.format(Locale.getDefault(), "%d km", (m / 1000).toInt())
}

/**
 * The card at the bottom of the map, in Material 3 Expressive form: a tonal surface (surfaceContainerHigh, dynamic colour)
 * with an extra-large rounded shape, one row per active SIM (dual SIM), the data SIM first and large. The primary row
 * leads with a carrier-tinted tile holding the signal bars, then the 4G/5G badge and carrier name over a fixed-height
 * band-chip line, and ends with the RSRP in its legend colour. Values cross-fade when they change and every slot has a
 * fixed size, so a band or reading change never moves the layout. Feed it [au.signalatlas.app.connected.ServingStabiliser]
 * output. Tapping it opens the cellular details (or asks for the missing permission).
 */
@Composable
fun ConnectedInfoBar(ui: ConnectedUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val summary = ui.allSims.mapIndexed { i, sim -> simSummary(sim, res, i) }.joinToString(". ")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth().testTag("connected-bar").semantics { contentDescription = summary },
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            ui.allSims.forEachIndexed { i, sim ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (i == 0) PrimaryRow(sim, i) else CompactRow(sim, i)
            }
        }
    }
}

@Composable
private fun accentOf(ui: ConnectedUi): Color = ui.network?.let { Color(NetworkColors.argb(it)) } ?: MaterialTheme.colorScheme.outline

/** The colour of an RSRP reading: the legend's, so it matches the map. */
@Composable
fun rsrpColor(rsrp: Int?): Color = when (signalQuality(rsrp)) {
    SignalQuality.EXCELLENT -> LegendExcellentText
    SignalQuality.GOOD -> LegendGoodText
    SignalQuality.FAIR -> LegendFairText
    SignalQuality.WEAK -> LegendWeakText
    null -> if (rsrp != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
}

private val LegendExcellentText @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6FB3F0) else Color(0xFF0B5FA5)
private val LegendGoodText @Composable get() = if (isSystemInDarkTheme()) Color(0xFF5CCBBD) else Color(0xFF1B7A6E)
private val LegendFairText @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE9C46A) else Color(0xFF8A6A00)
private val LegendWeakText @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF4A261) else Color(0xFFB4570F)

private val RSRP_WIDTH = 64.dp
private val CHIP_LINE_HEIGHT = 24.dp

@Composable
private fun PrimaryRow(ui: ConnectedUi, index: Int) {
    val res = LocalContext.current.resources
    val connected = ui.status == ConnectedStatus.CONNECTED || ui.status == ConnectedStatus.UNTRACKED
    val accent = accentOf(ui)
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // The carrier tile: the carrier's colour as a tonal accent behind the signal bars.
        Box(
            Modifier.size(48.dp).background(accent.copy(alpha = 0.16f), MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            if (connected) {
                Crossfade(signalBars(ui.rsrpDbm), label = "bars") { bars ->
                    SignalBars(bars, rsrpColor(ui.rsrpDbm), Modifier.size(26.dp))
                }
            } else {
                Box(Modifier.size(10.dp).background(accent, CircleShape))
            }
        }
        if (!connected) {
            Text(
                connectedText(ui, res), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            )
            return@Row
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crossfade(ui.fiveG, label = "tech") { TechBadge(it, large = true) }
                Spacer(Modifier.width(8.dp))
                Text(simName(ui, res, index), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            // One fixed-height line: bands never wrap, so the card keeps its height.
            Row(Modifier.height(CHIP_LINE_HEIGHT).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(ui.bandList, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bands") { bands ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        bands.forEach { BandChip(it, accent = accent) }
                    }
                }
                ui.tower?.let {
                    Text(
                        res.getString(R.string.connected_tower, distance(it.distanceM), it.compass),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        RsrpValue(ui.rsrpDbm, large = true)
    }
}

@Composable
private fun CompactRow(ui: ConnectedUi, index: Int) {
    val res = LocalContext.current.resources
    val connected = ui.status == ConnectedStatus.CONNECTED || ui.status == ConnectedStatus.UNTRACKED
    val accent = accentOf(ui)
    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(simName(ui, res, index), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (connected) {
            Spacer(Modifier.width(8.dp))
            Crossfade(ui.fiveG, label = "tech") { TechBadge(it, large = false) }
            AnimatedContent(ui.bandList, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bands", modifier = Modifier.weight(1f).clipToBounds()) { bands ->
                Row(verticalAlignment = Alignment.CenterVertically) { bands.forEach { Spacer(Modifier.width(4.dp)); BandChip(it, accent = accent) } }
            }
            SignalBars(signalBars(ui.rsrpDbm), rsrpColor(ui.rsrpDbm), Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            RsrpValue(ui.rsrpDbm, large = false)
        } else {
            Spacer(Modifier.width(8.dp))
            Text(connectedText(ui, res), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The RSRP in a fixed-width slot with tabular figures; a new value cross-fades in. */
@Composable
private fun RsrpValue(rsrp: Int?, large: Boolean) {
    val color = rsrpColor(rsrp)
    Column(Modifier.width(if (large) RSRP_WIDTH else 44.dp), horizontalAlignment = Alignment.End) {
        Crossfade(rsrp, label = "rsrp") { v ->
            Text(
                v?.let { dbmNumber(it) } ?: "–",
                style = (if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge)
                    .copy(fontFeatureSettings = "tnum"),
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        if (large) Text(stringResource(R.string.unit_dbm), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The big 4G / 5G badge. */
@Composable
fun TechBadge(fiveG: Boolean, large: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = if (fiveG) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (fiveG) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = if (large) MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            stringResource(if (fiveG) R.string.tech_5g else R.string.tech_4g),
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = if (large) 10.dp else 6.dp, vertical = if (large) 3.dp else 1.dp),
        )
    }
}

/** A band such as "n78" or "B3"; [accent] (the carrier's colour) outlines it. */
@Composable
fun BandChip(band: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        border = accent?.let { BorderStroke(1.dp, it.copy(alpha = 0.55f)) },
        modifier = modifier,
    ) {
        Text(band, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** Four rising bars; the first [bars] are filled in [color]. */
@Composable
fun SignalBars(bars: Int, color: Color, modifier: Modifier = Modifier) {
    val off = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.cellular_bars_description, bars)
    Canvas(modifier.semantics { contentDescription = description }) {
        val gap = size.width * 0.08f
        val w = (size.width - gap * 3) / 4f
        for (i in 0 until 4) {
            val h = size.height * (0.3f + 0.7f * i / 3f)
            drawRoundRect(
                color = if (i < bars) color else off,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w * 0.3f),
            )
        }
    }
}
