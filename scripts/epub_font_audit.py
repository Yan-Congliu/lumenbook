#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""EPUB 字体/分章体检

检查一本 EPUB 里有没有会跟阅读器"用户字体"打架的东西：
  - font-family / @font-face / 内嵌字体文件 / 内联 style / <style> 块
  - 正文被切成几个 XHTML（跨文档翻页是 Apple Books 掉字体的高发点）
  - spine 顺序、每章的标题、是否有 com.apple.ibooks.display-options.xml

用法:
    python scripts/epub_font_audit.py <book.epub> [-o report.md]
"""
import argparse
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

FONT_EXT = (".ttf", ".otf", ".woff", ".woff2", ".ttc")


def qname(tag):
    return tag.split("}")[-1]


def read_text(z, name):
    raw = z.read(name)
    for enc in ("utf-8", "utf-16"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")


def parse_opf(z, opf_path):
    root = ET.fromstring(z.read(opf_path))
    base = os.path.dirname(opf_path)
    items = {}
    spine = []
    toc_id = None
    for el in root.iter():
        t = qname(el.tag)
        if t == "item":
            items[el.get("id")] = {
                "href": el.get("href"),
                "type": el.get("media-type"),
                "path": os.path.normpath(os.path.join(base, el.get("href", ""))).replace("\\", "/"),
            }
        elif t == "spine":
            toc_id = el.get("toc")
            for ref in el:
                if qname(ref.tag) == "itemref":
                    spine.append(ref.get("idref"))
    return root, items, spine, toc_id


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("epub")
    ap.add_argument("-o", "--out", default=None)
    args = ap.parse_args()

    z = zipfile.ZipFile(args.epub)
    names = z.namelist()
    L = []
    a = L.append

    a(f"# EPUB 体检: {os.path.basename(args.epub)}")
    a("")
    a(f"- 包大小: {os.path.getsize(args.epub) / 1024:.0f} KB，条目 {len(names)} 个")
    a(f"- zip 完整性: {'OK' if z.testzip() is None else 'BAD'}")

    # --- container / opf
    cont = ET.fromstring(z.read("META-INF/container.xml"))
    opf_path = None
    for el in cont.iter():
        if qname(el.tag) == "rootfile":
            opf_path = el.get("full-path")
    a(f"- OPF: `{opf_path}`")
    root, items, spine, toc_id = parse_opf(z, opf_path)
    a(f"- EPUB 版本: {root.get('version')}   spine 条目: {len(spine)}")

    # --- 字体相关
    font_files = [n for n in names if n.lower().endswith(FONT_EXT)]
    a(f"- 内嵌字体文件: {len(font_files)}" + (f" -> {font_files}" if font_files else ""))
    display_opt = [n for n in names if n.endswith("com.apple.ibooks.display-options.xml")]
    a(f"- ibooks display-options: {display_opt or '无'}")

    # --- 逐文件扫描
    a("")
    a("## 各文件字体痕迹")
    a("")
    a("| 文件 | 大小KB | link样式表 | font-family | @font-face | 内联style | <style>块 | body class |")
    a("|---|---|---|---|---|---|---|---|")
    scans = {}
    for n in names:
        low = n.lower()
        if not low.endswith((".html", ".xhtml", ".htm", ".css")):
            continue
        t = read_text(z, n)
        fam = len(re.findall(r"font-family", t, re.I))
        face = len(re.findall(r"@font-face", t, re.I))
        inline = len(re.findall(r"style\s*=", t, re.I))
        blocks = len(re.findall(r"<style", t, re.I))
        links = re.findall(r'<link[^>]+href="([^"]+)"', t, re.I)
        body = re.search(r"<body[^>]*>", t, re.I)
        bcls = ""
        if body:
            m = re.search(r'class="([^"]*)"', body.group(0))
            bcls = m.group(1) if m else ""
        scans[n] = t
        a(f"| `{n}` | {len(t) / 1024:.0f} | {', '.join(links) or '-'} | {fam} | {face} | {inline} | {blocks} | {bcls or '-'} |")

    # --- CSS 全文（小文件才打出来）
    a("")
    a("## 样式表内容")
    for n in names:
        if not n.lower().endswith(".css"):
            continue
        t = scans.get(n, "")
        a("")
        a(f"### `{n}` ({len(t)} 字符)")
        a("```css")
        a(t.strip()[:4000])
        a("```")

    # --- spine 文档字数与标题
    a("")
    a("## spine 文档")
    a("")
    a("| # | idref | 文件 | 正文字数 | 首个标题 |")
    a("|---|---|---|---|---|")
    docs = []
    for i, idref in enumerate(spine):
        it = items.get(idref, {})
        path = it.get("path")
        t = scans.get(path, "")
        if not t:
            a(f"| {i} | {idref} | `{path}` | ? | - |")
            continue
        body = re.search(r"<body[^>]*>(.*)</body>", t, re.S | re.I)
        inner = body.group(1) if body else t
        txt = re.sub(r"<[^>]+>", "", inner)
        txt = re.sub(r"\s+", "", txt)
        h = re.findall(r"<h[1-4][^>]*>(.*?)</h[1-4]>", inner, re.S | re.I)
        first_h = re.sub(r"<[^>]+>", "", h[0]).strip()[:28] if h else "-"
        docs.append((path, len(txt)))
        a(f"| {i} | {idref} | `{path}` | {len(txt)} | {first_h} |")

    # --- 目录
    if toc_id and items.get(toc_id):
        ncx = scans.get(items[toc_id]["path"]) or read_text(z, items[toc_id]["path"])
        labels = re.findall(r"<text>(.*?)</text>", ncx, re.S)
        a("")
        a(f"## 目录条目 ({len(labels) - 1} 章)")
        a("")
        for lab in labels[1:]:
            a(f"- {lab.strip()}")

    a("")
    a("## 结论")
    total_fam = sum(len(re.findall(r"font-family", t, re.I)) for t in scans.values())
    total_face = sum(len(re.findall(r"@font-face", t, re.I)) for t in scans.values())
    a("")
    a(f"- font-family 总数 {total_fam}，@font-face 总数 {total_face}，内嵌字体 {len(font_files)} 个")
    a(f"- 正文文档 {len(docs)} 个" + ("（跨文档次数 = 文档数 - 1）" if docs else ""))
    big = [f"{p} {c} 字" for p, c in docs if c > 60000]
    if big:
        a(f"- 超长文档: {', '.join(big)}（单文件上万字在 2GB 设备上更容易被回收重排）")

    text = "\n".join(L)
    if args.out:
        open(args.out, "w", encoding="utf-8").write(text)
        print("written:", args.out)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
