#!/usr/bin/env python3
"""把 foliate-js（MIT）拉进 App 的 assets/reader/vendor/foliate/。

排版引擎不手抄、不魔改：永远从 npm 原始 tarball 拉，记录版本号，方便以后升级。
用法：python scripts/vendor_foliate.py [版本号]
"""
from __future__ import annotations

import io
import json
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

WS = Path(__file__).resolve().parent.parent
DEST = WS / "app" / "src" / "main" / "assets" / "reader" / "vendor" / "foliate"
REGISTRY = "https://registry.npmjs.org/foliate-js"
MIRROR = "https://registry.npmmirror.com/foliate-js"
UA = {"User-Agent": "lumenbook/1.0"}


def fetch_json(url: str) -> dict:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def main() -> int:
    want = sys.argv[1] if len(sys.argv) > 1 else None
    meta = None
    for base in (REGISTRY, MIRROR):
        try:
            meta = fetch_json(base)
            break
        except Exception as e:  # noqa: BLE001
            print(f"  元数据源失败 {base}: {type(e).__name__}")
    if meta is None:
        print("✗ 拿不到 foliate-js 元数据（npm 与镜像都不通）")
        return 1

    version = want or meta["dist-tags"]["latest"]
    info = meta["versions"].get(version)
    if info is None:
        print(f"✗ 没有版本 {version}；有：{sorted(meta['versions'])[-5:]}")
        return 1
    tarball = info["dist"]["tarball"]
    print(f"foliate-js {version}  license={info.get('license')}  {tarball}")

    data = None
    for url in (tarball, tarball.replace("registry.npmjs.org", "registry.npmmirror.com")):
        try:
            t0 = time.time()
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=180) as r:
                data = r.read()
            print(f"  下载 {len(data) / 2**20:.2f} MB 用时 {time.time() - t0:.1f}s")
            break
        except Exception as e:  # noqa: BLE001
            print(f"  下载失败 {url}: {type(e).__name__}")
    if data is None:
        return 1

    if DEST.exists():
        import shutil
        shutil.rmtree(DEST)
    DEST.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tf:
        for m in tf.getmembers():
            if not m.isfile():
                continue
            rel = m.name.split("/", 1)[-1]  # 去掉 package/ 前缀
            if not rel or rel.startswith("."):
                continue
            out = DEST / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            with tf.extractfile(m) as src, open(out, "wb") as dst:
                dst.write(src.read())

    files = sorted(p.relative_to(DEST).as_posix() for p in DEST.rglob("*") if p.is_file())
    (DEST.parent / "VERSION.txt").write_text(
        f"foliate-js {version}\nlicense: {info.get('license')}\n"
        f"tarball: {tarball}\nfiles: {len(files)}\n",
        encoding="utf-8",
    )
    print(f"✓ {len(files)} 个文件 → {DEST}")
    for f in files[:25]:
        print("   ", f)
    if len(files) > 25:
        print(f"    … 还有 {len(files) - 25} 个")
    return 0


if __name__ == "__main__":
    sys.exit(main())
