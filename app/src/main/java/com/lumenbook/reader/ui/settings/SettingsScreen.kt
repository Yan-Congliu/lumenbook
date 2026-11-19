package com.lumenbook.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import com.lumenbook.reader.data.LibraryStyle
import com.lumenbook.reader.data.ReaderTheme
import com.lumenbook.reader.data.RemoteRelease
import com.lumenbook.reader.data.UpdateChecker
import com.lumenbook.reader.data.UpdateResult
import com.lumenbook.reader.ui.theme.LocalLumenColors
import com.lumenbook.reader.ui.theme.lumenColorsFor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    theme: ReaderTheme,
    onThemeChange: (ReaderTheme) -> Unit,
    libraryStyle: LibraryStyle,
    onColumnsChange: (Int) -> Unit,
    onCoverRadiusChange: (Int) -> Unit,
    onShowAuthorChange: (Boolean) -> Unit,
    backgroundImage: String?,
    backgroundDim: Float,
    onBackgroundDimChange: (Float) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
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
            ChoiceRow("跟随系统", theme == ReaderTheme.SYSTEM) { onThemeChange(ReaderTheme.SYSTEM) }
            Divider()
            Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
                Text("纸张主题", fontSize = 13.sp, color = colors.textSecondary)
                Spacer(Modifier.height(10.dp))
                // 横向可滑动：以后加更多纸色也放得下，不会挤成两行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        ReaderTheme.PAPER,
                        ReaderTheme.WHITE,
                        ReaderTheme.GREEN,
                        ReaderTheme.BLUE,
                        ReaderTheme.INK,
                        ReaderTheme.BLACK,
                    ).forEach { item ->
                        ThemeSwatch(item, selected = theme == item) { onThemeChange(item) }
                    }
                }
            }
        }

        SectionTitle("书库外观")
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("每行本数", fontSize = 15.sp, color = colors.text)
                Spacer(Modifier.weight(1f))
                SegButton("三列", libraryStyle.columns == 3) { onColumnsChange(3) }
                Spacer(Modifier.width(8.dp))
                SegButton("四列", libraryStyle.columns == 4) { onColumnsChange(4) }
            }
            Divider()
            SliderRow(
                label = "封面圆角",
                value = libraryStyle.coverRadius.toFloat(),
                range = LibraryStyle.RADIUS_RANGE,
                valueText = "${libraryStyle.coverRadius} dp",
                onChange = { onCoverRadiusChange(it.toInt()) },
            )
            Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("显示作者", fontSize = 15.sp, color = colors.text)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = libraryStyle.showAuthor,
                    onCheckedChange = onShowAuthorChange,
                )
            }
        }

        SectionTitle("页面背景")
        Card {
            ChoiceRow("纸张（跟随外观）", selected = backgroundImage == null) {
                onClearBackground()
            }
            Divider()
            ChoiceRow(
                label = if (backgroundImage == null) "自定义背景图" else "自定义背景图（换一张）",
                selected = backgroundImage != null,
                onClick = onPickBackground,
            )
            if (backgroundImage != null) {
                Divider()
                ChoiceRow("清除背景图", selected = false) { onClearBackground() }
                Divider()
                SliderRow(
                    label = "压暗程度",
                    value = backgroundDim,
                    range = 0f..0.7f,
                    valueText = "${(backgroundDim * 100).toInt()}%",
                    onChange = onBackgroundDimChange,
                )
            }
        }
        Text(
            text = if (backgroundImage == null) {
                "挑一张手机里的图当书库底色，正文页仍然用纸色，保证读起来清静。"
            } else {
                "背景图已经铺上了；正文页不受影响。"
            },
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
            text = "鎏书 · 本地离线阅读，数据只存在这台手机上",
            fontSize = 11.sp,
            color = colors.textTertiary,
            modifier = Modifier.padding(22.dp),
        )
    }
}

/** 纸色小圆：选了就打勾。 */
@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLumenColors.current
    // 深色模式下护眼绿/灰蓝显示的是深色纸，小圆点也要跟着变
    val resolved = lumenColorsFor(theme, isSystemInDarkTheme())
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(52.dp)
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .background(resolved.page)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else colors.separator,
                    shape = RoundedCornerShape(50),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (theme.dark) Color.White else colors.text,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = theme.label,
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            color = if (selected) colors.text else colors.textTertiary,
        )
    }
}

/** 小的双段选择按钮（每行本数这种）。 */
@Composable
private fun SegButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLumenColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.accent else colors.surfaceMuted)
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (selected) Color.White else colors.textSecondary,
        )
    }
}

/** 带数值显示的滑条行。 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    val colors = LocalLumenColors.current
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = colors.text)
            Spacer(Modifier.weight(1f))
            Text(valueText, fontSize = 12.sp, color = colors.textSecondary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.surfaceMuted,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {    Text(
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
