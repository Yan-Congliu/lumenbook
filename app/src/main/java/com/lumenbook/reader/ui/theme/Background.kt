package com.lumenbook.reader.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 页面背景的扩展点。阶段 2 只有纯色两种（米色/深色，由 LumenColors.page 决定），
 * 以后「设置里换背景」只要新增一种 [LumenBackground.Image] 的 UI 入口即可，
 * 书库/阅读页不需要改。
 */
sealed interface LumenBackground {
    /** 用主题自带的纸色。*/
    data object Paper : LumenBackground

    /** 用户选的纯色。*/
    data class Solid(val color: Color) : LumenBackground

    /** 用户选的背景图（本地文件路径），dim 是压暗程度 0..1，保证文字可读。*/
    data class Image(val path: String, val dim: Float = 0.25f) : LumenBackground
}

/** 按背景类型铺底，内容画在上面。*/
@Composable
fun LumenBackgroundLayer(
    background: LumenBackground,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalLumenColors.current
    when (background) {
        is LumenBackground.Paper -> Box(modifier.background(colors.page), content = content)
        is LumenBackground.Solid -> Box(modifier.background(background.color), content = content)
        is LumenBackground.Image -> Box(modifier.background(colors.page)) {
            val bitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, background.path) {
                value = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(File(background.path).absolutePath)?.asImageBitmap() }
                        .getOrNull()
                }
            }.value
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (background.dim > 0f) {
                    Box(Modifier.fillMaxSize().background(colors.page.copy(alpha = background.dim.coerceIn(0f, 1f))))
                }
            }
            content()
        }
    }
}

/** 未来做「按背景自动选字色」时用得上，先留个明确的形状。*/
fun LumenColors.onBackgroundOf(background: LumenBackground): Color = when (background) {
    is LumenBackground.Paper -> text
    is LumenBackground.Solid, is LumenBackground.Image -> text
}

@Suppress("unused")
private val noFilter: ColorFilter? = null
