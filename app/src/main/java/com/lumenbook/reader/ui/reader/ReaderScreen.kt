package com.lumenbook.reader.ui.reader

import android.annotation.SuppressLint
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.lumenbook.reader.data.ReaderAlign
import com.lumenbook.reader.data.ReaderFont
import com.lumenbook.reader.data.ReaderTheme
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.lumenbook.reader.BuildConfig
import com.lumenbook.reader.data.AppContainer
import com.lumenbook.reader.data.Book
import com.lumenbook.reader.data.BookFormat
import com.lumenbook.reader.data.Bookmark
import com.lumenbook.reader.data.ReaderStyle
import com.lumenbook.reader.ui.pdf.PdfReaderScreen
import com.lumenbook.reader.ui.theme.LumenColors
import com.lumenbook.reader.ui.theme.lumenColorsFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

private const val ASSET_DOMAIN = "appassets.androidplatform.net"

/** 目录里的一项，depth 用于缩进。 */
data class TocNode(val label: String, val href: String?, val depth: Int)

/** 阅读页的 UI 状态。JS 桥在 WebView 线程回调，桥内部已 post 到主线程。 */
private class ReaderUi {
    var booted by mutableStateOf(false)
    var pageLoaded by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var stage by mutableStateOf("")
    var fraction by mutableStateOf(0f)
    var chapter by mutableStateOf("")
    var toc by mutableStateOf<List<TocNode>>(emptyList())
    var chromeVisible by mutableStateOf(false)
    var cfi by mutableStateOf<String?>(null)
    /** 当前页 / 全书总页数（网页估算的，拿不到总量时 total = 0）。 */
    var page by mutableIntStateOf(1)
    var totalPages by mutableIntStateOf(0)
    /** 工具栏展开后，点左上角书名位置才会露出返回箭头（Books 的藏法）。 */
    var backShown by mutableStateOf(false)
    /** 每次用户碰一下正文就 +1，用来给「自动收起工具栏」的 6 秒计时续命。 */
    var activity by mutableIntStateOf(0)
}

private fun BookFormat.usesFoliate(): Boolean = when (this) {
    BookFormat.EPUB, BookFormat.MOBI, BookFormat.AZW3, BookFormat.FB2, BookFormat.CBZ -> true
    else -> false
}

private fun BookFormat.mime(): String = when (this) {
    BookFormat.EPUB -> "application/epub+zip"
    BookFormat.MOBI -> "application/x-mobipocket-ebook"
    BookFormat.AZW3 -> "application/vnd.amazon.ebook"
    BookFormat.FB2 -> "application/x-fictionbook+xml"
    BookFormat.CBZ -> "application/vnd.comicbook+zip"
    BookFormat.PDF -> "application/pdf"
    else -> "application/octet-stream"
}

private fun Color.hex(): String = String.format(Locale.US, "#%06X", toArgb() and 0xFFFFFF)

/** 传给网页的排版参数；改字号/行距/主题只要重新发一次。 */
private fun themeJson(colors: LumenColors, style: ReaderStyle): String = JSONObject().apply {
    put("paper", colors.page.hex())
    put("ink", colors.text.hex())
    put("accent", colors.accent.hex())
    put("fontSize", style.fontSize)
    put("lineHeight", style.lineHeight.toDouble())
    put("margin", style.margin)
    put("gap", 10)
    put("justify", style.align == ReaderAlign.JUSTIFY)
    put("fontFamily", style.font.fontStack())
    put("indent", style.indent)
}.toString()

/** 字体栈：带双引号的族名在 CSS 里要保留引号，这里只做 JSON 侧的转义交给 put。 */
private fun ReaderFont.fontStack(): String = stack

private fun parseToc(json: String?): List<TocNode> {
    val obj = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
    val out = mutableListOf<TocNode>()
    val arr = obj.optJSONArray("toc") ?: return out
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out += TocNode(
            label = o.optString("label", "(无标题)"),
            href = o.optString("href", "").ifBlank { null },
            depth = o.optInt("depth", 0),
        )
    }
    return out
}

/** 把书库里的私有副本作为 /book/ 路径喂给网页（同源，免 CORS）。 */
private class BookFileHandler(
    private val file: File,
    private val mime: String,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        if (!file.isFile) {
            return WebResourceResponse(
                "text/plain",
                null,
                404,
                "Not Found",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream("missing".toByteArray()),
            )
        }
        val stream: InputStream = FileInputStream(file)
        return WebResourceResponse(
            mime,
            null,
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            stream,
        )
    }
}

/**
 * JS 侧的 Android 对象。
 *
 * 必须是 public 类：`addJavascriptInterface` 靠反射调用，Kotlin 顶层 `private class`
 * 会编译成 package-private，android.webkit 跨包反射会抛 IllegalAccessException，
 * 表现就是「JS 明明调了，App 一点反应都没有」。真机上踩过一次。
 *
 * 回调参数一律在桥里 post 到主线程，调用方可以直接改 Compose 状态。
 */
class ReaderBridge(
    private val main: Handler,
    private val booted: () -> Unit,
    private val loaded: () -> Unit,
    private val staged: (String?) -> Unit,
    private val ready: (String?) -> Unit,
    private val relocated: (String?, String?) -> Unit,
    private val failed: (String?) -> Unit,
    private val toggled: () -> Unit,
    private val touched: () -> Unit,
    private val hidden: () -> Unit,
    private val pageInfo: (Int, Int) -> Unit,
) {
    @JavascriptInterface
    fun onBoot() {
        main.post { booted() }
    }

    @JavascriptInterface
    fun onPageLoad() {
        main.post { loaded() }
    }

    @JavascriptInterface
    fun onStage(message: String?) {
        main.post { staged(message) }
    }

    @JavascriptInterface
    fun onReady(json: String?) {
        main.post { ready(json) }
    }

    @JavascriptInterface
    fun onRelocate(cfi: String?, json: String?) {
        main.post { relocated(cfi, json) }
    }

    @JavascriptInterface
    fun onError(message: String?) {
        main.post { failed(message) }
    }

    @JavascriptInterface
    fun onToggleChrome() {
        main.post { toggled() }
    }

    /** 用户碰了正文（点或划）：给自动收起计时续命。 */
    @JavascriptInterface
    fun onActivity() {
        main.post { touched() }
    }

    /** 翻页时把工具栏收起来。 */
    @JavascriptInterface
    fun onHideChrome() {
        main.post { hidden() }
    }

    /** 页码：当前页 / 全书总页数（total = 0 表示网页也算不出来）。 */
    @JavascriptInterface
    fun onPageInfo(current: Int, total: Int) {
        main.post { pageInfo(current, total) }
    }

    @JavascriptInterface
    fun onExternalLink(url: String?) {
        // 书里的外链暂时不跳，避免把用户带出阅读页。
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildReaderWebView(
    context: android.content.Context,
    loader: WebViewAssetLoader,
    bridge: ReaderBridge,
    background: Int,
    onPageFinished: (WebView) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(background)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    // 关掉 WebView 的「文字自动放大」：安卓会按容器宽度把字号悄悄放大，
    // 之前 18px 被放成 21.28px，一行只剩 12 个字。
    settings.textZoom = 100
    settings.mediaPlaybackRequiresUserGesture = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = request.url.host != ASSET_DOMAIN

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w("LumenReader", "资源加载失败 ${request.url} ${error.description}")
            }
        }

        override fun onPageFinished(view: WebView, url: String) = onPageFinished(view)
    }
    addJavascriptInterface(bridge, "Android")
    // 调试包给地址加 ?hud=1：阅读内核会画一个手势判定浮层（位移/速度/翻不翻）。
    // 正式包里 BuildConfig.DEBUG 是 false，URL 不带参数，浮层那段 JS 根本不会执行。
    val readerUrl = "https://$ASSET_DOMAIN/assets/reader/index.html" +
        if (BuildConfig.DEBUG) "?hud=1" else ""
    loadUrl(readerUrl)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book,
    container: AppContainer,
    colors: LumenColors,
    theme: ReaderTheme,
    onThemeChange: (ReaderTheme) -> Unit,
    onClose: () -> Unit,
) {
    if (book.format == BookFormat.TXT) {
        TxtReaderScreen(book, container, colors, theme, onThemeChange, onClose)
        return
    }
    if (book.format == BookFormat.PDF) {
        PdfReaderScreen(book, container, colors, onClose)
        return
    }
    if (!book.format.usesFoliate()) {
        UnsupportedFormatScreen(book, colors, onClose)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = remember(book.id) {
        ReaderUi().apply {
            fraction = book.progress
            cfi = book.location
        }
    }
    var showToc by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var bookmarks by remember(book.id) { mutableStateOf<List<Bookmark>>(emptyList()) }
    // 当前这一页有没有书签：CFI 精确到位置，换个段落就算另一处
    val currentBookmarked = bookmarks.any { it.location == ui.cfi }
    var lastError by remember { mutableStateOf<String?>(null) }
    val lastSaveAt = remember { longArrayOf(0L) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val readerStyle by container.prefs.readerStyle.collectAsState(initial = ReaderStyle())

    val assetLoader = remember(book.localPath) {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/book/", BookFileHandler(File(book.localPath), book.format.mime()))
            .build()
    }

    val bridge = remember(book.id) {
        ReaderBridge(
            main = mainHandler,
            booted = { ui.booted = true },
            loaded = { ui.pageLoaded = true },
            staged = { message -> ui.stage = message ?: "" },
            ready = { json ->
                val obj = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
                ui.toc = parseToc(json)
                val fraction = obj.optDouble("fraction", 0.0).toFloat()
                if (fraction > 0f) ui.fraction = fraction
                ui.ready = true
                ui.error = null
            },
            relocated = { cfi, json ->
                val obj = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
                val fraction = obj.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f)
                val label = obj.optString("label", "")
                ui.fraction = fraction
                ui.cfi = cfi
                if (label.isNotBlank()) ui.chapter = label
                val now = System.currentTimeMillis()
                if (now - lastSaveAt[0] > 2_000L) {
                    lastSaveAt[0] = now
                    scope.launch { container.library.saveLocation(book.id, cfi, fraction) }
                }
            },
            failed = { message ->
                lastError = message ?: "未知错误"
                if (!ui.ready) ui.error = message ?: "打开失败"
            },
            toggled = {
                ui.chromeVisible = !ui.chromeVisible
                // 每回收起工具栏，返回箭头也收回去，下次再点书名才出来
                if (!ui.chromeVisible) ui.backShown = false
            },
            touched = { ui.activity += 1 },
            hidden = { ui.chromeVisible = false },
            pageInfo = { current, total ->
                ui.page = current.coerceAtLeast(1)
                ui.totalPages = total.coerceAtLeast(0)
            },
        )
    }

    // 只跟书绑定：换主题/字号走 applyTheme（注入 CSS 变量），不要重新打开整本书 ——
    // 重开会丢掉当前页，而且外层页面的底色会退回默认的米纸色。
    val openScript = remember(book.id, book.localPath) {
        val url = "https://$ASSET_DOMAIN/book/${book.id}.${book.format.name.lowercase()}"
        "window.lumen.open(${JSONObject.quote(url)}, ${JSONObject.quote(book.location ?: "")}, ${themeJson(colors, readerStyle)})"
    }

    /** 退出阅读页前的收尾：落库最后位置。 */
    val finish = {
        if (ui.ready) {
            scope.launch { container.library.saveLocation(book.id, ui.cfi, ui.fraction) }
        }
        onClose()
    }

    val webView = remember(book.id) {
        buildReaderWebView(
            context = context,
            loader = assetLoader,
            bridge = bridge,
            background = colors.page.toArgb(),
            onPageFinished = { view ->
                // 安卓 WebView 在没拿到焦点时，第一次点击只用来「聚焦」，正文里那一次点击就丢了 ——
                // 用户感受到的就是「第一次点没反应」。加载完主动要焦点。
                view.isFocusableInTouchMode = true
                view.requestFocus()
                // 兜底：即使 JS 桥没连上（收不到 onPageLoad），也主动把书推给页面。
                // evaluateJavascript 不依赖 addJavascriptInterface。
                if (!opened) {
                    opened = true
                    view.evaluateJavascript(openScript, null)
                }
            },
        )
    }

    DisposableEffect(book.id) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    LaunchedEffect(ui.pageLoaded) {
        if (ui.pageLoaded && !opened) {
            opened = true
            webView.evaluateJavascript(openScript, null)
        }
    }

    // 改字号/行距/边距时立刻重排，不用重开书。
    LaunchedEffect(readerStyle, colors) {
        if (ui.ready) {
            webView.evaluateJavascript("window.lumen.applyTheme(${themeJson(colors, readerStyle)})", null)
        }
    }

    // 书签：进书时读一次，增删后重读
    LaunchedEffect(book.id) {
        bookmarks = container.library.bookmarks(book.id)
    }

    val toggleBookmark: () -> Unit = {
        val location = ui.cfi
        if (location.isNullOrBlank()) {
            scope.launch {
                bookmarks = container.library.bookmarks(book.id)
            }
        } else {
            scope.launch {
                container.library.toggleBookmark(book.id, location, ui.chapter.ifBlank { null })
                bookmarks = container.library.bookmarks(book.id)
            }
        }
    }

    // 正文只躲开状态栏和导航栏，**不再**因为工具栏出现而重排。
    // 之前把栏高推给网页，工具栏一出一收正文就整体跳一次，点几下就不知道读到哪了。
    // 现在工具栏是纯覆盖层（带渐入渐出的半透明遮罩），排版自始至终不动。
    //
    // ⚠️ 单位：WindowInsets 给的是物理像素，而 WebView 里的 CSS px 等于 dp
    // （WebView 的 devicePixelRatio 就等于屏幕 density）。直接把 px 传给 CSS 会放大 2~4 倍，
    // 表现成「正文被顶到屏幕中间、底部还被切掉」——真机上踩过这个坑。
    val density = LocalDensity.current
    val scale = density.density
    val statusBarDp = WindowInsets.statusBars.getTop(density) / scale
    val navBarDp = WindowInsets.navigationBars.getBottom(density) / scale
    // 常驻信息层会占掉顶部/底部各一条（书名、章节名、页码），正文让开 18dp，免得第一行压在字上。
    LaunchedEffect(ui.ready) {
        webView.evaluateJavascript(
            "window.lumen && window.lumen.setInsets(${statusBarDp + 18f}, ${navBarDp + 18f})",
            null,
        )
    }

    // 工具栏：6 秒没人碰就自己收起来。任何一次触摸（activity 变化）都重新计时。
    LaunchedEffect(ui.chromeVisible, ui.activity) {
        if (!ui.chromeVisible) return@LaunchedEffect
        delay(6_000)
        if (ui.chromeVisible) ui.chromeVisible = false
    }

    // 看门狗：真机上看不到日志，所以把「卡在哪一步」直接写在屏幕上。
    LaunchedEffect(book.id) {
        delay(9_000)
        if (ui.ready) return@LaunchedEffect
        val where = if (ui.stage.isNotBlank()) "卡在「${ui.stage}」" else "没有任何阶段回调"
        val stage = when {
            !ui.booted -> "页面脚本没能启动（Android 桥没连上）"
            !ui.pageLoaded -> "脚本启动了，但没通知页面就绪"
            else -> "书打不开：$where${lastError?.let { "，$it" } ?: ""}"
        }
        ui.error = stage
    }

    BackHandler {
        if (showToc) showToc = false else finish()
    }

    Box(Modifier.fillMaxSize().background(colors.page)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { webView })

        // 工具栏：覆盖在正文上，淡入淡出。正文不重排，只被柔和地遮一下。
        // Books 的样子：没有整条栏，只有上下两道柔和渐变 + 右下角一个菜单按钮 + 底部进度条。
        AnimatedVisibility(
            visible = ui.chromeVisible,
            enter = fadeIn(animationSpec = tween(190)),
            exit = fadeOut(animationSpec = tween(170)),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 上下两道柔和的遮罩，让文字在栏下面自然暗下去，而不是被硬切一刀。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.page.copy(alpha = 0.92f), colors.page.copy(alpha = 0f)),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.80f to Color.Transparent,
                                1f to colors.page.copy(alpha = 0.94f),
                            ),
                        ),
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    Spacer(Modifier.weight(1f))
                    ReaderSeekBar(
                        fraction = ui.fraction,
                        colors = colors,
                        onSeekPreview = { value -> ui.fraction = value },
                        onSeek = { value ->
                            webView.evaluateJavascript("window.lumen.goToFraction($value)", null)
                        },
                    )
                }
                // 右下角：菜单按钮。点开才是目录 / 书签 / 主题与字体。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 10.dp, bottom = 14.dp),
                ) {
                    IconButton(
                        onClick = {
                            ui.activity += 1
                            showMenu = true
                        },
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "菜单",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // 常驻信息层：左上书名（工具栏展开后点它变返回箭头）、顶中章节名、底中页码。
        ReaderMetaLayer(
            title = book.title,
            chapter = ui.chapter,
            page = ui.page,
            total = ui.totalPages,
            fraction = ui.fraction,
            chrome = ui.chromeVisible,
            backShown = ui.backShown,
            colors = colors,
            onTitleTap = {
                ui.activity += 1
                ui.backShown = !ui.backShown
            },
            onBack = { finish() },
        )

        if (!ui.ready && ui.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("正在翻开…", color = colors.textTertiary, fontSize = 14.sp)
                    if (ui.stage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(ui.stage, color = colors.textTertiary.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }

        ui.error?.let { message ->
            Box(
                Modifier.fillMaxSize().background(colors.page),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text("打不开这本书", color = colors.text, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        message,
                        color = colors.textTertiary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    TextButton(onClick = { finish() }) { Text("返回书库", color = colors.accent) }
                }
            }
        }
    }

    if (showMenu) {
        ReaderMenuSheet(
            hasToc = ui.toc.isNotEmpty(),
            bookmarkCount = bookmarks.size,
            bookmarked = currentBookmarked,
            colors = colors,
            onToc = { showMenu = false; showToc = true },
            onBookmarks = { showMenu = false; showToc = true },
            onStyle = { showMenu = false; showStyle = true },
            onToggleBookmark = {
                showMenu = false
                toggleBookmark()
            },
            onDismiss = { showMenu = false },
        )
    }

    if (showStyle) {
        ReaderStyleSheet(
            style = readerStyle,
            theme = theme,
            colors = colors,
            onTheme = { value -> scope.launch { onThemeChange(value) } },
            onFont = { value -> scope.launch { container.prefs.setFont(value) } },
            onAlign = { value -> scope.launch { container.prefs.setAlign(value) } },
            onIndent = { value -> scope.launch { container.prefs.setIndent(value) } },
            onFontSize = { value -> scope.launch { container.prefs.setFontSize(value) } },
            onLineHeight = { value -> scope.launch { container.prefs.setLineHeight(value) } },
            onMargin = { value -> scope.launch { container.prefs.setMargin(value) } },
            onDismiss = { showStyle = false },
        )
    }

    if (showToc) {
        ModalBottomSheet(
            onDismissRequest = { showToc = false },
            containerColor = colors.surface,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(440.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (bookmarks.isNotEmpty()) {
                    item {
                        Text(
                            "书签",
                            color = colors.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                        )
                    }
                    items(bookmarks, key = { it.id }) { mark ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = mark.label,
                                color = colors.text,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        showToc = false
                                        webView.evaluateJavascript(
                                            "window.lumen.goToCfi(${JSONObject.quote(mark.location)})",
                                            null,
                                        )
                                    }
                                    .padding(start = 24.dp, top = 12.dp, bottom = 12.dp),
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        container.library.removeBookmark(mark.id)
                                        bookmarks = container.library.bookmarks(book.id)
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除书签",
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    item {
                        Column {
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider(color = colors.separator)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                if (ui.toc.isNotEmpty()) {
                    item {
                        Text(
                            "目录",
                            color = colors.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                        )
                    }
                    items(ui.toc) { node ->
                        Text(
                            text = node.label,
                            color = if (node.depth == 0) colors.text else colors.textSecondary,
                            fontSize = if (node.depth == 0) 15.sp else 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showToc = false
                                    node.href?.let { href ->
                                        webView.evaluateJavascript(
                                            "window.lumen.goToHref(${JSONObject.quote(href)})",
                                            null,
                                        )
                                    }
                                }
                                .padding(
                                    start = 24.dp + (node.depth * 16).dp,
                                    end = 24.dp,
                                    top = 11.dp,
                                    bottom = 11.dp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
/**
 * 书签图标：material-icons-core 里没有 Bookmark，引 extended 又会把 APK 撑大十几 MB，
 * 索性用一条路径自己画——描边是「没加」，实心是「已加」。
 */
private fun BookmarkGlyph(color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.04f)
            lineTo(w * 0.84f, h * 0.04f)
            lineTo(w * 0.84f, h * 0.96f)
            lineTo(w * 0.50f, h * 0.72f)
            lineTo(w * 0.16f, h * 0.96f)
            close()
        }
        drawPath(path, color, style = if (filled) Fill else Stroke(width = h * 0.10f))
    }
}

/** 常驻信息层：左上书名（工具栏展开后点它变返回箭头）、顶中章节名、底中页码。 */
@Composable
private fun ReaderMetaLayer(
    title: String,
    chapter: String,
    page: Int,
    total: Int,
    fraction: Float,
    chrome: Boolean,
    backShown: Boolean,
    colors: LumenColors,
    onTitleTap: () -> Unit,
    onBack: () -> Unit,
) {
    val pageLabel = when {
        total > 0 && chrome -> "$page / $total"
        total > 0 -> "$page"
        else -> "${(fraction * 100).roundToInt()}%"
    }
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (chrome && backShown) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(30.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回书库",
                        tint = colors.text,
                        modifier = Modifier.size(19.dp),
                    )
                }
            } else {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .align(Alignment.CenterStart)
                        .clickable(onClick = onTitleTap)
                        .padding(vertical = 5.dp),
                )
            }
            Text(
                text = chapter,
                fontSize = 11.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth(0.74f)
                    .align(Alignment.Center)
                    .padding(vertical = 5.dp),
            )
        }
        Text(
            text = pageLabel,
            fontSize = 11.sp,
            color = colors.textTertiary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
        )
    }
}

/** 底部进度条：工具栏展开时才出现，只是一条细线，没有整条栏。 */
@Composable
private fun ReaderSeekBar(
    fraction: Float,
    colors: LumenColors,
    onSeekPreview: (Float) -> Unit,
    onSeek: (Float) -> Unit,
) {
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = onSeekPreview,
        onValueChangeFinished = { onSeek(fraction) },
        colors = SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.separator,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(26.dp),
    )
}

/** 右下角菜单按钮点开的单子：目录 / 书签 / 主题与字体。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderMenuSheet(
    hasToc: Boolean,
    bookmarkCount: Int,
    bookmarked: Boolean,
    colors: LumenColors,
    onToc: () -> Unit,
    onBookmarks: () -> Unit,
    onStyle: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(Modifier.padding(bottom = 18.dp)) {
            if (hasToc) {
                MenuRow("目录", colors = colors, onClick = onToc)
                HorizontalDivider(color = colors.separator, modifier = Modifier.padding(horizontal = 22.dp))
            }
            MenuRow(
                label = "书签",
                trailing = if (bookmarkCount > 0) "$bookmarkCount" else null,
                colors = colors,
                onClick = onBookmarks,
            )
            HorizontalDivider(color = colors.separator, modifier = Modifier.padding(horizontal = 22.dp))
            MenuRow("主题与字体", colors = colors, onClick = onStyle)
            HorizontalDivider(color = colors.separator, modifier = Modifier.padding(horizontal = 22.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleBookmark)
                    .padding(horizontal = 22.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (bookmarked) "取消这一页的书签" else "给这一页加书签",
                    fontSize = 15.sp,
                    color = colors.text,
                )
                Spacer(Modifier.weight(1f))
                BookmarkGlyph(
                    color = if (bookmarked) colors.accent else colors.textTertiary,
                    filled = bookmarked,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    colors: LumenColors,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = colors.text)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, fontSize = 13.sp, color = colors.textTertiary)
        }
    }
}

/** 阅读设置：主题、字体、对齐、字号、行距、边距、首行缩进。改一下即时重排，不用重开书。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderStyleSheet(
    style: ReaderStyle,
    theme: ReaderTheme,
    colors: LumenColors,
    onTheme: (ReaderTheme) -> Unit,
    onFont: (ReaderFont) -> Unit,
    onAlign: (ReaderAlign) -> Unit,
    onIndent: (Int) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onMargin: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 26.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("主题与字体", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))

            // 纸色
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(
                    ReaderTheme.PAPER,
                    ReaderTheme.WHITE,
                    ReaderTheme.GREEN,
                    ReaderTheme.BLUE,
                    ReaderTheme.INK,
                    ReaderTheme.BLACK,
                ).forEach { item ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(50))
                                .background(lumenColorsFor(item, isSystemInDarkTheme()).page)
                                .border(
                                    width = if (theme == item) 2.dp else 1.dp,
                                    color = if (theme == item) colors.accent else colors.separator,
                                    shape = RoundedCornerShape(50),
                                )
                                .clickable { onTheme(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (theme == item) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = if (item.dark) Color.White else colors.text,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.label,
                            fontSize = 9.sp,
                            color = if (theme == item) colors.text else colors.textTertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "「跟随系统」在设置里；这里选的是固定纸色。",
                fontSize = 11.sp,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(16.dp))
            Text("字体", fontSize = 12.sp, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { item ->
                    ChoiceChip(item.label, style.font == item, colors) { onFont(item) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("排版", fontSize = 12.sp, color = colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            StyleSlider(
                label = "字号",
                value = "${style.fontSize}",
                current = style.fontSize.toFloat(),
                range = ReaderStyle.FONT_RANGE,
                steps = 14,
                colors = colors,
                onChange = { onFontSize(it.roundToInt()) },
            )
            StyleSlider(
                label = "行距",
                value = String.format(Locale.US, "%.1f", style.lineHeight),
                current = style.lineHeight,
                range = ReaderStyle.LINE_RANGE,
                steps = 10,
                colors = colors,
                onChange = { onLineHeight((it * 10).roundToInt() / 10f) },
            )
            StyleSlider(
                label = "页边距",
                value = "${style.margin}",
                current = style.margin.toFloat(),
                range = ReaderStyle.MARGIN_RANGE,
                steps = 14,
                colors = colors,
                onChange = { onMargin((it / 4).roundToInt() * 4) },
            )
            StyleSlider(
                label = "首行缩进",
                value = if (style.indent == 0) "无" else "${style.indent} 字",
                current = style.indent.toFloat(),
                range = ReaderStyle.INDENT_RANGE,
                steps = 2,
                colors = colors,
                onChange = { onIndent(it.roundToInt()) },
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderAlign.entries.forEach { item ->
                    ChoiceChip(item.label, style.align == item, colors) { onAlign(item) }
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, colors: LumenColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.accent else colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) Color.White else colors.textSecondary,
        )
    }
}

@Composable
private fun StyleSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    colors: LumenColors,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = colors.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(value, color = colors.textTertiary, fontSize = 12.sp)
        }
        Slider(
            value = current.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.separator,
            ),
            modifier = Modifier.height(30.dp),
        )
    }
}

@Composable
private fun UnsupportedFormatScreen(book: Book, colors: LumenColors, onClose: () -> Unit) {
    BackHandler { onClose() }
    Box(
        Modifier.fillMaxSize().background(colors.page).statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                book.format.label,
                color = colors.textTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            Text("这个格式还没接阅读内核", color = colors.text, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                when (book.format) {
                    BookFormat.PDF -> "PDF 阅读安排在下一步（原生 PdfRenderer）。"
                    BookFormat.TXT -> "TXT 会先探测编码、拆章节，再走 EPUB 的排版。"
                    else -> "稍后的版本会支持。"
                },
                color = colors.textTertiary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onClose) { Text("返回书库", color = colors.accent) }
        }
    }
}
