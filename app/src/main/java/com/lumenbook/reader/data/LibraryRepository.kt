package com.lumenbook.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lumenbook.reader.importer.BookImporter
import com.lumenbook.reader.importer.EpubRewriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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

    data class ImportOutcome(val added: Int, val skipped: Int = 0, val failed: List<String>)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _books.value = db.all()
    }

    /** 把用户选中的文件复制进应用私有目录，抠出元数据，再入库。同一本书（指纹相同）直接跳过。 */
    suspend fun import(uris: List<Uri>): ImportOutcome = withContext(Dispatchers.IO) {
        var added = 0
        var skipped = 0
        val failed = mutableListOf<String>()
        val gate = Mutex()
        val permits = Semaphore(2)

        coroutineScope {
            uris.map { uri ->
                async {
                    val displayName = resolverName(uri)
                    permits.withPermit {
                        try {
                            takeReadPermission(uri)
                            val fingerprint = fingerprintOf(uri)
                            if (fingerprint != null && db.findByFingerprint(fingerprint) != null) {
                                gate.withLock { skipped++ }
                            } else {
                                importOne(uri, displayName, fingerprint)
                                gate.withLock { added++ }
                            }
                        } catch (e: Exception) {
                            gate.withLock {
                                failed += "$displayName（${e.message ?: e::class.simpleName}）"
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        _books.value = db.all()
        ImportOutcome(added = added, skipped = skipped, failed = failed)
    }

    /** 单个文件的复制 + 元数据 + 入库。 */
    private fun importOne(uri: Uri, displayName: String, fingerprint: String?) {
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
                fingerprint = fingerprint,
            ),
        )
    }

    /**
     * 文件指纹：大小 + 前 64KB 的 SHA-256。
     * 同一本书换个文件名再导入也能认出来；不同书几乎不可能撞。
     */
    /** 本机文件的指纹，算法与 [fingerprintOf] 完全一致（大小 + 前 64KB）。 */
    private fun fingerprintOfFile(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(file.length().toString().toByteArray())
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            val n = input.read(buf)
            if (n > 0) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fingerprintOf(uri: Uri): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        digest.update(size.toString().toByteArray())
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            val n = input.read(buf)
            if (n > 0) digest.update(buf, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * 老版本导入的书没有指纹：启动时补算一次，去重才对它们有效。
     * 只补不算重的（同一指纹只给最老的一条，后面重复的留着让用户自己删）。
     */
    suspend fun backfillFingerprints() = withContext(Dispatchers.IO) {
        val pending = db.all().filter { it.fingerprint.isNullOrBlank() && it.localPath != null }
        if (pending.isEmpty()) return@withContext
        val seen = mutableSetOf<String>()
        for (book in pending.sortedBy { it.addedAt }) {
            val path = book.localPath ?: continue
            val file = File(path)
            if (!file.exists()) continue
            val fp = runCatching { fingerprintOfFile(file) }.getOrNull() ?: continue
            if (!seen.add(fp)) continue          // 这一轮里已经见过同一本，留给用户手动处理
            if (db.findByFingerprint(fp) != null) continue
            db.updateFingerprint(book.id, fp)
        }
        _books.value = db.all()
    }

    suspend fun rename(id: Long, title: String, author: String?) = withContext(Dispatchers.IO) {
        db.updateMeta(id, title.trim().ifBlank { "未命名" }, author?.trim()?.ifBlank { null })
        _books.value = db.all()
    }

    /** 用户自己挑的封面：复制进私有目录，扩展名跟着源文件走。 */
    suspend fun setCover(id: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val name = resolverName(uri)
            val ext = name.substringAfterLast('.', "jpg").lowercase().let {
                if (it in setOf("png", "jpg", "jpeg", "webp", "gif")) it else "jpg"
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("打不开这张图")
            val file = File(coverDir, "custom-$id.$ext")
            file.writeBytes(bytes)
            db.find(id)?.coverPath?.let { old -> if (old != file.absolutePath) runCatching { File(old).delete() } }
            db.updateCover(id, file.absolutePath)
            _books.value = db.all()
            true
        }.getOrDefault(false)
    }

    /** 恢复成导入时从书里抠出来的那张封面（重新解析一次原文件）。 */
    suspend fun resetCover(id: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val book = db.find(id) ?: return@runCatching false
            val bytes = BookImporter.sniff(File(book.localPath), book.format).cover ?: return@runCatching false
            val file = File(coverDir, "custom-$id.img")
            file.writeBytes(bytes)
            db.find(id)?.coverPath?.let { old -> if (old != file.absolutePath) runCatching { File(old).delete() } }
            db.updateCover(id, file.absolutePath)
            _books.value = db.all()
            true
        }.getOrDefault(false)
    }

    /** 读一张用户选的图（写回 EPUB 时要把封面塞进包里），返回字节 + 扩展名。 */
    suspend fun bytesOf(uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val name = resolverName(uri)
            val ext = name.substringAfterLast('.', "jpg").lowercase().let {
                if (it in setOf("png", "jpg", "jpeg", "webp", "gif")) it else "jpg"
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("打不开这张图")
            bytes to ext
        }.getOrNull()
    }

    data class MetaWriteBack(val ok: Boolean, val message: String)

    /**
     * 把书名 / 作者 / 封面写回文件本身。
     *
     * 只支持 EPUB（重打包），其它格式返回 ok=false 并说明原因。
     * 写之前一定先备份到 filesDir/backup/，写坏了还能找回来。
     * 私有副本改完后，如果原来的 SAF 文件还有写权限，再同步回用户的原文件。
     */
    suspend fun writeBackMeta(
        id: Long,
        title: String,
        author: String?,
        coverBytes: ByteArray?,
        coverExt: String,
    ): MetaWriteBack = withContext(Dispatchers.IO) {
        val book = db.find(id) ?: return@withContext MetaWriteBack(false, "这本书不在书库里了")
        if (book.format != BookFormat.EPUB) {
            return@withContext MetaWriteBack(false, "暂时只有 EPUB 能把改动写回原文件")
        }
        val local = File(book.localPath)
        if (!local.isFile) return@withContext MetaWriteBack(false, "这份 EPUB 已经不在了")

        val backupDir = File(context.filesDir, "backup").apply { mkdirs() }
        val backup = File(backupDir, "${book.id}-${System.currentTimeMillis()}.epub")
        runCatching { local.copyTo(backup) }

        val result = EpubRewriter.rewrite(
            source = local,
            title = title.trim().ifBlank { "未命名" },
            author = author?.trim(),
            cover = coverBytes,
            coverExt = coverExt,
        )
        if (!result.ok) return@withContext MetaWriteBack(false, result.message)

        db.updateMeta(id, title.trim().ifBlank { "未命名" }, author?.trim()?.ifBlank { null })

        // 私有副本改好了；能不能同步回用户选的那个文件，取决于 SAF 写权限
        val synced = runCatching {
            val uri = book.sourceUri?.let(Uri::parse) ?: return@runCatching false
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                local.inputStream().use { it.copyTo(out) }
            }
            true
        }.getOrDefault(false)

        _books.value = db.all()
        MetaWriteBack(true, if (synced) "已写回原文件" else "已写回书库副本（原文件没有写入权限）")
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

    // ---- 书签 ----

    suspend fun bookmarks(bookId: Long): List<Bookmark> = withContext(Dispatchers.IO) { db.bookmarks(bookId) }

    /** 当前位置没有书签就加上，有就删掉（顶栏那一个按钮是切换）。 */
    suspend fun toggleBookmark(bookId: Long, location: String, chapter: String?): Boolean =
        withContext(Dispatchers.IO) {
            val existing = db.findBookmark(bookId, location)
            if (existing != null) {
                db.removeBookmark(existing.id)
                false
            } else {
                db.addBookmark(bookId, location, chapter)
                true
            }
        }

    suspend fun removeBookmark(id: Long) = withContext(Dispatchers.IO) { db.removeBookmark(id) }

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
