package com.xalvxnlab.meshron.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.ui.components.FilterChips
import com.xalvxnlab.meshron.ui.theme.MeshColors

/**
 * Stickers for chats and comments. Built in (no download, nothing tracked): big expressive
 * emoji drawn as stickers. A sticker message is just text, ":sticker:" + the emoji, so it
 * works on every Meshron app and in every chat.
 */
object Stickers {
    private const val PREFIX = ":sticker:"
    val packs = listOf(
        "Moods" to listOf("😂", "🥹", "😍", "🤯", "😭", "😎", "🥳", "😴", "🤔", "😡", "🙄", "😇", "🤗", "😬", "🫠", "🥺"),
        "Love" to listOf("❤️‍🔥", "💕", "💖", "🫶", "😘", "💌", "🌹", "💍", "🥰", "💞", "💘", "🤍"),
        "Hype" to listOf("🔥", "💯", "🎉", "✨", "👏", "🙌", "🚀", "🏆", "⚡", "🤩", "💪", "👑"),
        "Animals" to listOf("🐶", "🐱", "🦊", "🐼", "🐸", "🐵", "🦁", "🐧", "🦄", "🐙", "🐢", "🦋"),
        "Food" to listOf("🍕", "🍔", "🍟", "🍩", "🍿", "🧋", "🍦", "🌮", "🍣", "🍫", "☕", "🍉"),
        "Hello" to listOf("👋", "🙏", "👍", "👎", "🤝", "✌️", "🤞", "🫡", "💤", "❓", "‼️", "✅"),
    )

    fun encode(emoji: String) = PREFIX + emoji
    fun isSticker(text: String) = text.startsWith(PREFIX) && text.length <= PREFIX.length + 16
    fun emojiOf(text: String) = text.removePrefix(PREFIX)

    /** Shows a message/comment body: a sticker big, anything else as text. */
    @Composable
    fun Body(text: String, color: Color) {
        if (isSticker(text)) Sticker(emojiOf(text), 76)
        else Text(text, color = color, fontSize = 15.sp)
    }

    @Composable
    fun Sticker(emoji: String, size: Int) {
        Text(
            emoji,
            style = TextStyle(fontSize = size.sp, shadow = Shadow(Color.Black.copy(alpha = 0.25f), blurRadius = 12f)),
            modifier = Modifier.padding(4.dp),
        )
    }

    /** A picker with a few packs; [onPick] gets the encoded sticker. */
    @Composable
    fun Picker(onPick: (String) -> Unit) {
        var pack by remember { mutableStateOf(packs.first().first) }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            FilterChips(packs.map { it.first to it.first }, pack, { pack = it })
            LazyVerticalGrid(GridCells.Fixed(6), Modifier.fillMaxWidth().height(170.dp).padding(top = 6.dp)) {
                items(packs.first { it.first == pack }.second) { e ->
                    Box(
                        Modifier.padding(3.dp).size(52.dp).clip(RoundedCornerShape(14.dp)).background(MeshColors.Surface2).clickable { onPick(encode(e)) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 30.sp) }
                }
            }
        }
    }
}
