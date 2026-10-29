package com.arfoxcode.floatingepub.data

/**
 * Satu baris = satu buku yang sudah diimport user.
 * [filePath] menunjuk ke file .epub asli yang disalin ke internal storage
 * (mis. filesDir/epubs/<id>.epub), dipakai EpubRepository untuk extract+parse.
 *
 * Plain data class (bukan Room @Entity) — dipetakan manual ke/dari SQLite
 * lewat DbHelper, supaya tidak butuh annotation processor (kapt/ksp) sama sekali.
 */
data class Book(
    val id: Long = 0,
    val title: String,
    val author: String?,
    val filePath: String,
    val coverPath: String?,
    val fileSizeBytes: Long,
    val dateAdded: Long = System.currentTimeMillis()
)
