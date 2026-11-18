package com.lumenbook.reader.ui.imports

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.data.AppContainer
import com.lumenbook.reader.importer.ImportScanner
import com.lumenbook.reader.ui.theme.LocalLumenColors
import kotlinx.coroutines.launch

/**
 * 导入页：让用户挑一个文件夹，App 自己把里面（含子文件夹）的书都列出来。
 *
 * 系统文件选择器默认停在「最近」，书藏在下载目录或几层子文件夹里时用户翻不到，
 * 所以这里以「选文件夹 → 自动扫描」为主路径，选单个文件作为备用入口。
 */
@Composable
fun ImportScreen(container: AppContainer, onClose: () -> Unit) {
    val colors = LocalLumenColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var folderName by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<ImportScanner.Found>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    suspend fun doImport(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        importing = true
        val outcome = container.library.import(uris)
        importing = false
        val message = buildString {
            if (outcome.added > 0) append("导入 ${outcome.added} 本")
            if (outcome.skipped > 0) {
                if (isNotEmpty()) append("，")
                append("跳过 ${outcome.skipped} 本已有的")
            }
            if (outcome.failed.isNotEmpty()) {
                if (isNotEmpty()) append("，")
                append("${outcome.failed.size} 本失败")
            }
        }.ifBlank { "没有导入任何书" }
        toast(message)
        if (outcome.added > 0) onClose()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            scanning = true
            files = emptyList()
            picked = emptySet()
            folderName = "扫描中"
            val found = ImportScanner.scan(context, uri)
            files = found
            picked = found.map { it.uri.toString() }.toSet()
            folderName = if (found.isEmpty()) "这个文件夹里没有书" else "找到 ${found.size} 本"
            scanning = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch { doImport(uris) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.page),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.text)
                }
                Text(
                    "导入书籍",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                )
            }

            if (files.isEmpty() && !scanning) {
                EmptyImportHint(
                    folderLabel = folderName,
                    onPickFolder = { folderPicker.launch(null) },
                    onPick = { path -> folderPicker.launch(initialTreeUri(path)) },
                    onPickFiles = { filePicker.launch(ImportScanner.SUPPORTED.flatMap { mimeOf(it) }.toTypedArray()) },
                )
            } else if (scanning) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("正在扫描文件夹…", color = colors.textSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("子文件夹也会一起找，请稍等", color = colors.textTertiary, fontSize = 12.sp)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$folderName · 已选 ${picked.size} 本",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (picked.size == files.size) "全不选" else "全选",
                        fontSize = 13.sp,
                        color = colors.accent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                picked = if (picked.size == files.size) emptySet() else files.map { it.uri.toString() }.toSet()
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

                LazyColumn(Modifier.weight(1f)) {
                    items(files, key = { it.uri.toString() }) { file ->
                        val key = file.uri.toString()
                        val checked = key in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (checked) picked - key else picked + key
                                }
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { picked = if (it) picked + key else picked - key },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colors.accent,
                                    uncheckedColor = colors.textTertiary,
                                ),
                            )
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(
                                    file.name,
                                    fontSize = 14.sp,
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append(file.extension.uppercase())
                                        if (file.size > 0) append(" · ${formatSize(file.size)}")
                                        if (file.relativePath.isNotEmpty()) append(" · ${shortPath(file.relativePath)}")
                                    },
                                    fontSize = 11.sp,
                                    color = colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
                Button(
                    onClick = {
                        val uris = files.filter { it.uri.toString() in picked }.map { it.uri }
                        scope.launch { doImport(uris) }
                    },
                    enabled = picked.isNotEmpty() && !importing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(46.dp),
                    shape = RoundedCornerShape(23.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.surface,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在导入…", fontSize = 15.sp)
                    } else {
                        Text("导入 ${picked.size} 本", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyImportHint(
    folderLabel: String?,
    onPickFolder: () -> Unit,
    onPick: (String) -> Unit,
    onPickFiles: () -> Unit,
) {
    val colors = LocalLumenColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            folderLabel ?: "把书放在哪个文件夹都行",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colors.text,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "选一个文件夹，鎏书会把这个文件夹和它里面所有子文件夹里的书都找出来，省得你一层层翻。",
            fontSize = 13.sp,
            color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "小提示：安卓不允许直接选「下载」根目录，选它里面的子文件夹就行；" +
                "也可以新建一个「我的图书」，以后把书都放里面。",
            fontSize = 12.sp,
            color = colors.textTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPickFolder,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(23.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
        ) {
            Text("选择文件夹", fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickFiles,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(23.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
        ) {
            Text("只选几个文件", fontSize = 14.sp)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "常用位置（一点就跳到那儿）",
            fontSize = 12.sp,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(10.dp))
        QuickRow(
            listOf(
                "我的图书" to "我的图书",
                "下载" to "Download",
            ),
            onPick,
        )
        Spacer(Modifier.height(8.dp))
        QuickRow(
            listOf(
                "QQ 接收的文件" to "tencent/QQfile_recv",
                "微信下载" to "tencent/MicroMsg/Download",
            ),
            onPick,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "支持 EPUB · PDF · TXT · MOBI · AZW3 · FB2 · CBZ",
            fontSize = 11.sp,
            color = colors.textTertiary,
        )
    }
}

/** 一行两个快捷目录按钮；目录不存在时系统选择器会退回到上一级，不会报错。 */
@Composable
private fun QuickRow(items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    val colors = LocalLumenColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, path) ->
            TextButton(
                onClick = { onPick(path) },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .border(1.dp, colors.separator, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(label, fontSize = 13.sp, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

private fun mimeOf(ext: String): List<String> = when (ext) {
    "epub" -> listOf("application/epub+zip")
    "pdf" -> listOf("application/pdf")
    "txt" -> listOf("text/plain")
    "mobi" -> listOf("application/x-mobipocket-ebook")
    "azw3", "azw" -> listOf("application/vnd.amazon.ebook")
    "fb2" -> listOf("application/x-fictionbook+xml")
    "cbz" -> listOf("application/vnd.comicbook+zip")
    else -> listOf("application/octet-stream", "*/*")
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun shortPath(path: String): String {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "根目录"
        parts.size <= 2 -> parts.joinToString("/")
        else -> "…/" + parts.takeLast(2).joinToString("/")
    }
}

/**
 * 让系统文件夹选择器直接停在某个固定目录（QQ 接收文件、微信下载、Download 之类的位置是固定的，
 * 让用户自己去一层层翻，翻不到就以为「导入不了」——真实用户反馈过这个问题）。
 * 目录不存在时 DocumentsUI 会退回上一级，不会崩。
 */
private fun initialTreeUri(path: String): Uri = DocumentsContract.buildDocumentUri(
    "com.android.externalstorage.documents",
    "primary:" + path.trim('/'),
)
