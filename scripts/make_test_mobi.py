#!/usr/bin/env python3
"""生成一个最小 MOBI6 测试样书（PalmDB + PalmDOC 头 + MOBI 头，不压缩）。

存在意义：MOBI/AZW3 阅读要走 foliate 的 mobi.js，没有真样本就没法验证。
这里手搓一个合法的最小 MOBI（compression=1 不压缩，UTF-8，按 <mbp:pagebreak/> 分节），
丢进桌面阅读器跑一遍，就能确认整条链路（文件头解析 → 分节 → 排版）是通的。

用法：
  python scripts/make_test_mobi.py -o dist/test.mobi
"""
from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

TITLE = "雪夜读书记（MOBI 测试）"

CHAPTERS = [
    (
        "第一章 灯下",
        [
            "雪落下来的声音是听得见的——不是雪本身，而是屋檐、枯枝、以及远处某扇没有关严的窗，在承受重量时发出的那种极轻的、几乎算得上温柔的响动。",
            "他坐在窗边，手里翻着一本旧书。书页已经泛黄，边角卷起，油墨的味道淡得需要凑近才闻得到。",
            "楼下有人在烧水，壶盖被顶得嗒嗒作响。水汽顺着楼梯爬上来，混进房间里冷而干燥的空气里，于是整个屋子都变得有了一点暖意。",
        ],
    ),
    (
        "第二章 旧友",
        [
            "来人站着没动，过了一会儿才说：“明天还要早起。”",
            "“我知道。”",
            "窗外的雪越下越密，把那盏路灯裹进一团温柔的白里。",
        ],
    ),
    (
        "第三章 长夜",
        [
            "天快亮的时候，雪停了。窗台上积了薄薄一层，被第一缕光一照，像谁在那里放了一张白纸。",
            "他合上书，站起来走到窗边。远处的屋顶全都白了，连那些平日里最扎眼的电线，也在雪里变得软和起来。",
        ],
    ),
]

RECORD_SIZE = 4096
MOBI_HEADER_LENGTH = 232


def build_text() -> bytes:
    """拼成 MOBI6 认的 HTML：章节之间用 <mbp:pagebreak/> 分节。"""
    parts = ["<html><head><guide></guide></head><body>"]
    for index, (title, paragraphs) in enumerate(CHAPTERS):
        if index:
            parts.append("<mbp:pagebreak/>")
        parts.append(f"<h2>{title}</h2>")
        for paragraph in paragraphs:
            parts.append(f"<p>{paragraph}</p>")
    parts.append("</body></html>")
    return "".join(parts).encode("utf-8")


def split_records(text: bytes) -> list[bytes]:
    # 按 RECORD_SIZE 切；注意不要把多字节字符切断，否则解码会出乱码
    records: list[bytes] = []
    start = 0
    while start < len(text):
        end = min(start + RECORD_SIZE, len(text))
        while end < len(text) and (text[end] & 0xC0) == 0x80:
            end -= 1
        if end == start:
            end = min(start + RECORD_SIZE, len(text))
        records.append(text[start:end])
        start = end
    return records


def build_mobi() -> bytes:
    text = build_text()
    text_records = split_records(text)
    title_bytes = TITLE.encode("utf-8")

    # ---- 记录 0：PalmDOC 头 16B + MOBI 头 232B + 书名 ----
    palmdoc = struct.pack(
        ">HHIHHHH",
        1,                # compression = 1（不压缩）
        0,                # unused
        len(text),        # text length
        len(text_records),# text record count
        RECORD_SIZE,      # record size
        0,                # encryption
        0,                # unknown
    )

    mobi = bytearray(MOBI_HEADER_LENGTH)
    # 下面所有偏移都是「相对 MOBI 头起点」的，等于 foliate 的记录 0 偏移减 16
    mobi[0:4] = b"MOBI"
    struct.pack_into(">I", mobi, 4, MOBI_HEADER_LENGTH)     # header length
    struct.pack_into(">I", mobi, 8, 2)                      # mobi type = 2 (book)
    struct.pack_into(">I", mobi, 12, 65001)                 # encoding = UTF-8
    struct.pack_into(">I", mobi, 16, 0x1234)                # unique id
    struct.pack_into(">I", mobi, 20, 6)                     # file version = 6 (MOBI6)
    struct.pack_into(">I", mobi, 24, 0)                     # orthographic index
    struct.pack_into(">I", mobi, 28, 0)                     # inflection index
    for offset in (32, 36, 40, 44):                         # 各索引名 0xffffffff = 无
        struct.pack_into(">I", mobi, offset, 0xFFFFFFFF)
    struct.pack_into(">I", mobi, 48, 0)                     # extra index 0
    struct.pack_into(">I", mobi, 52, 0xFFFFFFFF)            # non-book index
    struct.pack_into(">I", mobi, 56, 0)                     # 第一个非书内容记录
    struct.pack_into(">I", mobi, 60, 0)                     # 全名偏移（不用，置 0）
    struct.pack_into(">I", mobi, 64, 0)                     # 全名长度
    struct.pack_into(">I", mobi, 68, 16 + MOBI_HEADER_LENGTH)  # title offset（记录 0 内绝对偏移）
    struct.pack_into(">I", mobi, 72, len(title_bytes))      # title length
    struct.pack_into(">B", mobi, 78, 0)                     # locale region
    struct.pack_into(">B", mobi, 79, 0)                     # locale language
    struct.pack_into(">I", mobi, 92, 0)                     # resource start（没有图片资源）
    struct.pack_into(">I", mobi, 96, 0)                     # huffcdic 偏移
    struct.pack_into(">I", mobi, 100, 0)                    # huffcdic 记录数
    struct.pack_into(">I", mobi, 112, 0)                    # exth flags（0 = 没有 EXTH）
    struct.pack_into(">I", mobi, 224, 0)                    # trailing flags（0 = 没有尾部附加数据）
    struct.pack_into(">I", mobi, 228, 0)                    # indx 记录

    record0 = bytes(palmdoc) + bytes(mobi) + title_bytes

    records = [record0] + text_records

    # ---- PalmDB 文件头 ----
    name = b"LumenTest" + b"\x00" * (32 - len("LumenTest"))
    header_len = 78 + 8 * len(records) + 2
    offsets = []
    cursor = header_len
    for record in records:
        offsets.append(cursor)
        cursor += len(record)

    header = bytearray()
    header += name                                   # 0..32
    header += struct.pack(">HH", 0, 0)               # attributes / version
    header += struct.pack(">III", 0, 0, 0)           # 创建/修改/备份时间
    header += struct.pack(">I", 0)                   # modification number
    header += struct.pack(">II", 0, 0)               # appInfoID / sortInfoID
    header += b"BOOK"                                # type
    header += b"MOBI"                                # creator
    header += struct.pack(">I", 0x5678)              # uniqueIDseed
    header += struct.pack(">I", 0)                   # nextRecordListID
    header += struct.pack(">H", len(records))        # numRecords
    for index, offset in enumerate(offsets):
        header += struct.pack(">I", offset)
        header += struct.pack(">B", 0)               # attributes
        header += struct.pack(">I", index)[1:]       # 3 字节 uniqueID
    header += b"\x00\x00"                            # 2 字节对齐填充

    assert len(header) == header_len, f"header {len(header)} != {header_len}"
    return bytes(header) + b"".join(records)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default="dist/test.mobi")
    a = ap.parse_args()
    path = Path(a.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = build_mobi()
    path.write_bytes(data)
    print(f"✓ {path} ({len(data) / 1024:.1f} KB, {len(CHAPTERS)} 章)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
