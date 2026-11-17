package com.lumenbook.reader.ui.reader

import android.annotation.SuppressLint
import android.os.Build
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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
import com.lumenbook.reader.ui.theme.LumenColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

private const val ASSET_DOMAIN = "appassets.androidplatform.net"

/** 目录里的一项，depth 用于缩进。 */
data class TocNode(val label: String, val href: String?, val depth: Int)

/** 阅读页的 UI 状态。JS 桥在 WebView 线程回调，统一 post 到主线程再写。 */
private class ReaderUi {
    var pageLoaded by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var fraction by mutableStateOf(0f)
    var chapter by mutableStateOf("")
    var toc by mutableStateOf<List<TocNode>>(emptyList())
    var chromeVisible by mutableStateOf(true)
    var cfi by mutableStateOf<String?>(null)
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

/** 传给网页的排版参数；以后改字号/主题只要重新发一次。 */
private fun themeJson(colors: LumenColors): String = JSONObject().apply {
    put("paper", colors.page.hex())
    put("ink", colors.text.hex())
    put("accent", colors.accent.hex())
    put("fontSize", 19)
    put("lineHeight", 1.85)
    put("margin", 40)
    put("justify", true)
}.toString()

/** 把书库里的私有副本作为 /book/ 路径喂给网页（同源，免 CORS）。 */
private class BookFileHandler(
    private val file: File,
    private val mime: String,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        if (!file.isFile) {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream("书文件不见了".toByteArray()),
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

/** JS 侧的 Android 对象。 */
private class ReaderBridge(
    private val ui: ReaderUi,
    private val main: Handler,
    private val onLocation: (String?, Float) -> Unit,
) {
    @JavascriptInterface
    fun onPageLoad() {
        main.post { ui.pageLoaded = true }
    }

    @JavascriptInterface
    fun onReady(json: String?) {
        val obj = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
        val toc = mutableListOf<TocNode>()
        val arr = obj.optJSONArray("toc")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                toc += TocNode(
                    label = o.optString("label", "(无标题)"),
                    href = o.optString("href", "").ifBlank { null },
                    depth = o.optInt("depth", 0),
                )
            }
        }
        val fraction = obj.optDouble("fraction", 0.0).toFloat()
        main.post {
            ui.ready = true
            ui.error = null
            ui.toc = toc
            if (fraction > 0f) ui.fraction = fraction
        }
    }

    @JavascriptInterface
    fun onRelocate(cfi: String?, json: String?) {
        val obj = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
        val fraction = obj.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f)
        val label = obj.optString("label", "")
        main.post {
            ui.fraction = fraction
            ui.cfi = cfi
            if (label.isNotBlank()) ui.chapter = label
        }
        onLocation(cfi, fraction)
    }

    @JavascriptInterface
    fun onError(message: String?) {
        main.post { ui.error = message ?: "打开失败" }
    }

    @JavascriptInterface
    fun onToggleChrome() {
        main.post { ui.chromeVisible = !ui.chromeVisible }
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
    onFinish: (WebView, String) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(background)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
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

        override fun onPageFinished(view: WebView, url: String) = onFinish(view, url)
    }
    addJavascriptInterface(bridge, "Android")
    loadUrl("https://$ASSET_DOMAIN/assets/reader/index.html")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book,
    container: AppContainer,
    colors: LumenColors,
    onClose: () -> Unit,
) {
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
    var opened by remember { mutableStateOf(false) }
    val lastSaveAt = remember { longArrayOf(0L) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val assetLoader = remember(book.localPath) {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/book/", BookFileHandler(File(book.localPath), book.format.mime()))
            .build()
    }

    val bridge = remember(book.id) {
        ReaderBridge(ui, mainHandler) { cfi, fraction ->
            val now = System.currentTimeMillis()
            if (now - lastSaveAt[0] > 2_000L) {
                lastSaveAt[0] = now
                scope.launch { container.library.saveLocation(book.id, cfi, fraction) }
            }
        }
    }

    val openScript = remember(book.id) {
        val url = "https://$ASSET_DOMAIN/book/${book.id}.${book.format.name.lowercase()}"
        "window.lumen.open(${JSONObject.quote(url)}, ${JSONObject.quote(book.location ?: "")}, ${themeJson(colors)})"
    }

    /** 退出阅读页前的收尾：落库最后位置，并销毁 WebView。 */
    val finish = {
        val cfi = ui.cfi
        val fraction = ui.fraction
        if (ui.ready) {
            scope.launch { container.library.saveLocation(book.id, cfi, fraction) }
        }
        onClose()
    }

    val webView = remember(book.id) {
        buildReaderWebView(
            context = context,
            loader = assetLoader,
            bridge = bridge,
            background = colors.page.toArgb(),
            onFinish = { view, _ ->
                // 兜底：万一 onPageLoad 没到（脚本报错时），这里再催一次
                view.evaluateJavascript(
                    "window.lumen ? 1 : 0",
                    null,
                )
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

    BackHandler {
        if (showToc) showToc = false else finish()
    }

    Box(Modifier.fillMaxSize().background(colors.page)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { webView })

        AnimatedVisibility(visible = ui.chromeVisible, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize()) {
                ReaderTopBar(
                    title = book.title,
                    subtitle = ui.chapter,
                    colors = colors,
                    onBack = { finish() },
                    onToc = { if (ui.toc.isNotEmpty()) showToc = true },
                )
                Spacer(Modifier.weight(1f))
                ReaderBottomBar(
                    fraction = ui.fraction,
                    colors = colors,
                    onSeekPreview = { value -> ui.fraction = value },
                    onSeek = { value ->
                        webView.evaluateJavascript("window.lumen.goToFraction($value)", null)
                    },
                )
            }
        }

        if (!ui.ready && ui.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在翻开…", color = colors.textTertiary, fontSize = 14.sp)
            }
        }

        ui.error?.let { message ->
            Box(
                Modifier.fillMaxSize().background(colors.page),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("打不开这本书", color = colors.text, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = colors.textTertiary, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { finish() }) { Text("返回书库", color = colors.accent) }
                }
            }
        }
    }

    if (showToc) {
        ModalBottomSheet(
            onDismissRequest = { showToc = false },
            containerColor = colors.surface,
        ) {
            Text(
                "目录",
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
            HorizontalDivider(color = colors.separator)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(420.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
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

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String,
    colors: LumenColors,
    onBack: () -> Unit,
    onToc: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.page.copy(alpha = 0.97f))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = colors.text,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(
                title,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = colors.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToc) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = "目录",
                tint = colors.text,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    fraction: Float,
    colors: LumenColors,
    onSeekPreview: (Float) -> Unit,
    onSeek: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.page.copy(alpha = 0.97f))
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        HorizontalDivider(color = colors.separator)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${(fraction * 100).toInt()}%",
                color = colors.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.width(44.dp),
            )
            Slider(
                value = fraction.coerceIn(0f, 1f),
                onValueChange = onSeekPreview,
                onValueChangeFinished = { onSeek(fraction) },
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.separator,
                ),
                modifier = Modifier.weight(1f).height(32.dp),
            )
        }
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
