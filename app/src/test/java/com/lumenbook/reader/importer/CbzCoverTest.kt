package com.lumenbook.reader.importer

import com.lumenbook.reader.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 漫画（CBZ）没有元数据，全靠包里的图片：封面取按文件名排序最靠前的那张图。
 * 这里用假的 PNG 字节（不需要真的是图，只验证选取逻辑）。
 */
class CbzCoverTest {

    private fun tempCbz(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("lumen-cbz", ".cbz").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `封面取文件名最靠前的图片`() {
        val cbz = tempCbz(
            "page-02.png" to "SECOND".toByteArray(),
            "page-01.png" to "FIRST".toByteArray(),
            "notes.txt" to "not an image".toByteArray(),
        )

        val meta = BookImporter.sniff(cbz, BookFormat.CBZ)

        assertEquals("FIRST", meta.cover?.decodeToString())
        assertNull(meta.title)
    }

    @Test
    fun `跳过非图片与目录项`() {
        val cbz = tempCbz(
            "META-INF/" to ByteArray(0),
            "readme.txt" to "hello".toByteArray(),
            "001.JPG" to "UPPER".toByteArray(),
        )

        val meta = BookImporter.sniff(cbz, BookFormat.CBZ)

        assertEquals("UPPER", meta.cover?.decodeToString())
    }

    @Test
    fun `包里没有图片时不给封面`() {
        val cbz = tempCbz("readme.txt" to "nothing".toByteArray())
        assertNull(BookImporter.sniff(cbz, BookFormat.CBZ).cover)
    }

    @Test
    fun `损坏的包不抛异常`() {
        val broken = File.createTempFile("lumen-broken", ".cbz").apply {
            writeText("this is definitely not a zip")
            deleteOnExit()
        }
        val meta = BookImporter.sniff(broken, BookFormat.CBZ)
        assertNull(meta.cover)
        assertTrue(meta.title == null)
    }
}
