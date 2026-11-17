package com.xalvxnlab.meshron

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.util.Log
import com.xalvxnlab.meshron.background.ConnectionService
import com.xalvxnlab.meshron.calls.CallManager
import com.xalvxnlab.meshron.background.Notifier
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.data.AppDatabase
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.files.FileInfo
import com.xalvxnlab.meshron.files.FileService
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.local.LocalTransports
import com.xalvxnlab.meshron.messaging.MessagingService
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Simple manual dependency container. */
class MeshronApp : Application() {
    val identityRepository by lazy { IdentityRepository(this) }
    val contactsRepository by lazy { ContactsRepository(this) }
    val database by lazy { AppDatabase.create(this) }
    val networkService by lazy { NetworkService(this, identityRepository, contactsRepository, settings, profiles) }
    val localTransports by lazy { LocalTransports(this, networkService, settings) }
    val messagingService by lazy {
        MessagingService(database.messages(), networkService, contactsRepository, identityRepository)
    }
    val fileService by lazy { FileService(this, database.messages(), networkService, messagingService, contactsRepository) }
    val storyService by lazy { com.xalvxnlab.meshron.messaging.StoryService(database.messages(), messagingService, fileService, contactsRepository, identityRepository, networkService) }
    val settings by lazy { AppSettings(this) }
    /** People you may know (opt-in, rough area only). */
    val suggestions by lazy { com.xalvxnlab.meshron.reels.Suggestions(this, networkService, reels, contactsRepository) }
    /** Per-chat settings (disappearing timers). */
    val chatPrefs by lazy { com.xalvxnlab.meshron.settings.ChatPrefs(this) }
    /** Public accounts: @username + password, email codes, 2-step. */
    val accounts by lazy { com.xalvxnlab.meshron.account.AccountService(this, identityRepository) }
    /** Meshron AI (through the account server). */
    val ai by lazy { com.xalvxnlab.meshron.account.AiService(accounts, this) }
    /** Reels: short public videos (not in anonymous mode). */
    val reels by lazy { com.xalvxnlab.meshron.reels.ReelsService(this, networkService, identityRepository, profiles) }
    val moderation by lazy { com.xalvxnlab.meshron.moderation.ModerationService(this, accounts, identityRepository) }

    /** Sends saved reports in the background (they wait on the phone while offline). */
    fun sendReports() { appScope.launch { runCatching { moderation.uploadPending() } } }

    val live by lazy { com.xalvxnlab.meshron.reels.LiveService(this, networkService, reels) }
    /** My bio/photo and the ones contacts share. */
    val profiles by lazy { com.xalvxnlab.meshron.profile.ProfileStore(this, com.xalvxnlab.meshron.identity.Mode.name("profiles")) }

    /** Asks the home screen to open a chat (e.g. "Chat" during a call). */
    val openChatRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Asks the app to open a reel (meshron://reel/<channel>/<id>), e.g. from a chat. */
    val openReelRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** Open someone's page (their Meshron ID), e.g. tapping a name in a group chat. */
    val openProfileRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** Asks the Moments strip to open its composer (from the create button). */
    val openMomentComposer = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notifier by lazy { Notifier(this) }
    val calls by lazy { CallManager(this, networkService, contactsRepository, notifier) { inForeground } }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True while any screen of the app is visible; notifications are only shown when not. */
    @Volatile var inForeground = false
    /** App lock (when a PIN is set): locked at start and after a minute away. */
    val locked = kotlinx.coroutines.flow.MutableStateFlow(true)
    @Volatile private var leftAt = 0L
        private set
    private var started = 0

    /** Starts files + messaging + network (idempotent). Needs an identity. */
    fun startMessaging() {
        fileService // subscribe to file events before the network starts
        messagingService.onIncoming = { notifyIncoming(it) }
        messagingService.retentionHours = settings.disappearHours
        messagingService.chatTimers = { chatPrefs.allTimers() }
        if (!com.xalvxnlab.meshron.identity.Mode.anon) { reels; suggestions } // listen before the network is ready
        calls.start()
        messagingService.start()
        localTransports.start()
    }

    /** Applies the "Stay connected" setting. Called from the UI (foreground) only. */
    fun applyBackgroundSetting() = appScope.launch {
        if (settings.stayConnected.first()) ConnectionService.start(this@MeshronApp) else ConnectionService.stop(this@MeshronApp)
    }

    override fun onCreate() {
        super.onCreate()
        // Public or anonymous: decides which identity's stores everything below opens.
        com.xalvxnlab.meshron.identity.Mode.load(this)
        com.xalvxnlab.meshron.settings.Age.init(this)
        // Meshron AI checks posts through moderation (started early so the checks can reach it).
        moderation
        appScope.launch { runCatching { contactsRepository.encryptLegacy() }.onFailure { Log.e(TAG, "contacts", it) } }
        // Moderation: hide what moderators removed, send reports made while offline, and turn a
        // reel's "Report" into a report for the AI moderator and the human moderators.
        reels.hiddenKeys = { keys -> moderation.hiddenAmong(keys) }
        reels.onReport = { r, reason ->
            com.xalvxnlab.meshron.moderation.Reports.submit(this, com.xalvxnlab.meshron.moderation.Reports.Report(
                "reel", r.key, r.author.label, reason, "", "Discover reel by ${r.author.label}", r.author.id, "Caption: " + r.cap,
            ), null)
            sendReports()
        appScope.launch { runCatching { moderation.checkRole() } }
        }
        sendReports()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                inForeground = true
                // App lock: back after more than a minute away locks again.
                if (started == 1 && leftAt > 0 && System.currentTimeMillis() - leftAt > 60_000) locked.value = true
                notifier.clearMessages()
            }
            override fun onActivityStopped(activity: Activity) {
                started--
                inForeground = started > 0
                if (started == 0) leftAt = System.currentTimeMillis()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // When the phone switches or regains a network (Wi-Fi <-> mobile data, airplane mode
        // off), tell the worklet to rebind its sockets so peers reconnect and logs resync.
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            private var lost = false
            override fun onAvailable(network: Network) {
                val changed = current != null && (lost || current != network)
                current = network
                lost = false
                if (changed) networkService.networkChanged()
            }
            override fun onLost(network: Network) {
                if (current == network) lost = true
            }
        })
    }

    private suspend fun notifyIncoming(m: MessageEntity) {
        if (inForeground) return
        // Anonymous: the notification never shows who or what.
        if (com.xalvxnlab.meshron.identity.Mode.anon) return notifier.newMessage(m.peerKey, "Meshron", "New message")
        val sender = m.senderKey ?: m.peerKey
        val contact = contactsRepository.contacts.first().find { it.keyHex == sender }
        val name = contact?.displayName ?: networkService.state.value.peers[sender]?.announcedName?.ifBlank { null }
            ?: (sender.take(8) + "…")
        val title = if (m.isGroup) {
            val group = database.messages().allGroups().find { it.conversationKey == m.peerKey }
            "$name in ${group?.name ?: "a group"}"
        } else name
        val text = when (m.kind) {
            MessageEntity.KIND_FILE -> FileInfo.parse(m.body)?.let { if (it.isImage) "Photo" else "File: ${it.name}" } ?: "File"
            MessageEntity.KIND_INVITE -> "Added you to a group"
            MessageEntity.KIND_TEXT -> com.xalvxnlab.meshron.messaging.MessagingService.bodyText(m.body).take(200)
            else -> return // e.g. group member updates
        }
        notifier.newMessage(m.peerKey, title, text)
    }

    private companion object {
        const val TAG = "MeshronApp"
    }
}
