package com.example.ledwallgui.ui.settings

import android.content.Context

/** A tablet-only view preference. Clock orientation is instead persisted by RoomConfiguration.northSegment. */
object RoomMapRotationStore {
    private const val FILE = "ledwall_map_view"
    private const val ROTATION = "quarter_turns"
    fun load(context: Context): Int = RoomMapOrientation.turns(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(ROTATION, 0))
    fun rotate(context: Context, amount: Int): Int {
        val value = RoomMapOrientation.turns(load(context) + amount)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(ROTATION, value).apply()
        return value
    }
}
