package com.xalvxnlab.meshron.ui.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.MeshronApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private val SosRed = Color(0xFFE5243B)
private val SosDark = Color(0xFF1A0508)

/** "🆘 SOS: …" — how SOS messages start, on every platform. */
const val SOS_PREFIX = "🆘 SOS"

/** The SOS text: message, map link (works on any phone), time. */
fun sosBody(text: String, loc: Location?): String = buildString {
    append(SOS_PREFIX).append(": ").append(text.trim().ifEmpty { "I need help." })
    if (loc != null) {
        append("\n📍 https://maps.google.com/?q=")
        append(String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude))
        if (loc.hasAccuracy()) append(" (±").append(loc.accuracy.toInt()).append(" m)")
    }
    append("\n🕒 ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()))
}

/**
 * SOS: tells every contact where you are, as an urgent message that every Meshron phone
 * nearby passes on (no internet needed), and offers the routes that work through any phone
 * network: an emergency call, SMS, and joining an open Wi-Fi.
 */
@Composable
fun SosScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("I need help.") }
    var location by remember { mutableStateOf<Location?>(null) }
    var locating by remember { mutableStateOf(true) }
    var sentTo by remember { mutableStateOf<Int?>(null) }
    var sending by remember { mutableStateOf(false) }
    var openWifi by remember { mutableStateOf<List<String>?>(null) }
    var asked by remember { mutableStateOf(false) }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true }
    LaunchedEffect(asked) {
        if (!hasLocation(context) && !asked) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return@LaunchedEffect
        }
        locating = true
        location = currentLocation(context)
        locating = false
        openWifi = openNetworks(context)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SosDark, Color(0xFF0A0A0F))))
                .statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Emergency SOS", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Works without internet", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, "Close", tint = Color.White, modifier = Modifier.size(26.dp)) }
                }
                Spacer(Modifier.height(20.dp))
                TextField(
                    value = text, onValueChange = { text = it.take(500) },
                    label = { Text("Message") }, minLines = 2, maxLines = 5, shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.08f), unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                        focusedLabelColor = Color.White.copy(alpha = 0.7f), unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, tint = if (location != null) Color(0xFF2ECC71) else Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            locating -> "Finding your location…"
                            location != null -> String.format(Locale.US, "Location added (±%d m)", location!!.accuracy.toInt())
                            !hasLocation(context) -> "Location not allowed — the SOS goes without it"
                            else -> "Location unavailable — the SOS goes without it"
                        },
                        color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(28.dp))

                // The big button.
                val pulse = rememberInfiniteTransition(label = "sos")
                val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "p")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(190.dp).graphicsLayer { scaleX = 1f + p * 0.25f; scaleY = 1f + p * 0.25f; alpha = (1f - p) * 0.45f }
                        .clip(CircleShape).background(SosRed))
                    Box(
                        Modifier.size(170.dp).clip(CircleShape).background(SosRed).clickable(enabled = !sending) {
                            sending = true
                            scope.launch {
                                val n = app.messagingService.sendSos(sosBody(text, location))
                                sending = false
                                sentTo = n
                                if (n == 0) Toast.makeText(context, "You have no contacts yet. Use the call or SMS buttons below.", Toast.LENGTH_LONG).show()
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sending) CircularProgressIndicator(color = Color.White)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("SOS", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                            Text(if (sentTo == null) "Tap to send" else "Send again", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    sentTo?.let { "Sent to $it ${if (it == 1) "contact" else "contacts"}. Every Meshron phone that comes near passes it on, and the first one with internet delivers it." }
                        ?: "Sends your message and location to all your contacts. Any Meshron phone nearby passes it on, even with no signal.",
                    color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(28.dp))
                Text("Other ways to get help", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Route(Icons.Filled.Call, "Call emergency services", "Works on any network, even without credit or a SIM") {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    Route(Icons.Filled.Sms, "Send as a text (SMS)", "Needs a little phone signal, not data") {
                        val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", sosBody(text, location))
                        runCatching { context.startActivity(sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { Toast.makeText(context, "No texting app on this phone", Toast.LENGTH_SHORT).show() }
                    }
                    val wifi = openWifi
                    Route(
                        Icons.Filled.Wifi, "Join an open Wi-Fi",
                        when {
                            wifi == null -> "Looking for open networks…"
                            wifi.isEmpty() -> "None found nearby right now"
                            else -> "Open nearby: " + wifi.take(3).joinToString(", ")
                        },
                    ) {
                        val panel = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
                        context.startActivity(panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Route(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.07f)).clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(SosRed.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(sub, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        }
    }
}

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** A fresh fix (GPS works without internet), else the newest one the phone already has. */
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    if (!hasLocation(context)) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val last = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (Build.VERSION.SDK_INT < 30) return last
    val provider = when {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> return last
    }
    val fresh = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
        }
    }
    return fresh ?: last
}

/** Open (password-free) Wi-Fi networks the phone can see right now. */
@SuppressLint("MissingPermission")
private fun openNetworks(context: Context): List<String> = runCatching {
    if (!hasLocation(context)) return emptyList()
    val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
    wm.scanResults
        .filter { r ->
            val caps = r.capabilities
            !caps.contains("WEP") && !caps.contains("PSK") && !caps.contains("EAP") && !caps.contains("SAE")
        }
        .sortedByDescending { it.level }
        .mapNotNull { r ->
            val name = if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") r.SSID
            name?.takeIf { it.isNotBlank() }
        }
        .distinct()
}.getOrDefault(emptyList())
