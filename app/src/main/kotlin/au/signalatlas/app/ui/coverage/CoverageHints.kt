@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package au.signalatlas.app.ui.coverage

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R

/** Shown the first time coverage is turned on: what the colours are and how to read a spot. Dismissible. */
@Composable
fun CoverageExplainerCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 3.dp,
        modifier = modifier.widthIn(max = 360.dp).testTag("coverage-explainer"),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.coverage_explainer_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.coverage_explainer_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(LegendExcellent, LegendGood, LegendFair, LegendWeak).forEach { c ->
                    Surface(color = c, shape = CircleShape, modifier = Modifier.size(12.dp)) { }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.coverage_explainer_dismiss)) }
            }
        }
    }
}

/** A small, non-blocking tonal pill while coverage tiles are being calculated. */
@Composable
fun CalculatingPill(modifier: Modifier = Modifier, done: Int = 0, total: Int = 0) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
        shadowElevation = 2.dp,
        modifier = modifier.testTag("coverage-calculating"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (total > 0) stringResource(R.string.coverage_rendering_count, done, total) else stringResource(R.string.coverage_calculating),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(10.dp))
            if (total > 0) {
                val progress by animateFloatAsState(done.toFloat() / total, label = "render-progress")
                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.width(72.dp))
            } else {
                LinearWavyProgressIndicator(Modifier.width(56.dp))
            }
        }
    }
}
