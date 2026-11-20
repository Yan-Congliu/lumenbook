package com.lumenbook.reader.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.ui.theme.SmoothRoundedCornerShape
import com.lumenbook.reader.data.Book
import com.lumenbook.reader.ui.theme.LocalLumenColors

/** 阶段 2 先给个诚实的概览；真正的阅读时长统计等阅读内核有了数据源再做。*/
@Composable
fun StatsScreen(books: List<Book>) {
    val colors = LocalLumenColors.current
    val finished = books.count { it.finished }
    val reading = books.count { it.progress > 0f && !it.finished }
    val byFormat = books.groupingBy { it.format.label }.eachCount().toList().sortedByDescending { it.second }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "统计",
            fontFamily = FontFamily.Serif,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            color = colors.text,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 12.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricTile("藏书", books.size.toString(), Modifier.weight(1f))
            MetricTile("在读", reading.toString(), Modifier.weight(1f))
            MetricTile("读完", finished.toString(), Modifier.weight(1f))
        }

        if (byFormat.isNotEmpty()) {
            Text(
                text = "格式分布",
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 22.dp, top = 24.dp, bottom = 8.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(SmoothRoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                byFormat.forEach { (label, count) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = colors.text, modifier = Modifier.width(56.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(SmoothRoundedCornerShape(3.dp))
                                .background(colors.surfaceMuted),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(count / books.size.toFloat())
                                    .height(6.dp)
                                    .clip(SmoothRoundedCornerShape(3.dp))
                                    .background(colors.accent),
                            )
                        }
                        Spacer(Modifier.padding(horizontal = 5.dp))
                        Text("$count", fontSize = 12.sp, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalLumenColors.current
    Column(
        modifier = modifier
            .clip(SmoothRoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontFamily = FontFamily.Serif, fontSize = 24.sp, color = colors.text)
        Text(label, fontSize = 11.sp, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}
