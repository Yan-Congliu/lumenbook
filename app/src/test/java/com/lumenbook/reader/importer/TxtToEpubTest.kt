package com.lumenbook.reader.importer

import com.lumenbook.reader.data.Book
import com.lumenbook.reader.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TxtToEpub 的纯逻辑单测：编码探测与拆章规则。
 * 这些规则跟 scripts/txt_split_proto.py 是同一套，改一边记得改另一边。
 */
class TxtToEpubTest {

    private val sample = """
        雪夜读书记

        第一章 灯下

        雪落下来的声音是听得见的——不是雪本身。

        他坐在窗边，手里翻着一本旧书。

        第二章 旧友

        来人站着没动，过了一会儿才说：“明天还要早起。”

        “我知道。”

        第三章 长夜

        天快亮的时候，雪停了。
    """.trimIndent()

    @Test
    fun `拆出三个章节并跳过开头的书名`() {
        val chapters = TxtToEpub.split(sample)
        assertEquals(3, chapters.size)
        assertEquals("第一章 灯下", chapters[0].title)
        assertEquals("第二章 旧友", chapters[1].title)
        assertEquals("第三章 长夜", chapters[2].title)
        assertTrue(chapters[0].body.contains("雪落下来的声音"))
        assertTrue(!chapters[0].body.contains("雪夜读书记"))
    }

    @Test
    fun `没有章节标题时整篇算一章`() {
        val chapters = TxtToEpub.split("只有一段话。\n\n还有第二段。")
        assertEquals(1, chapters.size)
        assertEquals(null, chapters[0].title)
    }

    @Test
    fun `超长章节按字数切块`() {
        val body = (1..900).joinToString("\n") { "这是第 $it 段，用来把章节撑长一些，让这一章超过一万字的上限。" }
        assertTrue(body.length > 20000)
        val chapters = TxtToEpub.split("第一章 很长\n\n" + body)
        assertTrue(chapters.size > 1)
        assertEquals("第一章 很长（1）", chapters.first().title)
        assertTrue(chapters.all { it.body.length <= 5100 })
    }

    @Test
    fun `编码探测_utf8 与 gb18030 都能解`() {
        val text = "雪夜读书记\n第一章 灯下\n正文内容。"
        val utf8 = TxtToEpub.decode(text.toByteArray(Charsets.UTF_8))
        assertEquals(text, utf8)

        val gb = text.toByteArray(charset("GB18030"))
        val decoded = TxtToEpub.decode(gb)
        assertEquals(text, decoded)
    }

    @Test
    fun `编码探测_utf8 BOM 会被剥掉`() {
        val text = "第一章 灯下"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, TxtToEpub.decode(bytes))
    }

    @Test
    fun `章节标题允许带空格和小标题`() {
        val chapters = TxtToEpub.split("第一章 灯下\n\n正文。\n\n第十二回 落幕\n\n又一段。")
        assertEquals(2, chapters.size)
        assertEquals("第一章 灯下", chapters[0].title)
        assertEquals("第十二回 落幕", chapters[1].title)
    }

    @Test
    fun `打包出的 EPUB 结构正确`() {
        val book = Book(
            id = 7L,
            title = "雪夜读书记",
            author = "佚名",
            format = BookFormat.TXT,
            localPath = "/tmp/book.txt",
            sizeBytes = 1234L,
        )
        val chapters = listOf(
            TxtToEpub.Chapter("第一章 灯下", "雪落下来的声音是听得见的。"),
            TxtToEpub.Chapter("第二章 旧友", "来人站着没动。"),
        )
        val out = java.io.File.createTempFile("lumenbook-test", ".epub")
        out.deleteOnExit()
        TxtToEpub.writeEpub(out, book, chapters)

        java.util.zip.ZipFile(out).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)          // 必须是第一条
            assertEquals(0, first.method)                  // 且必须不压缩（STORED）
            assertEquals("application/epub+zip", zip.getInputStream(first).readBytes().decodeToString())

            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("META-INF/container.xml"))
            assertTrue(names.contains("OEBPS/content.opf"))
            assertTrue(names.contains("OEBPS/nav.xhtml"))
            assertTrue(!names.any { it.startsWith("__MACOSX") })

            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().decodeToString()
            assertTrue(opf.contains("<dc:title>雪夜读书记</dc:title>"))
            assertTrue(opf.contains("text/ch0001.xhtml"))
            assertTrue(opf.contains("text/ch0002.xhtml"))

            val container = zip.getInputStream(zip.getEntry("META-INF/container.xml")).readBytes().decodeToString()
            assertTrue(container.contains("OEBPS/content.opf"))

            val ch1 = zip.getInputStream(zip.getEntry("OEBPS/text/ch0001.xhtml")).readBytes().decodeToString()
            assertTrue(ch1.contains("第一章 灯下"))
            assertTrue(ch1.contains("雪落下来的声音是听得见的。"))
        }
    }
}
