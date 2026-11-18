package com.lumenbook.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.lumenbook.reader.data.ReaderTheme
import com.lumenbook.reader.ui.LumenAppRoot
import com.lumenbook.reader.ui.theme.LumenTheme
import com.lumenbook.reader.ui.theme.rememberLumenColors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as LumenApp).container
        setContent {
            val theme by container.prefs.theme.collectAsState(initial = ReaderTheme.SYSTEM)
            val colors = rememberLumenColors(theme)
            val scope = rememberCoroutineScope()

            LumenTheme(colors = colors) {
                LumenAppRoot(
                    container = container,
                    theme = theme,
                    onThemeChange = { value ->
                        scope.launch { container.prefs.setTheme(value) }
                    },
                )
            }
        }
    }
}
