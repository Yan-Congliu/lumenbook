package com.lumenbook.reader.importer

import com.lumenbook.reader.data.Book
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * TXT → 派生 EPUB。
 *
 * 安卓上没有现成的「按字数分页」引擎，硬排版会做成一页到底的长条；所以把 TXT 拆成章节，
 * 打包成一个最小 EPUB（EPUB3，nav 目录），复用阅读页那套 foliate 分页引擎。派生文件缓存进
 * `filesDir/derived/<bookId>.epub`，原书更新（mtime 变了）才会重新生成。
 *
 * 只用 java.nio + java.util.zip，不引第三方库。
 */
object TxtToEpub {

    private val HEADING = Regex(
        "^\\s*(第[0-9０-９一二三四五六七八九十百千万零〇两]{1,6}[章节回卷部篇幕集])(?:[\\s:：、.．·\\-—]+\\S{0,28})?\\s*$" +
            "|^\\s*(Chapter\\s+[0-9IVXLC]+)\\b.{0,28}$" +
            "|^\\s*(序章|楔子|尾声|后记|番外.{0,10})\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private const val CHUNK_CHARS = 5000

    fun ensure(context: android.content.Context, book: Book): File {
        val source = File(book.localPath)
        val outDir = File(context.filesDir, "derived").apply { mkdirs() }
        val target = File(outDir, "${book.id}.epub")
        if (target.exists() && target.lastModified() >= source.lastModified()) return target

        val text = decode(readBytes(source))
        val chapters = split(text)
        writeEpub(target, book, chapters)
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    private fun readBytes(file: File): ByteArray = file.readBytes()

    /** BOM → 严格 UTF-8 → GB18030（中文老书的常见编码）。 */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val strictUtf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (strictUtf8 != null) return strictUtf8
        val gb = runCatching { Charset.forName("GB18030") }.getOrNull() ?: Charsets.UTF_8
        return String(bytes, gb)
    }

    /** 按「第 X 章」这类行拆章；一行都认不出来就按字数切块。 */
    fun split(text: String): List<Chapter> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val chapters = mutableListOf<Chapter>()
        var title: String? = null
        val body = StringBuilder()

        fun flush() {
            val content = body.toString().trim()
            if (content.isNotEmpty()) {
                // 开头的孤零零一行短文本（书名、作者署名）不算正文
                val loneHeader = title == null && chapters.isEmpty() && !content.contains('\n') && content.length <= 40
                if (!loneHeader) {
                    chapters += Chapter(title?.takeIf { it.isNotBlank() }, content)
                }
            }
            body.setLength(0)
            title = null
        }

        for (raw in lines) {
            val line = raw.trim()
            val isHeading = line.isNotEmpty() && line.length <= 40 && HEADING.matches(line)
            if (isHeading) {
                if (body.isNotEmpty()) flush()
                title = line
                continue
            }
            body.append(raw.trimEnd()).append('\n')
        }
        flush()

        if (chapters.isEmpty()) return listOf(Chapter(null, text.trim()))
        // 太长的章再按段落切块，避免一章几万字时翻页内存吃紧
        val chunked = mutableListOf<Chapter>()
        for (chapter in chapters) {
            if (chapter.body.length <= CHUNK_CHARS * 2) {
                chunked += chapter
                continue
            }
            var start = 0
            var part = 1
            while (start < chapter.body.length) {
                var end = (start + CHUNK_CHARS).coerceAtMost(chapter.body.length)
                if (end < chapter.body.length) {
                    val nextBreak = chapter.body.indexOf('\n', end)
                    if (nextBreak in end until (end + 800)) end = nextBreak
                }
                val piece = chapter.body.substring(start, end)
                val label = chapter.title?.let { "$it（$part）" } ?: "第 $part 部分"
                chunked += Chapter(label, piece)
                start = end
                part++
            }
        }
        return chunked
    }

    data class Chapter(val title: String?, val body: String)

    // ---- EPUB 打包 ----

    internal fun writeEpub(target: File, book: Book, chapters: List<Chapter>) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        ZipOutputStream(FileOutputStream(tmp).buffered()).use { zip ->
            // mimetype 必须是第一条、且不压缩
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
                time = 0L
            }
            zip.putNextEntry(mimeEntry)
            zip.write(mime)
            zip.closeEntry()
            zip.setComment(null)

            put(zip, "META-INF/container.xml", containerXml())
            put(zip, "OEBPS/content.opf", opf(book, chapters))
            put(zip, "OEBPS/nav.xhtml", nav(chapters))
            put(zip, "OEBPS/style.css", STYLE)
            chapters.forEachIndexed { index, chapter ->
                put(zip, "OEBPS/text/ch${(index + 1).toString().padStart(4, '0')}.xhtml", chapterXhtml(chapter))
            }
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun containerXml(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun opf(book: Book, chapters: List<Chapter>): String {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        chapters.forEachIndexed { index, _ ->
            val name = "ch${(index + 1).toString().padStart(4, '0')}.xhtml"
            manifest.append("""    <item id="c$index" href="text/$name" media-type="application/xhtml+xml"/>""")
            manifest.append('\n')
            spine.append("""    <itemref idref="c$index"/>""")
            spine.append('\n')
        }
        val identifier = "urn:lumenbook:txt:${book.id}:${book.sizeBytes}"
        return """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">$identifier</dc:identifier>
    <dc:title>${escape(book.title)}</dc:title>
    <dc:creator>${escape(book.author ?: "未知")}</dc:creator>
    <dc:language>zh</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="style.css" media-type="text/css"/>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>"""
    }

    private fun nav(chapters: List<Chapter>): String {
        val items = StringBuilder()
        chapters.forEachIndexed { index, chapter ->
            val name = "text/ch${(index + 1).toString().padStart(4, '0')}.xhtml"
            val label = chapter.title ?: "第 ${index + 1} 节"
            items.append("""      <li><a href="$name">${escape(label)}</a></li>""").append('\n')
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh">
<head>
  <title>目录</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>目录</h1>
    <ol>
$items    </ol>
  </nav>
</body>
</html>"""
    }

    private fun chapterXhtml(chapter: Chapter): String {
        val paragraphs = chapter.body
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "    <p>${escape(it)}</p>" }
        val heading = chapter.title?.let { "  <h2>${escape(it)}</h2>\n" } ?: ""
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" lang="zh">
<head>
  <title>${escape(chapter.title ?: "正文")}</title>
  <link rel="stylesheet" type="text/css" href="../style.css"/>
</head>
<body>
$heading$paragraphs
</body>
</html>"""
    }

    private fun escape(value: String): String = buildString(value.length + 16) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private const val STYLE = """html, body { margin: 0; padding: 0; }
body { font-family: serif; }
h2 { font-size: 1.25em; font-weight: 600; margin: 1.2em 0 0.8em; }
p { margin: 0 0 0.85em; text-indent: 2em; line-height: 1.8; }
"""
}
