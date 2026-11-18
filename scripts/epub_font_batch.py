#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量给一堆 EPUB 焊死同一种字体

用法:
    # 整个文件夹，输出到 D:\\books-宋体
    python scripts/epub_font_batch.py "D:\\vivo办公\\接收到的文件" -o "D:\\books-宋体" --family "Songti SC"
    # 只挑几本
    python scripts/epub_font_batch.py a.epub b.epub -o out --family "Kaiti SC"
    # 先看一眼会做什么
    python scripts/epub_font_batch.py "D:\\in" -o "D:\\out" --dry-run

产出: 输出目录里每本书一个 <原名>-<suffix>.epub，外加一份 报告.md
"""
import argparse
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from epub_font_inject import process_epub  # noqa: E402


def collect(srcs, recursive):
    files = []
    for s in srcs:
        if os.path.isdir(s):
            pat = "**/*.epub" if recursive else "*.epub"
            files += glob.glob(os.path.join(s, pat), recursive=recursive)
        elif os.path.isfile(s):
            files.append(s)
        else:
            print("找不到:", s)
    return sorted(set(files))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("srcs", nargs="+", help="文件夹或 epub 文件，可多个")
    ap.add_argument("-o", "--out", required=True, help="输出目录")
    ap.add_argument("--family", default="Songti SC")
    ap.add_argument("--font-file", default=None)
    ap.add_argument("--fallback", default="serif")
    ap.add_argument("--all-headings", action="store_true", help="连标题也统一（默认保留标题字体）")
    ap.add_argument("--suffix", default=None, help="输出文件名后缀，默认按字体名自动生成")
    ap.add_argument("--recursive", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    suffix = args.suffix
    if suffix is None:
        cn = {"Songti SC": "宋体", "Kaiti SC": "楷体", "Heiti SC": "黑体",
              "PingFang SC": "苹方", "Yuanti SC": "圆体", "Hiragino Sans GB": "冬青黑体"}
        suffix = "-" + cn.get(args.family, args.family.replace(" ", "") + "版")

    files = collect(args.srcs, args.recursive)
    if not files:
        print("没找到 epub")
        return 1
    os.makedirs(args.out, exist_ok=True)

    reps = []
    for i, src in enumerate(files, 1):
        stem = os.path.splitext(os.path.basename(src))[0]
        dst = os.path.join(args.out, stem + suffix + ".epub")
        tag = "[%d/%d] %s" % (i, len(files), os.path.basename(src))
        if stem.endswith(suffix):
            print(tag, "-> 已是目标版本，跳过")
            continue
        if args.dry_run:
            print(tag, "->", dst if not os.path.exists(dst) else "(覆盖)")
            reps.append({"src": os.path.basename(src), "dst": os.path.basename(dst),
                         "ok": None, "skip": "dry-run", "warnings": [], "css": [],
                         "html_patched": 0, "kb_in": round(os.path.getsize(src) / 1024), "kb_out": 0})
            continue
        rep = process_epub(src, dst, family=args.family, fallback=args.fallback,
                           keep_headings=not args.all_headings, font_file=args.font_file)
        reps.append(rep)
        if rep["skip"]:
            print(tag, "-> 跳过:", rep["skip"])
            if os.path.exists(dst) and rep["skip"] == "固定版式（fixed-layout），注入字体无意义":
                os.remove(dst)
        elif rep["ok"]:
            print(tag, "-> ok %dKB->%dKB css=%d %s" % (
                rep["kb_in"], rep["kb_out"], len(rep["css"]),
                ("; " + "; ".join(rep["warnings"])) if rep["warnings"] else ""))
        else:
            print(tag, "-> 失败:", "; ".join(rep["warnings"]))
            if os.path.exists(dst):
                os.remove(dst)

    if args.dry_run:
        print("\n(dry-run，没有写文件)")
        return 0

    ok = [r for r in reps if r["ok"]]
    bad = [r for r in reps if not r["ok"] and not r["skip"]]
    skp = [r for r in reps if r["skip"]]

    lines = ["# 批量字体注入报告", "",
             "- 字体: `%s`" % args.family,
             "- 标题: %s" % ("统一" if args.all_headings else "保留原设计"),
             "- 成功 %d / 跳过 %d / 失败 %d" % (len(ok), len(skp), len(bad)), "",
             "| 书 | 结果 | 体积 | CSS | 备注 |", "|---|---|---|---|---|"]
    for r in reps:
        state = "✅" if r["ok"] else ("⏭ " + (r["skip"] or ""))
        size = "%d→%d KB" % (r["kb_in"], r["kb_out"]) if r["ok"] else "%d KB" % r["kb_in"]
        lines.append("| %s | %s | %s | %d | %s |" % (
            r["src"], state, size, len(r["css"]), "; ".join(r["warnings"]) or ""))
    rp = os.path.join(args.out, "报告.md")
    open(rp, "w", encoding="utf-8").write("\n".join(lines) + "\n")

    print("\n完成：成功 %d，跳过 %d，失败 %d" % (len(ok), len(skp), len(bad)))
    print("输出目录:", args.out)
    print("报告:", rp)
    return 0 if not bad else 2


if __name__ == "__main__":
    sys.exit(main())
