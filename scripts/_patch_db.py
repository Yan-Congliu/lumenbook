import io

p = 'app/src/main/java/com/lumenbook/reader/data/LibraryDb.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace("""                location TEXT,
                finished INTEGER NOT NULL DEFAULT 0""", """                location TEXT,
                fingerprint TEXT,
                finished INTEGER NOT NULL DEFAULT 0""", 1)

s = s.replace("""            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_book ON bookmarks(book_id)")
        }
    }""", """            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_book ON bookmarks(book_id)")
        }
        // v3 -> v4: fingerprint, used to skip duplicate imports.
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE books ADD COLUMN fingerprint TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_fingerprint ON books(fingerprint)")
        }
    }""", 1)

s = s.replace("const val VERSION = 3", "const val VERSION = 4", 1)

s = s.replace("""            put("location", book.location)""", """            put("location", book.location)
            put("fingerprint", book.fingerprint)""", 1)

s = s.replace("""        location = getStringOrNull("location"),""", """        location = getStringOrNull("location"),
        fingerprint = getStringOrNull("fingerprint"),""", 1)

s = s.replace("""    fun insert(book: Book): Long {""", """    /** Find a book by its fingerprint, so imports can skip what we already have. */
    fun findByFingerprint(fingerprint: String): Book? = readableDatabase
        .query("books", null, "fingerprint = ?", arrayOf(fingerprint), null, null, null)
        .use { c -> if (c.moveToFirst()) c.toBook() else null }

    fun insert(book: Book): Long {""", 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
