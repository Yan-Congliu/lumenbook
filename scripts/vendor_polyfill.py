#!/usr/bin/env python3
"""把 foliate 需要的第三方小依赖也拉进 vendor（目前只有一个）。

foliate 的 fixed-layout.js（CBZ 漫画、以及固定版式 EPUB 都走它）第一行是
`import 'construct-style-sheets-polyfill'` —— 裸包名在 WebView 里解析不了，
必须落成本地文件并改成相对路径。

    python scripts/vendor_polyfill.py
"""

from __future__ import annotations

import io
import json
import sys
import tarfile
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

WS = Path(__file__).resolve().parent.parent
VENDOR = WS / "app" / "src" / "main" / "assets" / "reader" / "vendor" / "foliate"
TARGET = VENDOR / "vendor" / "construct-style-sheets-polyfill.js"

PACKAGE = "construct-style-sheets-polyfill"
REGISTRIES = ("https://registry.npmjs.org", "https://registry.npmmirror.com")


def fetch_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.load(r)


def pick_member(names: list[str]) -> str:
    """包里的入口文件：优先 dist / 根目录下的 .js，排除 min 与 esm 之外的变体。"""
    candidates = [n for n in names if n.endswith(".js") and "/src/" not in n]
    for want in ("dist/adoptedStyleSheets.js", "dist/index.js", "index.js", "dist/construct-style-sheets-polyfill.js"):
        if want in candidates:
            return want
    candidates.sort(key=len)
    if not candidates:
        raise SystemExit("包里没有 .js 文件")
    return candidates[0]


def main() -> int:
    meta = None
    for base in REGISTRIES:
        try:
            meta = fetch_json(f"{base}/{PACKAGE}")
            print(f"[polyfill] 元数据来自 {base}")
            break
        except Exception as e:  # noqa: BLE001 - 换镜像重试
            print(f"[polyfill] {base} 失败：{e}")
    if not meta:
        print("✗ 拿不到 %s 元数据" % PACKAGE)
        return 1

    info = meta["versions"][meta["dist-tags"]["latest"]]
    version = info["version"]
    tarball = info["dist"]["tarball"]
    print(f"[polyfill] {PACKAGE} {version}  license={info.get('license')}")

    data = None
    for url in (tarball, tarball.replace("registry.npmjs.org", "registry.npmmirror.com")):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
            print(f"[polyfill] 下载 {len(data) / 1024:.1f} KB")
            break
        except Exception as e:  # noqa: BLE001
            print(f"[polyfill] 下载失败 {url}：{e}")
    if not data:
        return 1

    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tf:
        names = [m.name for m in tf.getmembers() if m.isfile()]
        member = pick_member([n.split("/", 1)[1] for n in names])
        full = next(n for n in names if n.split("/", 1)[1] == member)
        source = tf.extractfile(full).read().decode("utf-8")

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(source, encoding="utf-8")
    print(f"[polyfill] ✓ {TARGET.relative_to(WS)}  {len(source) / 1024:.1f} KB")

    # 把 fixed-layout.js 的裸 import 改成本地路径
    fixed = VENDOR / "fixed-layout.js"
    text = fixed.read_text(encoding="utf-8")
    bare = "import 'construct-style-sheets-polyfill'"
    if bare in text:
        text = text.replace(bare, "import './vendor/construct-style-sheets-polyfill.js'")
        fixed.write_text(text, encoding="utf-8")
        print("[polyfill] ✓ fixed-layout.js 的 import 已改为相对路径")
    elif "./vendor/construct-style-sheets-polyfill.js" in text:
        print("[polyfill] fixed-layout.js 已经指过本地文件，跳过")
    else:
        print("[polyfill] ! fixed-layout.js 里没找到预期的 polyfill import，请人工检查")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
