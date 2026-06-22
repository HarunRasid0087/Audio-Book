package com.example.offlinetts

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Unified, fully-offline document reader.
 *
 * Detects the file type from its display name (extension) or MIME type and
 * extracts plain text suitable for the TTS engine. Supports:
 *   - .txt  (and any UTF-8 text-like file)
 *   - .pdf  (via PDFBox-Android, with OCR fallback for scanned PDFs)
 *   - .epub (via the in-app EpubExtractor — no external JitPack dependency)
 */
object DocumentParser {

    /** Call once (cheap, idempotent) before parsing a PDF. */
    fun init(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    enum class Type { TXT, PDF, EPUB, UNKNOWN }

    fun detectType(name: String, mime: String?): Type {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") || mime == "application/pdf" -> Type.PDF
            lower.endsWith(".epub") || mime == "application/epub+zip" -> Type.EPUB
            lower.endsWith(".txt") || mime?.startsWith("text/") == true -> Type.TXT
            else -> Type.UNKNOWN
        }
    }

    /**
     * Read & extract text from any supported document.
     * Runs synchronously — call from a background thread for large files.
     */
    fun extractText(
        context: Context,
        uri: Uri,
        onOcrProgress: ((current: Int, total: Int) -> Unit)? = null
    ): String {
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri)
        return when (detectType(name, mime)) {
            Type.PDF -> extractPdf(context, uri, onOcrProgress)
            Type.EPUB -> extractEpub(context, uri)
            Type.TXT -> extractTxt(context, uri)
            Type.UNKNOWN -> extractTxt(context, uri) // best-effort fallback
        }
    }

    private fun extractTxt(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = reader.readLine()
                }
            }
        } ?: throw IllegalStateException("Could not open the selected file.")
        return sb.toString()
    }

    private fun extractPdf(
        context: Context,
        uri: Uri,
        onOcrProgress: ((current: Int, total: Int) -> Unit)? = null
    ): String {
        init(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                if (doc.isEncrypted) {
                    throw IllegalStateException("This PDF is encrypted and cannot be read.")
                }
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                val text = stripper.getText(doc)
                if (text.isNotBlank()) return text
            }
        } ?: throw IllegalStateException("Could not open the PDF file.")

        // No embedded text layer -> the PDF is a scan of images. Fall back to OCR.
        return OcrHelper.extractFromPdf(context, uri, onOcrProgress)
    }

    private fun extractEpub(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return EpubExtractor.extract(input)
        } ?: throw IllegalStateException("Could not open the EPUB file.")
    }

    fun displayName(context: Context, uri: Uri): String {
        var name = "document"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx) ?: name
            }
        }
        return name
    }
}
