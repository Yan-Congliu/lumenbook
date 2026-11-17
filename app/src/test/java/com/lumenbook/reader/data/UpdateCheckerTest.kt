package com.lumenbook.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新检查的解析与版本比较（不发网络请求，纯逻辑）。 */
class UpdateCheckerTest {

    private val releaseJson = """
        {
          "tag_name": "v0.6.0",
          "published_at": "2026-11-18T00:00:00Z",
          "body": "修好了封面写回。",
          "assets": [
            {"name": "lumenbook-0.6.0-arm64-v8a.apk", "browser_download_url": "https://example.com/a.apk"},
            {"name": "lumenbook-0.6.0-x86_64.apk", "browser_download_url": "https://example.com/b.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun `有新版时给出 arm64 的下载地址`() {
        val result = UpdateChecker.parse(releaseJson, "0.5.0-debug")
        assertTrue(result is UpdateResult.Available)
        val release = (result as UpdateResult.Available).release
        assertEquals("0.6.0", release.versionName)
        assertEquals("https://example.com/a.apk", release.apkUrl)
        assertEquals("修好了封面写回。", release.notes)
    }

    @Test
    fun `版本一样时算最新`() {
        val result = UpdateChecker.parse(releaseJson, "0.6.0")
        assertTrue(result is UpdateResult.UpToDate)
    }

    @Test
    fun `本地版本更新时也算最新`() {
        val result = UpdateChecker.parse(releaseJson, "0.7.2")
        assertTrue(result is UpdateResult.UpToDate)
    }

    @Test
    fun `没有 APK 资源时给出说明`() {
        val json = """{"tag_name":"v9.9.9","assets":[]}"""
        val result = UpdateChecker.parse(json, "0.5.0")
        assertTrue(result is UpdateResult.Failed)
    }

    @Test
    fun `版本号归一化`() {
        assertEquals("0.5.0", UpdateChecker.normalize("v0.5.0"))
        assertEquals("0.5.0", UpdateChecker.normalize("0.5.0-debug"))
        assertEquals("0.5.0", UpdateChecker.normalize("0.5.0 (15)"))
    }

    @Test
    fun `版本比较`() {
        assertTrue(UpdateChecker.compare("0.10.0", "0.9.4") > 0)
        assertTrue(UpdateChecker.compare("1.0.0", "1.0.0") == 0)
        assertTrue(UpdateChecker.compare("0.5", "0.5.1") < 0)
    }
}
