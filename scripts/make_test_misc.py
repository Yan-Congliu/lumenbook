#!/usr/bin/env python3
"""生成 CBZ / FB2 测试样书。

这两个格式平时不好找样本，真机也没法连着 adb 看，所以干脆自己造：
  python scripts/make_test_misc.py -o dist/
产出 dist/test.cbz（3 页彩色图）和 dist/test.fb2（2 章中文）。

CBZ 用纯 Python 手写 PNG（zlib + struct），不依赖 Pillow。
"""

from __future__ import annotations

import argparse
import io
import struct
import sys
import zipfile
import zlib
from pathlib import Path

# Windows 控制台是 GBK，打印 ✓ 会直接崩
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

WS = Path(__file__).resolve().parent.parent


# ---------- 最小 PNG 编码 ----------

def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def make_png(width: int, height: int, bg: tuple[int, int, int], band: tuple[int, int, int]) -> bytes:
    """纯色底 + 中间一条横带 + 白色页边，够用来分辨「这是第几页」。"""
    rows = []
    for y in range(height):
        margin = 24
        if y < margin or y >= height - margin:
            row = bytes([250, 248, 244]) * width
            rows.append(b"\x00" + row)
            continue
        band_top = height // 2 - height // 12
        band_bottom = height // 2 + height // 12
        color = band if band_top <= y < band_bottom else bg
        row = bytes([250, 248, 244]) * margin + bytes(color) * (width - 2 * margin) + bytes([250, 248, 244]) * margin
        rows.append(b"\x00" + row)
    raw = b"".join(rows)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(raw, 6))
        + _chunk(b"IEND", b"")
    )


PALETTE = [
    ((196, 160, 120), (120, 90, 60)),
    ((150, 170, 190), (70, 100, 130)),
    ((170, 150, 170), (100, 70, 100)),
]


def build_cbz(target: Path, pages: int = 3) -> None:
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zf:
        for i in range(pages):
            bg, band = PALETTE[i % len(PALETTE)]
            png = make_png(600, 900, bg, band)
            zf.writestr(f"page-{i + 1:02d}.png", png)


FB2_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <description>
    <title-info>
      <genre>prose</genre>
      <author><first-name>测试</first-name><last-name>作者</last-name></author>
      <book-title>雪夜读书记（FB2 测试）</book-title>
      <lang>zh</lang>
    </title-info>
  </description>
  <body>
    <title><p>雪夜读书记</p></title>
{sections}
  </body>
</FictionBook>
"""

SECTION = """    <section id="s{n}">
      <title><p>{heading}</p></title>
{paras}
    </section>
"""

PARAS = [
    "雪落下来的声音是听得见的——不是雪本身，而是屋檐、枯枝、以及远处某扇没有关严的窗。",
    "他坐在窗边，手里翻着一本旧书。书页已经泛黄，边角卷起，油墨的味道淡得需要凑近才闻得到。",
    "窗外的雪越下越密，把那盏路灯裹进一团温柔的白里。",
]


def build_fb2(target: Path) -> None:
    sections = []
    for n, heading in enumerate(["第一章 灯下", "第二章 旧友"], start=1):
        paras = "\n".join(f"      <p>{p}</p>" for p in PARAS[: 2 + (n % 2)])
        sections.append(SECTION.format(n=n, heading=heading, paras=paras))
    target.write_text(FB2_TEMPLATE.format(sections="\n".join(sections)), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default=str(WS / "dist"))
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    cbz = out / "test.cbz"
    fb2 = out / "test.fb2"
    build_cbz(cbz)
    build_fb2(fb2)
    for f in (cbz, fb2):
        print(f"✓ {f}  ({f.stat().st_size / 1024:.1f} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
