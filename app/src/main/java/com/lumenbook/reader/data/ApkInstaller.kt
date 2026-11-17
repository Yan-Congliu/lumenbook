package com.lumenbook.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载新版本 APK 并交给系统安装器。
 *
 * 走自己的 HttpURLConnection 而不是 DownloadManager：进度能直接显示在设置页，
 * 而且不用注册广播接收器、不用申请通知权限。
 */
object ApkInstaller {

    /** 应用内安装需要用户在本机允许「安装未知应用」。 */
    fun canInstall(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermission(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    suspend fun download(
        context: Context,
        url: String,
        versionName: String,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        // 每次下载都清掉旧的，免得用户装到上一个版本
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "lumenbook-$versionName.apk")
        val partial = File(dir, "lumenbook-$versionName.apk.part")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LumenBook-Android")
        }
        try {
            if (connection.responseCode !in 200..299) error("下载失败（HTTP ${connection.responseCode}）")
            val total = connection.contentLengthLong
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            onProgress(1f)
            target
        } finally {
            connection.disconnect()
        }
    }

    /** 把下载好的 APK 交给系统安装界面。 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
