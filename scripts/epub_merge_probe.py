#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断用：把 calibre 自动按大小切开的两个 XHTML 合并成单文档，
用于验证 Apple Books「跨文档翻页字体回退」的假设。

用法: python scripts/epub_merge_probe.py <src.epub> <dst.epub>
"""
import re
import sys
import zipfile


def read(z, name):
    return z.read(name).decode("utf-8")


def body_paras(html):
    """取出 body 里所有 <p ...>...</p> 的完整原文（保留标签）。"""
    body = re.search(r"<body[^>]*>(.*)</body>", html, re.S | re.I).group(1)
    return re.findall(r"<p\b[^>]*>.*?</p>", body, re.S | re.I)


def para_text(p):
    return re.sub(r"<[^>]+>", "", p).strip()


def main(src, dst):
    z = zipfile.ZipFile(src)
    p0 = body_paras(read(z, "index_split_000.html"))
    p1 = body_paras(read(z, "index_split_001.html"))

    # calibre 分割点把「心理学小组」这一段同时留在了两个文件末尾/开头，丢掉重复的那条
    if para_text(p0[-1]) == para_text(p1[1]):
        print("dropping duplicated boundary para:", para_text(p0[-1])[:40])
        p0 = p0[:-1]

    head = read(z, "index_split_000.html")
    head = head[: head.index("<body")]

    merged = (
        head
        + '<body class="calibre">\n'
        + "\n".join(p0 + p1)
        + "\n</body>\n</html>\n"
    )
    print("merged paras:", len(p0) + len(p1), "chars:", len(merged))

    opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uuid_id" version="2.0">
  <metadata xmlns:dcterms="http://purl.org/dc/terms/" xmlns:opf="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:calibre="http://calibre.kovidgoyal.net/2009/metadata">
    <dc:title>中国2185</dc:title>
    <dc:creator opf:role="aut">刘慈欣</dc:creator>
    <dc:language>zh</dc:language>
    <dc:identifier id="uuid_id" opf:scheme="uuid">d95880cd-d17a-48dd-961c-b98b0d8d833c</dc:identifier>
    <dc:identifier opf:scheme="calibre">d95880cd-d17a-48dd-961c-b98b0d8d833c</dc:identifier>
    <meta name="cover" content="cover"/>
    <meta name="calibre:title_sort" content="中国2185"/>
  </metadata>
  <manifest>
    <item href="titlepage.xhtml" id="titlepage" media-type="application/xhtml+xml"/>
    <item href="merged.xhtml" id="body" media-type="application/xhtml+xml"/>
    <item href="toc.ncx" id="ncx" media-type="application/x-dtbncx+xml"/>
    <item href="page_styles.css" id="page_css" media-type="text/css"/>
    <item href="stylesheet.css" id="css" media-type="text/css"/>
    <item href="cover_image.jpg" id="cover" media-type="image/jpeg"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="titlepage"/>
    <itemref idref="body"/>
  </spine>
  <guide>
    <reference href="titlepage.xhtml" title="Title Page" type="cover"/>
  </guide>
</package>
"""

    ncx = """<?xml version='1.0' encoding='utf-8'?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="zho">
  <head>
    <meta content="d95880cd-d17a-48dd-961c-b98b0d8d833c" name="dtb:uid"/>
    <meta content="1" name="dtb:depth"/>
    <meta content="0" name="dtb:totalPageCount"/>
    <meta content="0" name="dtb:maxPageNumber"/>
  </head>
  <docTitle>
    <text>中国2185</text>
  </docTitle>
  <navMap>
    <navPoint id="nav1" playOrder="1">
      <navLabel>
        <text>中国2185</text>
      </navLabel>
      <content src="merged.xhtml"/>
    </navPoint>
  </navMap>
</ncx>
"""

    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as out:
        info = zipfile.ZipInfo("mimetype")
        info.compress_type = zipfile.ZIP_STORED
        out.writestr(info, b"application/epub+zip")
        for name in ("META-INF/container.xml", "titlepage.xhtml",
                     "page_styles.css", "stylesheet.css", "cover_image.jpg"):
            out.writestr(name, z.read(name))
        out.writestr("merged.xhtml", merged)
        out.writestr("toc.ncx", ncx)
        out.writestr("content.opf", opf)
    print("written:", dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
