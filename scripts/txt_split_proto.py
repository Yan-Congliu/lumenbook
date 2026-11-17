#!/usr/bin/env python3
"""TXT → 派生 EPUB 的参考实现（与 app 里 importer/TxtToEpub.kt 对齐）。

作用：在没有真机的情况下，先用 Python 跑通「编码探测 → 拆章 → 打包 EPUB」，
把产出的 EPUB 丢进桌面阅读器验证排版；Kotlin 那边照同一套规则实现。

用法：
  python scripts/txt_split_proto.py book.txt -o dist/book.epub
  python scripts/txt_split_proto.py book.txt --dry-run     # 只看拆出几章
"""
from __future__ import annotations

import argparse
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HEADING = re.compile(
    r"^\s*(第[0-9０-９一二三四五六七八九十百千万零〇两]{1,6}[章节回卷部篇幕集])"
    r"(?:[\s:：、.．·\-—]+\S{0,28})?\s*$"
    r"|^\s*(Chapter\s+[0-9IVXLC]+)\b.{0,28}$"
    r"|^\s*(序章|楔子|尾声|后记|番外.{0,10})\s*$",
    re.IGNORECASE,
)
CHUNK_CHARS = 5000


@dataclass
class Chapter:
    title: str | None
    body: str


def decode(data: bytes) -> tuple[str, str]:
    if data[:3] == b"\xef\xbb\xbf":
        return data[3:].decode("utf-8"), "utf-8-sig"
    if data[:2] == b"\xff\xfe":
        return data[2:].decode("utf-16-le"), "utf-16-le"
    if data[:2] == b"\xfe\xff":
        return data[2:].decode("utf-16-be"), "utf-16-be"
    try:
        return data.decode("utf-8"), "utf-8"
    except UnicodeDecodeError:
        return data.decode("gb18030", errors="replace"), "gb18030"


def split(text: str) -> list[Chapter]:
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    chapters: list[Chapter] = []
    title: str | None = None
    body: list[str] = []

    def flush() -> None:
        nonlocal title
        content = "\n".join(body).strip()
        if content:
            # 开头的孤零零一行短文本（书名、作者）不算正文
            is_lone_header = (
                title is None
                and not chapters
                and "\n" not in content
                and len(content) <= 40
            )
            if not is_lone_header:
                chapters.append(Chapter(title if title and title.strip() else None, content))
        body.clear()
        title = None

    for raw in lines:
        line = raw.strip()
        is_heading = bool(line) and len(line) <= 40 and HEADING.match(line)
        if is_heading:
            if body:
                flush()
            title = line
            continue
        body.append(raw.rstrip())

    flush()

    if not chapters:
        return [Chapter(None, text.strip())]

    out: list[Chapter] = []
    for chapter in chapters:
        if len(chapter.body) <= CHUNK_CHARS * 2:
            out.append(chapter)
            continue
        start = 0
        part = 1
        while start < len(chapter.body):
            end = min(start + CHUNK_CHARS, len(chapter.body))
            if end < len(chapter.body):
                br = chapter.body.find("\n", end)
                if end <= br < end + 800:
                    end = br
            label = f"{chapter.title}（{part}）" if chapter.title else f"第 {part} 部分"
            out.append(Chapter(label, chapter.body[start:end]))
            start = end
            part += 1
    return out


def esc(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

STYLE = """html, body { margin: 0; padding: 0; }
body { font-family: serif; }
h2 { font-size: 1.25em; font-weight: 600; margin: 1.2em 0 0.8em; }
p { margin: 0 0 0.85em; text-indent: 2em; line-height: 1.8; }
"""


def chapter_xhtml(chapter: Chapter) -> str:
    paras = "\n".join(
        f"    <p>{esc(p.strip())}</p>" for p in chapter.body.split("\n") if p.strip()
    )
    heading = f"  <h2>{esc(chapter.title)}</h2>\n" if chapter.title else ""
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" lang="zh">
<head>
  <title>{esc(chapter.title or "正文")}</title>
  <link rel="stylesheet" type="text/css" href="../style.css"/>
</head>
<body>
{heading}{paras}
</body>
</html>"""


def build_epub(path: Path, title: str, author: str, chapters: list[Chapter]) -> None:
    manifest, spine, nav = [], [], []
    for i, chapter in enumerate(chapters):
        name = f"ch{i + 1:04d}.xhtml"
        manifest.append(f'    <item id="c{i}" href="text/{name}" media-type="application/xhtml+xml"/>')
        spine.append(f'    <itemref idref="c{i}"/>')
        nav.append(f'      <li><a href="text/{name}">{esc(chapter.title or f"第 {i + 1} 节")}</a></li>')

    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:lumenbook:txt:proto</dc:identifier>
    <dc:title>{esc(title)}</dc:title>
    <dc:creator>{esc(author)}</dc:creator>
    <dc:language>zh</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="style.css" media-type="text/css"/>
{chr(10).join(manifest)}
  </manifest>
  <spine>
{chr(10).join(spine)}
  </spine>
</package>"""

    nav_xhtml = f"""<?xml version="1.0" encoding="utf-8"?>
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
{chr(10).join(nav)}
    </ol>
  </nav>
</body>
</html>"""

    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        info = zipfile.ZipInfo("mimetype", date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED
        zf.writestr(info, "application/epub+zip")
        zf.writestr("META-INF/container.xml", CONTAINER, zipfile.ZIP_DEFLATED)
        zf.writestr("OEBPS/content.opf", opf, zipfile.ZIP_DEFLATED)
        zf.writestr("OEBPS/nav.xhtml", nav_xhtml, zipfile.ZIP_DEFLATED)
        zf.writestr("OEBPS/style.css", STYLE, zipfile.ZIP_DEFLATED)
        for i, chapter in enumerate(chapters):
            zf.writestr(
                f"OEBPS/text/ch{i + 1:04d}.xhtml",
                chapter_xhtml(chapter),
                zipfile.ZIP_DEFLATED,
            )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("txt")
    ap.add_argument("-o", "--out", default="dist/txt-derived.epub")
    ap.add_argument("--title", default=None)
    ap.add_argument("--author", default="未知")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    source = Path(a.txt)
    data = source.read_bytes()
    text, encoding = decode(data)
    chapters = split(text)
    title = a.title or source.stem

    print(f"编码 {encoding}  字数 {len(text)}  章节 {len(chapters)}")
    for i, chapter in enumerate(chapters[:8]):
        print(f"  {i + 1:>2}. {chapter.title or '(无标题)'}  {len(chapter.body)} 字")
    if len(chapters) > 8:
        print(f"  … 还有 {len(chapters) - 8} 章")

    if a.dry_run:
        return 0

    out = Path(a.out)
    build_epub(out, title, a.author, chapters)
    print(f"✓ {out} ({out.stat().st_size / 1024:.1f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
