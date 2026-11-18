package com.lumenbook.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 书库的持久化。
 *
 * 只有一张表，用原生 SQLiteOpenHelper 就够了：不引 Room 是为了少一个注解处理器
 * （AGP 9 内置 Kotlin 之后 KSP 的版本匹配容易出岔子），这个规模的手写 DAO 更好维护。
 */
class LibraryDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE books (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                author TEXT,
                format TEXT NOT NULL,
                local_path TEXT NOT NULL,
                source_uri TEXT,
                cover_path TEXT,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                last_opened_at INTEGER,
                progress REAL NOT NULL DEFAULT 0,
                location TEXT,
                fingerprint TEXT,
                finished INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_books_added ON books(added_at)")
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL,
                location TEXT NOT NULL,
                chapter TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_bookmarks_book ON bookmarks(book_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2：加阅读位置（CFI）。老书重新打开会从头开始，不影响书库。
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE books ADD COLUMN location TEXT")
        }
        // v2 → v3：书签。书签是「书 + 位置」，位置对 EPUB 是 CFI、对 PDF 是 page:N。
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    book_id INTEGER NOT NULL,
                    location TEXT NOT NULL,
                    chapter TEXT,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_book ON bookmarks(book_id)")
        }
        // v3 -> v4: fingerprint, used to skip duplicate imports.
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE books ADD COLUMN fingerprint TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_fingerprint ON books(fingerprint)")
        }
    }

    fun all(): List<Book> = readableDatabase
        .rawQuery("SELECT * FROM books ORDER BY COALESCE(last_opened_at, added_at) DESC", null)
        .use { c ->
            buildList {
                while (c.moveToNext()) add(c.toBook())
            }
        }

    fun find(id: Long): Book? = readableDatabase
        .rawQuery("SELECT * FROM books WHERE id = ?", arrayOf(id.toString()))
        .use { c -> if (c.moveToFirst()) c.toBook() else null }

    /** Find a book by its fingerprint, so imports can skip what we already have. */
    /** 给老库里的书补指纹（老版本入库时还没有这一列）。 */
    fun updateFingerprint(id: Long, fingerprint: String?) {
        writableDatabase.update(
            "books",
            android.content.ContentValues().apply { put("fingerprint", fingerprint) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun findByFingerprint(fingerprint: String): Book? = readableDatabase
        .query("books", null, "fingerprint = ?", arrayOf(fingerprint), null, null, null)
        .use { c -> if (c.moveToFirst()) c.toBook() else null }

    fun insert(book: Book): Long {
        val values = ContentValues().apply {
            put("title", book.title)
            put("author", book.author)
            put("format", book.format.name)
            put("local_path", book.localPath)
            put("source_uri", book.sourceUri)
            put("cover_path", book.coverPath)
            put("size_bytes", book.sizeBytes)
            put("added_at", if (book.addedAt > 0) book.addedAt else System.currentTimeMillis())
            put("last_opened_at", book.lastOpenedAt)
            put("progress", book.progress)
            put("location", book.location)
            put("fingerprint", book.fingerprint)
            put("finished", if (book.finished) 1 else 0)
        }
        return writableDatabase.insert("books", null, values)
    }

    fun updateMeta(id: Long, title: String, author: String?) {
        writableDatabase.update(
            "books",
            ContentValues().apply {
                put("title", title)
                put("author", author)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    /** 只换封面：coverPath 传 null 表示回到「没有封面，用渐变」的状态。 */
    fun updateCover(id: Long, coverPath: String?) {
        writableDatabase.update(
            "books",
            ContentValues().apply { put("cover_path", coverPath) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun updateProgress(id: Long, progress: Float, finished: Boolean) {
        writableDatabase.update(
            "books",
            ContentValues().apply {
                put("progress", progress.coerceIn(0f, 1f))
                put("finished", if (finished) 1 else 0)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    /** 阅读位置 + 进度一起写：翻页时频繁调用，只更新这两列。 */
    fun updateLocation(id: Long, location: String?, progress: Float) {
        writableDatabase.update(
            "books",
            ContentValues().apply {
                put("location", location)
                put("progress", progress.coerceIn(0f, 1f))
                put("finished", if (progress >= 0.995f) 1 else 0)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun touch(id: Long, at: Long = System.currentTimeMillis()) {
        writableDatabase.update(
            "books",
            ContentValues().apply { put("last_opened_at", at) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun delete(id: Long) {
        writableDatabase.delete("books", "id = ?", arrayOf(id.toString()))
        // 书删了，书签也没意义了
        writableDatabase.delete("bookmarks", "book_id = ?", arrayOf(id.toString()))
    }

    // ---- 书签 ----

    fun bookmarks(bookId: Long): List<Bookmark> = readableDatabase
        .rawQuery(
            "SELECT * FROM bookmarks WHERE book_id = ? ORDER BY created_at DESC",
            arrayOf(bookId.toString()),
        )
        .use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Bookmark(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            bookId = c.getLong(c.getColumnIndexOrThrow("book_id")),
                            location = c.getString(c.getColumnIndexOrThrow("location")),
                            chapter = c.getStringOrNull("chapter"),
                            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        ),
                    )
                }
            }
        }

    fun findBookmark(bookId: Long, location: String): Bookmark? = readableDatabase
        .rawQuery(
            "SELECT * FROM bookmarks WHERE book_id = ? AND location = ? LIMIT 1",
            arrayOf(bookId.toString(), location),
        )
        .use { c ->
            if (!c.moveToFirst()) return null
            Bookmark(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                bookId = c.getLong(c.getColumnIndexOrThrow("book_id")),
                location = c.getString(c.getColumnIndexOrThrow("location")),
                chapter = c.getStringOrNull("chapter"),
                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            )
        }

    fun addBookmark(bookId: Long, location: String, chapter: String?): Long = writableDatabase.insert(
        "bookmarks",
        null,
        ContentValues().apply {
            put("book_id", bookId)
            put("location", location)
            put("chapter", chapter)
            put("created_at", System.currentTimeMillis())
        },
    )

    fun removeBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    private fun android.database.Cursor.toBook(): Book = Book(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        author = getStringOrNull("author"),
        format = runCatching { BookFormat.valueOf(getString(getColumnIndexOrThrow("format"))) }
            .getOrDefault(BookFormat.UNKNOWN),
        localPath = getString(getColumnIndexOrThrow("local_path")),
        sourceUri = getStringOrNull("source_uri"),
        coverPath = getStringOrNull("cover_path"),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        addedAt = getLong(getColumnIndexOrThrow("added_at")),
        lastOpenedAt = if (isNull(getColumnIndexOrThrow("last_opened_at"))) null
        else getLong(getColumnIndexOrThrow("last_opened_at")),
        progress = getFloat(getColumnIndexOrThrow("progress")),
        location = getStringOrNull("location"),
        fingerprint = getStringOrNull("fingerprint"),
        finished = getInt(getColumnIndexOrThrow("finished")) != 0,
    )

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getString(i)
    }

    private companion object {
        const val NAME = "lumenbook.db"
        const val VERSION = 4
    }
}
