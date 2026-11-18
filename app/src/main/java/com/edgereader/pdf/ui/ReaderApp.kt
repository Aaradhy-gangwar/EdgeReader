package com.edgereader.pdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgereader.pdf.ReaderViewModel
import com.edgereader.pdf.ReaderViewModel.UiState
import com.edgereader.pdf.pdf.PageBitmaps
import com.edgereader.pdf.pdf.PdfDocument
import com.edgereader.pdf.reader.ReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ReaderApp(viewModel: ReaderViewModel, onOpenRequest: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.displayCutout), // never draw content under a notch
    ) {
        when (val s = state) {
            UiState.Empty -> Message("Open a PDF to start reading", "Open PDF", onOpenRequest)
            UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            is UiState.Error -> Message(s.message, "Open another file", onOpenRequest)
            is UiState.Ready -> ReaderScreen(s.document, s.reader, onOpenRequest)
        }
    }
}

@Composable
private fun BoxScope.Message(text: String, action: String, onClick: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        Button(onClick = onClick) { Text(action) }
    }
}

@Composable
fun ReaderScreen(document: PdfDocument, reader: ReaderState, onOpenRequest: () -> Unit) {
    val bitmaps = remember(document) { PageBitmaps(document) }
    var chrome by remember { mutableStateOf(false) }   // shown by a tap
    var scrubbing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }   // briefly true after any scroll/zoom

    val zoomed by remember(reader) { derivedStateOf { reader.scale > ReaderState.FIT_EPSILON } }
    val page by remember(reader) { derivedStateOf { reader.currentPage() } }

    LaunchedEffect(reader) {
        snapshotFlow { Triple(reader.scale, reader.tx, reader.ty) }.collectLatest {
            moving = true
            delay(1200)
            moving = false
        }
    }
    LaunchedEffect(chrome, scrubbing) {
        if (chrome && !scrubbing) {
            delay(4000)
            chrome = false
        }
    }

    // Back: hide controls first, then return to fit-to-screen, then leave.
    BackHandler(enabled = chrome || zoomed) {
        if (chrome) chrome = false else reader.resetToFit()
    }

    Box(Modifier.fillMaxSize()) {
        ReaderSurface(reader, bitmaps, onTap = { chrome = !chrome })

        val hudVisible = chrome || moving || scrubbing
        AnimatedVisibility(
            visible = hudVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
        ) {
            PagePill(
                page = page + 1,
                count = reader.pageCount,
                showActions = chrome,
                onFit = { reader.resetToFit() },
                onOpen = onOpenRequest,
            )
        }
        if (reader.pageCount > 1) {
            AnimatedVisibility(
                visible = hudVisible,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(300)),
            ) {
                Scrubber(reader, onScrubbing = { scrubbing = it })
            }
        }
    }
}
