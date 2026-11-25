package app.tape.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for a LazyColumn. While dragging, [onMove] reorders a local copy of the
 * list so rows slide into place; [onDrop] then commits the single move (from → to) once.
 * [headerCount] = rows above the reorderable ones (titles, buttons).
 */
class ReorderState(
    private val listState: LazyListState,
    private val headerCount: Int,
    private val itemCount: () -> Int,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: (from: Int, to: Int) -> Unit,
) {
    var dragging by mutableStateOf<Int?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var startIndex = -1

    fun start(index: Int) {
        dragging = index
        startIndex = index
        offset = 0f
    }

    fun drag(dy: Float) {
        val current = dragging ?: return
        offset += dy
        val visible = listState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == current + headerCount } ?: return
        val center = item.offset + item.size / 2f + offset
        val target = visible.firstOrNull {
            val i = it.index - headerCount
            i in 0 until itemCount() && it.index != item.index && center >= it.offset && center <= it.offset + it.size
        } ?: return
        val to = target.index - headerCount
        onMove(current, to)
        // The row now sits where the target was; keep it under the finger.
        offset += (item.offset - target.offset).toFloat()
        dragging = to
    }

    fun end() {
        val to = dragging
        if (to != null && startIndex >= 0 && to != startIndex) onDrop(startIndex, to)
        dragging = null
        startIndex = -1
        offset = 0f
    }

    /** Apply to the row: lifts it above the others and follows the finger. */
    fun Modifier.draggedRow(index: Int): Modifier =
        if (dragging == index) zIndex(1f).graphicsLayer { translationY = offset; shadowElevation = 12f } else this
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    headerCount: Int,
    itemCount: () -> Int,
    onMove: (Int, Int) -> Unit,
    onDrop: (Int, Int) -> Unit,
): ReorderState {
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val count by rememberUpdatedState(itemCount)
    return remember(listState, headerCount) {
        ReorderState(listState, headerCount, { count() }, { a, b -> move(a, b) }, { a, b -> drop(a, b) })
    }
}

/** The ≡ handle at the end of a row; dragging it reorders. */
@Composable
fun DragHandle(state: ReorderState, index: Int) {
    val current by rememberUpdatedState(index)
    Icon(
        Icons.Rounded.DragHandle,
        contentDescription = "Drag to reorder",
        tint = Muted.copy(alpha = 0.7f),
        modifier = Modifier
            .padding(start = 8.dp)
            .size(24.dp)
            .pointerInput(state) {
                detectDragGestures(
                    onDragStart = { state.start(current) },
                    onDragEnd = { state.end() },
                    onDragCancel = { state.end() },
                ) { change, amount ->
                    change.consume()
                    state.drag(amount.y)
                }
            },
    )
}
