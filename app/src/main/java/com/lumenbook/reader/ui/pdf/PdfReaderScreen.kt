package com.lumenbook.reader.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.lumenbook.reader.data.AppContainer
import com.lumenbook.reader.data.Book
import com.lumenbook.reader.data.Bookmark
import com.lumenbook.reader.ui.theme.LumenColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/** PdfRenderer 不是线程安全的，所有渲染都串行化。 */
private class PdfDocument(file: File) : Closeable {
    private val descriptor: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()

    val pageCount: Int get() = renderer.pageCount

    /** 上一次渲染失败的原因：真机上看不到 logcat，只能把原因画到屏幕上。 */
    var lastError: String? = null
        private set

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastError = null
            if (index !in 0 until pageCount || widthPx <= 0) {
                lastError = "页码越界"
                return@withLock null
            }
            val page = runCatching { renderer.openPage(index) }.getOrElse { t ->
                lastError = "openPage 失败：${t.javaClass.simpleName} ${t.message.orEmpty()}"
                return@withLock null
            }
            try {
                val scale = widthPx.toFloat() / page.width
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                // PdfRenderer 只接受 ARGB_8888；RGB_565 会直接抛异常，页面一片空白（0.6.6 修）。
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                // PDF 的页面一般是白纸，先铺白再画，免得黑底。
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (t: Throwable) {
                lastError = "render 失败：${t.javaClass.simpleName} ${t.message.orEmpty()}"
                null
            } finally {
                runCatching { page.close() }
            }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/** 「页」存在 location 字段里：page:12（从 0 开始）。 */
private fun savedPage(location: String?): Int =
    location?.removePrefix("page:")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    book: Book,
    container: AppContainer,
    colors: LumenColors,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = remember(configuration.screenWidthDp) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }
    }

    var openError by remember(book.localPath) { mutableStateOf<String?>(null) }
    val document = remember(book.localPath) {
        runCatching { PdfDocument(File(book.localPath)) }.getOrElse { t ->
            openError = "${t.javaClass.simpleName} ${t.message.orEmpty()}"
            null
        }
    }

    DisposableEffect(book.localPath) {
        onDispose { document?.close() }
    }

    if (document == null) {
        Box(Modifier.fillMaxSize().background(colors.page), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("打不开这个 PDF", color = colors.text, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Text("文件可能损坏或加了密码。", color = colors.textTertiary, fontSize = 13.sp)
                openError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = colors.textTertiary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(18.dp))
                TextButton(onClick = onClose) { Text("返回书库", color = colors.accent) }
            }
        }
        return
    }

    val pageCount = document.pageCount
    val startPage = savedPage(book.location).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = startPage) { pageCount.coerceAtLeast(1) }
    var chromeVisible by remember { mutableStateOf(true) }
    var renderedPage by remember { mutableIntStateOf(startPage) }
    var bookmarks by remember(book.id) { mutableStateOf<List<Bookmark>>(emptyList()) }
    var showBookmarks by remember { mutableStateOf(false) }

    val currentLocation = "page:$renderedPage"
    val currentBookmarked = bookmarks.any { it.location == currentLocation }

    LaunchedEffect(book.id) { bookmarks = container.library.bookmarks(book.id) }

    // 翻页落库（松手后 300ms 才算数，避免快速滑动时反复写库）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            renderedPage = page
            val fraction = if (pageCount > 1) page.toFloat() / (pageCount - 1) else 0f
            container.library.saveLocation(book.id, "page:$page", fraction)
        }
    }

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(colors.page)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { it },
        ) { index ->
            PdfPage(
                document = document,
                index = index,
                widthPx = (screenWidthPx - with(density) { 24.dp.roundToPx() }).coerceAtLeast(240),
                colors = colors,
                onCenterTap = { chromeVisible = !chromeVisible },
                onLeftTap = { if (index > 0) scope.launch { pagerState.animateScrollToPage(index - 1) } },
                onRightTap = {
                    if (index < pageCount - 1) scope.launch { pagerState.animateScrollToPage(index + 1) }
                },
            )
        }

        if (chromeVisible) {
            Column(Modifier.fillMaxSize()) {
                PdfTopBar(
                    title = book.title,
                    subtitle = "${renderedPage + 1} / $pageCount",
                    colors = colors,
                    bookmarked = currentBookmarked,
                    hasBookmarks = bookmarks.isNotEmpty(),
                    onBookmark = {
                        scope.launch {
                            container.library.toggleBookmark(book.id, currentLocation, "第 ${renderedPage + 1} 页")
                            bookmarks = container.library.bookmarks(book.id)
                        }
                    },
                    onList = { if (bookmarks.isNotEmpty()) showBookmarks = true },
                    onBack = onClose,
                )
                Spacer(Modifier.weight(1f))
                PdfBottomBar(
                    page = renderedPage,
                    pageCount = pageCount,
                    colors = colors,
                    onSeekPreview = { value -> renderedPage = value },
                    onSeek = { value ->
                        scope.launch { pagerState.scrollToPage(value.coerceIn(0, pageCount - 1)) }
                    },
                )
            }
        }
    }

    if (showBookmarks) {
        ModalBottomSheet(
            onDismissRequest = { showBookmarks = false },
            containerColor = colors.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    "书签",
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                )
                bookmarks.forEach { mark ->
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
                                    showBookmarks = false
                                    scope.launch {
                                        pagerState.scrollToPage(mark.pageOf().coerceIn(0, pageCount - 1))
                                    }
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
            }
        }
    }
}

/** 书签位置 → 页码。PDF 的位置格式就是 `page:N`。 */
private fun Bookmark.pageOf(): Int = location.removePrefix("page:").toIntOrNull() ?: 0

/** 一页 PDF：按视口宽度渲染，支持双指缩放（放大后可拖）。 */
@Composable
private fun PdfPage(
    document: PdfDocument,
    index: Int,
    widthPx: Int,
    colors: LumenColors,
    onCenterTap: () -> Unit,
    onLeftTap: () -> Unit,
    onRightTap: () -> Unit,
) {
    var bitmap by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
    var error by remember(index, widthPx) { mutableStateOf<String?>(null) }
    var attempt by remember(index, widthPx) { mutableIntStateOf(0) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale <= 1.01f) {
            Offset.Zero
        } else {
            Offset(offset.x + panChange.x, offset.y + panChange.y)
        }
    }

    LaunchedEffect(index, widthPx, attempt) {
        bitmap = null
        error = null
        val rendered = document.render(index, widthPx)
        bitmap = rendered
        if (rendered == null) error = document.lastError ?: "未知原因"
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        val failure = error
        if (image == null && failure != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(28.dp),
            ) {
                Text("这一页渲染失败", color = colors.text, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(failure, color = colors.textTertiary, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { attempt++ }) { Text("重试", color = colors.accent) }
            }
        } else if (image == null) {
            Text("正在渲染…", color = colors.textTertiary, fontSize = 12.sp)
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    // canPan 只在放大后才为真：没放大时单指拖动必须放行给 Pager，否则翻不动页。
                    // 双指缩放始终可用。
                    .transformable(
                        state = transformState,
                        canPan = { scale > 1.01f },
                    )
                    .pointerInput(index) {
                        detectTapZones(
                            onCenter = onCenterTap,
                            onLeft = onLeftTap,
                            onRight = onRightTap,
                            isZoomed = { scale > 1.01f },
                        )
                    },
            )
        }
    }
}

/** 左右三分区轻点：左边上一页、中间呼出工具栏、右边下一页（放大时只用来拖）。 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapZones(
    onCenter: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    isZoomed: () -> Boolean,
) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var moved = false
            var up = down
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - start).getDistance() > 12f) moved = true
                up = change
            } while (change.pressed)
            if (moved) continue
            val width = size.width.toFloat().coerceAtLeast(1f)
            val ratio = (up.position.x / width).coerceIn(0f, 1f)
            if (isZoomed()) {
                onCenter()
            } else when {
                ratio < 0.30f -> onLeft()
                ratio > 0.70f -> onRight()
                else -> onCenter()
            }
        }
    }
}

@Composable
private fun PdfTopBar(
    title: String,
    subtitle: String,
    colors: LumenColors,
    bookmarked: Boolean,
    hasBookmarks: Boolean,
    onBookmark: () -> Unit,
    onList: () -> Unit,
    onBack: () -> Unit,
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
            Text(subtitle, color = colors.textTertiary, fontSize = 11.sp)
        }
        IconButton(onClick = onBookmark) {
            Canvas(Modifier.size(19.dp)) {
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
                drawPath(
                    path,
                    if (bookmarked) colors.accent else colors.text,
                    style = if (bookmarked) Fill else Stroke(width = h * 0.10f),
                )
            }
        }
        if (hasBookmarks) {
            IconButton(onClick = onList) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = "书签列表",
                    tint = colors.text,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
private fun PdfBottomBar(
    page: Int,
    pageCount: Int,
    colors: LumenColors,
    onSeekPreview: (Int) -> Unit,
    onSeek: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.page.copy(alpha = 0.97f))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        HorizontalDivider(color = colors.separator)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${page + 1}",
                color = colors.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.width(32.dp),
            )
            Slider(
                value = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)).toFloat(),
                onValueChange = { onSeekPreview(it.roundToInt()) },
                onValueChangeFinished = { onSeek(page.coerceIn(0, pageCount - 1)) },
                valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.separator,
                ),
                modifier = Modifier.weight(1f).height(32.dp),
            )
            Text(
                "$pageCount",
                color = colors.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.width(32.dp).padding(start = 8.dp),
            )
        }
    }
}
