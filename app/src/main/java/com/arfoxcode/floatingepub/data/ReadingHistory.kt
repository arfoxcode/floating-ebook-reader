package com.arfoxcode.floatingepub.data

/**
 * Progress baca untuk 1 buku. Satu buku = satu baris histori (di-replace,
 * bukan ditambah terus) supaya gampang query "lagi baca sampai mana".
 *
 * Plain data class (bukan Room @Entity) — lihat catatan di Book.kt.
 */
data class ReadingHistory(
    val bookId: Long,
    val lastChapterIndex: Int = 0,
    val lastScrollPercent: Float = 0f,      // posisi scroll dalam chapter (0.0 - 1.0)
    val overallProgressPercent: Float = 0f, // progress keseluruhan buku (0.0 - 1.0)
    val lastReadTimestamp: Long = System.currentTimeMillis()
)
