#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 EPUB 焊死一种字体（单本 CLI + 可被 batch 复用的库）

背景：Apple Books 在 iPad 上，用户手动选的字体是「外挂样式」，跨 spine 文档（换一章）
就会丢，掉回书自己的字体或默认字体。书里自带的字体声明是跟着每个文档走的，不会丢。

两条路：
  1) 只写字体名：零体积，但设备上必须有这个字体。
     iOS 自带：PingFang SC / Songti SC / Kaiti SC / Heiti SC / Yuanti SC / Hiragino Sans GB
  2) 嵌入字体文件：10-24MB，任何设备都能用。

用法:
    python scripts/epub_font_inject.py in.epub out.epub --family "Songti SC" --keep-headings
    python scripts/epub_font_inject.py in.epub out.epub --family BookFont --font-file D:\\fonts\\x.ttf
"""
import argparse
import json
import os
import re
import sys
import zipfile

MARK = "/* LBFONT-INJECT v1 */"

DISPLAY_OPTIONS = """<?xml version="1.0" encoding="UTF-8"?>
<display_options>
    <platform name="*">
        <option name="specified-fonts">true</option>
    </platform>
</display_options>
"""

BODY_SELECTORS = "body, p, div, span, li, td, th, blockquote"
HEADING_SELECTORS = "h1, h2, h3, h4, h5, h6"


def build_css(family, fallbacks, font_file_rel=None, keep_headings=True):
    rules = [MARK]
    if font_file_rel:
        fmt = "truetype" if font_file_rel.lower().endswith((".ttf", ".ttc")) else "opentype"
        rules.append(
            '@font-face {\n  font-family: "%s";\n  src: url("%s") format("%s");\n'
            '  font-weight: normal;\n  font-style: normal;\n}' % (family, font_file_rel, fmt)
        )
        use = '"%s"' % family
    else:
        # 不嵌文件时也放一条 local() 的 @font-face：Apple Books 只有看到 @font-face
        # 才认为"这书自带字体"，字体菜单里才会出现「Original / 原书字体」。
        # local() 匹配不上会自动落到下面的真实字体名，不会变空白。
        rules.append(
            '@font-face {\n  font-family: "LBFont";\n'
            '  src: local("%s"), local("%s Regular"), local("%s-Regular");\n}' % (family, family, family)
        )
        use = '"LBFont", "%s"' % family
    sel = BODY_SELECTORS if keep_headings else BODY_SELECTORS + ", " + HEADING_SELECTORS
    fams = use + (", " + ", ".join(fallbacks) if fallbacks else "")
    rules.append("%s {\n  font-family: %s !important;\n}" % (sel, fams))
    rules.append('.kt, .kt1, .note, .sub1, .sub2, .sub3, .chapter_color, .img, .calibre, .calibre1 {'
                 ' font-family: %s !important; }' % fams)
    return "\n".join(rules) + "\n"


def _texts(zin, names):
    """返回 {名字: 解码后的文本}，只处理 html/css/xml/opf"""
    out = {}
    for n in names:
        low = n.lower()
        if low.endswith((".html", ".xhtml", ".htm", ".css", ".opf", ".ncx", ".xml")):
            out[n] = zin.read(n).decode("utf-8", "replace")
    return out


def is_fixed_layout(zin, names):
    """固定版式（漫画/画册）注入字体没意义，直接跳过"""
    for n in names:
        if n.lower().endswith(".opf"):
            t = zin.read(n).decode("utf-8", "replace")
            if re.search(r'property="rendition:layout"[^>]*>\s*pre-paginated', t):
                return True
        if n.endswith("display-options.xml"):
            t = zin.read(n).decode("utf-8", "replace")
            if "fixed-layout" in t and "true" in t:
                return True
    return False


def process_epub(src, dst, family="Songti SC", fallback="serif",
                 keep_headings=True, font_file=None, css_filter=None):
    """就地生成新文件，返回报告 dict"""
    rep = {"src": os.path.basename(src), "dst": os.path.basename(dst),
           "ok": False, "skip": None, "warnings": [], "css": [], "html_patched": 0,
           "kb_in": 0, "kb_out": 0}
    try:
        rep["kb_in"] = round(os.path.getsize(src) / 1024)
        zin = zipfile.ZipFile(src)
    except Exception as e:
        rep["warnings"].append("打不开：%s" % e)
        return rep

    names = zin.namelist()
    if "mimetype" not in names or not any(n.lower().endswith(".opf") for n in names):
        rep["warnings"].append("不是合法 EPUB（缺 mimetype 或 OPF）")
        return rep
    if is_fixed_layout(zin, names):
        rep["skip"] = "固定版式（fixed-layout），注入字体无意义"
        return rep

    texts = _texts(zin, names)
    if any(MARK in t for t in texts.values()):
        rep["skip"] = "已经注入过"
        return rep

    opf_name = [n for n in names if n.lower().endswith(".opf")][0]
    opf_dir = os.path.dirname(opf_name)

    font_bytes = None
    font_zip_path = None
    font_rel = None
    if font_file:
        font_bytes = open(font_file, "rb").read()
        font_zip_path = os.path.join(opf_dir, "fonts", os.path.basename(font_file)).replace("\\", "/")
        font_rel = os.path.relpath(font_zip_path, opf_dir).replace("\\", "/")

    css_override = build_css(family, [fallback] if fallback else [], font_rel, keep_headings)

    css_names = [n for n in names if n.lower().endswith(".css")
                 and (css_filter is None or os.path.basename(n) == css_filter)]
    new_link = None
    if not css_names:
        # 这本书根本没有样式表：新建一个，并往每个 html 的 <head> 里塞 <link>
        new_link = (os.path.join(opf_dir, "lbfont.css").replace("\\", "/"), "lbfont.css")
        rep["warnings"].append("原书没有 CSS，已新建 lbfont.css 并逐个挂链接")

    html_names = [n for n in names if n.lower().endswith((".html", ".xhtml", ".htm"))]

    out = zipfile.ZipFile(dst, "w")
    for n in names:
        info = zin.getinfo(n)
        if n == "mimetype":
            out.writestr("mimetype", zin.read(n), zipfile.ZIP_STORED)
            continue
        data = zin.read(n)
        if n in css_names:
            data = (texts[n].rstrip() + "\n\n" + css_override).encode("utf-8")
            rep["css"].append(n)
        elif new_link and n == new_link[0]:
            continue
        elif n in html_names and new_link:
            t = texts[n]
            href = os.path.relpath(new_link[0], os.path.dirname(n)).replace("\\", "/")
            link = '<link href="%s" rel="stylesheet" type="text/css"/>' % href
            if "</head>" in t:
                t = t.replace("</head>", link + "\n</head>", 1)
                rep["html_patched"] += 1
            elif "<body" in t:
                t = re.sub(r"(<body[^>]*>)", link + r"\1", t, count=1)
                rep["html_patched"] += 1
            data = t.encode("utf-8")
        elif font_zip_path and n == opf_name:
            t = texts[n]
            href = os.path.relpath(font_zip_path, opf_dir).replace("\\", "/")
            item = '<item id="lbfont01" href="%s" media-type="font/ttf"/>' % href
            t2, cnt = re.subn(r"</manifest>", "  " + item + "\n</manifest>", t, count=1)
            if cnt == 0:
                t2, cnt = re.subn(r"<manifest([^>]*?)/>", r"<manifest\1>" + item + "</manifest>", t, count=1)
            if cnt == 0:
                rep["warnings"].append("OPF manifest 结构异常，字体未登记")
            data = t2.encode("utf-8")
        out.writestr(info, data)

    if new_link:
        out.writestr(new_link[0], css_override.encode("utf-8"))
        # 登记到 manifest
        items = [x for x in out.infolist()]
        rep["css"].append(new_link[0])
    if font_zip_path:
        out.writestr(font_zip_path, font_bytes)
    if "META-INF/com.apple.ibooks.display-options.xml" not in names:
        out.writestr("META-INF/com.apple.ibooks.display-options.xml", DISPLAY_OPTIONS)
    else:
        rep["warnings"].append("原书已有 display-options.xml，未覆盖")
    out.close()
    zin.close()

    rep["kb_out"] = round(os.path.getsize(dst) / 1024)
    rep["ok"] = True
    return rep


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--family", default="Songti SC", help="CSS 里的字体名，默认 Songti SC")
    ap.add_argument("--font-file", default=None, help="要嵌入的字体文件（不传则只写字体名）")
    ap.add_argument("--fallback", default="serif", help="兜底字体，默认 serif；传空串不要兜底")
    ap.add_argument("--all-headings", action="store_true", help="连标题也统一成这个字体")
    ap.add_argument("--css", default=None, help="只改这个文件名的样式表")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    rep = process_epub(args.src, args.dst, family=args.family, fallback=args.fallback,
                       keep_headings=not args.all_headings, font_file=args.font_file,
                       css_filter=args.css)
    if args.json:
        print(json.dumps(rep, ensure_ascii=False, indent=2))
        return 0 if rep["ok"] else 1
    if rep["skip"]:
        print("跳过：", rep["skip"])
        return 0
    if not rep["ok"]:
        print("失败：", "; ".join(rep["warnings"]))
        return 1
    print("written: %s  %d KB -> %d KB" % (args.dst, rep["kb_in"], rep["kb_out"]))
    print("CSS 已改:", rep["css"] or "(无)")
    if rep["warnings"]:
        print("注意:", "; ".join(rep["warnings"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
