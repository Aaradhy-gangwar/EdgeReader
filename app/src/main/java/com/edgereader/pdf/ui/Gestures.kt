package com.edgereader.pdf.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One detector for everything: pan, pinch, fling, tap and double-tap.
 * Pan and pinch share a single code path (no competing scroll/zoom detectors), and a second
 * finger joining mid-drag simply turns the gesture into a pinch.
 *
 * [onGestureStart] returns true if it interrupted a running fling; that touch is then treated
 * as "stop", never as a tap.
 */
suspend fun PointerInputScope.detectReaderGestures(
    onGestureStart: () -> Boolean,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onFling: (Offset) -> Unit,
    onTap: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
) = coroutineScope {
    var lastTapUptime = 0L
    var lastTapPosition: Offset? = null
    var pendingTap: Job? = null

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val interruptedFling = onGestureStart()
        pendingTap?.cancel()
        pendingTap = null

        val config = viewConfiguration
        val slop = config.touchSlop
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)

        var zoomAcc = 1f
        var panAcc = Offset.Zero
        var pastSlop = false
        var multiTouch = false
        var cancelled = false
        var lastUptime = down.uptimeMillis

        do {
            val event = awaitPointerEvent()
            lastUptime = event.changes[0].uptimeMillis
            if (event.changes.fastAny { it.isConsumed }) {
                cancelled = true
            } else {
                if (event.changes.count { it.pressed } > 1) multiTouch = true
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastSlop) {
                    zoomAcc *= zoomChange
                    panAcc += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    if (abs(1f - zoomAcc) * centroidSize > slop || panAcc.getDistance() > slop) pastSlop = true
                } else {
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onTransform(event.calculateCentroid(useCurrent = true), panChange, zoomChange)
                    }
                    event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                }
                if (!multiTouch) tracker.addPointerInputChange(event.changes[0])
            }
        } while (!cancelled && event.changes.fastAny { it.pressed })

        if (cancelled) return@awaitEachGesture

        if (pastSlop) {
            if (!multiTouch) {
                val v = tracker.calculateVelocity()
                onFling(Offset(v.x, v.y))
            }
        } else if (!multiTouch && !interruptedFling &&
            lastUptime - down.uptimeMillis < config.longPressTimeoutMillis
        ) {
            val previous = lastTapPosition
            if (previous != null &&
                lastUptime - lastTapUptime <= config.doubleTapTimeoutMillis &&
                (down.position - previous).getDistance() < slop * 6
            ) {
                lastTapPosition = null
                onDoubleTap(down.position)
            } else {
                lastTapPosition = down.position
                lastTapUptime = lastUptime
                // A single tap is only confirmed once the double-tap window has passed.
                pendingTap = launch {
                    delay(config.doubleTapTimeoutMillis)
                    onTap()
                }
            }
        }
    }
}
