package com.xalvxnlab.meshron

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xalvxnlab.meshron.ui.AppRoot
import com.xalvxnlab.meshron.ui.theme.MeshronTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-first app: always light status/nav bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val app = application as MeshronApp
        setContent {
            MeshronTheme {
                AppRoot(app)
            }
        }
    }
}
