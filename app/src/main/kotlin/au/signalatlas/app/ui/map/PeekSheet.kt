package au.signalatlas.app.ui.map

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import au.signalatlas.app.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where a [PeekSheet] rests. */
enum class PeekValue { Peek, Expanded }

/**
 * A non-modal bottom sheet for the selected site: the map stays live behind it. Swiping down collapses it to a
 * [peekHeight] strip (the site's header), never away: only the header's close button (or tapping the tower again)
 * deselects. [state] is hoisted so the caller can expand it for a new site and know when it is expanded.
 *
 * The content scrolls inside [maxHeight]; dragging it down from the top of its list, or dragging the handle, moves the
 * sheet. [bottomInset] keeps content clear of the floating navigation bar drawn over the sheet's foot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeekSheet(
    state: AnchoredDraggableState<PeekValue>,
    peekHeight: Dp,
    maxHeight: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val full = maxHeight + bottomInset
    val travelPx = with(density) { (maxHeight - peekHeight).coerceAtLeast(0.dp).toPx() }
    SideEffect {
        state.updateAnchors(
            DraggableAnchors {
                PeekValue.Expanded at 0f
                PeekValue.Peek at travelPx
            },
        )
    }
    val nested = remember(state) { sheetNestedScroll(state) { v -> scope.launch { state.settleBy(v) } } }
    val expandLabel = stringResource(R.string.sheet_expand)
    val collapseLabel = stringResource(R.string.sheet_collapse)
    Box(modifier.fillMaxWidth().height(full), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 8.dp,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(full)
                .offset { IntOffset(0, state.offset.takeIf { !it.isNaN() }?.roundToInt() ?: travelPx.roundToInt()) }
                .anchoredDraggable(state, Orientation.Vertical)
                .nestedScroll(nested)
                .semantics {
                    if (state.currentValue == PeekValue.Peek) {
                        expand(expandLabel) { scope.launch { state.animateTo(PeekValue.Expanded, tween()) }; true }
                    } else {
                        collapse(collapseLabel) { scope.launch { state.animateTo(PeekValue.Peek, tween()) }; true }
                    }
                }
                .testTag("site-sheet"),
        ) {
            Column {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                Box(Modifier.height(maxHeight - DRAG_HANDLE_HEIGHT)) { content() }
                Box(Modifier.size(1.dp, bottomInset))
            }
        }
    }
}

/** The drag handle's height (22 dp of padding around a 4 dp bar). */
val DRAG_HANDLE_HEIGHT = 48.dp

/** Settles to the nearer anchor in the fling's direction. */
private suspend fun AnchoredDraggableState<PeekValue>.settleBy(velocity: Float) {
    val target = when {
        velocity < -FLING_PX -> PeekValue.Expanded
        velocity > FLING_PX -> PeekValue.Peek
        else -> {
            val peek = anchors.positionOf(PeekValue.Peek)
            if (offset > peek / 2) PeekValue.Peek else PeekValue.Expanded
        }
    }
    animateTo(target, tween())
}

private const val FLING_PX = 600f

/**
 * Upward scrolls first expand the sheet; downward scrolls the list cannot take (it is at its top) collapse it; a fling
 * settles it.
 */
private fun sheetNestedScroll(state: AnchoredDraggableState<PeekValue>, settle: (Float) -> Unit) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        return if (dy < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(dy)) else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val moving = state.offset.let { !it.isNaN() && it > 0f && it < state.anchors.positionOf(PeekValue.Peek) }
        return if (moving || (available.y < 0 && state.currentValue == PeekValue.Peek)) {
            settle(available.y)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        settle(available.y)
        return available
    }
}

/** A sheet state that starts collapsed to its peek. */
@Composable
fun rememberPeekSheetState(initial: PeekValue = PeekValue.Peek): AnchoredDraggableState<PeekValue> =
    remember { AnchoredDraggableState(initial) }

/**
 * Collapses the sheet to its peek whenever [key] (the selected site) changes to a new non-null value, so a newly
 * selected site's lobes are in view at once; the user drags the sheet up for the details.
 */
@Composable
fun PeekOnNewKey(state: AnchoredDraggableState<PeekValue>, key: Any?) {
    LaunchedEffect(key) { if (key != null && state.currentValue != PeekValue.Peek) state.animateTo(PeekValue.Peek, tween()) }
}
