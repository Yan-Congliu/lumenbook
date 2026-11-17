package com.lumenbook.reader.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (index !in 0 until pageCount || widthPx <= 0) return@withLock null
            val page = runCatching { renderer.openPage(index) }.getOrNull() ?: return@withLock null
            try {
                val scale = widthPx.toFloat() / page.width
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.RGB_565)
                // PDF 的页面一般是白纸，先铺白再画，免得黑底。
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (e: Throwable) {
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

    val document = remember(book.localPath) {
        runCatching { PdfDocument(File(book.localPath)) }.getOrNull()
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
}

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
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { androidx.compose.runtime.mutableStateOf(Offset.Zero) }

    LaunchedEffect(index, widthPx) {
        bitmap = document.render(index, widthPx)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
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
                    .pointerInput(index) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            offset = if (scale <= 1.01f) {
                                Offset.Zero
                            } else {
                                Offset(offset.x + pan.x, offset.y + pan.y)
                            }
                        }
                    }
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
