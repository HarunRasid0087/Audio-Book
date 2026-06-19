package com.example.offlinetts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Optical Character Recognition for scanned / image-only PDFs.
 *
 * Uses Android's built-in [PdfRenderer] to rasterise each page to a bitmap,
 * then ML Kit's on-device Latin text recogniser (bundled model = NO network
 * required) to extract the text. Everything runs fully offline.
 *
 * Call [extractFromPdf] only from a background thread — it blocks while each
 * page is recognised.
 */
object OcrHelper {

    /** Higher = sharper OCR but more memory/time. ~2x device density is plenty. */
    private const val RENDER_SCALE = 2.0f

    /** Safety cap so a 1000-page scan doesn't OOM / run forever. */
    private const val MAX_PAGES = 300

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Render every page of [uri] and OCR it.
     *
     * @param onPage optional progress callback (current page, total pages).
     * @return concatenated recognised text.
     */
    fun extractFromPdf(
        context: Context,
        uri: Uri,
        onPage: ((current: Int, total: Int) -> Unit)? = null
    ): String {
        val pfd: ParcelFileDescriptor = context.contentResolver
            .openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not open the PDF for OCR.")

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_PAGES)
                if (pageCount == 0) {
                    throw IllegalStateException("The PDF has no pages to scan.")
                }

                val sb = StringBuilder()
                for (i in 0 until pageCount) {
                    onPage?.invoke(i + 1, pageCount)
                    val pageText = ocrPage(renderer, i)
                    if (pageText.isNotBlank()) {
                        sb.append(pageText).append("\n\n")
                    }
                }

                if (sb.isBlank()) {
                    throw IllegalStateException(
                        "OCR found no readable text — the scan quality may be too low."
                    )
                }
                return sb.toString()
            }
        }
    }

    private fun ocrPage(renderer: PdfRenderer, index: Int): String {
        renderer.openPage(index).use { page ->
            val width = (page.width * RENDER_SCALE).toInt().coerceAtLeast(1)
            val height = (page.height * RENDER_SCALE).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // White background so transparent PDFs render readable black text.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            return try {
                val image = InputImage.fromBitmap(bitmap, 0)
                // ML Kit is async; block on the Task since we're already off-thread.
                val result = Tasks.await(recognizer.process(image))
                result.text
            } finally {
                bitmap.recycle()
            }
        }
    }
}
