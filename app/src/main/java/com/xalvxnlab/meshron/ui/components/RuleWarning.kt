package com.xalvxnlab.meshron.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.moderation.ModerationService
import com.xalvxnlab.meshron.ui.theme.MeshColors

private val WHERE = mapOf(
    "comment" to "Your comment wasn't posted.",
    "group" to "Your message was flagged.",
    "caption" to "Your video wasn't posted.",
    "description" to "This description wasn't saved.",
    "username" to "This username wasn't saved.",
    "name" to "This name wasn't saved.",
    "bio" to "This bio wasn't saved.",
)

/** Meshron AI's warning, the moment something posted breaks a rule. */
@Composable
fun RuleWarning(b: ModerationService.RuleBreak, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MeshColors.Surface1,
        icon = {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF4F7BFF), Color(0xFF19D3C5), Color(0xFF8B5CFF)))), contentAlignment = Alignment.Center) {
                Text("✦", color = Color.White, fontSize = 24.sp)
            }
        },
        title = { Text("Warning from Meshron AI", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MeshColors.Danger.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(b.rule, color = MeshColors.Danger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(b.reason)
                Text(
                    (WHERE[b.kind] ?: "") + (if (b.kind == "group") " In groups for everyone it was deleted; " else " ") +
                        "Repeated breaks can lead to a suspension. You'll find this warning in Activity.",
                    color = MeshColors.TextSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("OK, got it", fontWeight = FontWeight.Bold) } },
    )
}
