package com.lumenbook.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.lumenbook.reader.data.Appearance
import com.lumenbook.reader.ui.LumenAppRoot
import com.lumenbook.reader.ui.theme.LumenTheme
import com.lumenbook.reader.ui.theme.shouldUseDarkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as LumenApp).container
        setContent {
            val appearance by container.prefs.appearance.collectAsState(initial = Appearance.SYSTEM)
            val dark = shouldUseDarkTheme(appearance)
            val scope = rememberCoroutineScope()

            LumenTheme(dark = dark) {
                LumenAppRoot(
                    container = container,
                    appearance = appearance,
                    onAppearanceChange = { value ->
                        scope.launch { container.prefs.setAppearance(value) }
                    },
                )
            }
        }
    }
}
