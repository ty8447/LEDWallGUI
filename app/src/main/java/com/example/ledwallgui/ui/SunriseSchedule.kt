package com.example.ledwallgui.ui

/** Sunrise color-blend schedule. 255 = inactive; 0..100 spans 45 minutes before
 * to 20 minutes after the forecast sunrise. Does not depend on the device timezone. */
object SunriseSchedule {
    const val INACTIVE = 255
    const val BEFORE_MS = 45L * 60_000L
    const val AFTER_MS = 20L * 60_000L
    fun level(nowMs: Long, sunriseMs: Long, clearForecast: Boolean): Int {
        if (!clearForecast || sunriseMs <= 0) return INACTIVE
        val elapsed = nowMs - sunriseMs
        if (elapsed < -BEFORE_MS || elapsed > AFTER_MS) return INACTIVE
        return (((elapsed + BEFORE_MS) * 100L) / (BEFORE_MS + AFTER_MS)).toInt().coerceIn(0, 100)
    }
}
