package com.lumenbook.reader.ui.library

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.data.Book
import com.lumenbook.reader.data.BookFormat
import com.lumenbook.reader.data.LibraryStyle
import com.lumenbook.reader.ui.theme.LocalLumenColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Filter(val label: String) { RECENT("最近阅读"), ALL("全部书籍"), FINISHED("已读完") }

/** 排序方式。最近阅读沿用库里的顺序（打开过的排前面）。*/
private enum class Sort(val label: String) {
    RECENT("最近阅读"),
    TITLE("书名"),
    AUTHOR("作者"),
    ADDED("加入时间"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    style: LibraryStyle,
    onImport: () -> Unit,
    onOpen: (Book) -> Unit,
    onEdit: (Book, String, String?, Uri?, Boolean) -> Unit,
    onRemove: (Book, Boolean) -> Unit,
) {
    val colors = LocalLumenColors.current
    var filter by remember { mutableStateOf(Filter.RECENT) }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetBook by remember { mutableStateOf<Book?>(null) }
    var editBook by remember { mutableStateOf<Book?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.RECENT) }

    val picked = when (filter) {
        Filter.RECENT -> books
        Filter.ALL -> books.sortedByDescending { it.addedAt }
        Filter.FINISHED -> books.filter { it.finished }
    }
    val trimmed = query.trim()
    val matched = if (trimmed.isEmpty()) picked else picked.filter { book ->
        book.title.contains(trimmed, ignoreCase = true) ||
            (book.author?.contains(trimmed, ignoreCase = true) ?: false)
    }
    val shown = when (sort) {
        Sort.RECENT -> matched
        Sort.TITLE -> matched.sortedBy { it.title }
        Sort.AUTHOR -> matched.sortedWith(compareBy({ it.author.orEmpty() }, { it.title }))
        Sort.ADDED -> matched.sortedByDescending { it.addedAt }
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
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(
                    imageVector = if (searching) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searching) "关闭搜索" else "搜索",
                    tint = colors.textSecondary,
                )
            }
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
                    HorizontalDivider()
                    Sort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("排序：${option.label}") },
                            leadingIcon = {
                                if (sort == option) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                } else {
                                    Spacer(Modifier.size(24.dp))
                                }
                            },
                            onClick = {
                                menuOpen = false
                                sort = option
                            },
                        )
                    }
                }
            }
        }

        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索书名或作者", fontSize = 14.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                text = when {
                    trimmed.isNotEmpty() -> "没有找到「$trimmed」"
                    filter == Filter.FINISHED -> "还没有读完的书"
                    else -> "这里是空的"
                },
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(style.columns),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (style.columns >= 4) 10.dp else 12.dp),
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
                            corner = style.coverRadius.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f),
                        )
                        Text(
                            text = book.title,
                            color = colors.text,
                            fontSize = if (style.columns >= 4) 12.sp else 13.sp,
                            lineHeight = if (style.columns >= 4) 15.sp else 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                        )
                        if (style.showAuthor) {
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
                    refreshKey { sheetBook = null; editBook = it }
                },
                onRemove = { deleteFile ->
                    sheetBook = null
                    onRemove(book, deleteFile)
                },
            )
        }
    }

    editBook?.let { book ->
        EditBookDialog(
            book = book,
            onDismiss = { editBook = null },
            onConfirm = { title, author, coverUri, writeBack ->
                editBook = null
                onEdit(book, title, author, coverUri, writeBack)
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
        SheetAction("编辑书籍信息", Icons.Filled.Edit, colors.text) { onRename(book) }
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
private fun EditBookDialog(
    book: Book,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, Uri?, Boolean) -> Unit,
) {
    val colors = LocalLumenColors.current
    val context = LocalContext.current
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author ?: "") }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var writeBack by remember { mutableStateOf(false) }
    val canWriteBack = book.format == BookFormat.EPUB

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) coverUri = uri
    }

    val picked by produceState<ImageBitmap?>(null, coverUri) {
        value = coverUri?.let { uri ->
            withContext(Dispatchers.IO) { decodeUriImage(context, uri) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书籍信息", fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val preview = picked
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp)),
                    ) {
                        if (preview != null) {
                            Image(
                                bitmap = preview,
                                contentDescription = "新封面",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            BookCover(book, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("封面", fontSize = 13.sp, color = colors.text)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (coverUri != null) "已选新封面" else "点右边换一张",
                            fontSize = 11.sp,
                            color = colors.textTertiary,
                        )
                        TextButton(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                        ) {
                            Text(if (coverUri != null) "重新选择" else "选择图片", fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("同时写回文件", fontSize = 14.sp, color = if (canWriteBack) colors.text else colors.textTertiary)
                        Text(
                            text = if (canWriteBack) {
                                "把书名、作者、封面写进这本 EPUB（会先备份）"
                            } else {
                                "只有 EPUB 支持写回，${book.format.label} 只能改应用里的显示"
                            },
                            fontSize = 11.sp,
                            color = colors.textTertiary,
                        )
                    }
                    Switch(
                        checked = writeBack && canWriteBack,
                        onCheckedChange = { if (canWriteBack) writeBack = it },
                        enabled = canWriteBack,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, author.ifBlank { null }, coverUri, writeBack && canWriteBack) },
                enabled = title.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 用户挑的封面图：按 480px 采样解码，别把 5000 万像素的图整个读进内存。 */
private fun decodeUriImage(context: Context, uri: Uri): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?.asImageBitmap()
}.getOrNull()

/** 原型里那个胶囊分段控件。*/
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    // 选项少的时候均分宽度（原型里的胶囊分段控件）；选项一多就横向可滑，
    // 否则每格会被压得放不下文字 —— 以后加「系列」「标签」这类分页也不会挤。
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perItem = maxWidth / options.size.coerceAtLeast(1)
        val scrollable = perItem < 92.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(colors.surfaceMuted)
                .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEachIndexed { index, label ->
                val active = index == selected
                Box(
                    modifier = (if (scrollable) Modifier.width(92.dp) else Modifier.weight(1f))
                        .clip(RoundedCornerShape(9.dp))
                    .background(if (active) colors.surface else Color.Transparent)
                    .combinedClickable(onClick = { onSelect(index) }, onLongClick = null)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = if (active) colors.text else colors.textSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                }
            }
        }
    }
}
