package com.arfoxcode.floatingepub.epub

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

/**
 * Parser EPUB murni pakai XmlPullParser bawaan Android (tanpa dependency tambahan).
 *
 * Alur EPUB:
 *  1. META-INF/container.xml -> menunjuk ke lokasi file .opf
 *  2. file .opf -> berisi <metadata>, <manifest> (semua file), <spine> (urutan baca)
 *  3. toc.ncx (EPUB2) atau nav.xhtml (EPUB3) -> daftar isi / table of contents
 */
object EpubParser {

    fun parse(extractedDir: File): EpubBook {
        val opfRelativePath = findOpfPath(extractedDir)
        val opfFile = File(extractedDir, opfRelativePath)
        val opfDir = opfFile.parentFile ?: extractedDir

        val parsed = parseOpf(opfFile)
        val toc = resolveToc(opfDir, parsed.manifest)

        return EpubBook(
            baseDir = opfDir.absolutePath,
            metadata = parsed.metadata,
            manifest = parsed.manifest,
            spine = parsed.spine,
            toc = toc
        )
    }

    // ---------- container.xml ----------

    private fun findOpfPath(rootDir: File): String {
        val containerFile = File(rootDir, "META-INF/container.xml")
        require(containerFile.exists()) { "container.xml tidak ditemukan — file EPUB tidak valid" }

        val parser = Xml.newPullParser()
        parser.setInput(FileInputStream(containerFile), "UTF-8")

        var opfPath: String? = null
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                opfPath = parser.getAttributeValue(null, "full-path")
            }
            eventType = parser.next()
        }
        return opfPath ?: throw IllegalStateException("full-path OPF tidak ditemukan di container.xml")
    }

    // ---------- file .opf ----------

    private data class OpfResult(
        val metadata: EpubMetadata,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>
    )

    private fun parseOpf(opfFile: File): OpfResult {
        val parser = Xml.newPullParser()
        parser.setInput(FileInputStream(opfFile), "UTF-8")

        var title = opfFile.nameWithoutExtension
        var author: String? = null
        var coverId: String? = null
        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        var currentTag = ""
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (parser.name) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            if (id != null && href != null) {
                                manifest[id] = ManifestItem(id, href, mediaType)
                            }
                        }
                        "itemref" -> {
                            parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                        }
                        "meta" -> {
                            // format EPUB2: <meta name="cover" content="cover-image-id"/>
                            if (parser.getAttributeValue(null, "name") == "cover") {
                                coverId = parser.getAttributeValue(null, "content")
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) {
                        when (currentTag) {
                            "dc:title", "title" -> title = text
                            "dc:creator", "creator" -> author = text
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val coverHref = coverId?.let { manifest[it]?.href }
            ?: manifest.values.find { it.mediaType.startsWith("image/") }?.href // fallback tebak cover

        return OpfResult(
            metadata = EpubMetadata(title, author, coverHref),
            manifest = manifest,
            spine = spine
        )
    }

    // ---------- Table of Contents ----------

    private fun resolveToc(opfDir: File, manifest: Map<String, ManifestItem>): List<TocEntry> {
        // EPUB2: cari item dengan media-type NCX
        val ncxItem = manifest.values.find { it.mediaType == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncxFile = File(opfDir, ncxItem.href)
            if (ncxFile.exists()) return parseNcx(ncxFile)
        }
        // EPUB3 nav.xhtml belum didukung di versi ini -> fallback ke daftar kosong,
        // UI cukup pakai urutan spine sebagai daftar chapter "Chapter 1, 2, 3..."
        return emptyList()
    }

    private fun parseNcx(ncxFile: File): List<TocEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(FileInputStream(ncxFile), "UTF-8")

        val entries = mutableListOf<TocEntry>()
        var label = ""
        var src = ""
        var insideText = false
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "text" -> insideText = true
                    "content" -> src = parser.getAttributeValue(null, "src") ?: ""
                }
                XmlPullParser.TEXT -> if (insideText) {
                    label = parser.text?.trim() ?: label
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "text" -> insideText = false
                    "navPoint" -> {
                        if (src.isNotEmpty()) entries.add(TocEntry(label, src))
                        src = ""
                        label = ""
                    }
                }
            }
            eventType = parser.next()
        }
        return entries
    }
}
