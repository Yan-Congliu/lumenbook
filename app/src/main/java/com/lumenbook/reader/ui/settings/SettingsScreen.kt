package com.lumenbook.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.BuildConfig
import com.lumenbook.reader.data.ApkInstaller
import com.lumenbook.reader.data.Appearance
import com.lumenbook.reader.data.RemoteRelease
import com.lumenbook.reader.data.UpdateChecker
import com.lumenbook.reader.data.UpdateResult
import com.lumenbook.reader.ui.theme.LocalLumenColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    appearance: Appearance,
    onAppearanceChange: (Appearance) -> Unit,
) {
    val colors = LocalLumenColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    var found by remember { mutableStateOf<RemoteRelease?>(null) }
    var progress by remember { mutableStateOf(0f) }

    // 只有点了「检查更新」才会真的发请求；联网只做这一件事
    LaunchedEffect(updateState) {
        if (updateState !== UpdateUi.Checking) return@LaunchedEffect
        updateState = when (val result = UpdateChecker.check(BuildConfig.VERSION_NAME)) {
            is UpdateResult.UpToDate -> UpdateUi.UpToDate(result.current)
            is UpdateResult.Failed -> UpdateUi.Failed(result.message)
            is UpdateResult.Available -> {
                found = result.release
                UpdateUi.Idle
            }
        }
    }

    found?.let { release ->
        ReleaseDialog(
            release = release,
            progress = progress,
            onDismiss = { if (progress <= 0f) found = null },
            onInstall = {
                if (!ApkInstaller.canInstall(context)) {
                    // 系统设置里给了「安装未知应用」的权限才能装
                    ApkInstaller.openInstallPermission(context)
                } else {
                    scope.launch {
                        runCatching {
                            val file = ApkInstaller.download(context, release.apkUrl, release.versionName) { p ->
                                progress = p
                            }
                            ApkInstaller.install(context, file)
                        }.onFailure { e ->
                            updateState = UpdateUi.Failed(e.message ?: "下载失败")
                        }
                        progress = 0f
                        found = null
                    }
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "设置",
            fontFamily = FontFamily.Serif,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            color = colors.text,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 12.dp),
        )

        SectionTitle("外观")
        Card {
            ChoiceRow("跟随系统", appearance == Appearance.SYSTEM) { onAppearanceChange(Appearance.SYSTEM) }
            Divider()
            ChoiceRow("浅色（米纸）", appearance == Appearance.LIGHT) { onAppearanceChange(Appearance.LIGHT) }
            Divider()
            ChoiceRow("深色（墨纸）", appearance == Appearance.DARK) { onAppearanceChange(Appearance.DARK) }
        }

        SectionTitle("页面背景")
        Card {
            ChoiceRow("纸张（跟随外观）", selected = true, onClick = null)
            Divider()
            ChoiceRow("自定义背景图", selected = false, onClick = null, enabled = false)
        }
        Text(
            text = "背景图的接口已经留好了，等阅读内核做完再接上。",
            fontSize = 11.sp,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp),
        )

        SectionTitle("更新")
        Card {
            UpdateRow(
                state = updateState,
                onCheck = { if (updateState !== UpdateUi.Checking) updateState = UpdateUi.Checking },
            )
        }

        SectionTitle("关于")
        Card {
            InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Divider()
            InfoRow("格式", "EPUB · PDF · TXT · MOBI · AZW3 · FB2 · CBZ")
            Divider()
            InfoRow("仓库", "github.com/Yan-Congliu/lumenbook")
        }

        Text(
            text = "鎏书 LumenBook · 本地离线阅读，数据只存在这台手机上",
            fontSize = 11.sp,
            color = colors.textTertiary,
            modifier = Modifier.padding(22.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = LocalLumenColors.current.textSecondary,
        modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = LocalLumenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface),
    ) {
        content()
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        color = LocalLumenColors.current.separator,
        modifier = Modifier.padding(start = 22.dp),
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val colors = LocalLumenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = null)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = when {
                !enabled -> colors.textTertiary
                else -> colors.text
            },
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) colors.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalLumenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = colors.text)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 12.sp, color = colors.textSecondary)
    }
}

/** 「检查更新」这一行的几种状态。 */
private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val current: String) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

@Composable
private fun UpdateRow(state: UpdateUi, onCheck: () -> Unit) {
    val colors = LocalLumenColors.current
    val status = when (state) {
        UpdateUi.Idle -> "从 GitHub 上看看有没有新版本"
        UpdateUi.Checking -> "正在检查…"
        is UpdateUi.UpToDate -> "已是最新版本（${state.current}）"
        is UpdateUi.Failed -> state.message
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCheck, onLongClick = null)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("检查更新", fontSize = 15.sp, color = colors.text)
            Text(status, fontSize = 11.sp, color = colors.textTertiary)
        }
        if (state === UpdateUi.Checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun ReleaseDialog(
    release: RemoteRelease,
    progress: Float,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val colors = LocalLumenColors.current
    AlertDialog(
        onDismissRequest = { if (progress <= 0f) onDismiss() },
        title = { Text("发现新版本 ${release.versionName}", fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (release.notes.isNotBlank()) {
                    Text(
                        text = release.notes,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                }
                if (progress > 0f) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "正在下载 ${(progress * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = colors.textTertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = progress <= 0f) {
                Text(if (progress > 0f) "下载中…" else "下载并安装")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = progress <= 0f) { Text("稍后") }
        },
    )
}
