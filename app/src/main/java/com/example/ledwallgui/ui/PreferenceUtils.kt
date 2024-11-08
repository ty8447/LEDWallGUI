package com.example.ledwallgui

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.ledwallgui.ui.alarm.Alarm


class PreferenceUtils(private val context: Context) {
    private val PREFS_NAME = "MyPrefs"
    private val gson = Gson()

    private val sharedPreferences: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePowerState(powerState: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("powerState", powerState)
        editor.apply()
    }

    fun getMotionDetectionEnabled(): Boolean {
        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean(KEY_MOTION_DETECTION_ENABLED, false) // Default to false
    }

    fun saveConnectState(connectState: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("connectState", connectState)
        editor.apply()
    }

    fun saveTimeFormatOption(timeFormatOption: Int) {
        val editor = sharedPreferences.edit()
        editor.putInt("timeFormatOption", timeFormatOption)
        editor.apply()
    }

    fun saveUse12HourTimeFormat(use12HourTimeFormat: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("use12HourTimeFormat", use12HourTimeFormat)
        editor.apply()
    }

    fun saveAlarms(alarms: List<Alarm>) {
        val alarmsJson = gson.toJson(alarms)
        val editor = sharedPreferences.edit()
        editor.putString("alarmsJson", alarmsJson)
        editor.apply()
    }

    fun getAlarms(): List<Alarm> {
        val alarmsJson = sharedPreferences.getString("alarmsJson", null)
        return if (alarmsJson != null) {
            val alarmType = object : TypeToken<List<Alarm>>() {}.type
            gson.fromJson(alarmsJson, alarmType)
        } else {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_MOTION_DETECTION_ENABLED = "motion_detection_enabled"
    }
}
