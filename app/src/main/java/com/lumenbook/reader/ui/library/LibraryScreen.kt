package com.lumenbook.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.data.Book
import com.lumenbook.reader.ui.theme.LocalLumenColors

private enum class Filter(val label: String) { RECENT("最近阅读"), ALL("全部书籍"), FINISHED("已读完") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    onImport: () -> Unit,
    onOpen: (Book) -> Unit,
    onRename: (Book, String, String?) -> Unit,
    onRemove: (Book, Boolean) -> Unit,
) {
    val colors = LocalLumenColors.current
    var filter by remember { mutableStateOf(Filter.RECENT) }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetBook by remember { mutableStateOf<Book?>(null) }
    var renameBook by remember { mutableStateOf<Book?>(null) }

    val shown = when (filter) {
        Filter.RECENT -> books
        Filter.ALL -> books.sortedByDescending { it.addedAt }
        Filter.FINISHED -> books.filter { it.finished }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "书库",
                fontFamily = FontFamily.Serif,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                color = colors.text,
            )
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = colors.textSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("导入书籍") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onImport()
                        },
                    )
                }
            }
        }

        SegmentedControl(
            options = Filter.entries.map { it.label },
            selected = filter.ordinal,
            onSelect = { filter = Filter.entries[it] },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when {
            shown.isEmpty() && books.isEmpty() -> EmptyLibrary(onImport)
            shown.isEmpty() -> Text(
                text = when (filter) {
                    Filter.FINISHED -> "还没有读完的书"
                    else -> "这里是空的"
                },
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { book ->
                    Column(
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpen(book) },
                            onLongClick = { sheetBook = book },
                        ),
                    ) {
                        BookCover(
                            book = book,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f),
                        )
                        Text(
                            text = book.title,
                            color = colors.text,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                        )
                        Text(
                            text = book.author ?: book.format.label,
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 1.dp),
                        )
                    }
                }
            }
        }
    }

    sheetBook?.let { book ->
        ModalBottomSheet(
            onDismissRequest = { sheetBook = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = colors.surface,
        ) {
            BookActions(
                book = book,
                onOpen = {
                    sheetBook = null
                    onOpen(book)
                },
                onRename = {
                    refreshKey { sheetBook = null; renameBook = it }
                },
                onRemove = { deleteFile ->
                    sheetBook = null
                    onRemove(book, deleteFile)
                },
            )
        }
    }

    renameBook?.let { book ->
        RenameDialog(
            book = book,
            onDismiss = { renameBook = null },
            onConfirm = { title, author ->
                renameBook = null
                onRename(book, title, author)
            },
        )
    }
}

/** 小工具：在 composable 里先后改两个状态，避免 sheet 关闭动画打架。*/
private inline fun refreshKey(block: () -> Unit) = block()

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    val colors = LocalLumenColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "书库还空着",
            fontFamily = FontFamily.Serif,
            fontSize = 20.sp,
            color = colors.text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "支持 EPUB、PDF、TXT、MOBI、AZW3、FB2、CBZ",
            fontSize = 12.sp,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.accent)
                .combinedClickable(onClick = onImport, onLongClick = null)
                .padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("导入书籍", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
private fun BookActions(
    book: Book,
    onOpen: () -> Unit,
    onRename: (Book) -> Unit,
    onRemove: (Boolean) -> Unit,
) {
    val colors = LocalLumenColors.current
    Column(Modifier.padding(bottom = 28.dp)) {
        Text(
            text = book.title,
            fontFamily = FontFamily.Serif,
            fontSize = 17.sp,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
        HorizontalDivider(color = colors.separator)
        SheetAction("打开", Icons.Filled.PlayArrow, colors.text, onOpen)
        SheetAction("更改书名和作者", Icons.Filled.Edit, colors.text) { onRename(book) }
        SheetAction("从书库移除（保留文件）", Icons.Filled.Delete, colors.textSecondary) { onRemove(false) }
        SheetAction("永久删除（含文件）", Icons.Filled.Delete, Color(0xFFB3261E)) { onRemove(true) }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = tint, fontSize = 15.sp)
    }
}

@Composable
private fun RenameDialog(
    book: Book,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更改信息", fontFamily = FontFamily.Serif) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "只改应用里的显示，不动原文件。",
                    fontSize = 11.sp,
                    color = LocalLumenColors.current.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, author.ifBlank { null }) },
                enabled = title.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 原型里那个胶囊分段控件。*/
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(colors.surfaceMuted)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) colors.surface else Color.Transparent)
                    .combinedClickable(onClick = { onSelect(index) }, onLongClick = null)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = if (active) colors.text else colors.textSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
