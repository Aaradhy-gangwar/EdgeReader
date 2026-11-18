package com.edgereader.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

data class PageSize(val width: Float, val height: Float)

class EmptyDocumentException : IOException("The PDF contains no pages")

/**
 * Thin, thread-safe wrapper over [PdfRenderer].
 *
 * PdfRenderer allows only one open page at a time and is not thread-safe, so every
 * access goes through [lock]. Pages are opened one at a time on demand, so the whole
 * document is never held in memory.
 */
class PdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val fd: ParcelFileDescriptor,
    private val tempFile: File?,
) {
    val pageCount: Int = renderer.pageCount
    private val lock = Mutex()

    @Volatile
    var isClosed = false
        private set

    /** Page size in PDF points, or null if the page can't be read. */
    suspend fun pageSize(index: Int): PageSize? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (isClosed) return@withLock null
            try {
                renderer.openPage(index).use {
                    PageSize(it.width.toFloat().coerceAtLeast(1f), it.height.toFloat().coerceAtLeast(1f))
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Renders [index] into [bitmap]. [matrix] maps PDF points to bitmap pixels, which lets
     * callers render only a zoomed-in region of a page at full sharpness.
     */
    suspend fun render(index: Int, bitmap: Bitmap, matrix: Matrix): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (isClosed) return@withLock false
            try {
                renderer.openPage(index).use {
                    it.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Closes asynchronously, after any in-flight render has finished. */
    fun close() {
        cleanupScope.launch {
            lock.withLock {
                if (!isClosed) {
                    isClosed = true
                    runCatching { renderer.close() }
                    runCatching { fd.close() }
                    tempFile?.delete()
                }
            }
        }
    }

    companion object {
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun open(context: Context, uri: Uri): PdfDocument = withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            var pfd: ParcelFileDescriptor? = null
            var temp: File? = null
            try {
                var opened: ParcelFileDescriptor =
                    resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Cannot open $uri")
                pfd = opened
                val renderer = try {
                    PdfRenderer(opened)
                } catch (e: IllegalArgumentException) {
                    // Descriptor isn't seekable (some cloud providers hand out pipes):
                    // stream it to a cache file and open that instead.
                    opened.close()
                    val copy = copyToCache(context, uri)
                    temp = copy
                    opened = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                    pfd = opened
                    PdfRenderer(opened)
                }
                if (renderer.pageCount == 0) {
                    renderer.close()
                    throw EmptyDocumentException()
                }
                PdfDocument(renderer, opened, temp)
            } catch (t: Throwable) {
                runCatching { pfd?.close() }
                temp?.delete()
                throw t
            }
        }

        private fun copyToCache(context: Context, uri: Uri): File {
            val dir = File(context.cacheDir, "pdf").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() } // purge leftovers from earlier sessions
            }
            val file = File(dir, "doc_${System.nanoTime()}.pdf")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException("Cannot read $uri")
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
            return file
        }
    }
}
