package com.edgereader.pdf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edgereader.pdf.pdf.EmptyDocumentException
import com.edgereader.pdf.pdf.PdfDocument
import com.edgereader.pdf.reader.ReaderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.IOException

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface UiState {
        data object Empty : UiState
        data object Loading : UiState
        data class Error(val message: String) : UiState
        class Ready(val document: PdfDocument, val reader: ReaderState) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Empty)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var current: PdfDocument? = null
    private var job: Job? = null

    fun open(uri: Uri) {
        job?.cancel()
        closeCurrent()
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            try {
                val doc = PdfDocument.open(getApplication(), uri)
                current = doc
                val first = doc.pageSize(0) ?: throw IOException("Unreadable first page")
                val reader = ReaderState(doc.pageCount, first.width, first.height)
                _state.value = UiState.Ready(doc, reader)
                loadPageSizes(doc, reader, first.width, first.height)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeCurrent()
                _state.value = UiState.Error(describe(e))
            }
        }
    }

    /**
     * Reads the real size of every page in the background, in chunks, so mixed-size documents
     * lay out correctly without blocking the first frame. Uniform documents never trigger a relayout.
     */
    private suspend fun loadPageSizes(doc: PdfDocument, reader: ReaderState, fallbackW: Float, fallbackH: Float) {
        var i = 1
        while (i < doc.pageCount && !doc.isClosed) {
            val end = minOf(i + CHUNK, doc.pageCount)
            val w = FloatArray(end - i)
            val h = FloatArray(end - i)
            for (p in i until end) {
                val s = doc.pageSize(p)
                w[p - i] = s?.width ?: fallbackW
                h[p - i] = s?.height ?: fallbackH
            }
            reader.applyPageSizes(i, w, h)
            i = end
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SecurityException -> "This PDF is password-protected, or the app isn't allowed to read it."
        is FileNotFoundException -> "The file couldn't be found or is no longer accessible."
        is EmptyDocumentException -> "This PDF contains no pages."
        is IOException -> "This file looks corrupted or isn't a valid PDF."
        else -> "Couldn't open this file."
    }

    private fun closeCurrent() {
        current?.close()
        current = null
    }

    override fun onCleared() {
        closeCurrent()
    }

    private companion object {
        const val CHUNK = 64
    }
}
