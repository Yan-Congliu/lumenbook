package com.lumenbook.reader.data

/**
 * 一条书签。
 *
 * `location` 的形态跟着格式走：EPUB/FB2/TXT 用 CFI（`epubcfi(/6/6!/4/2,,/8/1:41)`），
 * PDF 用 `page:<页码>`。这样一套表能同时服务两种阅读器。
 */
data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val location: String,
    val chapter: String? = null,
    val createdAt: Long = 0,
) {
    /** 列表里显示的一行字：优先章节名，没有就退化成位置。 */
    val label: String
        get() = chapter?.takeIf { it.isNotBlank() } ?: "位置 ${location.takeLast(12)}"
}
