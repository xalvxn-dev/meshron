package com.xalvxnlab.meshron.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How to use the assistant on Android, every kind of command (tap one to try it), and the hand gestures. */
object Help {
    val start = listOf(
        "Talk to it" to "Tap the orb (in the app or floating over other apps), or turn on \"Hey NAME\" listening in settings. Then say what you want, the way you would to a person.",
        "Type instead" to "Type in the box at the bottom of the chat.",
        "It asks first" to "Texts, calls, deleting and anything that can't be undone show Approve / Cancel before they happen.",
        "The floating orb" to "Tap to talk, drag it anywhere (drop it near an edge to dock it), long-press to open the app.",
        "Act on screen" to "Turn it on in settings and it can work inside other apps for you: scroll, tap, type and send (it asks before sending)."
    )
    val sections = listOf(
        "Apps and the phone" to listOf(
            "open WhatsApp" to "Opens any app",
            "turn on the flashlight" to "Flashlight on or off",
            "volume to 50" to "Volume",
            "battery" to "Battery level",
            "lock my phone" to "Locks the screen"
        ),
        "Time, tasks and your day" to listOf(
            "set an alarm for 6:30" to "Alarms",
            "set a timer for 10 minutes" to "Timers",
            "remind me to call Mum at 6" to "Reminders",
            "add buy milk to my tasks" to "A to-do list",
            "what is on my calendar today?" to "Your calendar",
            "plan my day" to "Puts it all together"
        ),
        "Messages and calls" to listOf(
            "read my notifications" to "Reads what came in",
            "reply to Ada: on my way" to "Replies from a notification",
            "text Kofi: running late" to "Texts (you approve first)",
            "call Mira" to "Calls (you approve first)",
            "send a WhatsApp to Tunde saying hi" to "Inside other apps, with Act on screen on"
        ),
        "Your screen, camera and files" to listOf(
            "what is on my screen?" to "Reads the screen",
            "what am I looking at?" to "Looks through the camera",
            "summarise this document" to "Reads a document you pick",
            "sort my screenshots into a folder" to "Photos and files (asks before moving or deleting)"
        ),
        "The web and teaching" to listOf(
            "what is the weather in Lagos?" to "Searches the web for anything current",
            "teach me fractions" to "The screen becomes a whiteboard: it writes and draws step by step",
            "clear the board" to "Clears it"
        ),
        "Pictures, look and fun" to listOf(
            "generate an image of a cat astronaut" to "Makes a picture with AI, saved to Pictures/Meshron",
            "come here" to "Companions (pick one under Look): come here, sit, dance, take a nap, fly, jump",
            "open game mode" to "Games you play with your hands and face"
        ),
        "Your other devices" to listOf(
            "on my PC, open Spotify" to "Turn on \"Your other devices\" here and on your PC (same account). Start with \"on my PC\""
        )
    )
    val gestures = listOf(
        "Point (one finger)" to "Aim and move",
        "Pinch (thumb and index)" to "Select, press a key",
        "Two fingers up (V)" to "Scroll; remove a block in Block Builder",
        "Swipe left or right" to "Previous / next, change colour",
        "Hold a fist (1.5 s)" to "Back to the game menu",
        "Punch toward the camera" to "Drop a block",
        "Turn your head" to "Steer in Face Dash"
    )
}

@Composable
fun HelpScreen(name: String, onBack: () -> Unit, onTry: (String) -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ BACK", color = Hud.cyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.padding(6.dp))
            Text("HELP AND COMMANDS", color = Hud.cyan, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Head("Getting started")
        for ((t, d) in Help.start) Box2(t, d.replace("NAME", name))
        Text("Say these or type them. Tap one to try it now. You don't need the exact words.", color = Hud.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        for ((title, items) in Help.sections) {
            Head(title)
            for ((say, what) in items) Box2("\"$say\"", what) { onTry(say) }
        }
        Head("Hand gestures (game mode)")
        for ((g, what) in Help.gestures) Box2(g, what)
        Text("Every game shows its own moves under its title. Hand School teaches each one.", color = Hud.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable private fun Head(t: String) = Text(t.uppercase(), color = Hud.cyan.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, fontSize = 11.sp, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))

@Composable private fun Box2(title: String, sub: String, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(shape).border(1.dp, Hud.line, shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, color = Hud.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = Hud.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
