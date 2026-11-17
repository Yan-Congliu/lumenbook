package com.lumenbook.reader.data

/** 支持的电子书格式。UNKNOWN 用于兜底（例如没有扩展名的文件）。 */
enum class BookFormat(val label: String) {
    EPUB("EPUB"),
    PDF("PDF"),
    MOBI("MOBI"),
    AZW3("AZW3"),
    FB2("FB2"),
    CBZ("CBZ"),
    TXT("TXT"),
    UNKNOWN("未知"),
}

/** 书库里的一本书。localPath 指向应用私有副本，sourceUri 是导入时的 SAF 地址（可能为空）。 */
data class Book(
    val id: Long = 0L,
    val title: String,
    val author: String? = null,
    val format: BookFormat = BookFormat.UNKNOWN,
    val localPath: String,
    val sourceUri: String? = null,
    val coverPath: String? = null,
    val sizeBytes: Long = 0L,
    val addedAt: Long = 0L,
    val lastOpenedAt: Long? = null,
    val progress: Float = 0f,
    val finished: Boolean = false,
)

/** 阅读偏好：跟随系统 / 浅色 / 深色。 */
enum class Appearance { SYSTEM, LIGHT, DARK }
