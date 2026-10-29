package com.arfoxcode.floatingepub.epub

/** Metadata dasar buku, diambil dari tag <metadata> di file .opf */
data class EpubMetadata(
    val title: String,
    val author: String?,
    val coverHref: String?
)

/** Satu entri di <manifest>: representasi 1 file dalam EPUB (chapter, css, gambar, dll) */
data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String
)

/** Satu entri di Table of Contents (dari toc.ncx atau nav.xhtml) */
data class TocEntry(
    val title: String,
    val href: String
)

/**
 * Representasi lengkap 1 buku EPUB yang sudah diparse & diekstrak.
 *
 * @param baseDir folder hasil ekstrak epub (untuk resolve path relatif file chapter)
 */
data class EpubBook(
    val baseDir: String,
    val metadata: EpubMetadata,
    val manifest: Map<String, ManifestItem>, // id -> item
    val spine: List<String>,                 // urutan baca: list of manifest id
    val toc: List<TocEntry>
) {
    val chapterCount: Int get() = spine.size

    /** Path absolut file HTML untuk chapter ke-[index] (0-based), null kalau index invalid */
    fun chapterFilePath(index: Int): String? {
        val itemId = spine.getOrNull(index) ?: return null
        val item = manifest[itemId] ?: return null
        return "$baseDir/${item.href}"
    }

    // Catatan: dulu ada chapterFileUrl() yang mengembalikan "file://..." — dihapus
    // karena WebView modern memblokir akses file:// ke folder data privat aplikasi.
    // Sekarang path absolut dari chapterFilePath() diubah ke URL virtual
    // https://appassets.androidplatform.net/... lewat WebViewAssetLoader (lihat OverlayService).

    val coverFilePath: String?
        get() = metadata.coverHref?.let { "$baseDir/$it" }
}
