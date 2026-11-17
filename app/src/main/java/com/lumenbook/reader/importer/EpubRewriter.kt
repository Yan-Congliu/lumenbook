package com.lumenbook.reader.importer

import java.io.File
import java.net.URLDecoder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 把改过的书名 / 作者 / 封面写回 EPUB 文件本身。
 *
 * 做法是「重打包」而不是原地改：EPUB 规范要求 `mimetype` 必须是压缩包里的第一个条目且不压缩，
 * 任何工具重写 zip 都可能打乱这个顺序，所以这里自己写一遍。
 *
 * 只替换两处内容——OPF 里的 `dc:title` / `dc:creator`，以及封面图片条目；其余条目原样搬过去。
 */
object EpubRewriter {

    data class Result(val ok: Boolean, val message: String)

    /**
     * @param source 原始 EPUB
     * @param title  新书名，null 表示不动
     * @param author 新作者，null 表示不动（空串表示清空）
     * @param cover  新封面字节，null 表示不动
     * @param coverExt 封面扩展名（不带点），如 "jpg" / "png"
     */
    fun rewrite(
        source: File,
        title: String?,
        author: String?,
        cover: ByteArray? = null,
        coverExt: String = "jpg",
    ): Result {
        if (!source.isFile) return Result(false, "找不到原文件")
        val temp = File(source.parentFile, source.name + ".rewrite.tmp")
        if (temp.exists()) temp.delete()

        ZipFile(source).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?: return Result(false, "这本 EPUB 缺少 META-INF/container.xml")
            val opfPath = zip.getInputStream(container).use { it.readBytes().decodeToString() }
                .let { Roots.opfFromContainer(it) }
                ?: return Result(false, "这本 EPUB 的 container.xml 里没有 rootfile")
            val opfEntry = zip.getEntry(opfPath) ?: return Result(false, "找不到 OPF 文件：$opfPath")
            val opf = zip.getInputStream(opfEntry).use { it.readBytes().decodeToString() }

            // OPF 里 href 相对于 OPF 所在目录
            val opfDir = opfPath.substringBeforeLast('/', "")
            val coverHref = Roots.coverHref(opf)
            val coverEntryName = coverHref?.let { zipName(opfDir, it) }
            val hasCoverEntry = coverEntryName != null && zip.getEntry(coverEntryName) != null

            val newOpf = Roots.applyMeta(opf, title, author, cover != null, coverEntryName, coverExt, opfDir)

            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                // 1) mimetype 必须第一个、且不压缩
                val mime = "application/epub+zip".toByteArray()
                val mimeEntry = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                }
                out.putNextEntry(mimeEntry)
                out.write(mime)
                out.closeEntry()

                // 2) 其余条目，跳过被替换的
                zip.entries().asSequence()
                    .filter { it.name != "mimetype" }
                    .forEach { entry ->
                        val replacedOpf = entry.name == opfPath
                        val replacedCover = cover != null && hasCoverEntry && entry.name == coverEntryName
                        if (replacedOpf || replacedCover) return@forEach
                        copyEntry(zip, entry, out)
                    }

                // 3) 写新的 OPF
                writeEntry(out, opfPath, newOpf.toByteArray())

                // 4) 写新封面（有封面条目就覆盖它，没有就新建一条）
                if (cover != null) {
                    val target = coverEntryName ?: zipName(opfDir, "images/lumen-cover.$coverExt")
                    writeEntry(out, target, cover)
                }
            }
        }

        return if (temp.renameTo(source)) {
            Result(true, "已写回原文件")
        } else {
            // renameTo 在部分文件系统上会失败（目标存在），退化成「删了再挪」
            val ok = runCatching {
                source.delete()
                temp.renameTo(source)
            }.getOrDefault(false)
            if (ok) Result(true, "已写回原文件")
            else {
                temp.delete()
                Result(false, "写回时无法替换原文件")
            }
        }
    }

    /** 从压缩包里读出一张封面图，用于「换封面」的预览。 */
    fun readCover(epub: File): ByteArray? = runCatching {
        ZipFile(epub).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return null
            val opfPath = Roots.opfFromContainer(
                zip.getInputStream(container).use { it.readBytes().decodeToString() },
            ) ?: return null
            val opf = zip.getEntry(opfPath)?.let { zip.getInputStream(it).use { s -> s.readBytes().decodeToString() } }
                ?: return null
            val href = Roots.coverHref(opf) ?: return null
            val name = zipName(opfPath.substringBeforeLast('/', ""), href)
            zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } }
        }
    }.getOrNull()

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, out: ZipOutputStream) {
        out.putNextEntry(ZipEntry(entry.name))
        zip.getInputStream(entry).use { it.copyTo(out) }
        out.closeEntry()
    }

    private fun writeEntry(out: ZipOutputStream, name: String, bytes: ByteArray) {
        out.putNextEntry(ZipEntry(name))
        out.write(bytes)
        out.closeEntry()
    }

    /** OPF 目录 + 相对 href → zip 里的条目名（去掉 ./ 与前导斜杠，处理百分号编码）。 */
    private fun zipName(opfDir: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        val clean = decoded.substringBefore('#').removePrefix("./").removePrefix("/")
        return if (opfDir.isEmpty()) clean else "$opfDir/$clean"
    }
}

/** OPF（package document）的读写：只碰 title / creator / 封面这三样。 */
internal object Roots {

    private val ROOTFILE = Regex("""<rootfile[^>]*full-path\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

    fun opfFromContainer(xml: String): String? = ROOTFILE.find(xml)?.groupValues?.get(1)

    fun coverHref(opf: String): String? {
        // 首选 manifest 里声明 properties="cover-image" 的条目
        Regex("""<item\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val tag = m.value
            val props = Regex("""properties\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1).orEmpty()
            if (props.split(' ', '\t').any { it == "cover-image" }) {
                return Regex("""href\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)
            }
        }
        // 退回到 <meta name="cover" content="id"> → 找对应 id 的 manifest 条目
        val coverId = Regex("""<meta[^>]*name\s*=\s*"cover"[^>]*content\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1) ?: return null
        Regex("""<item\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val tag = m.value
            val id = Regex("""id\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            if (id == coverId) {
                return Regex("""href\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            }
        }
        return null
    }

    fun applyMeta(
        opf: String,
        title: String?,
        author: String?,
        withCover: Boolean,
        coverEntryName: String?,
        coverExt: String,
        opfDir: String,
    ): String {
        var out = opf

        if (title != null) {
            out = replaceOrInsert(out, "dc:title", title.escaped(), inMetadata = true)
        }
        if (author != null) {
            out = if (author.isBlank()) {
                Regex("""<dc:creator[^>]*>.*?</dc:creator>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .replace(out, "")
            } else {
                replaceOrInsert(out, "dc:creator", author.escaped(), inMetadata = true)
            }
        }

        if (withCover) {
            val href = coverEntryName?.let { zipToHref(opfDir, it) } ?: "images/lumen-cover.$coverExt"
            val mediaType = when (coverExt.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            val hasEntry = coverEntryName != null &&
                Regex("""href\s*=\s*"${Regex.escape(href)}"""", RegexOption.IGNORE_CASE).containsMatchIn(out)
            if (!hasEntry) {
                // 清单里补一条，并标成 cover-image
                val item = """<item id="lumen-cover" href="$href" media-type="$mediaType" properties="cover-image"/>"""
                out = if (Regex("""</manifest>""", RegexOption.IGNORE_CASE).containsMatchIn(out)) {
                    Regex("""</manifest>""", RegexOption.IGNORE_CASE).replaceFirst(out, "  $item\n</manifest>")
                } else {
                    out.replace("</package>", "<manifest>$item</manifest></package>")
                }
            }
            // meta name="cover" 也补上（老阅读器认这个）
            if (!Regex("""name\s*=\s*"cover"""", RegexOption.IGNORE_CASE).containsMatchIn(out)) {
                out = out.replace(
                    "</metadata>",
                    """  <meta name="cover" content="lumen-cover"/></metadata>""",
                )
            }
        }
        return out
    }

    private fun zipToHref(opfDir: String, entry: String): String =
        if (opfDir.isEmpty()) entry else entry.removePrefix("$opfDir/")

    private fun replaceOrInsert(xml: String, tag: String, value: String, inMetadata: Boolean): String {
        val pattern = Regex("""<$tag(\s[^>]*)?>.*?</$tag>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        if (pattern.containsMatchIn(xml)) {
            return pattern.replaceFirst(xml, "<$tag>${value}</$tag>")
        }
        val anchor = if (inMetadata) "</metadata>" else "</package>"
        return xml.replaceFirst(anchor, "<$tag>$value</$tag></metadata>")
    }

    private fun String.escaped(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
