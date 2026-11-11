package com.example.ledwallgui.ui

/** Pure scheduling rules shared by the weather worker and the status readout. */
object WeatherSchedule {
    const val POLL_MS = 30L * 60_000L
    const val RETRY_MS = 2L * 60_000L

    fun due(now: Long, lastSuccess: Long, lastAttempt: Long,
            force: Boolean, failed: Boolean = false): Boolean {
        if (force) return true
        // A failed forced refresh is retried after two minutes, even if the
        // previously successful forecast has not yet reached its 30-minute age.
        if (failed) return lastAttempt == 0L || now - lastAttempt >= RETRY_MS
        if (lastSuccess > 0L && now - lastSuccess < POLL_MS) return false
        if (lastAttempt > 0L && now - lastAttempt < RETRY_MS) return false
        return true
    }

    fun nextDue(lastSuccess: Long, lastAttempt: Long, failed: Boolean): Long? = when {
        failed && lastAttempt > 0L -> lastAttempt + RETRY_MS
        lastSuccess > 0L -> lastSuccess + POLL_MS
        else -> null
    }
}
