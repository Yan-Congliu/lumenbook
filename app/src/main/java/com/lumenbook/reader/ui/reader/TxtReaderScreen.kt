package com.lumenbook.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.data.AppContainer
import com.lumenbook.reader.data.Book
import com.lumenbook.reader.data.BookFormat
import com.lumenbook.reader.importer.TxtToEpub
import com.lumenbook.reader.ui.theme.LumenColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface TxtState {
    data object Loading : TxtState
    data class Ready(val file: File) : TxtState
    data class Failed(val message: String) : TxtState
}

/**
 * TXT 没有分页信息，直接按行排版会变成一条竖着的长卷，翻页手感全无。
 * 这里先把它拆章打包成一个派生 EPUB（缓存进 filesDir/derived），再交给同一套 foliate 引擎。
 */
@Composable
fun TxtReaderScreen(
    book: Book,
    container: AppContainer,
    colors: LumenColors,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember(book.id) { mutableStateOf<TxtState>(TxtState.Loading) }
    var attempt by remember(book.id) { mutableIntStateOf(0) }

    LaunchedEffect(book.id, attempt) {
        state = TxtState.Loading
        val result = withContext(Dispatchers.IO) {
            runCatching { TxtToEpub.ensure(context, book) }
        }
        state = result.fold(
            onSuccess = { TxtState.Ready(it) },
            onFailure = { TxtState.Failed("${it.javaClass.simpleName}: ${it.message ?: ""}") },
        )
    }

    when (val current = state) {
        TxtState.Loading -> TxtLoading(colors, book.title)
        is TxtState.Failed -> TxtFailed(colors, current.message, onRetry = { attempt++ }, onClose = onClose)
        is TxtState.Ready -> ReaderScreen(
            book = book.copy(
                format = BookFormat.EPUB,
                localPath = current.file.absolutePath,
                sizeBytes = current.file.length(),
            ),
            container = container,
            colors = colors,
            onClose = onClose,
        )
    }
}

@Composable
private fun TxtLoading(colors: LumenColors, title: String) {
    Box(Modifier.fillMaxSize().background(colors.page), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
            Spacer(Modifier.height(16.dp))
            Text("正在把《$title》拆成章节…", color = colors.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "第一次打开 TXT 会生成一个本地索引，之后秒开",
                color = colors.textTertiary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun TxtFailed(
    colors: LumenColors,
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(colors.page), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("这卷 TXT 打不开", color = colors.text, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = colors.textTertiary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onRetry) { Text("再试一次", color = colors.accent) }
            TextButton(onClick = onClose) { Text("返回书库", color = colors.textTertiary) }
        }
    }
}
