package au.signalatlas.app.ui.map

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.map.TowerLayer
import au.signalatlas.core.model.SiteKind

/** The ring colour the map uses for a DAS site of [kind]. */
fun dasColor(kind: SiteKind): Color =
    Color(if (kind == SiteKind.DAS_TUNNEL) TowerLayer.DAS_TUNNEL_ARGB else TowerLayer.DAS_BUILDING_ARGB)

/** "DAS · in-building" / "DAS · tunnel" (site sheet); [label] overrides the text (the phone card shows just "DAS"). */
@Composable
fun DasBadge(kind: SiteKind, modifier: Modifier = Modifier, label: String? = null) {
    val c = dasColor(kind)
    Surface(color = c.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            label ?: stringResource(if (kind == SiteKind.DAS_TUNNEL) R.string.das_tunnel else R.string.das_in_building),
            style = MaterialTheme.typography.labelMedium,
            color = c,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** What the map screen needs of DAS detection: the current announcement (null = none) and its dismissal. */
class DasUi(val banner: StateFlow<String?>, val onDismiss: () -> Unit) {
    companion object {
        val None = DasUi(MutableStateFlow(null), {})
    }
}

/** "DAS detected: {site name} ({networks})" along the top; dismisses itself after [AUTO_DISMISS_MS]. */
@Composable
fun DasBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(text) {
        delay(AUTO_DISMISS_MS)
        onDismiss()
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().testTag("das-banner"),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("das-banner-dismiss")) { Text(stringResource(R.string.das_dismiss)) }
        }
    }
}

private const val AUTO_DISMISS_MS = 15_000L
