package com.arfoxcode.floatingepub.epub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Titik masuk sederhana: dari file .epub di storage -> EpubBook siap pakai.
 * Ekstraksi hanya dilakukan sekali per bookId (hasil di-cache di internal storage).
 *
 * open() dan delete() sengaja dibuat suspend + Dispatchers.IO karena keduanya
 * melibatkan I/O berat (unzip, baca-tulis file, parsing XML) yang tidak boleh
 * jalan di Main thread — bisa bikin UI freeze/ANR untuk file EPUB besar.
 */
class EpubRepository(private val context: Context) {

    suspend fun open(epubFile: File, bookId: Long): EpubBook = withContext(Dispatchers.IO) {
        val extractedDir = EpubExtractor.extract(context, epubFile, bookId)
        EpubParser.parse(extractedDir)
    }

    suspend fun delete(bookId: Long) = withContext(Dispatchers.IO) {
        EpubExtractor.deleteExtracted(context, bookId)
    }
}
