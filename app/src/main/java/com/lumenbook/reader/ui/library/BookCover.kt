package com.lumenbook.reader.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.ui.theme.SmoothRoundedCornerShape
import com.lumenbook.reader.ui.theme.SmoothPillShape
import com.lumenbook.reader.data.Book
import com.lumenbook.reader.ui.theme.coverPaletteFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 封面。[book.coverPath] 有图就画图，没有就用书名散列出一套渐变，保证书库不空单调。 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 7.dp,
) {
    val shape = SmoothRoundedCornerShape(corner)
    val (c1, c2) = coverPaletteFor(book.title)
    val bitmap by produceState<ImageBitmap?>(initialValue = book.coverPath?.let { CoverCache.get(it) }, book.coverPath) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            book.coverPath?.let { path ->
                CoverCache.get(path) ?: runCatching { decodeSampled(File(path), 480)?.asImageBitmap() }
                    .getOrNull()
                    ?.also { CoverCache.put(path, it) }
            }
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(c1, c2))),
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            ) {
                Text(
                    text = book.title,
                    color = Color.White.copy(alpha = 0.95f),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!book.author.isNullOrBlank()) {
                    Text(
                        text = book.author,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }

        // 阅读进度：底部细线
        if (book.progress > 0f && !book.finished) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(book.progress.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.55f)),
            )
        }

        if (book.finished) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(SmoothPillShape())
                    .background(Color(0xFFF2ECE1))
                    .padding(2.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已读完",
                    tint = Color(0xFF1A1917),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** 书库封面小图的内存缓存：滚动时不再每次从磁盘重新解码，同时限制总占用（省的也是内存）。 */
internal object CoverCache {
    /** 24MB 上限，按 RGB_565 每像素 2 字节折算。 */
    private const val MAX_KB = 24 * 1024

    private val cache = object : android.util.LruCache<String, ImageBitmap>(MAX_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width * value.height * 2) / 1024

        // 被淘汰的图交给 GC，不主动 recycle：上一帧可能还在画
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: ImageBitmap, newValue: ImageBitmap?) = Unit
    }

    fun get(path: String): ImageBitmap? = cache.get(path)

    fun put(path: String, bitmap: ImageBitmap) {
        cache.put(path, bitmap)
    }

    fun clear() = cache.evictAll()
}

/** 只为缩略图解码，先读 bounds 再按 2 的幂降采样，避免在大图上吃内存。*/
internal fun decodeSampled(file: File, targetWidth: Int): android.graphics.Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
}
