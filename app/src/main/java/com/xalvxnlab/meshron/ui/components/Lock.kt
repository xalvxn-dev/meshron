package com.xalvxnlab.meshron.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PIN pad. [check] verifies a PIN (slow on purpose); after 5 wrong tries it waits 30 s.
 * Also used to choose a new PIN ([title] says which).
 */
@Composable
fun PinPad(title: String, subtitle: String, check: suspend (String) -> Boolean, onDone: (String) -> Unit, footer: (@Composable () -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var wrong by remember { mutableIntStateOf(0) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(waitUntil) { while (System.currentTimeMillis() < waitUntil) { now = System.currentTimeMillis(); delay(500) }; now = System.currentTimeMillis() }
    val blocked = now < waitUntil

    fun submit() {
        val entered = pin
        scope.launch {
            val ok = withContext(Dispatchers.Default) { check(entered) }
            if (ok) { pin = ""; onDone(entered) } else {
                wrong++
                pin = ""
                error = "Wrong PIN"
                if (wrong % 5 == 0) { waitUntil = System.currentTimeMillis() + 30_000; error = "Too many tries" }
                for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(50))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MeshColors.Bg).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(30.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (blocked) "Try again in ${((waitUntil - now) / 1000) + 1} s" else error ?: subtitle,
            color = if (error != null) MeshColors.Danger else MeshColors.TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Row(Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(6) { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) MeshColors.GradMid else MeshColors.Surface3))
            }
        }
        Spacer(Modifier.height(32.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        for (row in keys.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                for (k in row) {
                    Box(
                        Modifier.size(76.dp).clip(CircleShape)
                            .then(if (k.isEmpty()) Modifier else Modifier.background(MeshColors.Surface2).clickable(enabled = !blocked) {
                                error = null
                                if (k == "<") pin = pin.dropLast(1)
                                else if (pin.length < 6) {
                                    pin += k
                                    if (pin.length == 6) submit()
                                }
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "<") Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = MeshColors.TextSecondary)
                        else if (k.isNotEmpty()) Text(k, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        footer?.invoke()
    }
}
