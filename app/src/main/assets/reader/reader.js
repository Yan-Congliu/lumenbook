// 鎏书阅读内核：foliate-js 跑在 WebView 里，Kotlin 通过 Android 这个 JS 接口拿到进度和目录。
// 约定：所有回调都带 try/catch —— 桌面浏览器里没有 Android 对象，页面也要能单独打开调试。

import './vendor/foliate/view.js'

const view = document.getElementById('view')
const splash = document.getElementById('splash')

/** 当前排版参数，Kotlin 打开书时传进来，改字号/主题会再传一次。 */
/** 诊断卡只在调试时显示：在地址后面加 ?diag=1（平时不打扰阅读）。 */
const DIAG = new URLSearchParams(location.search).has('diag')

/** 手势调试浮层：只有调试包会带 ?hud=1（正式包永远不带，所以这段在正式包里是死代码）。
 *  翻页判定在 vendor/foliate/paginator.js 的 snap() 里：位移 ≥ 页宽 10% 或速度 ≥ 0.20 px/ms。 */
const HUD = new URLSearchParams(location.search).has('hud')
const HUD_DRAG_MIN = 0.10
const HUD_SPEED_MIN = 0.20

if (HUD) {
  let hudEl = null
  let hudCount = 0
  let hudHideTimer = 0
  const ensureHud = () => {
    if (hudEl) return hudEl
    hudEl = document.createElement('div')
    hudEl.id = 'lumen-gesture-hud'
    Object.assign(hudEl.style, {
      position: 'fixed', left: '8px', bottom: '82px', zIndex: '2147483000',
      padding: '6px 8px',
      font: '11px/1.45 ui-monospace, SFMono-Regular, Menlo, monospace',
      color: '#111', background: 'rgba(255,255,255,.94)',
      border: '1px solid rgba(0,0,0,.18)', borderRadius: '5px',
      pointerEvents: 'none', whiteSpace: 'pre', maxWidth: '94vw',
      transition: 'opacity .25s', opacity: '0',
    })
    document.body.append(hudEl)
    return hudEl
  }
  const show = () => {
    const el = ensureHud()
    el.style.opacity = '1'
    clearTimeout(hudHideTimer)
    hudHideTimer = setTimeout(() => { if (hudEl) hudEl.style.opacity = '0' }, 7000)
  }
  const mark = (ok) => (ok ? '✓' : '✗')
  // 拖动途中：实时百分比，能看出「还差多少」
  globalThis.__lumenGestureLive = (dragPx, size) => {
    if (!size) return
    const ratio = Math.abs(dragPx) / size
    const changed = Math.round(ratio * 100)
    if (changed === hudCount) return
    hudCount = changed
    const el = ensureHud()
    el.textContent = '拖动 ' + Math.abs(Math.round(dragPx)) + 'px / ' + Math.round(size) + 'px = '
      + (ratio * 100).toFixed(1) + '%  (' + HUD_DRAG_MIN * 100 + '%)'
    show()
  }
  // 松手判定：这一下到底翻没翻、为什么
  globalThis.__lumenGestureResult = (d, size, pages) => {
    const ratio = size ? Math.abs(d.drag) / size : 0
    const speed = Math.abs(d.v)
    const el = ensureHud()
    el.textContent =
      '拖动 ' + Math.abs(d.drag).toFixed(0) + 'px / ' + Math.round(size) + 'px = '
        + (ratio * 100).toFixed(1) + '%  ' + mark(d.byDrag) + ' ≥' + HUD_DRAG_MIN * 100 + '%\n'
      + '速度 ' + speed.toFixed(2) + ' px/ms        ' + mark(speed >= HUD_SPEED_MIN)
        + ' ≥' + HUD_SPEED_MIN.toFixed(2) + '\n'
      + (d.move
        ? '→ 翻页 ' + (d.forward ? '后翻' : '前翻') + ' 到第 ' + (d.target + 1) + ' / ' + pages + ' 页'
        : '→ 不翻页（回弹）')
    show()
  }
}


let style = {
  paper: '#F7F4EF',
  ink: '#1A1917',
  accent: '#A9764D',
  fontSize: 18,
  lineHeight: 1.8,
  fontFamily: '"Noto Serif CJK SC", "Source Han Serif SC", Songti SC, serif',
  margin: 32,
  gap: 10,
  justify: true,
  indent: 2,
}

function bridge(method, ...args) {
  try {
    if (window.Android && typeof window.Android[method] === 'function') {
      window.Android[method](...args)
    }
  } catch (e) {
    console.error('bridge ' + method, e)
  }
}

// 页面一跑起来就报个到：Kotlin 那边据此判断「是 JS 没起来，还是书打不开」。
bridge('onBoot')

// 未捕获的错误也往上报，真机上没有控制台，只能靠 App 显示。
window.addEventListener('error', (e) => {
  bridge('onError', 'JS 错误: ' + (e.message || e.error || 'unknown'))
})
window.addEventListener('unhandledrejection', (e) => {
  const r = e.reason
  bridge('onError', 'JS 异常: ' + ((r && r.message) || r || 'unknown'))
})

/** 顶栏/底栏占掉的高度（px）：正文要躲开它们，但纸张背景仍然铺满整屏。 */
let insets = { top: 0, bottom: 0 }

/** 兜底：安卓 WebView 有时先给 0 高度再放大，这里按视口把书页尺寸直接钉死，
 *  不让排版去依赖 html/body 的百分比高度链。 */
function syncViewSize() {
  const w = window.innerWidth || document.documentElement.clientWidth || 0
  const rawH = window.innerHeight || document.documentElement.clientHeight || 0
  const h = Math.max(0, rawH - insets.top - insets.bottom)
  if (w > 0) view.style.width = w + 'px'
  if (h > 0) view.style.height = h + 'px'
  view.style.top = insets.top + 'px'
  return { w, h }
}

function setInsets(top, bottom) {
  insets = { top: Math.max(0, top | 0), bottom: Math.max(0, bottom | 0) }
  return syncViewSize()
}

syncViewSize()
window.addEventListener('resize', syncViewSize)
window.addEventListener('orientationchange', () => setTimeout(syncViewSize, 300))

/** foliate 的渲染器在 closed shadow root 里，只能靠 setStyles 注入字体/颜色。 */
function contentCss() {
  const j = style.justify ? 'justify' : 'start'
  return `
    @namespace epub "http://www.idpf.org/2007/ops";
    html, body {
      background: ${style.paper} !important;
      color: ${style.ink} !important;
      -webkit-text-size-adjust: 100% !important;
      text-size-adjust: 100% !important;
    }
    body {
      font-family: ${style.fontFamily} !important;
      font-size: ${style.fontSize}px !important;
      line-height: ${style.lineHeight} !important;
      text-align: ${j} !important;
      -webkit-font-smoothing: antialiased;
    }
    p, div, li, blockquote { line-height: ${style.lineHeight} !important; }
    p { text-indent: ${style.indent ?? 0}em !important; }
    a { color: ${style.accent} !important; text-decoration: none; }
    img { max-width: 100% !important; height: auto !important; }
    ::selection { background: ${style.accent}44; }
  `
}

function applyTheme(next) {
  if (!next) return
  style = { ...style, ...next }
  document.documentElement.style.setProperty('--paper', style.paper)
  document.documentElement.style.setProperty('--ink', style.ink)
  document.body.style.background = style.paper
  // foliate 的排版参数在 closed shadow 里，默认值写死在 #top 上；
  // 我们打过补丁让它读 --lumen-*，所以这里设到宿主元素上即可。
  view.style.setProperty('--lumen-gap', (style.gap ?? 12) + '%')
  view.style.setProperty('--lumen-margin', style.margin + 'px')
  try {
    view.renderer?.setStyles?.(contentCss())
  } catch (e) {
    console.error('setStyles', e)
  }
}

function tocToJson(items, depth = 0) {
  if (!Array.isArray(items)) return []
  return items.flatMap((item) => {
    if (!item) return []
    const node = {
      label: (item.label || '').trim() || '(无标题)',
      href: item.href || null,
      depth,
    }
    const children = tocToJson(item.subitems, depth + 1)
    return [node, ...children]
  })
}

let lastReported = { cfi: '', fraction: -1 }

/** 页码：本节页数 + 本节在全书里的占比，估算全书总页数。
 *  foliate 只给「本节有几页」，但 book.sections[i].size 是每节的字数权重，
 *  拿 totalSize * pages / sectionSize 就能估出全书页数（排版一致，误差很小）。
 */
function reportPages(detail, fraction) {
  try {
    const renderer = view.renderer
    const pages = renderer?.pages || 0
    const sectionPage = (renderer?.page || 0) + 1
    const sections = view.book?.sections || []
    const idx = detail?.section?.current ?? 0
    const sectionSize = sections[idx]?.size || 0
    const totalSize = sections.reduce((a, s) => a + (s.size || 0), 0)
    let total = 0
    if (pages > 0 && sectionSize > 0 && totalSize > 0) {
      total = Math.max(pages, Math.round(totalSize * pages / sectionSize))
    } else if (pages > 0) {
      total = pages
    }
    const current = total > 0 ? Math.max(1, Math.min(total, Math.round(fraction * total) + 1)) : sectionPage
    bridge('onPageInfo', current, total)
  } catch (e) {
    console.error('pages', e)
  }
}

function onRelocate(detail) {
  try {
    const fraction = typeof detail?.fraction === 'number' ? detail.fraction : 0
    const cfi = detail?.cfi || ''
    const label = detail?.tocItem?.label?.trim() || ''
    // 同一页重复上报没有意义，按 cfI 去重
    if (cfi && cfi === lastReported.cfi) return
    lastReported = { cfi, fraction }
    bridge('onRelocate', cfi, JSON.stringify({ fraction, label }))
    reportPages(detail, fraction)
  } catch (e) {
    console.error('relocate', e)
  }
}

/** 阶段日志：真机上没有控制台，卡在哪一步只能靠这个显示在屏幕上。 */
function stage(name, extra) {
  bridge('onStage', extra ? `${name} · ${extra}` : name)
}

// 排版在 foliate 的 closed shadow 里，外面看不见；vendor 补丁会把每一步抛成
// composed 的 lumen-debug 事件，这里攒着，出错时一起显示，免得再来回猜。
let debugLog = []
document.addEventListener('lumen-debug', (e) => {
  const d = e.detail || {}
  debugLog.push(d)
  if (debugLog.length > 8) debugLog.shift()
})

function debugTail(n = 3) {
  return debugLog
    .slice(-n)
    .map((d) => {
      const extra = Object.entries(d)
        .filter(([k]) => k !== 'stage')
        .map(([k, v]) => `${k}=${v}`)
        .join(' ')
      return extra ? `${d.stage}(${extra})` : d.stage
    })
    .join(' → ')
}

/** 给可能永远不 resolve 的 Promise 加个超时，避免页面永远停在「正在翻开」。 */
function withTimeout(promise, ms, label) {
  let timer = 0
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} 超时（${ms / 1000}s）`)), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

async function open(url, cfi, themeJson) {
  stage('开始打开')
  syncViewSize()
  applyTheme(themeJson)
  try {
    stage('下载书文件')
    const controller = new AbortController()
    const abortTimer = setTimeout(() => controller.abort(), 15000)
    let res
    try {
      res = await fetch(url, { signal: controller.signal })
    } finally {
      clearTimeout(abortTimer)
    }
    if (!res.ok) throw new Error(`加载失败：HTTP ${res.status}`)
    stage('读取内容')
    const blob = await res.blob()
    stage('文件就绪', `${Math.round(blob.size / 1024)} KB`)
    const name = url.split('/').pop() || 'book.epub'
    const file = new File([blob], name)

    view.addEventListener('relocate', (e) => onRelocate(e.detail))
    view.addEventListener('link', (e) => {
      // 书内链接由 foliate 自己跳，这里只拦外链
      e.preventDefault?.()
    })
    view.addEventListener('external-link', (e) => {
      e.preventDefault()
      bridge('onExternalLink', e.detail?.href || '')
    })
    view.addEventListener('error', (e) => bridge('onError', String(e.detail?.message || e.detail || '未知错误')))
    view.addEventListener('load', (e) => {
      // 换章节时新文档是新的，再补一次（幂等）
      try {
        view.renderer?.setStyles?.(contentCss())
      } catch (e2) {
        console.error('setStyles on load', e2)
      }
      // 正文在 iframe 里，事件不会冒泡到 window，得挂到每个文档上
      attachDocGestures(e.detail?.doc)
    })

    stage('解析书结构')
    await withTimeout(view.open(file), 20000, '解析书结构')
    // 翻页动画：真正干活的是内部的 <foliate-paginator>（View 只是个壳），
    // 只有它身上有 animated 才会走 300ms 缓动，所以补到它身上。
    try {
      view.renderer?.setAttribute?.('animated', '')
    } catch (e) {
      console.error('animated', e)
    }
    stage('排版')
    await withTimeout(
      view.init({ lastLocation: cfi || undefined, showTextStart: !cfi }),
      20000,
      '排版',
    )
    // 样式必须在 init 之后发：渲染器的文档和 style 槽位这时候才建好，
    // 早发的 setStyles 只会被存起来然后被丢掉（踩过）。
    applyTheme()
    // 若这时 WebView 的尺寸和排版时不一致（安卓会先 0 后放大），再钉一次，
    // 渲染器的 ResizeObserver 会跟着重排。
    syncViewSize()
    // 正文文档是 iframe，事件冒不到 window：给已经建好的文档补挂手势
    try {
      ;(view.renderer?.getContents?.() || []).forEach((c) => attachDocGestures(c.doc))
    } catch (e) {
      console.error('attachDocGestures', e)
    }
    stage('完成')

    splash.classList.add('hidden')
    await new Promise((r) => setTimeout(r, 200))
    splash.remove()

    const meta = view.book?.metadata || {}
    const payload = {
      title: meta.title || '',
      author: (Array.isArray(meta.author) ? meta.author.join('、') : meta.author) || '',
      language: meta.language || '',
      toc: tocToJson(view.book?.toc || []),
      fraction: lastReported.fraction < 0 ? 0 : lastReported.fraction,
    }
    bridge('onReady', JSON.stringify(payload))
    if (DIAG) showDiagnostics()
  } catch (e) {
    console.error(e)
    const msg = String(e?.message || e)
    const tail = debugTail()
    const full = tail ? `${msg}｜排版：${tail}` : msg
    splash.textContent = full
    bridge('onError', full)
  }
}

// 真机上没有控制台，也没法远程调试：把关键尺寸和计算结果画在屏幕中间，
// 出问题让用户截个图就能定位（定位完会去掉）。
function showDiagnostics() {
  try {
    const strip = document.createElement('div')
    strip.id = 'lumen-diag'
    const content = (view.renderer?.getContents?.() || [])[0]
    const doc = content?.doc
    const win = doc?.defaultView
    const cs = win ? win.getComputedStyle(doc.body) : null
    const de = win ? win.getComputedStyle(doc.documentElement) : null
    const bodyRect = doc?.body?.getBoundingClientRect?.()
    const box = view.getBoundingClientRect()
    const parts = [
      `视口 ${window.innerWidth}x${window.innerHeight}`,
      `书页 ${Math.round(box.width)}x${Math.round(box.height)}`,
      `正文 ${((doc?.body?.innerText || '').replace(/\s/g, '')).length}字`,
      cs ? `字色${cs.color} 底${cs.backgroundColor} 字号${cs.fontSize}` : '无文档',
      bodyRect
        ? `body ${Math.round(bodyRect.width)}x${Math.round(bodyRect.height)} @${Math.round(bodyRect.left)},${Math.round(bodyRect.top)}`
        : '',
      de ? `列宽${de.columnWidth} 列数${de.columnCount} 高${de.height}` : '',
      `滚动 ${doc?.documentElement?.scrollWidth}x${doc?.documentElement?.scrollHeight}`,
      debugTail(3),
    ].filter(Boolean)
    strip.textContent = parts.join(' · ')
    Object.assign(strip.style, {
      position: 'fixed', left: '6px', right: '6px', top: '34%',
      padding: '5px 7px', font: '10px/1.5 ui-monospace, monospace',
      color: '#111', background: 'rgba(255,255,255,.95)',
      border: '1px solid rgba(0,0,0,.2)', borderRadius: '4px',
      zIndex: 2147483000, pointerEvents: 'none',
      whiteSpace: 'normal', wordBreak: 'break-all',
    })
    document.body.append(strip)
    // 这是临时诊断条：没有异常就自己淡出，免得挡住正文
    const suspicious = box.height < 50 || ((doc?.body?.innerText || '').trim().length === 0)
    setTimeout(() => {
      strip.style.transition = 'opacity .4s'
      strip.style.opacity = '0'
      setTimeout(() => strip.remove(), 500)
    }, suspicious ? 30000 : 8000)
  } catch (e) {
    console.error('diagnostics', e)
  }
}

function goToHref(href) {
  view.goTo(href).catch((e) => console.error('goTo', e))
}

function goToFraction(frac) {
  view.goToFraction(frac).catch((e) => console.error('goToFraction', e))
}

// 翻页排队：foliate 在动画期间会忽略新的 next()/prev()，连点就会丢。
// 这里把翻页串起来，动画没结束时只记一个待办，结束后立刻补上 —— 点几下就翻几页。
let flipBusy = false
let flipPending = 0
async function flip(dir) {
  if (flipBusy) { flipPending = dir; return }
  flipBusy = true
  try {
    if (dir > 0) await view.next()
    else await view.prev()
  } catch (e) {
    /* 到书头书尾会 reject，忽略 */
  } finally {
    flipBusy = false
  }
  if (flipPending) {
    const d = flipPending
    flipPending = 0
    setTimeout(() => flip(d), 20)
  }
}

function next() {
  globalThis.__lumenTapFlipAt = Date.now()
  flip(1)
}

function prev() {
  globalThis.__lumenTapFlipAt = Date.now()
  flip(-1)
}

/** 跳到书签（CFI）。书签存的是点击那一刻的 CFI，回来时按它定位。 */
async function goToCfi(cfi) {
  if (!cfi) return false
  try {
    await view.goTo(cfi)
    return true
  } catch (e) {
    stage('书签定位失败', String(e))
    return false
  }
}

// ---- 点击分区：左 30% 上一页、右 30% 下一页、中间 40% 呼出/收起工具栏 ----
const ZONE_LEFT = 0.30
const ZONE_RIGHT = 0.70

/** ratio = 点击位置占屏宽的比例（0..1） */
function tapZone(ratio) {
  const mark = () => { globalThis.__lumenTapFlipAt = Date.now() }
  // 翻页时把工具栏收起来（工具栏本来就该在翻页后消失）
  if (ratio < ZONE_LEFT) { mark(); bridge('onHideChrome'); prev() }
  else if (ratio > ZONE_RIGHT) { mark(); bridge('onHideChrome'); next() }
  else bridge('onToggleChrome')
}

let downAt = null
window.addEventListener('pointerdown', (e) => {
  downAt = { x: e.clientX, y: e.clientY, t: Date.now() }
})
window.addEventListener('pointerup', (e) => {
  if (!downAt) return
  const dx = Math.abs(e.clientX - downAt.x)
  const dy = Math.abs(e.clientY - downAt.y)
  const dt = Date.now() - downAt.t
  downAt = null
  // 拖动翻页交给 foliate 自己处理，这里只认「轻点」
  if (dx > 12 || dy > 12 || dt > 600) return
  const ratio = e.clientX / window.innerWidth
  tapZone(ratio)
}, true)

/** 固定版式（漫画 CBZ / 固定版式 EPUB）自己没有手势，横滑也得我们来翻。 */
const isFixedLayout = () => {
  const tag = view.renderer?.tagName || ''
  return tag.includes('FXL') || tag.includes('FIXED')
}

/**
 * 正文 iframe 里的事件不会冒泡到外层 window，所以轻点/横滑要挂到每个内容文档上。
 * 幂等：同一个文档只挂一次。
 */
function attachDocGestures(doc) {
  if (!doc || doc.__lumenGestures) return
  const win = doc.defaultView
  if (!win) return
  doc.__lumenGestures = true
  let down = null
  const onDown = (x, y) => { down = { x, y, t: Date.now(), max: 0 } }
  const onMove = (x, y) => {
    if (!down) return
    const d = Math.hypot(x - down.x, y - down.y)
    if (d > down.max) down.max = d
  }
  const onUp = (x, y) => {
    if (!down) return
    const dx = x - down.x
    const dy = y - down.y
    const dt = Date.now() - down.t
    const maxDist = Math.max(down.max, Math.hypot(dx, dy))
    down = null
    // 用**正文文档自己的宽度**算比例：iframe 的实际排版宽度比外层 window 窄一点
    // （留给滚动条的 20 多像素），拿外层宽度算会把右 25% 算成中间区，点击就该翻页的位置变成呼出工具栏。
    const w = win.innerWidth || window.innerWidth || 360
    // 竖着划：交给文档自己滚
    if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 12) return
    if (Math.abs(dx) > 12) {
      if (isFixedLayout() && Math.abs(dx) >= w * 0.12) {
        bridge('onHideChrome')
        if (dx < 0) next()
        else prev()
      }
      // 可重排的书：拖动已经由 paginator 处理，这里不插手
      return
    }
    // 真·轻点：手指几乎没动、也没按太久。
    // 之前只看首尾位移，轻扫结束时手指偶尔回到起点附近就会被当成点击，于是「一划就弹出工具栏」。
    if (maxDist > 8 || dt > 400) return
    // 正文 iframe 有两种情形：
    //  1) 普通书：iframe 宽度 = 屏幕宽度，clientX 直接就是屏幕坐标；
    //  2) 单文档超长书（中国2185 这种）：整个正文排成一条 10 万像素的横向长带，
    //     iframe 跟着变得很宽、由外层容器横向滚动 —— 这时 clientX 是「长带里的坐标」，
    //     除以 innerWidth 会永远落在左侧区，表现为「点右边反而往前翻」。
    // 用 frameElement 的屏幕矩形把坐标换算回屏幕坐标。
    const frame = win.frameElement
    let screenX = x
    let viewW = window.innerWidth || 360
    if (frame && frame.getBoundingClientRect) {
      const r = frame.getBoundingClientRect()
      const scale = r.width && win.innerWidth ? r.width / win.innerWidth : 1
      screenX = r.left + x * scale
    }
    tapZone(screenX / viewW)
  }
  // 触屏用 touch 事件（WebView 里 pointer 系列在 iframe 内可能收不到），桌面用 pointer
  win.addEventListener('touchstart', (e) => {
    const t = e.touches[0]
    if (t) onDown(t.clientX, t.clientY)
    // 碰一下就通知外层：工具栏的 6 秒自动收起要重新计时
    bridge('onActivity')
  }, { capture: true, passive: true })
  win.addEventListener('touchmove', (e) => {
    const t = e.touches[0]
    if (t) onMove(t.clientX, t.clientY)
  }, { capture: true, passive: true })
  win.addEventListener('touchend', (e) => {
    const t = e.changedTouches[0]
    if (t) onUp(t.clientX, t.clientY)
    bridge('onActivity')
  }, { capture: true, passive: true })
  if (!('ontouchstart' in win)) {
    win.addEventListener('pointerdown', (e) => onDown(e.clientX, e.clientY), true)
    win.addEventListener('pointermove', (e) => onMove(e.clientX, e.clientY), true)
    win.addEventListener('pointerup', (e) => onUp(e.clientX, e.clientY), true)
  }
}

// Kotlin 侧调用入口
/**
 * 全书搜索：foliate 的 view.search() 是个 async generator，
 * 每个章节先 yield 一个 { label, subitems }，再逐条 yield { cfi, excerpt }。
 * 这里统一收成扁平列表，搜完一次性回给 Kotlin（避免太多桥调用）。
 */
/** foliate 的 excerpt 是 { pre, match, post } 对象，这里拍平成一行可读文字。 */
function excerptText(ex) {
  if (!ex) return ''
  if (typeof ex === 'string') return ex
  const pre = ex.pre || ''
  const match = ex.match || ''
  const post = ex.post || ''
  return `${pre}【${match}】${post}`.trim().slice(0, 120)
}

async function searchBook(query, limit = 80) {
  const q = String(query || '').trim()
  if (!q || !view.book) return []
  const hits = []
  try {
    for await (const r of view.search({ query: q })) {
      if (r === 'done') break
      if (r && Array.isArray(r.subitems)) {
        for (const it of r.subitems) {
          if (hits.length >= limit) break
          hits.push({
            cfi: it.cfi || '',
            excerpt: excerptText(it.excerpt),
            chapter: r.label || '',
          })
        }
      } else if (r && r.cfi) {
        if (hits.length >= limit) break
        hits.push({ cfi: r.cfi, excerpt: excerptText(r.excerpt), chapter: '' })
      }
      if (hits.length >= limit) break
    }
  } catch (e) {
    stage('搜索失败', String(e))
  }
  return hits
}

async function searchAndReport(query) {
  const hits = await searchBook(query)
  bridge('onSearchResults', JSON.stringify({ query, hits }))
}

window.lumen = {
  open,
  applyTheme,
  setInsets,
  goToHref,
  goToFraction,
  next,
  search: searchAndReport,
  prev,
  goToCfi,
}

bridge('onPageLoad')

// 桌面调试入口：index.html?book=book.epub&cfi=epubcfi(...)
const params = new URLSearchParams(location.search)
if (params.get('book')) {
  open(params.get('book'), params.get('cfi') || '', {}).catch((e) =>
    console.error('auto open', e),
  )
}
