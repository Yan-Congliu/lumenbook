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

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

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
    patch_paginator()
    patch_paginator_load()
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


def patch_paginator() -> None:
    """让宿主的排版变量能覆盖 paginator 写死在 shadow 里的默认值。

    foliate 把 --_gap / --_margin / --_max-inline-size 直接定义在 shadow 内的 #top 上，
    子元素自己声明的自定义属性会盖过继承值，所以从外面改不动（页边距永远是 7%）。
    这里把它们改成读 --lumen-*，再带原默认值兜底。升级 foliate 后重跑本脚本即自动补上。
    """
    path = DEST / "paginator.js"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    old = """        #top {
            --_gap: 7%;
            --_margin: 48px;
            --_max-inline-size: 720px;"""
    new = """        #top {
            /* LUMENBOOK PATCH: 允许宿主(foliate-view)覆盖排版参数 */
            --_gap: var(--lumen-gap, 7%);
            --_margin: var(--lumen-margin, 48px);
            --_max-inline-size: var(--lumen-max-inline-size, 720px);"""
    if old in text:
        path.write_text(text.replace(old, new), encoding="utf-8")
        print("  ✓ 已打上排版参数补丁（--lumen-gap / --lumen-margin）")
    elif "--lumen-gap" in text:
        print("  · 排版参数补丁已存在")
    else:
        print("  ! 补丁失败：paginator.js 的结构变了，需要人工检查")


NEW_LOAD = r"""    async load(src, afterLoad, beforeRender) {
        if (typeof src !== 'string') throw new Error(`${src} is not string`)
        // LUMENBOOK PATCH: srcdoc + 调试事件
        // ① 安卓 WebView 里 blob: 的 iframe 常常等不到 load 事件（桌面正常），
        //    所以把 blob 抓成文本改用 srcdoc，等价内容、不依赖 blob 导航。
        // ② 排版在 closed shadow 里，出问题外面看不见；这里把每一步抛出 composed 事件，
        //    reader.js 收下来，真机卡住时能显示卡在哪个动作。
        const dbg = (stage, extra) => {
            try {
                this.dispatchEvent(new CustomEvent('lumen-debug', {
                    detail: { stage, ...extra }, bubbles: true, composed: true,
                }))
            } catch {}
        }
        const started = globalThis.performance?.now?.() ?? 0
        const elapsed = () => Math.round((globalThis.performance?.now?.() ?? 0) - started)
        return new Promise((resolve, reject) => {
            this.#iframe.addEventListener('load', () => {
                dbg('iframe-load', { ms: elapsed() })
                try {
                    const doc = this.document
                    if (!doc?.body) throw new Error('iframe 空白，没有 body')
                    afterLoad?.(doc)

                    // it needs to be visible for Firefox to get computed style
                    this.#iframe.style.display = 'block'
                    const { vertical, rtl } = getDirection(doc)
                    this.docBackground = getBackground(doc)
                    doc.body.style.background = 'none'
                    const background = this.docBackground
                    this.#iframe.style.display = 'none'

                    this.#vertical = vertical
                    this.#rtl = rtl

                    this.#contentRange.selectNodeContents(doc.body)
                    const layout = beforeRender?.({ vertical, rtl, background })
                    this.#iframe.style.display = 'block'
                    this.render(layout)
                    this.#observer.observe(doc.body)

                    // the resize observer above doesn't work in Firefox
                    // (see https://bugzilla.mozilla.org/show_bug.cgi?id=1832939)
                    // until the bug is fixed we can at least account for font load
                    doc.fonts.ready.then(() => this.expand())

                    dbg('render-done', { ms: elapsed() })
                    resolve()
                } catch (e) {
                    dbg('render-error', { message: String(e?.message ?? e) })
                    reject(e)
                }
            }, { once: true })
            if (src.startsWith('blob:')) {
                dbg('srcdoc-start', {})
                fetch(src).then(r => r.text()).then(text => {
                    dbg('srcdoc-ready', { chars: text.length })
                    this.#iframe.srcdoc = text
                }).catch(e => {
                    dbg('srcdoc-fail', { message: String(e?.message ?? e) })
                    this.#iframe.src = src
                })
            } else {
                dbg('iframe-src', { scheme: src.slice(0, 6) })
                this.#iframe.src = src
            }
            setTimeout(() => dbg('iframe-wait', {
                ms: elapsed(),
                body: !!this.#iframe.contentDocument?.body,
            }), 4000)
        })
    }
"""


def patch_paginator_load() -> None:
    """让 iframe 在安卓 WebView 里能真正加载出来，并把排版过程抛给宿主看。

    原实现是 `this.#iframe.src = src`（src 是 blob: URL），然后等 iframe 的 load 事件；
    真机上 load 一直不来，`view.init()` 就永远不 resolve —— 表现为卡在「排版」。
    这里换成 srcdoc（内容等价），并补上一串 composed 调试事件。
    """
    path = DEST / "paginator.js"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    if "LUMENBOOK PATCH: srcdoc" in text:
        print("  · iframe 加载补丁已存在")
        return
    start_marker = "    async load(src, afterLoad, beforeRender) {"
    end_marker = "            this.#iframe.src = src\n        })\n    }\n"
    i = text.find(start_marker)
    j = text.find(end_marker, i) if i >= 0 else -1
    if i < 0 or j < 0:
        print("  ! iframe 加载补丁失败：paginator.js 结构变了，需要人工检查")
        return
    text = text[:i] + NEW_LOAD + text[j + len(end_marker):]
    path.write_text(text, encoding="utf-8")
    print("  ✓ 已打上 iframe 加载补丁（blob→srcdoc + 调试事件）")


if __name__ == "__main__":
    sys.exit(main())
