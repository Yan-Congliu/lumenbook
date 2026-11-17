// 鎏书阅读内核：foliate-js 跑在 WebView 里，Kotlin 通过 Android 这个 JS 接口拿到进度和目录。
// 约定：所有回调都带 try/catch —— 桌面浏览器里没有 Android 对象，页面也要能单独打开调试。

import './vendor/foliate/view.js'

const view = document.getElementById('view')
const splash = document.getElementById('splash')

/** 当前排版参数，Kotlin 打开书时传进来，改字号/主题会再传一次。 */
let style = {
  paper: '#F7F4EF',
  ink: '#1A1917',
  accent: '#A9764D',
  fontSize: 19,
  lineHeight: 1.85,
  fontFamily: '"Noto Serif CJK SC", "Source Han Serif SC", Songti SC, serif',
  margin: 40,
  justify: true,
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

/** foliate 的渲染器在 closed shadow root 里，只能靠 setStyles 注入字体/颜色。 */
function contentCss() {
  const j = style.justify ? 'justify' : 'start'
  return `
    @namespace epub "http://www.idpf.org/2007/ops";
    html, body {
      background: ${style.paper} !important;
      color: ${style.ink} !important;
    }
    body {
      font-family: ${style.fontFamily} !important;
      font-size: ${style.fontSize}px !important;
      line-height: ${style.lineHeight} !important;
      text-align: ${j} !important;
      -webkit-font-smoothing: antialiased;
    }
    p, div, li, blockquote { line-height: ${style.lineHeight} !important; }
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

function onRelocate(detail) {
  try {
    const fraction = typeof detail?.fraction === 'number' ? detail.fraction : 0
    const cfi = detail?.cfi || ''
    const label = detail?.tocItem?.label?.trim() || ''
    // 同一页重复上报没有意义，按 cfI 去重
    if (cfi && cfi === lastReported.cfi) return
    lastReported = { cfi, fraction }
    bridge('onRelocate', cfi, JSON.stringify({ fraction, label }))
  } catch (e) {
    console.error('relocate', e)
  }
}

async function open(url, cfi, themeJson) {
  applyTheme(themeJson)
  try {
    const res = await fetch(url)
    if (!res.ok) throw new Error(`加载失败：${res.status}`)
    const blob = await res.blob()
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
    view.addEventListener('load', () => {
      // 换章节时新文档是新的，再补一次（幂等）
      try {
        view.renderer?.setStyles?.(contentCss())
      } catch (e) {
        console.error('setStyles on load', e)
      }
    })

    await view.open(file)
    await view.init({ lastLocation: cfi || undefined, showTextStart: !cfi })
    // 样式必须在 init 之后发：渲染器的文档和 style 槽位这时候才建好，
    // 早发的 setStyles 只会被存起来然后被丢掉（踩过）。
    applyTheme()

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
  } catch (e) {
    console.error(e)
    splash.textContent = String(e?.message || e)
    bridge('onError', String(e?.message || e))
  }
}

function goToHref(href) {
  view.goTo(href).catch((e) => console.error('goTo', e))
}

function goToFraction(frac) {
  view.goToFraction(frac).catch((e) => console.error('goToFraction', e))
}

function next() {
  view.next().catch(() => {})
}

function prev() {
  view.prev().catch(() => {})
}

// ---- 点击分区：左右翻页，中间呼出工具栏 ----
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
  if (ratio < 0.28) prev()
  else if (ratio > 0.72) next()
  else bridge('onToggleChrome')
}, true)

// Kotlin 侧调用入口
window.lumen = {
  open,
  applyTheme,
  goToHref,
  goToFraction,
  next,
  prev,
}

bridge('onPageLoad')

// 桌面调试入口：index.html?book=book.epub&cfi=epubcfi(...)
const params = new URLSearchParams(location.search)
if (params.get('book')) {
  open(params.get('book'), params.get('cfi') || '', {}).catch((e) =>
    console.error('auto open', e),
  )
}
