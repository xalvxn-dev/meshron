package com.xalvxnlab.meshron.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.moderation.Reports
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch

/** Report anything: pick a reason, add details and an optional screenshot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(kind: String, target: String, name: String, page: String, onClose: () -> Unit, owner: String = "", content: String = "") {
    val app = LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) shot = it }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Text("Report $name", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
            Text("Reports are private. Meshron's moderators review them and act if it breaks the rules.", color = MeshColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            val r = reason
            if (r == null) {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(Reports.REASONS) { x ->
                        Text(x, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().clickable { reason = x }.padding(horizontal = 20.dp, vertical = 14.dp))
                    }
                }
                return@Column
            }
            Text(r, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            TextField(details, { details = it.take(1000) }, placeholder = { Text("What happened? (optional)") }, minLines = 3,
                colors = TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(50)).background(MeshColors.Surface2)
                .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (shot != null) Icons.Rounded.Check else Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp))
                Text(if (shot != null) "  Screenshot added" else "  Add a screenshot", fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MeshColors.Surface2).clickable { reason = null }.padding(14.dp), contentAlignment = Alignment.Center) { Text("Back") }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MeshColors.Danger).clickable(enabled = !busy) {
                    busy = true
                    scope.launch {
                        runCatching { Reports.submit(context, Reports.Report(kind, target, name, r, details, page, owner, content), shot) }
                        app.sendReports()
                        com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Thanks. Meshron's moderators will review it.", long = true)
                        onClose()
                    }
                }.padding(14.dp), contentAlignment = Alignment.Center) { Text("Submit report", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
