"""生成一本用于测试阅读内核的最小 EPUB，并把阅读页搭成可在桌面浏览器里打开的临时站点。

用法：python scripts/reader_smoke.py [port]
产物：.tooling/smoke/（index.html + reader.js + vendor/ + book.epub），起 http.server 后用 ego 浏览器打开。
"""

from __future__ import annotations

import http.server
import os
import shutil
import socketserver
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "reader"
OUT = ROOT / ".tooling" / "smoke"

CHAPTERS = [
    (
        "第一章 雪落",
        [
            "雪落下来的声音是听得见的——不是雪本身，而是屋檐、枯枝、以及远处某扇没有关严的窗，"
            "在承受重量时发出的那种极轻的、几乎算得上温柔的响动。",
            "他坐在窗边，手里翻着一本旧书。书页已经泛黄，边角卷起，油墨的味道淡得需要凑近才闻得到。"
            "像这样的冬夜，最适合读一点不着急读完的东西。",
            "楼下有人在烧水，壶盖被顶得嗒嗒作响。水汽顺着楼梯爬上来，混进房间里冷而干燥的空气里，"
            "于是整个屋子都变得有了一点暖意。",
        ],
    ),
    (
        "第二章 灯下",
        [
            "“还没睡？”门口传来一句话，很轻，像是不太确定该不该继续说下去。",
            "“嗯。”他合上书，没有抬头。“把灯开着吧，我想再坐一会儿。”",
            "来人站着没动，过了一会儿才说：“明天还要早起。”",
            "“我知道。”",
        ],
    ),
    (
        "第三章 长夜",
        [
            "窗外的雪越下越密，把那盏路灯裹进一团温柔的白里。书被放回膝上，页码停在原来的地方——"
            "他忽然觉得，这本书不必着急读完，就像这个夜晚也不必着急结束。",
            "很久以后他才明白，那一夜的安静并不是偶然，而是有人替他把时间轻轻地扶住了。",
            "天快亮的时候，雪停了。窗台上积了薄薄一层，被第一缕光一照，像谁在那里放了一张白纸。",
        ],
    ),
]


def build_epub(target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w") as z:
        # mimetype 必须是第一条且不压缩
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", zipfile.ZIP_STORED)
        z.writestr(
            "META-INF/container.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""",
            zipfile.ZIP_DEFLATED,
        )

        manifest = []
        spine = []
        nav_items = []
        for i, (title, paras) in enumerate(CHAPTERS, start=1):
            name = f"ch{i}.xhtml"
            body = "\n".join(f"      <p>{p}</p>" for p in paras)
            z.writestr(
                f"OEBPS/{name}",
                f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
  <head><title>{title}</title></head>
  <body>
    <section epub:type="chapter" xmlns:epub="http://www.idpf.org/2007/ops">
      <h2>{title}</h2>
{body}
    </section>
  </body>
</html>""",
                zipfile.ZIP_DEFLATED,
            )
            manifest.append(f'    <item id="ch{i}" href="{name}" media-type="application/xhtml+xml"/>')
            spine.append(f'    <itemref idref="ch{i}"/>')
            nav_items.append(f'      <li><a href="{name}">{title}</a></li>')

        z.writestr(
            "OEBPS/nav.xhtml",
            f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN">
  <head><title>目录</title></head>
  <body>
    <nav epub:type="toc" id="toc">
      <h1>目录</h1>
      <ol>
{chr(10).join(nav_items)}
      </ol>
    </nav>
  </body>
</html>""",
            zipfile.ZIP_DEFLATED,
        )

        z.writestr(
            "OEBPS/content.opf",
            f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:lumenbook-smoke-0001</dc:identifier>
    <dc:title>雪夜读书记</dc:title>
    <dc:creator>测试作者</dc:creator>
    <dc:language>zh-CN</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
{chr(10).join(manifest)}
  </manifest>
  <spine>
{chr(10).join(spine)}
  </spine>
</package>""",
            zipfile.ZIP_DEFLATED,
        )


def build_site() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)
    for name in ("index.html", "reader.js", "reader.css"):
        shutil.copy2(ASSETS / name, OUT / name)
    shutil.copytree(ASSETS / "vendor", OUT / "vendor")
    build_epub(OUT / "book.epub")


def serve(port: int) -> None:
    handler = lambda *a, **kw: http.server.SimpleHTTPRequestHandler(*a, directory=str(OUT), **kw)
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", port), handler) as httpd:
        print(f"serving {OUT} at http://127.0.0.1:{port}/index.html?book=book.epub", flush=True)
        httpd.serve_forever()


if __name__ == "__main__":
    build_site()
    print("site ready:", OUT)
    if "--serve" in sys.argv:
        serve(int(os.environ.get("PORT", "8899")))
