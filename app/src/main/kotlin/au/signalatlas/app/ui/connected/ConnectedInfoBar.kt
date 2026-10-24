package au.signalatlas.app.ui.connected

import android.content.res.Resources
import androidx.compose.foundation.layout.Row
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
 * The card at the bottom of the map: one row per active SIM (dual SIM), the data SIM first and large. Each row has the
 * carrier's colour as an accent, a signal-bars glyph from the RSRP, a large 4G/5G badge, band chips and the RSRP in its
 * quality colour; the primary row adds the tower distance and bearing. Tapping it opens the cellular details (or asks for
 * the missing permission).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectedInfoBar(ui: ConnectedUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val summary = ui.allSims.mapIndexed { i, sim -> simSummary(sim, res, i) }.joinToString(". ")
    ElevatedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
        modifier = modifier.fillMaxWidth().testTag("connected-bar").semantics { contentDescription = summary },
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            ui.allSims.forEachIndexed { i, sim ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrimaryRow(ui: ConnectedUi, index: Int) {
    val res = LocalContext.current.resources
    val connected = ui.status == ConnectedStatus.CONNECTED || ui.status == ConnectedStatus.UNTRACKED
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(vertical = 10.dp).width(5.dp).fillMaxHeight().background(accentOf(ui), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        if (!connected) {
            Text(
                connectedText(ui, res), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
            )
            return@Row
        }
        SignalBars(signalBars(ui.rsrpDbm), rsrpColor(ui.rsrpDbm), Modifier.padding(start = 14.dp).size(30.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TechBadge(ui.fiveG, large = true)
                Spacer(Modifier.width(8.dp))
                Text(simName(ui, res, index), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ui.bandList.forEach { BandChip(it) }
                ui.tower?.let {
                    Text(
                        res.getString(R.string.connected_tower, distance(it.distanceM), it.compass),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(accentOf(ui), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(simName(ui, res, index), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (connected) {
            Spacer(Modifier.width(8.dp))
            TechBadge(ui.fiveG, large = false)
            ui.bandList.forEach { Spacer(Modifier.width(4.dp)); BandChip(it) }
            Spacer(Modifier.weight(1f))
            SignalBars(signalBars(ui.rsrpDbm), rsrpColor(ui.rsrpDbm), Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            RsrpValue(ui.rsrpDbm, large = false)
        } else {
            Spacer(Modifier.width(8.dp))
            Text(connectedText(ui, res), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RsrpValue(rsrp: Int?, large: Boolean) {
    val color = rsrpColor(rsrp)
    Column(horizontalAlignment = Alignment.End) {
        Text(
            rsrp?.let { dbmNumber(it) } ?: "–",
            style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
        if (large) Text("dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A band such as "n78" or "B3". */
@Composable
fun BandChip(band: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
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
