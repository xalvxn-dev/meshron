package com.xalvxnlab.meshron.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xalvxnlab.meshron.ui.theme.MeshColors

/**
 * A settings page that fills the screen (back arrow, title, scrolling body, actions at the
 * bottom), like the Terms and Privacy pages. Takes the same arguments as AlertDialog, so any
 * dialog can become a page by renaming the call.
 */
@Composable
fun PageDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onDismissRequest)
        Column(modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismissRequest) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.width(4.dp))
                ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) { title?.invoke() }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (icon != null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalContentColor provides MeshColors.Accent) { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() } }
                    }
                }
                ProvideTextStyle(MaterialTheme.typography.bodyLarge) { text?.invoke() }
                // The page's actions sit at the end of the page, like a document (the back arrow closes it).
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MeshColors.Hairline)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    Spacer(Modifier.width(8.dp))
                    confirmButton()
                }
                // Room for the gesture bar (dialog windows don't always get its size).
                Spacer(Modifier.height(64.dp))
            }
        }
    }
}
