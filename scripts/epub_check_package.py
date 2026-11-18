# -*- coding: utf-8 -*-
"""校验 epub_font_inject.py 的输出：mimetype 位置、display-options、CSS 尾部、HTML 良构"""
import sys
import zipfile
import xml.etree.ElementTree as ET

p = sys.argv[1]
z = zipfile.ZipFile(p)
n = z.namelist()
print("mimetype first:", n[0] == "mimetype", "| stored:",
      z.getinfo("mimetype").compress_type == zipfile.ZIP_STORED)
print("entries:", len(n))
print("display-options:", "META-INF/com.apple.ibooks.display-options.xml" in n)
print("zip test:", z.testzip())
css_name = [x for x in n if x.endswith(".css")][0]
css = z.read(css_name).decode("utf-8")
i = css.find("epub_font_inject.py")
print("css:", css_name, "| injected at", i)
print(css[max(0, i - 30):i + 460])
bad = []
for d in [x for x in n if x.lower().endswith((".html", ".xhtml"))]:
    try:
        ET.fromstring(z.read(d))
    except Exception as e:
        bad.append((d, e))
print("html well-formed:", "OK" if not bad else bad[:3])
