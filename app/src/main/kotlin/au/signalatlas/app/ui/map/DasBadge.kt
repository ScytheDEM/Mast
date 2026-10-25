package au.signalatlas.app.ui.map

import androidx.compose.foundation.layout.padding
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
