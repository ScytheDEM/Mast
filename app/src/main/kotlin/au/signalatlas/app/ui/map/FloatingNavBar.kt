package au.signalatlas.app.ui.map

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R

/** Height of [FloatingNavBar] and its gap above the system navigation bar: what map controls must stay clear of. */
val FLOATING_NAV_HEIGHT = 64.dp
val FLOATING_NAV_MARGIN = 12.dp

/**
 * The floating navigation bar: a detached, fully rounded tonal pill inset from the screen edges with Home, Coverage and
 * Nearby. The selected destination sits in an expressive pill indicator with its label beside the icon; the others are
 * icon over label. [tripRunning] badges Coverage (where the trip is started and stopped).
 */
@Composable
fun FloatingNavBar(
    section: MapSection,
    onSelect: (MapSection) -> Unit,
    modifier: Modifier = Modifier,
    tripRunning: Boolean = false,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.height(FLOATING_NAV_HEIGHT).testTag("nav-bar"),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                selected = section == MapSection.HOME,
                icon = rememberVectorPainter(if (section == MapSection.HOME) Icons.Filled.Home else Icons.Outlined.Home),
                label = stringResource(R.string.nav_home),
                tag = "nav-home",
                onClick = { onSelect(MapSection.HOME) },
            )
            NavItem(
                selected = section == MapSection.COVERAGE,
                icon = painterResource(R.drawable.ic_coverage),
                label = stringResource(R.string.nav_coverage),
                tag = "nav-coverage",
                badge = tripRunning,
                onClick = { onSelect(MapSection.COVERAGE) },
            )
            NavItem(
                selected = section == MapSection.NEARBY,
                icon = painterResource(R.drawable.ic_near_me),
                label = stringResource(R.string.nav_nearby),
                tag = "nav-nearby",
                onClick = { onSelect(MapSection.NEARBY) },
            )
        }
    }
}

@Composable
private fun NavItem(selected: Boolean, icon: Painter, label: String, tag: String, onClick: () -> Unit, badge: Boolean = false) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "indicator",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "content",
    )
    val width by animateDpAsState(
        if (selected) 124.dp else 76.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "width",
    )
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .width(width)
            .height(48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab, indication = ripple(), interactionSource = null)
            .testTag(tag),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val glyph: @Composable () -> Unit = {
                BadgedBox(badge = { if (badge) Badge() }) { Icon(icon, null, Modifier.size(22.dp)) }
            }
            if (selected) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    glyph()
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    glyph()
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
