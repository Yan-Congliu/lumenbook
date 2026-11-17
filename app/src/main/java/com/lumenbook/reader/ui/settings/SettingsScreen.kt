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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenbook.reader.BuildConfig
import com.lumenbook.reader.data.Appearance
import com.lumenbook.reader.ui.theme.LocalLumenColors

@Composable
fun SettingsScreen(
    appearance: Appearance,
    onAppearanceChange: (Appearance) -> Unit,
) {
    val colors = LocalLumenColors.current

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
