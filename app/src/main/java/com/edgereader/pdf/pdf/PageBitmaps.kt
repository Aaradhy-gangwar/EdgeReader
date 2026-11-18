package com.edgereader.pdf.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.edgereader.pdf.reader.PageLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Two render layers per page:
 *  - base:   the whole page at fit-to-screen resolution (LRU cached, also the blurry fallback while zoomed)
 *  - detail: only the part of the page currently on screen (plus a margin), rendered at the current
 *            zoom, so sharpness never costs a full-page bitmap at 10x.
 * Everything here is touched from the main thread; only PdfRenderer work hops to IO.
 */
class PageBitmaps(private val document: PdfDocument) {

    class Detail(
        val layout: PageLayout,
        val bitmap: Bitmap,
        /** Top-left of the rendered region in document space. */
        val left: Float,
        val top: Float,
        /** Zoom at which it was rendered (bitmap pixels per document unit). */
        val scale: Float,
    ) {
        val right get() = left + bitmap.width / scale
        val bottom get() = top + bitmap.height / scale
    }

    /** Bumped whenever a bitmap arrives; read in the draw phase to trigger a redraw. */
    var tick by mutableIntStateOf(0)
        private set

    private val baseCache = object : LruCache<Int, Bitmap>(
        min(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val details = HashMap<Int, Detail>()
    private val failed = HashSet<Int>()

    fun base(index: Int): Bitmap? = baseCache.get(index)

    fun detail(index: Int, layout: PageLayout): Detail? = details[index]?.takeIf { it.layout === layout }

    fun clearDetails() {
        if (details.isNotEmpty()) details.clear()
    }

    fun pruneDetails(keep: IntRange) {
        details.keys.removeAll { it !in keep }
    }

    suspend fun ensureBase(index: Int, layout: PageLayout) {
        val w = ceil(layout.width[index]).toInt().coerceAtLeast(1)
        val h = ceil(layout.height[index]).toInt().coerceAtLeast(1)
        val cached = baseCache.get(index)
        if ((cached != null && cached.width == w && cached.height == h) || index in failed) return
        try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val m = Matrix().apply { setScale(layout.pxPerPt[index], layout.pxPerPt[index]) }
            if (document.render(index, bitmap, m)) {
                baseCache.put(index, bitmap)
                tick++
            } else if (document.isClosed.not()) {
                failed += index
            }
        } catch (e: OutOfMemoryError) {
            baseCache.evictAll()
            details.clear()
        }
    }

    suspend fun ensureDetail(index: Int, layout: PageLayout, scale: Float, tx: Float, ty: Float) {
        val pageL = layout.left[index]
        val pageT = layout.top[index]
        val pageR = pageL + layout.width[index]
        val pageB = pageT + layout.height[index]

        // Part of this page that is on screen, in document space.
        val visL = max(pageL, -tx / scale)
        val visT = max(pageT, -ty / scale)
        val visR = min(pageR, (layout.viewportWidth - tx) / scale)
        val visB = min(pageB, (layout.viewportHeight - ty) / scale)
        if (visR <= visL || visB <= visT) {
            details.remove(index)
            return
        }

        val eps = 1f / scale
        val cur = details[index]
        if (cur != null && cur.layout === layout && abs(ln(scale / cur.scale)) < 0.1f &&
            cur.left <= visL + eps && cur.top <= visT + eps &&
            cur.right >= visR - eps && cur.bottom >= visB - eps
        ) return

        // Render the visible part plus a margin so small pans don't expose blurry areas.
        val mx = layout.viewportWidth * 0.25f / scale
        val my = layout.viewportHeight * 0.25f / scale
        var rL = max(pageL, visL - mx)
        var rT = max(pageT, visT - my)
        var rR = min(pageR, visR + mx)
        var rB = min(pageB, visB + my)
        if ((rR - rL) * scale * (rB - rT) * scale > MAX_DETAIL_PIXELS) {
            rL = visL; rT = visT; rR = visR; rB = visB
        }

        try {
            val bw = ceil((rR - rL) * scale).toInt().coerceAtLeast(1)
            val bh = ceil((rB - rT) * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val ppp = layout.pxPerPt[index] * scale
            val m = Matrix().apply {
                setScale(ppp, ppp)
                postTranslate(-(rL - pageL) * scale, -(rT - pageT) * scale)
            }
            if (document.render(index, bitmap, m)) {
                details[index] = Detail(layout, bitmap, rL, rT, scale)
                tick++
            }
        } catch (e: OutOfMemoryError) {
            baseCache.evictAll()
            details.clear()
        }
    }

    private companion object {
        const val MAX_DETAIL_PIXELS = 6_000_000f
    }
}
