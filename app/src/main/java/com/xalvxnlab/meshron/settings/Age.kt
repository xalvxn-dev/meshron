package com.xalvxnlab.meshron.settings

import android.content.Context
import java.time.LocalDate
import java.time.Period

/**
 * The person's birthday, asked when they start Meshron. Kept only on this phone (one for the
 * person, shared by the public and the anonymous identity). It decides who may create or join
 * 18+ groups; nobody under 13 may use Meshron.
 */
object Age {
    const val MIN = 13
    const val ADULT = 18
    private const val PREFS = "age"
    private const val KEY = "birth" // epoch day
    @Volatile private var birth: Long? = null
    @Volatile private var loaded = false
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        birth = if (p.contains(KEY)) p.getLong(KEY, 0) else null
        loaded = true
    }

    val known: Boolean get() = birth != null
    val birthday: LocalDate? get() = birth?.let { LocalDate.ofEpochDay(it) }

    /** Full years today (null when not given yet). */
    fun years(today: LocalDate = LocalDate.now()): Int? = birthday?.let { Period.between(it, today).years }
    val isAdult: Boolean get() = (years() ?: 0) >= ADULT

    fun set(date: LocalDate) {
        birth = date.toEpochDay()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, date.toEpochDay()).apply()
    }

    /** Problem with a birthday someone typed, or null when it's fine. */
    fun problem(date: LocalDate?, today: LocalDate = LocalDate.now()): String? = when {
        date == null -> "Enter your birthday"
        date.isAfter(today) || date.year < today.year - 120 -> "Check the date"
        Period.between(date, today).years < MIN -> "You must be at least $MIN to use Meshron"
        else -> null
    }
}
