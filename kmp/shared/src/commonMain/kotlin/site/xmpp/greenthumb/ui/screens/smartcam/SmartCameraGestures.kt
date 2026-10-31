package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import site.xmpp.greenthumb.ui.theme.Sizes

/**
 * All viewfinder gestures in one detector, so they cannot fight: two fingers zoom (and never count as a mode swipe,
 * even when they move apart sideways), one finger dragged sideways switches the mode, and two quick taps inside
 * [previewBounds] reset the zoom. Taps on buttons still reach the buttons: nothing here consumes a single-finger event.
 */
@Composable
internal fun Modifier.viewfinderGestures(
    previewBounds: () -> Rect?,
    onSwipe: (dragX: Float) -> Unit,
    onZoom: (factor: Float) -> Unit,
    onDoubleTap: () -> Unit,
): Modifier {
    val swipe by rememberUpdatedState(onSwipe)
    val zoom by rememberUpdatedState(onZoom)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    return pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
        val doubleTapDistance = Sizes.touch.toPx()
        var lastTap: Offset? = null
        var lastTapUp = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var multiTouch = false
            var moved = false
            var dragX = 0f
            var upTime = down.uptimeMillis
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) multiTouch = true
                if (multiTouch) {
                    if (pressed >= 2) {
                        val factor = event.calculateZoom()
                        if (factor != 1f) zoom(factor)
                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                    }
                } else {
                    event.changes.firstOrNull { it.id == down.id }?.let { change ->
                        dragX += change.positionChange().x
                        if ((change.position - down.position).getDistance() > slop) moved = true
                    }
                }
                if (event.changes.none { it.pressed }) {
                    upTime = event.changes.maxOf { it.uptimeMillis }
                    break
                }
            }
            when {
                multiTouch -> lastTap = null
                moved -> {
                    lastTap = null
                    swipe(dragX)
                }
                previewBounds()?.contains(down.position) != true -> lastTap = null
                else -> {
                    val previous = lastTap
                    if (previous != null && down.uptimeMillis - lastTapUp <= doubleTapTimeout &&
                        (down.position - previous).getDistance() <= doubleTapDistance
                    ) {
                        lastTap = null
                        doubleTap()
                    } else {
                        lastTap = down.position
                        lastTapUp = upTime
                    }
                }
            }
        }
    }
}
