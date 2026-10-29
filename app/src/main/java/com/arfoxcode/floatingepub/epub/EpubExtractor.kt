package com.arfoxcode.floatingepub.epub

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Mengekstrak file .epub (yang sebenarnya adalah file ZIP) ke folder
 * internal storage aplikasi: /data/data/<pkg>/files/books/<bookId>/
 */
object EpubExtractor {

    /** File penanda kecil yang hanya ditulis setelah ekstraksi selesai 100% */
    private const val MARKER_FILE = ".extracted_complete"

    fun extract(context: Context, epubFile: File, bookId: Long): File {
        val destDir = File(context.filesDir, "books/$bookId")
        val marker = File(destDir, MARKER_FILE)

        // Hanya anggap "sudah diekstrak" kalau marker ada — bukan sekadar folder tidak kosong.
        // Ini mencegah buku dianggap valid padahal ekstraksi sebelumnya terputus di tengah jalan.
        if (marker.exists()) {
            return destDir
        }

        // Bersihkan sisa ekstraksi yang gagal/terputus sebelum mulai ulang
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }
        destDir.mkdirs()

        ZipInputStream(epubFile.inputStream()).use { zis ->
            val buffer = ByteArray(8192)
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)

                // Proteksi zip-slip: pastikan hasil ekstrak tetap di dalam destDir
                val canonicalDest = destDir.canonicalPath + File.separator
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalDest)) {
                    throw SecurityException("Entry EPUB mencoba keluar dari folder tujuan: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        marker.createNewFile() // tandai ekstraksi selesai dengan sukses
        return destDir
    }

    /** Hapus hasil ekstrak sebuah buku (misal saat buku dihapus dari daftar) */
    fun deleteExtracted(context: Context, bookId: Long) {
        File(context.filesDir, "books/$bookId").deleteRecursively()
    }
}
