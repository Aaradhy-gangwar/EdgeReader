package com.edgereader.pdf.reader

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/**
 * Single source of truth for the reading surface: one continuous document, one transform.
 *
 * screen = doc * scale + (tx, ty)
 *
 * Because pinch, pan, fling and page scrolling all mutate this one transform there is no
 * gesture conflict between zooming and moving between pages. Clamping keeps every edge and
 * corner reachable and the document can never drift off-screen.
 */
@Stable
class ReaderState(
    val pageCount: Int,
    firstPageWidth: Float,
    firstPageHeight: Float,
) {
    var layout by mutableStateOf<PageLayout?>(null)
        private set
    var scale by mutableFloatStateOf(1f)
        private set
    var tx by mutableFloatStateOf(0f)
        private set
    var ty by mutableFloatStateOf(0f)
        private set

    /** Assigned by the UI; must carry a MonotonicFrameClock (rememberCoroutineScope does). */
    var animationScope: CoroutineScope? = null

    // Unknown pages assume the first page's size until the background pass reads the real one.
    private val pageWidths = FloatArray(pageCount) { firstPageWidth }
    private val pageHeights = FloatArray(pageCount) { firstPageHeight }
    private var gap = 0f
    private var animJob: Job? = null

    // ---- layout ----------------------------------------------------------------------

    fun onViewportChanged(width: Float, height: Float, gapPx: Float) {
        if (width <= 0f || height <= 0f) return
        val old = layout
        if (old != null && old.viewportWidth == width && old.viewportHeight == height && gap == gapPx) return
        gap = gapPx
        rebuild(width, height)
    }

    /** Called by the background pass that reads real page sizes, in chunks. */
    fun applyPageSizes(from: Int, widths: FloatArray, heights: FloatArray) {
        var changed = false
        for (k in widths.indices) {
            if (pageWidths[from + k] != widths[k] || pageHeights[from + k] != heights[k]) {
                pageWidths[from + k] = widths[k]
                pageHeights[from + k] = heights[k]
                changed = true
            }
        }
        val l = layout ?: return
        if (changed) rebuild(l.viewportWidth, l.viewportHeight)
    }

    private class Anchor(val page: Int, val fraction: Float)

    /** Rebuilds the layout while keeping the same spot of the same page at the top of the screen. */
    private fun rebuild(width: Float, height: Float) {
        val old = layout
        val anchor = old?.let {
            val y = -ty / scale
            val p = it.pageAtY(y)
            Anchor(p, (y - it.top[p]) / it.height[p])
        }
        val oldWidth = old?.viewportWidth ?: width
        val fresh = PageLayout(width, height, gap, pageWidths, pageHeights)
        layout = fresh
        if (anchor != null) {
            ty = -(fresh.top[anchor.page] + anchor.fraction * fresh.height[anchor.page]) * scale
            tx *= width / oldWidth
        }
        val l = fresh
        tx = clampX(tx, scale, l)
        ty = clampY(ty, scale, l)
    }

    private fun clampX(x: Float, s: Float, l: PageLayout): Float {
        val content = l.viewportWidth * s
        return if (content <= l.viewportWidth) (l.viewportWidth - content) / 2f
        else x.coerceIn(l.viewportWidth - content, 0f)
    }

    private fun clampY(y: Float, s: Float, l: PageLayout): Float {
        val content = l.contentHeight * s
        return if (content <= l.viewportHeight) (l.viewportHeight - content) / 2f
        else y.coerceIn(l.viewportHeight - content, 0f)
    }

    // ---- gestures --------------------------------------------------------------------

    /** Pinch + pan in one step. Zooms about the gesture centroid so the content under the fingers stays put. */
    fun transformBy(centroid: Offset, pan: Offset, zoom: Float) {
        val l = layout ?: return
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val f = newScale / scale
        val prev = centroid - pan
        val nx = clampX(prev.x - (prev.x - tx) * f + pan.x, newScale, l)
        val ny = clampY(prev.y - (prev.y - ty) * f + pan.y, newScale, l)
        scale = newScale
        tx = nx
        ty = ny
    }

    /** Pans and returns how much actually moved (less than asked when an edge was hit). */
    fun panBy(dx: Float, dy: Float): Offset {
        val l = layout ?: return Offset.Zero
        val nx = clampX(tx + dx, scale, l)
        val ny = clampY(ty + dy, scale, l)
        val consumed = Offset(nx - tx, ny - ty)
        tx = nx
        ty = ny
        return consumed
    }

    // ---- animation -------------------------------------------------------------------

    /** Stops any fling/zoom animation. Returns true if one was running. */
    fun stopAnimation(): Boolean {
        val active = animJob?.isActive == true
        animJob?.cancel()
        animJob = null
        return active
    }

    fun fling(velocity: Offset) {
        stopAnimation()
        animJob = animationScope?.launch {
            coroutineScope {
                launch { decay(velocity.x) { panBy(it, 0f).x } }
                launch { decay(velocity.y) { panBy(0f, it).y } }
            }
        }
    }

    private suspend fun decay(velocity: Float, step: (Float) -> Float) {
        if (abs(velocity) < MIN_FLING_VELOCITY) return
        var last = 0f
        AnimationState(initialValue = 0f, initialVelocity = velocity)
            .animateDecay(exponentialDecay<Float>(frictionMultiplier = 1.4f, absVelocityThreshold = 40f)) {
                val delta = value - last
                last = value
                if (abs(step(delta) - delta) > 0.5f) cancelAnimation() // hit an edge
            }
    }

    fun animateScaleTo(target: Float, focus: Offset) {
        stopAnimation()
        animJob = animationScope?.launch {
            val start = scale
            val goal = target.coerceIn(MIN_SCALE, MAX_SCALE)
            animate(0f, 1f, animationSpec = tween(240, easing = FastOutSlowInEasing)) { t, _ ->
                val s = start * (goal / start).pow(t) // exponential interpolation feels linear to the eye
                transformBy(focus, Offset.Zero, s / scale)
            }
        }
    }

    /**
     * Double-tap: back to fit when zoomed; otherwise zoom so the tapped page fills the screen width
     * (the natural reading zoom in landscape), or a fixed step when the page already spans the width.
     */
    fun toggleZoom(focus: Offset) {
        val l = layout ?: return
        if (scale > FIT_EPSILON) {
            animateScaleTo(1f, focus)
            return
        }
        val page = l.pageAtY((focus.y - ty) / scale)
        val widthFit = l.viewportWidth / l.width[page]
        animateScaleTo(if (widthFit > 1.2f) widthFit else DOUBLE_TAP_SCALE, focus)
    }

    fun resetToFit() {
        val l = layout ?: return
        animateScaleTo(1f, Offset(l.viewportWidth / 2f, l.viewportHeight / 2f))
    }

    // ---- queries ---------------------------------------------------------------------

    fun currentPage(): Int {
        val l = layout ?: return 0
        return l.pageAtY((l.viewportHeight / 2f - ty) / scale)
    }

    fun visibleRange(): IntRange {
        val l = layout ?: return IntRange.EMPTY
        return l.pageAtY(-ty / scale)..l.pageAtY((l.viewportHeight - ty) / scale)
    }

    fun scrollFraction(): Float {
        val l = layout ?: return 0f
        val range = l.contentHeight * scale - l.viewportHeight
        return if (range <= 0f) 0f else (-ty / range).coerceIn(0f, 1f)
    }

    fun scrollToFraction(fraction: Float) {
        val l = layout ?: return
        val range = (l.contentHeight * scale - l.viewportHeight).coerceAtLeast(0f)
        ty = clampY(-fraction * range, scale, l)
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 10f
        const val FIT_EPSILON = 1.05f
        const val DOUBLE_TAP_SCALE = 2.5f
        private const val MIN_FLING_VELOCITY = 50f
    }
}
