package com.lumenbook.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.data.AppContainer
import com.lumenbook.reader.data.Appearance
import com.lumenbook.reader.data.BackgroundStore
import com.lumenbook.reader.ui.library.LibraryScreen
import com.lumenbook.reader.ui.reader.ReaderScreen
import com.lumenbook.reader.ui.settings.SettingsScreen
import com.lumenbook.reader.ui.stats.StatsScreen
import com.lumenbook.reader.ui.theme.LocalLumenColors
import com.lumenbook.reader.ui.theme.LumenBackground
import com.lumenbook.reader.ui.theme.LumenBackgroundLayer
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("书库", Icons.AutoMirrored.Filled.List),
    STATS("统计", Icons.Filled.Star),
    SETTINGS("设置", Icons.Filled.Settings),
}

@Composable
fun LumenAppRoot(
    container: AppContainer,
    appearance: Appearance,
    onAppearanceChange: (Appearance) -> Unit,
) {
    val colors = LocalLumenColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by container.library.books.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    var readingId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { container.library.refresh() }

    // 正在读的书；书被删掉时自动退回书库
    val readingBook = readingId?.let { id -> books.firstOrNull { it.id == id } }

    if (readingBook != null) {
        ReaderScreen(
            book = readingBook,
            container = container,
            colors = colors,
            onClose = {
                readingId = null
                scope.launch { container.library.refresh() }
            },
        )
        return
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = container.library.import(uris)
            val message = buildString {
                if (outcome.added > 0) append("导入 ${outcome.added} 本")
                if (outcome.failed.isNotEmpty()) {
                    if (isNotEmpty()) append("，")
                    append("${outcome.failed.size} 本失败")
                }
            }.ifBlank { "没有导入任何书" }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val importBooks = remember(context) {
        {
            picker.launch(
                arrayOf(
                    "application/epub+zip",
                    "application/pdf",
                    "text/plain",
                    "application/x-mobipocket-ebook",
                    "application/vnd.amazon.ebook",
                    "application/x-fictionbook+xml",
                    "application/vnd.comicbook+zip",
                    "application/octet-stream",
                ),
            )
        }
    }

    // ---- 自定义背景图 ----
    val backgroundPath by container.prefs.backgroundImage.collectAsState(initial = null)
    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = BackgroundStore.save(context, uri)
                if (path != null) {
                    container.prefs.setBackgroundImage(path)
                } else {
                    Toast.makeText(context, "这张图读不出来，换一张试试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val pickBackground: () -> Unit = {
        backgroundPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    val clearBackground: () -> Unit = {
        scope.launch {
            container.prefs.setBackgroundImage(null)
            BackgroundStore.clear(context)
        }
    }
    val background = remember(backgroundPath) {
        backgroundPath?.let { LumenBackground.Image(it, dim = 0.30f) } ?: LumenBackground.Paper
    }

    LumenBackgroundLayer(background, Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.LIBRARY -> LibraryScreen(
                        books = books,
                        onImport = importBooks,
                        onOpen = { book ->
                            scope.launch { container.library.markOpened(book.id) }
                            readingId = book.id
                        },
                        onEdit = { book, title, author, coverUri, writeBack ->
                            scope.launch {
                                container.library.rename(book.id, title, author)
                                if (coverUri != null) {
                                    container.library.setCover(book.id, coverUri)
                                }
                                if (writeBack) {
                                    Toast.makeText(context, "正在写回文件…", Toast.LENGTH_SHORT).show()
                                    val cover = coverUri?.let { uri ->
                                        container.library.bytesOf(uri)
                                    }
                                    val result = container.library.writeBackMeta(
                                        id = book.id,
                                        title = title,
                                        author = author,
                                        coverBytes = cover?.first,
                                        coverExt = cover?.second ?: "jpg",
                                    )
                                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onRemove = { book, deleteFile ->
                            scope.launch { container.library.remove(book.id, deleteFile) }
                        },
                    )

                    Tab.STATS -> StatsScreen(books)

                    Tab.SETTINGS -> SettingsScreen(
                        appearance = appearance,
                        onAppearanceChange = onAppearanceChange,
                        backgroundImage = backgroundPath,
                        onPickBackground = pickBackground,
                        onClearBackground = clearBackground,
                    )
                }
            }

            HorizontalDivider(color = colors.separator)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(colors.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { item ->
                    val active = item == tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .combinedClickable(onClick = { tab = item }, onLongClick = null)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (active) colors.accent else colors.textTertiary,
                            modifier = Modifier.size(21.dp),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.label,
                            fontSize = 10.sp,
                            color = if (active) colors.accent else colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}
