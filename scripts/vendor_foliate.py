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
    patch_paginator_snap()
    patch_paginator_touchend()
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
            // 注意：这里的 View 不是 DOM 元素，没有 dispatchEvent，得走 document
            try {
                globalThis.document?.dispatchEvent(new CustomEvent('lumen-debug', {
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
                    dbg('measure', {
                        vw: this.container?.clientWidth ?? -1,
                        vh: this.container?.clientHeight ?? -1,
                        iw: this.#iframe.clientWidth,
                        ih: this.#iframe.clientHeight,
                        ew: Math.round(this.element?.getBoundingClientRect?.().width ?? -1),
                        eh: Math.round(this.element?.getBoundingClientRect?.().height ?? -1),
                        text: (doc.body.textContent || '').trim().length,
                    })
                    const { vertical, rtl } = getDirection(doc)
                    this.docBackground = getBackground(doc)
                    doc.body.style.background = 'none'
                    const background = this.docBackground
                    this.#iframe.style.display = 'none'

                    this.#vertical = vertical
                    this.#rtl = rtl

                    this.#contentRange.selectNodeContents(doc.body)
                    const layout = beforeRender?.({ vertical, rtl, background })
                    dbg('layout', {
                        w: Math.round(layout?.width ?? -1),
                        h: Math.round(layout?.height ?? -1),
                        cw: Math.round(layout?.columnWidth ?? -1),
                        m: Math.round(layout?.margin ?? -1),
                    })
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


NEW_SNAP = r"""    snap(vx, vy) {
        // LUMENBOOK PATCH: 轻扫判定改成「拖动超过页宽 10% 或速度 ≥ 0.20 px/ms」。
        // 原式是 (start + end) / 2 + velocity * size，等于要求拖满一整页才肯翻，
        // 真机上就是「页面跟着手指走、一松手又弹回原页」。
        const velocity = this.#vertical ? vy : vx
        const { pages, size } = this
        if (this.scrolled || !size) {
            const page0 = Math.floor((this.start + this.end) / (2 * size))
            return this.#scrollToPage(page0, 'snap')
        }
        // 起手那页的滚动位置就存在 #scrollBounds[0] 里；当前滚动位置减去它
        // 就是这次手势把页面拖出去了多少像素。
        const settled = Math.abs(this.#scrollBounds[0])
        const base = Math.round(settled / size)
        const drag = (this.start + this.end) / 2 - settled - size / 2
        const v = isNaN(velocity) ? 0 : velocity
        const byDrag = Math.abs(drag) / size >= 0.10
        const move = byDrag || Math.abs(v) >= 0.20
        const forward = ((this.#rtl ? -1 : 1) * (byDrag ? drag : v)) > 0
        const target = Math.max(0, Math.min(pages - 1,
            move ? base + (forward ? 1 : -1) : base))
        this._lumenDebug = { settled, base, drag: +drag.toFixed(1), v: +v.toFixed(3), byDrag, move, forward, target }
        // LUMENBOOK DEBUG: 手势判定结果送到调试浮层（正式包里没人挂这个函数，一行 JS 就跳过了）
        try { globalThis.__lumenGestureResult?.(this._lumenDebug, size, pages) } catch (e) {}

        this.#scrollToPage(target, 'snap').then(() => {
            const dir = target <= 0 ? -1 : target >= pages - 1 ? 1 : null
            if (dir) return this.#goTo({
                index: this.#adjacentIndex(dir),
                anchor: dir < 0 ? () => 1 : () => 0,
            })
        })
    }
"""

TOUCH_START_OLD = """        this.#touchState = {
            x: touch?.screenX, y: touch?.screenY,
            t: e.timeStamp,
            vx: 0, xy: 0,
        }
    }"""

TOUCH_START_NEW = """        this.#touchState = {
            x: touch?.screenX, y: touch?.screenY,
            t: e.timeStamp,
            vx: 0, xy: 0,
        }
        // LUMENBOOK PATCH: 记下起手那页，松手时按位移决定翻不翻页
        this._lumenStartPage = this.page
        this._lumenDrag = 0
    }"""

TOUCH_MOVE_OLD = """        state.vx = dx / dt
        state.vy = dy / dt"""

TOUCH_MOVE_NEW = """        state.vx = dx / dt
        state.vy = dy / dt
        // LUMENBOOK PATCH: 累计横向拖动（正数 = 向左划 = 往后翻）
        this._lumenDrag = (this._lumenDrag || 0) + (Math.abs(dx) >= Math.abs(dy) ? dx : 0)
        // LUMENBOOK DEBUG: 拖动过程中实时报位移，调试浮层要看到「差多少到阈值」
        try { globalThis.__lumenGestureLive?.(this._lumenDrag || 0, this.size || 0) } catch (e) {}"""


def patch_paginator_snap() -> None:
    """把「轻扫翻页」的判定改软：按「拖了多远 + 划得多快」决定，而不是拖满整页。

    foliate 在 touchend 里调用 `snap(vx, vy)`，判定式是
    `floor(((start + end) / 2 + velocity * size) / size)`：手指已经把页面拖出去半屏，
    只要没跨过整页边界就弹回去 —— 真机上表现为「轻轻一划翻不了页，要划好几次或者很用力」。
    这里换成「拖动 ≥ 10% 页宽 或 速度 ≥ 0.20 px/ms 就翻相邻页」。
    拖动量直接从滚动位置反推（#scrollBounds[0] 是起手那页的位置），不依赖 touch 状态的字段。
    """
    path = DEST / "paginator.js"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")

    start_marker = "    snap(vx, vy) {"
    end_marker = "\n    #onTouchStart(e) {"
    i = text.find(start_marker)
    j = text.find(end_marker, i) if i >= 0 else -1
    if i < 0 or j < 0:
        print("  ! 轻扫补丁失败：paginator.js 的 snap() 结构变了，需要人工检查")
        return
    already = "LUMENBOOK PATCH: 轻扫判定改成" in text[i:j]
    text = text[:i] + NEW_SNAP + text[j + 1:]

    if TOUCH_START_OLD in text:
        text = text.replace(TOUCH_START_OLD, TOUCH_START_NEW)
    elif "this._lumenStartPage" not in text:
        print("  ! 轻扫补丁：touchstart 结构变了，起手页记录没打上")

    if TOUCH_MOVE_OLD in text:
        text = text.replace(TOUCH_MOVE_OLD, TOUCH_MOVE_NEW)
    elif "this._lumenDrag" not in text:
        print("  ! 轻扫补丁：touchmove 结构变了，累计位移没打上")

    path.write_text(text, encoding="utf-8")
    print("  · 轻扫翻页补丁已更新" if already else "  ✓ 已打上轻扫翻页补丁（位移 10% / 速度 0.20 px/ms）")


TOUCH_END_OLD = """    #onTouchEnd() {
        this.#touchScrolled = false
        if (this.scrolled) return

        // XXX: Firefox seems to report scale as 1... sometimes...?
        // at this point I'm basically throwing `requestAnimationFrame` at
        // anything that doesn't work
        requestAnimationFrame(() => {
            if (globalThis.visualViewport.scale === 1)
                this.snap(this.#touchState.vx, this.#touchState.vy)
        })
    }"""

TOUCH_END_NEW = """    #onTouchEnd() {
        this.#touchScrolled = false
        if (this.scrolled) return
        // LUMENBOOK PATCH: 同步决定停在哪一页，不再塞进 requestAnimationFrame。
        // 原实现在 rAF 里才调 snap()，某些 WebView / 后台标签页下 rAF 会被节流甚至不跑，
        // 于是页面停在手指松开的位置：既不回弹，也不翻页。
        this.snap(this.#touchState.vx, this.#touchState.vy)
    }"""


def patch_paginator_touchend() -> None:
    """松手后同步决定停在哪一页（foliate 原本塞在 requestAnimationFrame 里）。

    rAF 在部分 WebView / 后台标签页会被节流到 1fps 甚至完全不跑；一旦不跑，
    `snap()` 永远不执行，页面就停在手指松开的位置 —— 既不回弹也不翻页。
    """
    path = DEST / "paginator.js"
    if not path.is_file():
        return
    raw = path.read_text(encoding="utf-8")
    crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if "LUMENBOOK PATCH: 同步决定停在哪一页" in text:
        print("  · 松手同步判定补丁已存在")
        return
    if TOUCH_END_OLD not in text:
        print("  ! 松手同步判定补丁失败：paginator.js 的 #onTouchEnd 结构变了")
        return
    text = text.replace(TOUCH_END_OLD, TOUCH_END_NEW)
    path.write_text(text.replace("\n", "\r\n") if crlf else text, encoding="utf-8")
    print("  ✓ 已打上松手同步判定补丁（#onTouchEnd 直接 snap）")


if __name__ == "__main__":
    sys.exit(main())
