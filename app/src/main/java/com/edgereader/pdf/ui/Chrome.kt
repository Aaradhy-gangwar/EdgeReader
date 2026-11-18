package com.edgereader.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgereader.pdf.reader.ReaderState
import kotlin.math.roundToInt

/** Small floating page indicator; grows "Fit" and "Open" actions only after a tap. */
@Composable
fun PagePill(
    page: Int,
    count: Int,
    showActions: Boolean,
    onFit: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Bottom))
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC1B1B1F))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$page / $count", color = Color.White, fontSize = 13.sp)
        if (showActions) {
            PillAction("Fit", onFit)
            PillAction("Open", onOpen)
        }
    }
}

@Composable
private fun PillAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 44.dp, minHeight = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFF9CC9FF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Fast-scroll handle on the right edge, kept clear of the system back-gesture zone. */
@Composable
fun Scrubber(
    reader: ReaderState,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbHeight = 56.dp
    val thumbPx = with(LocalDensity.current) { thumbHeight.roundToPx() }
    var trackPx by remember { mutableIntStateOf(0) }

    Box(
        modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.End))
            .width(40.dp)
            .onSizeChanged { trackPx = it.height }
            .pointerInput(reader) {
                detectVerticalDragGestures(
                    onDragStart = {
                        reader.stopAnimation()
                        onScrubbing(true)
                    },
                    onDragEnd = { onScrubbing(false) },
                    onDragCancel = { onScrubbing(false) },
                ) { change, _ ->
                    change.consume()
                    val range = (size.height - thumbPx).coerceAtLeast(1)
                    reader.scrollToFraction(((change.position.y - thumbPx / 2f) / range).coerceIn(0f, 1f))
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val travel = (trackPx - thumbPx).coerceAtLeast(0)
                    IntOffset(0, (reader.scrollFraction() * travel).roundToInt())
                }
                .padding(end = 4.dp)
                .size(width = 8.dp, height = thumbHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.8f)),
        )
    }
}
