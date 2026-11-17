package com.lumenbook.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lumenbook.reader.importer.BookImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryRepository(
    private val context: Context,
    private val db: LibraryDb,
) {
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val libraryDir = File(context.filesDir, "library").apply { mkdirs() }
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

    data class ImportOutcome(val added: Int, val failed: List<String>)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _books.value = db.all()
    }

    /** 把用户选中的文件复制进应用私有目录，抠出元数据，再入库。 */
    suspend fun import(uris: List<Uri>): ImportOutcome = withContext(Dispatchers.IO) {
        var added = 0
        val failed = mutableListOf<String>()

        uris.forEach { uri ->
            val displayName = resolverName(uri)
            try {
                takeReadPermission(uri)

                val header = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(64)
                    val n = input.read(buf)
                    if (n > 0) buf.copyOf(n) else ByteArray(0)
                } ?: ByteArray(0)

                val format = BookImporter.detect(displayName, header)
                val id = UUID.randomUUID().toString().replace("-", "")
                val target = File(libraryDir, "$id.${format.name.lowercase()}")

                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (copied == null) error("打不开这个文件")

                val meta = BookImporter.sniff(target, format)
                val coverFile = meta.cover?.let { bytes -> saveCover(id, bytes) }

                db.insert(
                    Book(
                        title = (meta.title ?: fallbackTitle(displayName)).trim().ifBlank { "未命名" },
                        author = meta.author?.trim()?.ifBlank { null },
                        format = format,
                        localPath = target.absolutePath,
                        sourceUri = uri.toString(),
                        coverPath = coverFile?.absolutePath,
                        sizeBytes = target.length(),
                        addedAt = System.currentTimeMillis(),
                    ),
                )
                added++
            } catch (e: Exception) {
                failed += "$displayName（${e.message ?: e::class.simpleName}）"
            }
        }

        _books.value = db.all()
        ImportOutcome(added = added, failed = failed)
    }

    suspend fun rename(id: Long, title: String, author: String?) = withContext(Dispatchers.IO) {
        db.updateMeta(id, title.trim().ifBlank { "未命名" }, author?.trim()?.ifBlank { null })
        _books.value = db.all()
    }

    /** deleteLocal=true 时连私有副本一起删；false 只从书库移除。 */
    suspend fun remove(id: Long, deleteLocal: Boolean) = withContext(Dispatchers.IO) {
        val book = db.find(id)
        if (book != null) {
            if (deleteLocal) runCatching { File(book.localPath).delete() }
            runCatching { book.coverPath?.let { File(it).delete() } }
        }
        db.delete(id)
        _books.value = db.all()
    }

    suspend fun markOpened(id: Long) = withContext(Dispatchers.IO) {
        db.touch(id)
        _books.value = db.all()
    }

    /**
     * 记住阅读位置。翻页时会被频繁调用，所以只写库里这两列，
     * 内存里的 books 列表等退出阅读页再刷新（refresh），避免每页都重建整个列表。
     */
    suspend fun saveLocation(id: Long, location: String?, progress: Float) = withContext(Dispatchers.IO) {
        db.updateLocation(id, location, progress)
    }

    private fun saveCover(id: String, bytes: ByteArray): File? = runCatching {
        val file = File(coverDir, "$id.img")
        file.writeBytes(bytes)
        file
    }.getOrNull()

    private fun resolverName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "book.bin"
    }

    private fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun fallbackTitle(displayName: String): String =
        displayName.substringBeforeLast('.').trim().ifBlank { displayName }
}
