package com.lumenbook.reader.importer

import com.lumenbook.reader.data.Book
import com.lumenbook.reader.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 写回原文件（EPUB 重打包）的行为验证。
 *
 * 重点盯三件事：mimetype 还是第一条且不压缩、OPF 里的书名/作者真的改了、
 * 原来的章节内容一个字都没丢。
 */
class EpubRewriterTest {

    private fun sampleEpub(dir: File): File {
        val target = File(dir, "sample.epub")
        val book = Book(
            id = 1,
            title = "旧名字",
            author = "旧作者",
            format = BookFormat.EPUB,
            localPath = target.absolutePath,
            addedAt = 0L,
        )
        TxtToEpub.writeEpub(
            target,
            book,
            listOf(
                TxtToEpub.Chapter("第一章 灯下", "雪落下来的声音是听得见的。"),
                TxtToEpub.Chapter("第二章 旧友", "来人站着没动。"),
            ),
        )
        return target
    }

    private fun opfText(epub: File): String = ZipFile(epub).use { zip ->
        val container = zip.getEntry("META-INF/container.xml")
        assertNotNull("container.xml 不见了", container)
        val xml = zip.getInputStream(container).use { it.readBytes().decodeToString() }
        val path = Roots.opfFromContainer(xml)
        assertNotNull("container.xml 里找不到 rootfile", path)
        zip.getInputStream(zip.getEntry(path!!)).use { it.readBytes().decodeToString() }
    }

    @Test
    fun `写回书名和作者`() {
        val dir = createTempDir()
        val epub = sampleEpub(dir)

        val result = EpubRewriter.rewrite(epub, title = "新名字", author = "新作者")
        assertTrue(result.message, result.ok)

        val opf = opfText(epub)
        assertTrue("书名没写进去：$opf", opf.contains("<dc:title>新名字</dc:title>"))
        assertTrue("作者没写进去：$opf", opf.contains("<dc:creator>新作者</dc:creator>"))
        assertTrue("旧书名还在：$opf", !opf.contains("旧名字"))
    }

    @Test
    fun `写回后 mimetype 仍是第一条且不压缩`() {
        val dir = createTempDir()
        val epub = sampleEpub(dir)
        EpubRewriter.rewrite(epub, title = "新名字", author = "新作者")

        ZipFile(epub).use { zip ->
            val first: ZipEntry = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals("mimetype 必须 STORED", 0, first.method)
            assertEquals(
                "application/epub+zip",
                zip.getInputStream(first).use { it.readBytes().decodeToString() },
            )
        }
    }

    @Test
    fun `写回后章节内容没丢`() {
        val dir = createTempDir()
        val epub = sampleEpub(dir)
        EpubRewriter.rewrite(epub, title = "新名字", author = "新作者")

        val all = ZipFile(epub).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.contains("text/") && it.name.endsWith(".xhtml") }
                .map { zip.getInputStream(it).use { s -> s.readBytes().decodeToString() } }
                .joinToString("")
        }
        assertTrue("正文丢了", all.contains("雪落下来的声音是听得见的"))
        assertTrue("正文丢了", all.contains("来人站着没动"))
    }

    @Test
    fun `换封面会补上 cover 声明和图片条目`() {
        val dir = createTempDir()
        val epub = sampleEpub(dir)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03)

        val result = EpubRewriter.rewrite(epub, title = null, author = null, cover = jpeg, coverExt = "jpg")
        assertTrue(result.message, result.ok)

        val opf = opfText(epub)
        assertTrue("没有 cover-image 声明：$opf", opf.contains("""properties="cover-image""""))
        assertTrue("没有 meta cover：$opf", opf.contains("""name="cover""""))

        val coverBytes = ZipFile(epub).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".jpg") }
                .map { zip.getInputStream(it).use { s -> s.readBytes() } }
                .firstOrNull()
        }
        assertNotNull("封面条目没写进包里", coverBytes)
        assertEquals(jpeg.size, coverBytes!!.size)
    }

    @Test
    fun `写回后 OPF 仍是合法 XML`() {
        val dir = createTempDir()
        val epub = sampleEpub(dir)
        EpubRewriter.rewrite(epub, title = "新名字", author = "新作者", cover = byteArrayOf(1, 2, 3), coverExt = "jpg")

        val opf = opfText(epub)
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // 解析不过会抛异常，测试直接失败
        val doc = factory.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(opf)))
        assertEquals("package", doc.documentElement.localName)
    }

    @Test
    fun `多余的空作者会删掉 dc_creator`() {
        val dir = createTempDir()
        val epub = sampleEpub(dir)
        EpubRewriter.rewrite(epub, title = null, author = "")

        val opf = opfText(epub)
        assertTrue("作者应该被清空：$opf", !opf.contains("<dc:creator"))
    }
}
