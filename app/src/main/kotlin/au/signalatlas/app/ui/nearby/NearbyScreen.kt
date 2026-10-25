@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package au.signalatlas.app.ui.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.app.ui.map.NetworkDot
import au.signalatlas.app.ui.map.networkName
import au.signalatlas.core.model.SiteSummary
import kotlin.math.roundToInt

/** The towers nearest the user (filtered like the map), nearest first. */
@Composable
fun NearbyContent(
    locationGranted: Boolean,
    locationEnabled: Boolean,
    hasLocation: Boolean,
    onOpenLocationSettings: () -> Unit,
    nearby: List<Pair<SiteSummary, Double>>,
    onSiteClick: (SiteSummary) -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
) {
    LazyColumn(modifier, contentPadding = contentPadding) {
        item(key = "header") {
            // Its own page (a navigation bar section): nothing to close.
            Text(
                stringResource(R.string.nearby_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        when {
            !locationGranted -> item(key = "permission") {
                Message(stringResource(R.string.nearby_no_permission)) {
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onRequestPermission) { Text(stringResource(R.string.nearby_allow)) }
                }
            }
            !locationEnabled -> item(key = "off") {
                Message(stringResource(R.string.location_off), stringResource(R.string.location_off_detail)) {
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onOpenLocationSettings) { Text(stringResource(R.string.location_settings)) }
                }
            }
            !hasLocation -> item(key = "finding") {
                Message(stringResource(R.string.nearby_finding)) {
                    Spacer(Modifier.height(16.dp))
                    CircularWavyProgressIndicator()
                }
            }
            nearby.isEmpty() -> item(key = "empty") { Message(stringResource(R.string.nearby_empty)) }
            else -> items(nearby, key = { it.first.id }) { (site, metres) ->
                ListItem(
                    headlineContent = { Text(site.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            site.networks.sortedBy { it.ordinal }.map { networkName(it) }.joinToString(" · "),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy((-3).dp)) {
                            site.networks.sortedBy { it.ordinal }.forEach { NetworkDot(it, size = 12) }
                        }
                    },
                    trailingContent = {
                        Text(distanceLabel(metres), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSiteClick(site) },
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, detail: String? = null, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        extra()
    }
}

/** "350 m", "4.2 km", "27 km". */
@Composable
fun distanceLabel(metres: Double): String = when {
    metres < 995 -> stringResource(R.string.distance_m, ((metres / 10).roundToInt() * 10).coerceAtLeast(10))
    metres < 9_950 -> stringResource(R.string.distance_km_1dp, metres / 1_000)
    else -> stringResource(R.string.distance_km, (metres / 1_000).roundToInt())
}
