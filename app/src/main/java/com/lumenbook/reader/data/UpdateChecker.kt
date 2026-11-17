package com.lumenbook.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases 上的一个正式版本。 */
data class RemoteRelease(
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val publishedAt: String,
)

sealed interface UpdateResult {
    /** 已经是最新的（或本地比线上还新，比如自己装的 debug 包）。 */
    data class UpToDate(val current: String) : UpdateResult

    data class Available(val release: RemoteRelease) : UpdateResult

    data class Failed(val message: String) : UpdateResult
}

/**
 * 检查 GitHub Releases 上有没有新版本。
 *
 * 只发一个 GET 请求（api.github.com，国内基本可达），失败一律当成「检查不了」而不是崩溃：
 * 这是个离线也能用的阅读器，联网只为了更新。
 */
object UpdateChecker {

    const val REPO = "Yan-Congliu/lumenbook"
    private const val ENDPOINT = "https://api.github.com/repos/$REPO/releases/latest"

    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val json = fetch(ENDPOINT)
            parse(json, currentVersion)
        }.getOrElse { e ->
            UpdateResult.Failed(e.message ?: "网络不给力，稍后再试")
        }
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LumenBook-Android")
        }
        try {
            val code = connection.responseCode
            if (code == 404) error("仓库还没有发布过版本")
            if (code != 200) error("GitHub 返回 $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** 纯函数，方便单测：把 release JSON 变成一个结果。 */
    fun parse(json: String, currentVersion: String): UpdateResult {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").ifBlank {
            return UpdateResult.Failed("这个 release 没有版本号")
        }
        val remoteVersion = normalize(tag)

        val assets = root.optJSONArray("assets")
        val apkUrl = assets?.let { array ->
            (0 until array.length())
                .map { array.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith("-arm64-v8a.apk") }
                ?: (0 until array.length())
                    .map { array.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
        }?.optString("browser_download_url")

        if (apkUrl.isNullOrBlank()) {
            return UpdateResult.Failed("这个版本没有可下载的 APK")
        }

        return if (compare(remoteVersion, normalize(currentVersion)) > 0) {
            UpdateResult.Available(
                RemoteRelease(
                    versionName = remoteVersion,
                    apkUrl = apkUrl,
                    notes = root.optString("body").trim(),
                    publishedAt = root.optString("published_at"),
                ),
            )
        } else {
            UpdateResult.UpToDate(currentVersion)
        }
    }

    /** "v0.5.0" / "0.5.0-debug" / "0.5.0 (15)" 统统削成 "0.5.0"。 */
    fun normalize(version: String): String =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore(' ')
            .substringBefore('-')
            .substringBefore('+')

    /** 数字逐段比较；返回 >0 表示 a 更新。 */
    fun compare(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val x = left.getOrElse(index) { 0 }
            val y = right.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
