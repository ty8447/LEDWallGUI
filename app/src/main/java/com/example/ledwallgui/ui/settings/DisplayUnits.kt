package com.example.ledwallgui.ui.settings

import android.content.Context
import java.util.Locale

/** Only changes displayed measurements; room calibration is always LEDs per meter. */
object DisplayUnits {
    private const val KEY = "room_display_unit"
    private const val PREFS = "settings_prefs"
    fun feet(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "m") == "ft"
    fun set(context: Context, feet: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, if (feet) "ft" else "m").apply()
    fun formatMeters(context: Context, meters: Double): String =
        if (feet(context)) String.format(Locale.US, "%.2f ft", meters * 3.280839895)
        else String.format(Locale.US, "%.2f m", meters)
}
