package com.arfoxcode.floatingepub.data

import android.content.Context
import com.arfoxcode.floatingepub.epub.EpubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Titik akses tunggal untuk UI: import buku baru, ambil daftar buku,
 * dan simpan/ambil progress baca.
 *
 * Dulu pakai Room; sekarang pakai DbHelper (SQLiteOpenHelper) langsung supaya
 * tidak butuh annotation processor sama sekali (aman dibuild di JStudio).
 * Reaktivitas ke UI ditiru pakai StateFlow yang di-refresh manual setiap
 * ada perubahan data (insert/update/delete).
 */
class BookRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dbHelper = DbHelper(appContext)
    private val epubRepository = EpubRepository(appContext)

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    private val _history = MutableStateFlow<List<ReadingHistory>>(emptyList())

    fun getAllBooks(): StateFlow<List<Book>> = _books.asStateFlow()
    fun getRecentHistory(): StateFlow<List<ReadingHistory>> = _history.asStateFlow()

    /** Panggil sekali di awal (mis. MainActivity.onCreate) supaya StateFlow terisi data awal */
    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        _books.value = queryAllBooks()
        _history.value = queryAllHistorySortedByRecent()
    }

    private fun queryAllBooks(): List<Book> {
        val result = mutableListOf<Book>()
        dbHelper.readableDatabase.query(
            DbHelper.TABLE_BOOKS, null, null, null, null, null,
            "${DbHelper.COL_DATE_ADDED} DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(dbHelper.cursorToBook(cursor))
        }
        return result
    }

    private fun queryAllHistorySortedByRecent(): List<ReadingHistory> {
        val result = mutableListOf<ReadingHistory>()
        dbHelper.readableDatabase.query(
            DbHelper.TABLE_HISTORY, null, null, null, null, null,
            "${DbHelper.COL_LAST_READ_TS} DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(dbHelper.cursorToHistory(cursor))
        }
        return result
    }

    suspend fun getBookById(bookId: Long): Book? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            DbHelper.TABLE_BOOKS, null, "${DbHelper.COL_ID} = ?", arrayOf(bookId.toString()),
            null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) dbHelper.cursorToBook(cursor) else null }
    }

    suspend fun getHistoryForBook(bookId: Long): ReadingHistory? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            DbHelper.TABLE_HISTORY, null, "${DbHelper.COL_BOOK_ID} = ?", arrayOf(bookId.toString()),
            null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) dbHelper.cursorToHistory(cursor) else null }
    }

    /**
     * Query langsung ke DB (bukan lewat StateFlow cache) untuk "buku terakhir dibaca".
     * Dipakai OverlayService, yang punya instance BookRepository sendiri dan tidak selalu
     * sempat memanggil refreshAll() sebelum butuh data ini.
     */
    suspend fun getMostRecentHistory(): ReadingHistory? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            DbHelper.TABLE_HISTORY, null, null, null, null, null,
            "${DbHelper.COL_LAST_READ_TS} DESC", "1"
        ).use { cursor -> if (cursor.moveToFirst()) dbHelper.cursorToHistory(cursor) else null }
    }

    /**
     * Import file EPUB baru:
     *  1. Insert baris sementara untuk dapat id
     *  2. Salin file ke internal storage (filesDir/epubs/<id>.epub)
     *  3. Extract + parse metadata asli (judul, penulis, cover) dari EPUB
     *  4. Update baris dengan data final
     */
    suspend fun importBook(sourceFile: File): Book = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase

        val tempValues = dbHelper.bookToValues(
            Book(
                title = sourceFile.nameWithoutExtension,
                author = null,
                filePath = "",
                coverPath = null,
                fileSizeBytes = sourceFile.length()
            )
        )
        val tempId = db.insert(DbHelper.TABLE_BOOKS, null, tempValues)

        val epubsDir = File(appContext.filesDir, "epubs").apply { mkdirs() }
        val destFile = File(epubsDir, "$tempId.epub")
        sourceFile.copyTo(destFile, overwrite = true)

        val epubBook = epubRepository.open(destFile, tempId)

        val finalBook = Book(
            id = tempId,
            title = epubBook.metadata.title,
            author = epubBook.metadata.author,
            filePath = destFile.absolutePath,
            coverPath = epubBook.coverFilePath,
            fileSizeBytes = destFile.length()
        )
        db.update(
            DbHelper.TABLE_BOOKS, dbHelper.bookToValues(finalBook),
            "${DbHelper.COL_ID} = ?", arrayOf(tempId.toString())
        )

        _books.value = queryAllBooks()
        finalBook
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(DbHelper.TABLE_HISTORY, "${DbHelper.COL_BOOK_ID} = ?", arrayOf(book.id.toString()))
        db.delete(DbHelper.TABLE_BOOKS, "${DbHelper.COL_ID} = ?", arrayOf(book.id.toString()))
        epubRepository.delete(book.id)
        File(book.filePath).delete()

        _books.value = queryAllBooks()
        _history.value = queryAllHistorySortedByRecent()
    }

    /** Dipanggil tiap kali user pindah chapter, supaya posisi baca tersimpan */
    suspend fun updateProgress(
        bookId: Long,
        chapterIndex: Int,
        scrollPercent: Float,
        overallProgressPercent: Float
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = dbHelper.historyToValues(
            ReadingHistory(
                bookId = bookId,
                lastChapterIndex = chapterIndex,
                lastScrollPercent = scrollPercent,
                overallProgressPercent = overallProgressPercent
            )
        )
        // INSERT OR REPLACE: 1 buku = 1 baris histori (bookId adalah PRIMARY KEY)
        db.insertWithOnConflict(DbHelper.TABLE_HISTORY, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)

        _history.value = queryAllHistorySortedByRecent()
    }

    // ---------- Bookmark (posisi baca yang ditandai manual, bisa lebih dari satu per buku) ----------

    suspend fun addBookmark(bookId: Long, chapterIndex: Int, scrollPercent: Float, label: String? = null): Bookmark =
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val bookmark = Bookmark(
                bookId = bookId,
                chapterIndex = chapterIndex,
                scrollPercent = scrollPercent,
                label = label
            )
            val id = db.insert(DbHelper.TABLE_BOOKMARKS, null, dbHelper.bookmarkToValues(bookmark))
            bookmark.copy(id = id)
        }

    suspend fun getBookmarksForBook(bookId: Long): List<Bookmark> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Bookmark>()
        dbHelper.readableDatabase.query(
            DbHelper.TABLE_BOOKMARKS, null, "${DbHelper.COL_BM_BOOK_ID} = ?", arrayOf(bookId.toString()),
            null, null, "${DbHelper.COL_BM_CREATED_TS} DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(dbHelper.cursorToBookmark(cursor))
        }
        result
    }

    suspend fun deleteBookmark(bookmarkId: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(
            DbHelper.TABLE_BOOKMARKS, "${DbHelper.COL_BM_ID} = ?", arrayOf(bookmarkId.toString())
        )
        Unit
    }
}
