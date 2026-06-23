package com.example.offlinetts

import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Self-contained, fully-offline EPUB text extractor.
 *
 * An EPUB is just a ZIP archive of XHTML/HTML content documents plus an OPF
 * manifest that lists the correct reading order (the "spine"). This class:
 *
 *   1. Reads the whole archive into memory (entry path -> bytes).
 *   2. Locates the OPF package file via META-INF/container.xml.
 *   3. Parses the OPF <manifest> + <spine> to recover reading order.
 *   4. Strips HTML from each spine document with Jsoup and concatenates text.
 *
 * If anything in the structured path fails, it falls back to extracting text
 * from every .xhtml, .html or .htm entry in the archive (alphabetical order).
 *
 * This intentionally replaces the flaky JitPack `epublib` dependency, so the
 * build can never break on a third-party JitPack resolution failure.
 */
object EpubExtractor {

    /**
     * Extract readable plain text from an EPUB input stream.
     * Runs synchronously — call from a background thread for large books.
     *
     * @throws IllegalStateException if no readable text can be found.
     */
    fun extract(input: InputStream): String {
        val entries = readAllEntries(input)
        if (entries.isEmpty()) {
            throw IllegalStateException("This EPUB appears to be empty or corrupted.")
        }

        val orderedDocs = resolveSpineOrder(entries)
            ?: entries.keys
                .filter { it.lowercase().let { p -> p.endsWith(".xhtml") || p.endsWith(".html") || p.endsWith(".htm") } }
                .sorted()

        val sb = StringBuilder()
        for (path in orderedDocs) {
            val bytes = entries[path] ?: continue
            try {
                val html = String(bytes, Charsets.UTF_8)
                val text = Jsoup.parse(html).text()
                if (text.isNotBlank()) sb.append(text).append("\n\n")
            } catch (_: Exception) {
                // Skip unreadable / non-text resources.
            }
        }

        if (sb.isBlank()) {
            throw IllegalStateException("No readable text found in this EPUB.")
        }
        return sb.toString()
    }

    /** Reads every entry of the ZIP into a map of normalized-path -> bytes. */
    private fun readAllEntries(input: InputStream): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val buffer = ByteArray(16 * 1024)
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    var n = zis.read(buffer)
                    while (n != -1) {
                        out.write(buffer, 0, n)
                        n = zis.read(buffer)
                    }
                    result[normalize(entry.name)] = out.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    /**
     * Returns the spine reading order as a list of content-document paths,
     * or null if the OPF could not be parsed.
     */
    private fun resolveSpineOrder(entries: Map<String, ByteArray>): List<String>? {
        // 1. Find the OPF path from META-INF/container.xml.
        val containerBytes = entries["meta-inf/container.xml"]
            ?: entries.entries.firstOrNull { it.key.endsWith("container.xml") }?.value
            ?: return null

        val container = Jsoup.parse(String(containerBytes, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
        val opfPath = container.select("rootfile").firstOrNull()?.attr("full-path")
            ?.takeIf { it.isNotBlank() } ?: return null

        val opfBytes = entries[normalize(opfPath)] ?: return null
        val opfDir = normalize(opfPath).substringBeforeLast('/', "")

        val opf = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())

        // 2. Build manifest: id -> href (resolved relative to the OPF directory).
        val idToHref = HashMap<String, String>()
        for (item in opf.select("manifest > item")) {
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                idToHref[id] = resolveRelative(opfDir, href)
            }
        }

        // 3. Walk the spine in order.
        val ordered = ArrayList<String>()
        for (ref in opf.select("spine > itemref")) {
            val idref = ref.attr("idref")
            val href = idToHref[idref] ?: continue
            if (entries.containsKey(href)) ordered.add(href)
        }

        return ordered.takeIf { it.isNotEmpty() }
    }

    private fun normalize(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./").lowercase()

    /** Resolves [href] relative to [baseDir], handling "../" and "./" segments. */
    private fun resolveRelative(baseDir: String, href: String): String {
        val cleanHref = href.substringBefore('#').replace('\\', '/')
        val combined = if (baseDir.isEmpty()) cleanHref else "$baseDir/$cleanHref"
        val parts = ArrayList<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return normalize(parts.joinToString("/"))
    }
}
