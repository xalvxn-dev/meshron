package com.xalvxnlab.meshron.identity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Public or anonymous. They are two separate identities: different keys, contacts, chats,
 * files and network storage, so nothing links one to the other. Only one runs at a time;
 * switching saves the choice and restarts the app into the other one.
 */
object Mode {
    private const val PREFS = "meshron-mode"

    @Volatile
    var anon = false
        private set

    /** Called first thing at startup, before any store or screen exists. */
    fun load(context: Context) {
        anon = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("anon", false)
    }

    /** "contacts" -> "contacts_anon" in anonymous mode: every store has its own copy. */
    fun name(base: String): String = if (anon) base + "_anon" else base

    /** Saves the choice and restarts the app in the other identity. */
    fun switchTo(context: Context, toAnon: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("anon", toAnon).commit()
        com.xalvxnlab.meshron.background.ConnectionService.stop(context)
        // A tiny activity in its own process restarts us once this process is gone.
        context.startActivity(
            Intent(context, Restarter::class.java)
                .putExtra("pid", Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Runtime.getRuntime().exit(0)
    }

    /** Runs in ":restart". Waits for the old process to end, then opens the app again. */
    class Restarter : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val old = intent.getIntExtra("pid", -1)
            if (old > 0) Process.killProcess(old)
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            finish()
            Runtime.getRuntime().exit(0)
        }
    }
}
