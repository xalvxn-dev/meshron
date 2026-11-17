package com.xalvxnlab.meshron.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/**
 * Meshron's short notes ("Saved", "Reposted to your followers"): a slim dark pill near the
 * bottom, instead of the system's tall toast with the app icon. Shown above dialogs too.
 */
object Toasts {
    private var last: Toast? = null

    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        val d = context.resources.displayMetrics.density
        val view = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            maxLines = 3
            setPadding((16 * d).toInt(), (9 * d).toInt(), (16 * d).toInt(), (9 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(0xE6202330.toInt()) }
            elevation = 6 * d
        }
        last?.cancel()
        @Suppress("DEPRECATION")
        last = Toast(context.applicationContext).apply {
            this.view = view
            duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (110 * d).toInt())
            show()
        }
    }
}
