package com.arfoxcode.floatingepub.data

/**
 * Satu bookmark = satu posisi baca yang ditandai manual oleh user (bisa lebih
 * dari satu per buku) - beda dengan ReadingHistory yang cuma nyimpen posisi
 * TERAKHIR dan otomatis ter-overwrite tiap pindah halaman.
 */
data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val scrollPercent: Float,
    val label: String?,
    val createdAt: Long = System.currentTimeMillis()
)
