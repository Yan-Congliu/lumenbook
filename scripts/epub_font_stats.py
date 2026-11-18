# -*- coding: utf-8 -*-
"""一本书一行：分几章、有没有自己的字体声明、适合哪种改法

用法: python scripts/epub_font_stats.py 文件夹或epub ...
"""
import glob
import os
import re
import sys
import zipfile


def stats(p):
    z = zipfile.ZipFile(p)
    n = z.namelist()
    docs = [x for x in n if x.lower().endswith((".html", ".xhtml", ".htm"))]
    css = [x for x in n if x.lower().endswith(".css")]
    fonts = [x for x in n if x.lower().endswith((".ttf", ".otf", ".woff", ".woff2", ".ttc"))]
    fam = face = inline = 0
    fixed = False
    for x in css:
        t = z.read(x).decode("utf-8", "replace")
        fam += len(re.findall(r"font-family", t, re.I))
        face += len(re.findall(r"@font-face", t, re.I))
    for x in docs:
        t = z.read(x).decode("utf-8", "replace")
        inline += len(re.findall(r'style="[^"]*font-family', t, re.I))
    for x in n:
        if x.lower().endswith(".opf"):
            if re.search(r'property="rendition:layout"[^>]*>\s*pre-paginated', z.read(x).decode("utf-8", "replace")):
                fixed = True
    injected = any("LBFONT-INJECT" in z.read(x).decode("utf-8", "replace")
                   for x in n if x.lower().endswith(".css"))
    return dict(docs=len(docs), css=len(css), fam=fam, face=face, fonts=len(fonts),
                inline=inline, fixed=fixed, injected=injected,
                kb=round(os.path.getsize(p) / 1024))


def main():
    targets = []
    for a in sys.argv[1:]:
        targets += glob.glob(os.path.join(a, "*.epub")) if os.path.isdir(a) else [a]
    print("%-46s %5s %4s %6s %6s %6s %8s %5s" % ("书", "正文", "CSS", "字体声明", "@font-face", "内嵌字体", "内联字体", "已注入"))
    for p in sorted(targets):
        try:
            s = stats(p)
        except Exception as e:
            print("%-46s 读取失败 %s" % (os.path.basename(p)[:44], e))
            continue
        note = "固定版式" if s["fixed"] else ""
        print("%-46s %5d %4d %6d %6d %6d %8d %5s %s" % (
            os.path.basename(p)[:44], s["docs"], s["css"], s["fam"], s["face"],
            s["fonts"], s["inline"], "是" if s["injected"] else "否", note))


if __name__ == "__main__":
    main()
