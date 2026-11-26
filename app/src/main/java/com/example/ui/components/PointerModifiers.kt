package com.example.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Ensures consistent physical mouse wheel scrolling across Compose scrollable containers,
 * including nested lists/dialogs and horizontal chip rows (Shift+wheel or horizontal wheel axis).
 *
 * Runs on [PointerEventPass.Main] after child scrollables so inner lists scroll cleanly first,
 * dispatching only remaining unconsumed wheel delta so scrolling never double-applies.
 */
fun Modifier.mouseWheelScroll(
    scrollState: ScrollState,
    stepDp: Float = 64f,
): Modifier = pointerInput(scrollState, stepDp) {
    val stepPx = stepDp.dp.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Scroll) {
                var totalY = 0f
                var anyUnconsumed = false
                for (change in event.changes) {
                    if (!change.isConsumed) {
                        totalY += change.scrollDelta.y
                        anyUnconsumed = true
                    }
                }
                if (anyUnconsumed && abs(totalY) > 0.001f) {
                    val consumed = scrollState.dispatchRawDelta(totalY * stepPx)
                    if (abs(consumed) > 0.5f) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }
}

fun Modifier.mouseWheelScroll(
    lazyListState: LazyListState,
    stepDp: Float = 64f,
): Modifier = pointerInput(lazyListState, stepDp) {
    val stepPx = stepDp.dp.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Scroll) {
                var totalY = 0f
                var anyUnconsumed = false
                for (change in event.changes) {
                    if (!change.isConsumed) {
                        totalY += change.scrollDelta.y
                        anyUnconsumed = true
                    }
                }
                if (anyUnconsumed && abs(totalY) > 0.001f) {
                    val consumed = lazyListState.dispatchRawDelta(totalY * stepPx)
                    if (abs(consumed) > 0.5f) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }
}

fun Modifier.mouseWheelHorizontalScroll(
    scrollState: ScrollState,
    stepDp: Float = 64f,
): Modifier = pointerInput(scrollState, stepDp) {
    val stepPx = stepDp.dp.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Scroll) {
                var totalX = 0f
                var totalY = 0f
                var anyUnconsumed = false
                for (change in event.changes) {
                    if (!change.isConsumed) {
                        totalX += change.scrollDelta.x
                        totalY += change.scrollDelta.y
                        anyUnconsumed = true
                    }
                }
                val effectiveDelta = if (abs(totalX) > 0.001f) totalX else totalY
                if (anyUnconsumed && abs(effectiveDelta) > 0.001f) {
                    val consumed = scrollState.dispatchRawDelta(effectiveDelta * stepPx)
                    if (abs(consumed) > 0.5f) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }
}
