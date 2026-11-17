package com.lumenbook.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 自定义背景图：从相册里挑一张，采样解码到 1600px 以内、压成 JPEG 存到
 * `filesDir/backgrounds/bg.jpg`，只保留一张（换图就覆盖）。
 *
 * 为什么要缩：手机相册里动辄 4000x3000、几 MB 的原图，直接铺底会吃掉大量内存，
 * 而且背景是全屏 Crop，1600px 已经远超屏幕实际需要。
 */
object BackgroundStore {

    private const val MAX_SIDE = 1600
    private const val QUALITY = 88

    suspend fun save(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null

            val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
            val target = File(dir, "bg.jpg")
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            bitmap.recycle()
            target.absolutePath
        }.getOrNull()
    }

    /** 清掉已经存下的背景图文件（设置里「清除背景图」时调用）。 */
    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, "backgrounds").listFiles()?.forEach { it.delete() }
        }
    }
}
