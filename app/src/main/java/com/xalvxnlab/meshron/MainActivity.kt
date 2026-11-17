package com.xalvxnlab.meshron

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xalvxnlab.meshron.ui.AppRoot
import com.xalvxnlab.meshron.ui.theme.MeshronTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        // Anonymous: no screenshots or screen recording, and a blank preview in Recents.
        if (com.xalvxnlab.meshron.identity.Mode.anon) window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val app = application as MeshronApp
        setContent {
            val mode by app.settings.theme.collectAsState(initial = "system")
            MeshronTheme(mode) {
                // Status/nav bar icons follow the look (light icons on dark, dark on light).
                val dark = com.xalvxnlab.meshron.ui.theme.MeshColors.isDark
                androidx.compose.runtime.LaunchedEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    )
                }
                AppRoot(app)
            }
        }
    }
}
