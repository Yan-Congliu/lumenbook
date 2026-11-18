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
import com.lumenbook.reader.data.BackgroundStore
import com.lumenbook.reader.data.LibraryStyle
import com.lumenbook.reader.data.ReaderTheme
import com.lumenbook.reader.ui.imports.ImportScreen
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
    theme: ReaderTheme,
    onThemeChange: (ReaderTheme) -> Unit,
) {
    val colors = LocalLumenColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by container.library.books.collectAsState()
    val libraryStyle by container.prefs.libraryStyle.collectAsState(initial = LibraryStyle())
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
            theme = theme,
            onThemeChange = onThemeChange,
            onClose = {
                readingId = null
                scope.launch { container.library.refresh() }
            },
        )
        return
    }

    // ---- 导入：走自己的「选文件夹 → 自动扫描」页 ----
    // 系统文件选择器默认停在「最近」，书放在下载目录或几层子文件夹里时用户翻不到（真机反馈过），
    // 所以导入入口改成让用户挑一个文件夹，目录遍历由 App 自己做。
    var importing by rememberSaveable { mutableStateOf(false) }

    if (importing) {
        ImportScreen(
            container = container,
            onClose = {
                importing = false
                scope.launch { container.library.refresh() }
            },
        )
        return
    }

    val importBooks = remember {
        { importing = true }
    }

    // ---- 自定义背景图 ----
    val backgroundPath by container.prefs.backgroundImage.collectAsState(initial = null)
    val backgroundDim by container.prefs.backgroundDim.collectAsState(initial = 0.30f)
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
    val background = remember(backgroundPath, backgroundDim) {
        backgroundPath?.let { LumenBackground.Image(it, dim = backgroundDim) } ?: LumenBackground.Paper
    }

    LumenBackgroundLayer(background, Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.LIBRARY -> LibraryScreen(
                        books = books,
                        style = libraryStyle,
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
                        theme = theme,
                        onThemeChange = onThemeChange,
                        libraryStyle = libraryStyle,
                        onColumnsChange = { value -> scope.launch { container.prefs.setColumns(value) } },
                        onCoverRadiusChange = { value ->
                            scope.launch { container.prefs.setCoverRadius(value) }
                        },
                        onShowAuthorChange = { value ->
                            scope.launch { container.prefs.setShowAuthor(value) }
                        },
                        backgroundImage = backgroundPath,
                        backgroundDim = backgroundDim,
                        onBackgroundDimChange = { value ->
                            scope.launch { container.prefs.setBackgroundDim(value) }
                        },
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
