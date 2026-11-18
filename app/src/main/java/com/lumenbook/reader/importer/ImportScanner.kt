package com.lumenbook.reader.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫描用户选中的整个文件夹，把里面（含各级子文件夹）能读的书都找出来。
 *
 * 为什么需要它：系统文件选择器默认停在「最近」，书放在 QQ/微信下载目录、或者几层子文件夹里
 * 时用户根本翻不到（真机上反馈过「导入不了 / 太里头就找不见」）。这里让用户直接挑一个文件夹，
 * 剩下的目录遍历由 App 自己做。
 */
object ImportScanner {

    val SUPPORTED = setOf("epub", "pdf", "txt", "mobi", "azw3", "azw", "fb2", "cbz")

    data class Found(
        val uri: Uri,
        /** 文件名，带后缀 */
        val name: String,
        /** 相对所选文件夹的路径，用来在列表里显示来源 */
        val relativePath: String,
        val size: Long,
    ) {
        val extension: String get() = name.substringAfterLast('.', "").lowercase()
    }

    private const val MAX_FILES = 3000
    private const val MAX_DEPTH = 12

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        onProgress: (found: Int) -> Unit = {},
    ): List<Found> = withContext(Dispatchers.IO) {
        val out = ArrayList<Found>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext out
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = queryName(context, rootDoc)
        walk(context, treeUri, rootDoc, rootName, 0, out) { out.size }
        runCatching {
            onProgress(out.size)
        }
        out.sortedWith(compareBy({ it.relativePath.count { c -> c == '/' } }, { it.name.lowercase() }))
    }

    private fun walk(
        context: Context,
        treeUri: Uri,
        dirUri: Uri,
        dirPath: String,
        depth: Int,
        out: MutableList<Found>,
        progress: () -> Int,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = childrenOf(context, dirUri) ?: return
        for (child in children) {
            if (out.size >= MAX_FILES) return
            if (child.name.startsWith(".")) continue
            if (child.isDir) {
                walk(context, treeUri, child.uri, "$dirPath/${child.name}", depth + 1, out, progress)
            } else {
                val ext = child.name.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED) {
                    out += Found(
                        uri = child.uri,
                        name = child.name,
                        relativePath = dirPath,
                        size = child.size,
                    )
                    if (out.size % 25 == 0) progress()
                }
            }
        }
    }

    private data class Child(
        val uri: Uri,
        val name: String,
        val isDir: Boolean,
        val size: Long,
    )

    private fun childrenOf(context: Context, dirUri: Uri): List<Child>? {
        val docId = runCatching { DocumentsContract.getDocumentId(dirUri) }.getOrNull() ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, docId)
        val result = ArrayList<Child>(32)
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    result += Child(
                        uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, id),
                        name = name,
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = size,
                    )
                }
            }
        }
        return result
    }

    private fun queryName(context: Context, docUri: Uri): String {
        runCatching {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0) ?: "文件夹"
            }
        }
        return "文件夹"
    }
}
