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
                finished INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_books_added ON books(added_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 阶段 2 还没有历史版本；以后加字段在这里按版本号 ALTER。
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
        finished = getInt(getColumnIndexOrThrow("finished")) != 0,
    )

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getString(i)
    }

    private companion object {
        const val NAME = "lumenbook.db"
        const val VERSION = 1
    }
}
