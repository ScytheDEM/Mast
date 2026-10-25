package au.signalatlas.app.ui.offline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import au.signalatlas.core.map.MapCameraState
import au.signalatlas.core.model.GeoBounds
import kotlin.math.max
import kotlin.math.min

/** Hooks MapScreen to the draw-a-box mode; [onResult] gets the box, or null if the user cancelled. */
class DrawBoxHooks(val active: Boolean, val onResult: (GeoBounds?) -> Unit)

/**
 * Over the map: drag to draw a rectangle, "Move map" lets the map be panned or zoomed instead (the rectangle is
 * cleared, since it was drawn for the old view), Next hands the box on.
 */
@Composable
fun DrawBoxOverlay(camera: MapCameraState, onResult: (GeoBounds?) -> Unit, modifier: Modifier = Modifier) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var moving by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val drawn = start != null && end != null && (end!! - start!!).let { it.x * it.x + it.y * it.y > MIN_DRAG_SQ }

    Box(modifier.fillMaxSize().testTag("draw-box")) {
        if (!moving) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start = it; end = it },
                        onDrag = { change, _ -> end = change.position },
                    )
                },
            ) {
                val a = start
                val b = end
                if (a != null && b != null) {
                    val topLeft = Offset(min(a.x, b.x), min(a.y, b.y))
                    val size = Size(max(a.x, b.x) - topLeft.x, max(a.y, b.y) - topLeft.y)
                    drawRect(color.copy(alpha = 0.15f), topLeft, size)
                    drawRect(color, topLeft, size, style = Stroke(width = 3.dp.toPx()))
                }
            }
        }
        Surface(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
        ) {
            Text(stringResource(R.string.offline_draw_hint), Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onResult(null) }) { Text(stringResource(R.string.offline_cancel)) }
            FilterChip(
                selected = moving, onClick = { moving = !moving; start = null; end = null },
                label = { Text(stringResource(R.string.offline_draw_move)) },
            )
            Button(
                onClick = {
                    val a = camera.latLngAt(start!!.x, start!!.y)
                    val b = camera.latLngAt(end!!.x, end!!.y)
                    if (a != null && b != null) {
                        onResult(GeoBounds(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second)))
                    }
                },
                enabled = drawn && !moving,
                modifier = Modifier.testTag("draw-next"),
            ) { Text(stringResource(R.string.offline_draw_next)) }
        }
    }
}

private const val MIN_DRAG_SQ = 24f * 24f
