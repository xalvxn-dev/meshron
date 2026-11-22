package com.xalvxnlab.meshron.ui.legal

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xalvxnlab.meshron.ui.theme.MeshColors

/** Bump when the documents change in a way people must agree to again. */
const val LEGAL_VERSION = 2

/** Where people reach Meshron about legal, privacy and safety questions. Shown in the documents. */
const val LEGAL_CONTACT = "xalvxn@gmail.com"

class LegalDoc(val title: String, val updated: String, val intro: String, val sections: List<Pair<String, String>>)

val TERMS = LegalDoc(
    "Terms of Service", "16 November 2026",
    "These terms are an agreement between you and Meshron. By using Meshron you agree to them, to the Privacy Policy and to the Community Guidelines.",
    listOf(
        "Who can use Meshron" to "You must be at least 13 years old, or the minimum age to use online services in your country if that is higher. If you are under 18, you confirm a parent or guardian agrees to these terms. You may not use Meshron if you have been suspended from it, or if the law forbids you to. After you create your account, Meshron asks your birthday once: it checks that you are old enough to use Meshron, and only people 18 or older can create or join 18+ groups. Giving a false birthday breaks these terms.",
        "How Meshron works" to "Meshron is peer-to-peer. Chats travel directly between devices (or through other devices carrying them, still encrypted) and are stored on the devices of the people in the chat, not on Meshron's servers. Public videos (Reels) are shared from people's devices to the people who watch them. Meshron's servers only handle public accounts (username, sign-in, email codes), moderation, verification, and optional AI features.",
        "Your account" to "Keep your password, PIN and recovery key safe: they protect your identity, and Meshron cannot read your chats or recover them for you. You are responsible for what happens under your account. Anonymous mode is a separate identity on your device; the same rules apply to it.",
        "Your content" to "You own what you post. By posting a public video, comment or profile, you give Meshron and the people who see it a worldwide, non-exclusive, royalty-free licence to store, copy, show and share it inside Meshron (for example in feeds, reposts, collaborations and shares), until you delete it. Others may already have a copy of something before you delete it. Only post content you have the rights to, including music.",
        "What you must not do" to "Follow the Community Guidelines. Do not break the law; harass, threaten or exploit anyone; post sexual content involving minors (it is reported to authorities); impersonate others; spread scams, malware or spam; collect other people's data without permission; or try to break, overload or get around Meshron's security and moderation.",
        "18+ groups" to "Adults can make groups marked 18+. Only people whose birthday shows they are 18 or older can create or join them. Looser rules apply inside them (adult talk and swearing between adults), but the rules that protect people still apply: no harassment, threats, hate, scams, or anything involving minors.",
        "Moderation" to "People can report content and accounts. An automated moderator reviews reports first and Meshron's moderators review its decisions. We may hide content, limit features, warn, suspend or ban accounts that break these terms or the guidelines. Hidden content is not deleted: you can appeal from Activity, and if you win it comes back as it was. Repeated or serious violations lead to a permanent ban.",
        "AI features" to "Meshron AI (helper, captions, smart replies, translation) sends the text you choose to an AI service to get an answer. Answers can be wrong: do not rely on them for medical, legal, financial or safety decisions.",
        "Safety features" to "SOS and nearby features help you reach people, but Meshron is not an emergency service and cannot guarantee delivery. Always contact local emergency services when you are in danger.",
        "Availability and changes" to "Meshron is provided as it is. Because it runs on people's devices and networks, messages and videos may be delayed or lost. We may change or stop features. If these terms change in an important way we will ask you to agree again in the app.",
        "Liability" to "To the extent the law allows, Meshron is not liable for indirect or consequential losses, for content posted by users, or for losses caused by devices, networks or people outside our control. Nothing in these terms limits rights you have under consumer law that cannot be limited.",
        "Ending" to "You can stop using Meshron and delete your account at any time in Settings. We may suspend or end access for violations, with a chance to appeal.",
        "Contact" to "Questions about these terms: $LEGAL_CONTACT.",
    ),
)

val PRIVACY = LegalDoc(
    "Privacy Policy", "16 November 2026",
    "Meshron is built so we hold as little about you as possible. This explains what is collected, why, and your choices.",
    listOf(
        "Chats, calls and moments" to "End-to-end encrypted between the people in the chat and stored only on their devices (in an encrypted database). Meshron's servers never receive them. Devices that carry messages for others only ever hold locked copies they cannot read.",
        "Public account" to "If you make a public account, our server stores your username, your email address (for codes and account recovery), a one-way hash of a key made from your password (never the password), your public ID, and a locked copy of your identity that only your password can open. Anonymous mode never uses the server.",
        "Reels and your profile" to "Your name, @username, photo, bio, public videos, likes, comments, follows and reposts are public and shared peer-to-peer with people who view them. Saved and liked lists stay on your device.",
        "Your birthday" to "Asked once, after you create your account. It is kept only on your device, is not shown on your profile and is not sent to our servers. We use it to check that you are old enough to use Meshron (13, or older where the law requires) and to decide whether you can create or join 18+ groups.",
        "Nearby suggestions" to "Off unless you turn it on. When on, your device shares a rough area (a square of about 5 km), never your exact location, with Meshron users in the same area.",
        "Automatic checks" to "When you post a comment, a group message, a caption, a description, a name, a username or a bio, its text is checked by Meshron AI against the Community Guidelines (in 18+ groups, the looser 18+ rules). The text is sent to an AI provider only for this check. If it breaks a rule you get a warning, kept with your moderation notices.",
        "Reports and moderation" to "When you report something, we receive your report, what you were looking at, the content in question, and any screenshot you attach. Reports are reviewed by an automated moderator and by Meshron's moderators, and are emailed to the moderation team. We keep moderation records (hidden items, warnings, suspensions, appeals) to enforce the rules.",
        "AI features" to "Only when you use them: the text you send is passed to an AI provider to answer, and is not used by Meshron to build profiles. A daily limit is counted per account.",
        "Verification" to "If you ask for verification, we receive the name, category, reasons and links you provide, and keep the decision.",
        "What we don't do" to "No ads, no selling or renting your data, no tracking across apps, and no reading of your private messages.",
        "How long we keep things" to "Account data until you delete your account. Email codes expire in 10 minutes. Moderation notices after 90 days; reports and decisions as long as needed to keep Meshron safe and meet legal duties. Messages follow your own settings (for example disappearing messages).",
        "Your rights" to "You can download your data (Settings → Your data), correct your profile, and delete your account and everything on your device. Depending on where you live you may have more rights (such as access, objection or complaint to a data-protection authority); contact us to use them.",
        "Children" to "Meshron is not for children under 13. If we learn an account belongs to someone under 13 we will remove it.",
        "Security" to "We use strong encryption and keep server data small, but no system is perfect. Protect your device with a screen lock and keep your recovery key private.",
        "Changes and contact" to "We will tell you in the app about important changes. Privacy questions: $LEGAL_CONTACT.",
    ),
)

val GUIDELINES = LegalDoc(
    "Community Guidelines", "16 November 2026",
    "Meshron should feel safe and fun for everyone. These rules apply to everything: chats you report, videos, comments, lives, moments, profiles and groups.",
    listOf(
        "Be kind" to "No bullying, harassment, threats, doxxing, or encouraging others to target someone.",
        "No hate" to "No attacks on people for who they are: race, ethnicity, nationality, religion, caste, gender, sexual orientation, disability, or serious illness.",
        "Keep it safe for minors" to "Zero tolerance for sexual content involving anyone under 18, grooming, or sexualising minors. Accounts are banned and reported to authorities.",
        "No sexual content or nudity" to "No pornography or sexual services in public content.",
        "No violence or dangerous acts" to "No threats, glorifying violence, terrorism, or dangerous challenges. Self-harm and suicide content must not promote or instruct; we point people to help.",
        "Be real" to "No impersonation, fake accounts to mislead, fake verification, or coordinated manipulation.",
        "No scams or spam" to "No fraud, phishing, pyramid schemes, fake giveaways, malware, or repetitive unwanted messages.",
        "Respect others' rights" to "Only post what you have the rights to. No sharing private information or intimate images without consent.",
        "No harmful misinformation" to "No false information likely to cause real harm (for example about health, elections or emergencies).",
        "18+ groups" to "Groups marked 18+ are for adults only. Adult talk and swearing between adults are fine there; everything else in these guidelines still applies.",
        "Instant warnings" to "Meshron AI checks what you post as you post it. If something breaks these guidelines it isn't posted (or, in a group, it's deleted) and you get a warning right away. Repeated breaks lead to a suspension.",
        "Enforcement" to "Content that breaks these rules is hidden, and accounts can be warned, limited, suspended or banned. You can appeal any decision from Activity → From Meshron. Report anything that breaks the rules: it's private and helps everyone.",
    ),
)

/** Full-screen reader for one document. */
@Composable
fun LegalDocScreen(doc: LegalDoc, onBack: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(doc.title, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Last updated " + doc.updated, color = MeshColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(10.dp))
                Text(doc.intro, style = MaterialTheme.typography.bodyLarge)
                for ((h, b) in doc.sections) {
                    Spacer(Modifier.height(18.dp))
                    Text(h, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(b, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** Shown once (and again when the documents change): confirm age and agree before using Meshron. */
@Composable
fun LegalGate(onAccept: () -> Unit) {
    val age = true
    var agree by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<LegalDoc?>(null) }
    Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome to Meshron", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text("Private chats, moments and videos, phone to phone. Before you start:", color = MeshColors.TextSecondary)
        Spacer(Modifier.height(24.dp))
        Tick(agree, "I agree to the Terms of Service and Community Guidelines, and I've read the Privacy Policy") { agree = !agree }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (d in listOf(TERMS, PRIVACY, GUIDELINES)) Text(d.title, color = MeshColors.Accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { reading = d }.padding(vertical = 4.dp))
        }
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(if (age && agree) MeshColors.TextPrimary else MeshColors.Surface3)
            .clickable(enabled = age && agree, onClick = onAccept), contentAlignment = Alignment.Center) {
            Text("Continue", color = if (age && agree) MeshColors.Bg else MeshColors.TextTertiary, fontWeight = FontWeight.Bold)
        }
    }
    reading?.let { LegalDocScreen(it) { reading = null } }
}

/** Asked once of people who started Meshron before birthdays were asked. */
@Composable
fun BirthdayGate(onDone: () -> Unit) {
    var birthday by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val problem = com.xalvxnlab.meshron.settings.Age.problem(birthday)
    Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("When's your birthday?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(24.dp))
        BirthdayField(birthday) { birthday = it }
        if (birthday != null && problem != null) Text(problem, color = MeshColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(if (problem == null) MeshColors.TextPrimary else MeshColors.Surface3)
            .clickable(enabled = problem == null) { birthday?.let { com.xalvxnlab.meshron.settings.Age.set(it) }; onDone() }, contentAlignment = Alignment.Center) {
            Text("Continue", color = if (problem == null) MeshColors.Bg else MeshColors.TextTertiary, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Month, day and year wheels. Starts at 13 years ago (the youngest allowed), so nobody is made
 * an adult by default.
 */
@Composable
fun BirthdayField(value: java.time.LocalDate?, onPick: (java.time.LocalDate?) -> Unit) {
    val thisYear = java.time.LocalDate.now().year
    val first = thisYear - 110
    var date by remember { mutableStateOf(value ?: java.time.LocalDate.of(thisYear - 13, 1, 1)) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (value == null) onPick(date) }
    fun set(y: Int = date.year, m: Int = date.monthValue, d: Int = date.dayOfMonth) {
        val days = java.time.YearMonth.of(y, m).lengthOfMonth()
        date = java.time.LocalDate.of(y, m, d.coerceAtMost(days))
        onPick(date)
    }
    val months = remember { java.text.DateFormatSymbols.getInstance().shortMonths.take(12) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
        Wheel(12, date.monthValue - 1, { months[it] }, Modifier.size(width = 92.dp, height = 236.dp)) { set(m = it + 1) }
        Wheel(date.lengthOfMonth(), date.dayOfMonth - 1, { (it + 1).toString() }, Modifier.size(width = 72.dp, height = 236.dp)) { set(d = it + 1) }
        Wheel(thisYear - first + 1, date.year - first, { (first + it).toString() }, Modifier.size(width = 96.dp, height = 236.dp)) { set(y = first + it) }
    }
}

/**
 * One rolling wheel: arrow up, the value before (faded), the value between two accent lines,
 * the value after (faded), arrow down. Tap the arrows or the faded values, or drag up and down.
 */
@Composable
private fun Wheel(count: Int, index: Int, label: (Int) -> String, modifier: Modifier, onChange: (Int) -> Unit) {
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
    var dragged by remember { mutableStateOf(0f) }
    val latest = androidx.compose.runtime.rememberUpdatedState(index)
    val latestCount = androidx.compose.runtime.rememberUpdatedState(count)
    fun move(d: Int) {
        val i = (latest.value + d).coerceIn(0, latestCount.value - 1)
        if (i != latest.value) onChange(i)
    }
    val dim = MeshColors.TextTertiary.copy(alpha = 0.6f)
    Column(
        modifier.pointerInput(Unit) {
            detectVerticalDragGestures(onDragEnd = { dragged = 0f }) { _, dy ->
                dragged -= dy
                val steps = (dragged / stepPx).toInt()
                if (steps != 0) { move(steps); dragged -= steps * stepPx }
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).clickable { move(-1) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowDropUp, "Previous", tint = MeshColors.TextTertiary, modifier = Modifier.size(44.dp))
        }
        Box(Modifier.fillMaxWidth().height(52.dp).clickable(enabled = index > 0) { move(-1) }, contentAlignment = Alignment.Center) {
            if (index > 0) Text(label(index - 1), color = dim, fontSize = 20.sp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(MeshColors.Accent))
        androidx.compose.animation.AnimatedContent(
            targetState = index,
            transitionSpec = {
                val down = targetState > initialState
                (androidx.compose.animation.slideInVertically { if (down) it / 3 else -it / 3 } + androidx.compose.animation.fadeIn()) togetherWith
                    (androidx.compose.animation.slideOutVertically { if (down) -it / 3 else it / 3 } + androidx.compose.animation.fadeOut())
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            label = "wheel",
        ) { i ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(label(i.coerceIn(0, count - 1)), color = MeshColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(MeshColors.Accent))
        Box(Modifier.fillMaxWidth().height(52.dp).clickable(enabled = index < count - 1) { move(1) }, contentAlignment = Alignment.Center) {
            if (index < count - 1) Text(label(index + 1), color = dim, fontSize = 20.sp)
        }
        Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).clickable { move(1) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowDropDown, "Next", tint = MeshColors.TextTertiary, modifier = Modifier.size(44.dp))
        }
    }
}

/** (Old pop-up date picker, no longer used.) */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayPicker(value: java.time.LocalDate?, onPick: (java.time.LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open = true }.background(MeshColors.Surface1).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Birthday", color = MeshColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            Text(value?.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)) ?: "Tap to pick your birthday", style = MaterialTheme.typography.bodyLarge,
                color = if (value == null) MeshColors.TextTertiary else MeshColors.TextPrimary)
        }
        Text("Change", color = MeshColors.Accent, fontWeight = FontWeight.SemiBold)
    }
    if (open) {
        val utc = java.time.ZoneOffset.UTC
        val start = (value ?: java.time.LocalDate.now().minusYears(18)).atStartOfDay(utc).toInstant().toEpochMilli()
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = start, initialDisplayMode = androidx.compose.material3.DisplayMode.Input)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate()) }
                open = false
            }) { Text("OK") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { androidx.compose.material3.DatePicker(state, title = { Text("Your birthday", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }, showModeToggle = true) }
    }
}

@Composable
private fun Tick(on: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).background(MeshColors.Surface1).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (on) MeshColors.AccentStrong else MeshColors.Surface3), contentAlignment = Alignment.Center) {
            if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
