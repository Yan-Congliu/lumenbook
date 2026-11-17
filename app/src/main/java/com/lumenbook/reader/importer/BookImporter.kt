package com.lumenbook.reader.importer

import com.lumenbook.reader.data.BookFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * 只负责「看文件说话」：判断格式、把书名/作者/封面从文件里抠出来。
 * 复制文件、入库在 LibraryRepository 里做。
 */
object BookImporter {

    data class Meta(val title: String? = null, val author: String? = null, val cover: ByteArray? = null)

    private val EXT_MAP = mapOf(
        "epub" to BookFormat.EPUB,
        "pdf" to BookFormat.PDF,
        "mobi" to BookFormat.MOBI,
        "azw3" to BookFormat.AZW3,
        "azw" to BookFormat.AZW3,
        "fb2" to BookFormat.FB2,
        "cbz" to BookFormat.CBZ,
        "txt" to BookFormat.TXT,
        "text" to BookFormat.TXT,
    )

    /** 先看扩展名，再看文件头（有些下载来的文件扩展名是乱的）。 */
    fun detect(displayName: String, header: ByteArray): BookFormat {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        EXT_MAP[ext]?.let { return it }

        val head = header.copyOf(minOf(header.size, 64))
        val magic = String(head, Charsets.ISO_8859_1)
        return when {
            magic.startsWith("PK\u0003\u0004") -> BookFormat.EPUB
            magic.startsWith("%PDF") -> BookFormat.PDF
            magic.contains("BOOKMOBI") -> BookFormat.MOBI
            magic.startsWith("<?xml") -> BookFormat.FB2
            else -> BookFormat.UNKNOWN
        }
    }

    fun sniff(file: File, format: BookFormat): Meta = runCatching {
        when (format) {
            BookFormat.EPUB -> sniffEpub(file)
            BookFormat.CBZ -> sniffCbz(file)
            BookFormat.FB2 -> sniffFb2(file)
            BookFormat.TXT -> sniffTxt(file)
            else -> Meta()
        }
    }.getOrDefault(Meta())

    // ---------- EPUB ----------

    private fun sniffEpub(file: File): Meta {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return Meta()
            val containerXml = zip.getInputStream(container).use { it.readBytes().decodeToString() }
            val opfPath = Regex("""full-path\s*=\s*"([^"]+)"""")
                .find(containerXml)?.groupValues?.get(1) ?: return Meta()

            val opfEntry = zip.getEntry(opfPath) ?: return Meta()
            val opf = zip.getInputStream(opfEntry).use { it.readBytes().decodeToString() }

            val title = tagText(opf, "dc:title")?.takeIf { it.isNotBlank() }
            val author = tagText(opf, "dc:creator")?.takeIf { it.isNotBlank() }

            val coverHref = findCoverHref(opf)
            val cover = coverHref?.let { href ->
                val base = opfPath.substringBeforeLast('/', "")
                val path = normalizeZipPath(if (base.isEmpty()) href else "$base/$href")
                zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
            }
            return Meta(title = title?.trim(), author = author?.trim(), cover = cover)
        }
    }

    // ---------- CBZ（漫画） ----------

    /**
     * 漫画没有元数据，书名就用文件名；封面取包内按名字排在最前面的那张图。
     * 太大的（正常不会）直接放弃，免得把几 MB 的图塞进书库。
     */
    private fun sniffCbz(file: File): Meta {
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXT }
                .minByOrNull { it.name }
                ?: return Meta()
            if (entry.size > MAX_CBZ_COVER) return Meta()
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return Meta(cover = bytes)
        }
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")
    private const val MAX_CBZ_COVER = 6L * 1024 * 1024

    private fun tagText(xml: String, tag: String): String? =
        Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xml)?.groupValues?.get(1)?.let(::stripTags)

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .trim()

    /** EPUB3 用 properties="cover-image"，EPUB2 用 <meta name="cover" content="id">。 */
    private fun findCoverHref(opf: String): String? {
        val items = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).map { it.value }.toList()

        items.firstOrNull { it.contains("cover-image") }
            ?.let { attr(it, "href") }
            ?.let { return it }

        val coverId = Regex("""<meta[^>]*name\s*=\s*"cover"[^>]*content\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)
            ?: return null
        return items.firstOrNull { attr(it, "id") == coverId }?.let { attr(it, "href") }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    private fun normalizeZipPath(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }

    // ---------- FB2 ----------

    private fun sniffFb2(file: File): Meta {
        val head = file.inputStream().use { it.readAtMost(96 * 1024) }
        val text = head.decodeToString()
        val title = Regex("<book-title>(.*?)</book-title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.get(1)?.let(::stripTags)
        val author = Regex("<author>(.*?)</author>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.get(1)?.let { block ->
                val first = Regex("<first-name>(.*?)</first-name>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
                val last = Regex("<last-name>(.*?)</last-name>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
                listOfNotNull(first, last).joinToString(" ").ifBlank { null }
            }
        return Meta(title = title, author = author)
    }

    // ---------- TXT ----------

    private fun sniffTxt(file: File): Meta {
        val head = file.inputStream().use { it.readAtMost(16 * 1024) }
        val text = decodeText(head)
        val firstLine = text.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .firstOrNull { it.isNotEmpty() }
            ?: return Meta()
        val title = firstLine.take(40).ifBlank { null }
        return Meta(title = title)
    }

    /** 中文 txt 一半是 GBK，先试严格 UTF-8，失败再按 GB18030 解。 */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            runCatching { String(bytes, Charset.forName("GB18030")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
        }
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val n = read(buffer, total, limit - total)
            if (n <= 0) break
            total += n
        }
        return buffer.copyOf(total)
    }

    /** 有些调用方只有流，没有文件。 */
    fun sniffStream(bytes: ByteArray, format: BookFormat, temp: File): Meta =
        if (format == BookFormat.EPUB) {
            temp.writeBytes(bytes)
            runCatching { sniffEpub(temp) }.getOrDefault(Meta())
        } else {
            Meta()
        }

    @Suppress("unused")
    private fun unused() = ByteArrayInputStream(ByteArray(0))
}
