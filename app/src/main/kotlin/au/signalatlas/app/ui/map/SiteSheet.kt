package au.signalatlas.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.map.NetworkColors
import au.signalatlas.core.model.Carrier
import au.signalatlas.core.model.Network
import au.signalatlas.core.model.Sector
import au.signalatlas.core.model.SiteDetail
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A site's details: header, then one card per network with its sectors in azimuth order and one
 * line per carrier. Used in the compact bottom sheet and the expanded side pane.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SiteSheetContent(
    detail: SiteDetail,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    val site = detail.site
    val groups = detail.sectors.groupBy { it.network }.toSortedMap(compareBy { it.ordinal })
    LazyColumn(modifier, contentPadding = contentPadding) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(site.name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            stringResource(R.string.site_id, site.id),
                            listOfNotNull(site.state, site.postcode).joinToString(" ").ifBlank { null },
                            site.elevationM?.let { stringResource(R.string.site_elevation, trim(it, 0)) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.keys.forEach { NetworkPill(it) }
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            }
        }
        groups.forEach { (network, sectors) ->
            item(key = "net-${network.name}") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            NetworkDot(network)
                            Spacer(Modifier.width(10.dp))
                            Text(networkName(network), style = MaterialTheme.typography.titleMedium)
                        }
                        sectors.forEachIndexed { i, sector ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            SectorBlock(sector)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectorBlock(sector: Sector) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sector.azimuthDeg?.let { stringResource(R.string.sector_azimuth, trim(it, 1)) } ?: stringResource(R.string.sector_omni),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.sector_height, trim(sector.heightM, 1)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        val numbers = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
        sector.carriers.forEach { c ->
            Text(carrierLine(c), style = numbers, modifier = Modifier.padding(vertical = 2.dp))
        }
        Spacer(Modifier.height(6.dp))
        val details = listOfNotNull(
            antennaLabel(sector)?.let { stringResource(R.string.sector_antenna, it) },
            sector.carriers.map { it.licenceNo }.distinct().takeIf { it.isNotEmpty() }
                ?.let { stringResource(R.string.sector_licence, it.joinToString(", ")) },
        )
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun NetworkPill(network: Network, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkDot(network, size = 8)
        Spacer(Modifier.width(6.dp))
        Text(networkName(network), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun NetworkDot(network: Network, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).background(Color(NetworkColors.argb(network)), CircleShape))
}

@Composable
fun networkName(network: Network): String = stringResource(
    when (network) {
        Network.TELSTRA -> R.string.network_telstra
        Network.OPTUS -> R.string.network_optus
        Network.TPG -> R.string.network_tpg
        Network.OPTUS_TPG_JV -> R.string.network_optus_tpg_jv
        Network.NBN -> R.string.network_nbn
    },
)

/** `"n78 · 3.41 GHz · 100 MHz · 61.8 dBm EIRP"`. */
fun carrierLine(c: Carrier): String =
    "${c.band.band} · ${frequencyLabel(c.freqHz)} · ${bandwidthLabel(c.bandwidthHz)} · ${trimFixed(c.eirpDbm, 1)} dBm EIRP"

/** GHz from 1 GHz up (to 1 MHz precision), else MHz (to 0.1 MHz). */
fun frequencyLabel(hz: Long): String =
    if (hz >= 1_000_000_000L) "${trim(hz / 1e9, 3)} GHz" else "${trim(hz / 1e6, 1)} MHz"

fun bandwidthLabel(hz: Long): String =
    if (hz >= 1_000_000L) "${trim(hz / 1e6, 1)} MHz" else "${trim(hz / 1e3, 0)} kHz"

private fun antennaLabel(sector: Sector): String? {
    val a = sector.antenna ?: return null
    val model = a.model?.trim()?.takeIf { it.isNotEmpty() }
    val maker = a.manufacturer?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        model != null && maker != null && !model.contains(maker, ignoreCase = true) -> "$maker $model"
        model != null -> model
        else -> maker
    }
}

/** Rounds half-up to [decimals] and drops trailing zeros: 3.410 → "3.41", 100.0 → "100". */
internal fun trim(value: Double, decimals: Int): String =
    BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** Rounds half-up to exactly [decimals]: 61.8 → "61.8", 62.0 → "62.0". */
internal fun trimFixed(value: Double, decimals: Int): String =
    BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
