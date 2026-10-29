package com.arfoxcode.floatingepub.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Pengganti Room. Pakai API SQLite bawaan Android langsung — tanpa kapt/ksp,
 * tanpa dependency native (sqlite-jdbc) — supaya aman dibuild di lingkungan
 * seperti JStudio yang sering bermasalah dengan annotation processor Room.
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "floating_epub.db"
        private const val DB_VERSION = 2

        const val TABLE_BOOKS = "books"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_AUTHOR = "author"
        const val COL_FILE_PATH = "filePath"
        const val COL_COVER_PATH = "coverPath"
        const val COL_FILE_SIZE = "fileSizeBytes"
        const val COL_DATE_ADDED = "dateAdded"

        const val TABLE_HISTORY = "reading_history"
        const val COL_BOOK_ID = "bookId"
        const val COL_LAST_CHAPTER = "lastChapterIndex"
        const val COL_LAST_SCROLL = "lastScrollPercent"
        const val COL_OVERALL_PROGRESS = "overallProgressPercent"
        const val COL_LAST_READ_TS = "lastReadTimestamp"

        const val TABLE_BOOKMARKS = "bookmarks"
        const val COL_BM_ID = "id"
        const val COL_BM_BOOK_ID = "bookId"
        const val COL_BM_CHAPTER = "chapterIndex"
        const val COL_BM_SCROLL = "scrollPercent"
        const val COL_BM_LABEL = "label"
        const val COL_BM_CREATED_TS = "createdAt"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_BOOKS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL,
                $COL_AUTHOR TEXT,
                $COL_FILE_PATH TEXT NOT NULL,
                $COL_COVER_PATH TEXT,
                $COL_FILE_SIZE INTEGER NOT NULL,
                $COL_DATE_ADDED INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COL_BOOK_ID INTEGER PRIMARY KEY,
                $COL_LAST_CHAPTER INTEGER NOT NULL,
                $COL_LAST_SCROLL REAL NOT NULL,
                $COL_OVERALL_PROGRESS REAL NOT NULL,
                $COL_LAST_READ_TS INTEGER NOT NULL,
                FOREIGN KEY($COL_BOOK_ID) REFERENCES $TABLE_BOOKS($COL_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        createBookmarksTable(db)
    }

    private fun createBookmarksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BOOKMARKS (
                $COL_BM_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_BM_BOOK_ID INTEGER NOT NULL,
                $COL_BM_CHAPTER INTEGER NOT NULL,
                $COL_BM_SCROLL REAL NOT NULL,
                $COL_BM_LABEL TEXT,
                $COL_BM_CREATED_TS INTEGER NOT NULL,
                FOREIGN KEY($COL_BM_BOOK_ID) REFERENCES $TABLE_BOOKS($COL_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true) // supaya ON DELETE CASCADE di atas benar-benar jalan
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrasi non-destruktif: jangan hapus data buku/histori yang sudah ada,
        // cukup tambahkan tabel baru yang belum ada.
        if (oldVersion < 2) {
            createBookmarksTable(db)
        }
    }

    // ---------- Mapping Book <-> ContentValues/Cursor ----------

    fun bookToValues(book: Book): ContentValues = ContentValues().apply {
        put(COL_TITLE, book.title)
        put(COL_AUTHOR, book.author)
        put(COL_FILE_PATH, book.filePath)
        put(COL_COVER_PATH, book.coverPath)
        put(COL_FILE_SIZE, book.fileSizeBytes)
        put(COL_DATE_ADDED, book.dateAdded)
    }

    fun cursorToBook(cursor: android.database.Cursor): Book = Book(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
        title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
        author = cursor.getString(cursor.getColumnIndexOrThrow(COL_AUTHOR)),
        filePath = cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_PATH)),
        coverPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_COVER_PATH)),
        fileSizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FILE_SIZE)),
        dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE_ADDED))
    )

    fun historyToValues(history: ReadingHistory): ContentValues = ContentValues().apply {
        put(COL_BOOK_ID, history.bookId)
        put(COL_LAST_CHAPTER, history.lastChapterIndex)
        put(COL_LAST_SCROLL, history.lastScrollPercent)
        put(COL_OVERALL_PROGRESS, history.overallProgressPercent)
        put(COL_LAST_READ_TS, history.lastReadTimestamp)
    }

    fun cursorToHistory(cursor: android.database.Cursor): ReadingHistory = ReadingHistory(
        bookId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BOOK_ID)),
        lastChapterIndex = cursor.getInt(cursor.getColumnIndexOrThrow(COL_LAST_CHAPTER)),
        lastScrollPercent = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_LAST_SCROLL)),
        overallProgressPercent = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_OVERALL_PROGRESS)),
        lastReadTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_READ_TS))
    )

    // ---------- Mapping Bookmark <-> ContentValues/Cursor ----------

    fun bookmarkToValues(bookmark: Bookmark): ContentValues = ContentValues().apply {
        put(COL_BM_BOOK_ID, bookmark.bookId)
        put(COL_BM_CHAPTER, bookmark.chapterIndex)
        put(COL_BM_SCROLL, bookmark.scrollPercent)
        put(COL_BM_LABEL, bookmark.label)
        put(COL_BM_CREATED_TS, bookmark.createdAt)
    }

    fun cursorToBookmark(cursor: android.database.Cursor): Bookmark = Bookmark(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BM_ID)),
        bookId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BM_BOOK_ID)),
        chapterIndex = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BM_CHAPTER)),
        scrollPercent = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_BM_SCROLL)),
        label = cursor.getString(cursor.getColumnIndexOrThrow(COL_BM_LABEL)),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BM_CREATED_TS))
    )
}
