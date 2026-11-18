package com.edgereader.pdf.reader

import kotlin.math.max
import kotlin.math.min

/**
 * Vertical stack of pages in "document space" (pixels at zoom 1).
 * Every page is scaled to fit entirely inside the viewport (aspect ratio preserved) and
 * centred horizontally. The first and last pages get extra padding so they can be
 * centred vertically; a short document is therefore centred as a whole.
 */
class PageLayout(
    val viewportWidth: Float,
    val viewportHeight: Float,
    gap: Float,
    pageWidths: FloatArray,
    pageHeights: FloatArray,
) {
    val count = pageWidths.size
    val left = FloatArray(count)
    val top = FloatArray(count)
    val width = FloatArray(count)
    val height = FloatArray(count)

    /** Pixels per PDF point at zoom 1. */
    val pxPerPt = FloatArray(count)
    val contentHeight: Float

    init {
        var y = 0f
        for (i in 0 until count) {
            val fit = min(viewportWidth / pageWidths[i], viewportHeight / pageHeights[i])
            pxPerPt[i] = fit
            width[i] = pageWidths[i] * fit
            height[i] = pageHeights[i] * fit
            left[i] = (viewportWidth - width[i]) / 2f
            if (i == 0) y = max(gap, (viewportHeight - height[0]) / 2f)
            top[i] = y
            y += height[i] + gap
        }
        val bottomPad = max(gap, (viewportHeight - height[count - 1]) / 2f)
        contentHeight = y - gap + bottomPad
    }

    /** Index of the last page whose top is at or above [y] (document space). */
    fun pageAtY(y: Float): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (top[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }
}
