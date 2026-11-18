package com.edgereader.pdf.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.edgereader.pdf.pdf.PageBitmaps
import com.edgereader.pdf.reader.PageLayout
import com.edgereader.pdf.reader.ReaderState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DETAIL_MIN_SCALE = 1.03f

private data class DetailKey(val layout: PageLayout?, val scale: Float, val tx: Float, val ty: Float)

@Composable
fun ReaderSurface(
    reader: ReaderState,
    bitmaps: PageBitmaps,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 10.dp.toPx() }
    val scope = rememberCoroutineScope()
    SideEffect { reader.animationScope = scope }
    val currentOnTap by rememberUpdatedState(onTap)

    RenderEffects(reader, bitmaps)

    val pagePaint = remember { Paint().apply { color = android.graphics.Color.WHITE } }
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val pageRect = remember { RectF() }
    val dstRect = remember { RectF() }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { reader.onViewportChanged(it.width.toFloat(), it.height.toFloat(), gapPx) }
            .pointerInput(reader) {
                detectReaderGestures(
                    onGestureStart = { reader.stopAnimation() },
                    onTransform = reader::transformBy,
                    onFling = reader::fling,
                    onTap = { currentOnTap() },
                    onDoubleTap = reader::toggleZoom,
                )
            },
    ) {
        @Suppress("UNUSED_VARIABLE")
        val redrawOnNewBitmap = bitmaps.tick
        val layout = reader.layout ?: return@Canvas
        val s = reader.scale
        val tx = reader.tx
        val ty = reader.ty
        val first = layout.pageAtY(-ty / s)
        val last = layout.pageAtY((size.height - ty) / s)

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for (i in first..last) {
                pageRect.set(
                    (tx + layout.left[i] * s).roundToInt().toFloat(),
                    (ty + layout.top[i] * s).roundToInt().toFloat(),
                    (tx + (layout.left[i] + layout.width[i]) * s).roundToInt().toFloat(),
                    (ty + (layout.top[i] + layout.height[i]) * s).roundToInt().toFloat(),
                )
                nc.drawRect(pageRect, pagePaint)
                bitmaps.base(i)?.let { nc.drawBitmap(it, null, pageRect, bitmapPaint) }
                bitmaps.detail(i, layout)?.let { d ->
                    val k = s / d.scale
                    val l = tx + d.left * s
                    val t = ty + d.top * s
                    dstRect.set(
                        l.roundToInt().toFloat(),
                        t.roundToInt().toFloat(),
                        (l + d.bitmap.width * k).roundToInt().toFloat(),
                        (t + d.bitmap.height * k).roundToInt().toFloat(),
                    )
                    nc.save()
                    nc.clipRect(pageRect)
                    nc.drawBitmap(d.bitmap, null, dstRect, bitmapPaint)
                    nc.restore()
                }
            }
        }
    }
}

/** Drives rendering: base pages as they approach the viewport, sharp detail once zoomed. */
@OptIn(FlowPreview::class)
@Composable
private fun RenderEffects(reader: ReaderState, bitmaps: PageBitmaps) {
    // Base layer: visible pages plus one neighbour each side, nearest to the centre first.
    LaunchedEffect(reader, bitmaps) {
        snapshotFlow { reader.layout to reader.visibleRange() }
            .distinctUntilChanged()
            .collectLatest { (layout, range) ->
                if (layout == null || range.isEmpty()) return@collectLatest
                val center = (range.first + range.last) / 2
                val order = ((range.first - 1)..(range.last + 1))
                    .filter { it in 0 until layout.count }
                    .sortedBy { abs(it - center) }
                for (i in order) bitmaps.ensureBase(i, layout)
            }
    }

    // Detail layer: re-render the on-screen region while panning/zooming (sampled), and drop it at fit zoom.
    LaunchedEffect(reader, bitmaps) {
        snapshotFlow { DetailKey(reader.layout, reader.scale, reader.tx, reader.ty) }
            .sample(100)
            .collectLatest { key ->
                val layout = key.layout ?: return@collectLatest
                if (key.scale <= DETAIL_MIN_SCALE) {
                    bitmaps.clearDetails()
                    return@collectLatest
                }
                val range = reader.visibleRange()
                bitmaps.pruneDetails(range)
                for (i in range) bitmaps.ensureDetail(i, layout, key.scale, key.tx, key.ty)
            }
    }
}
