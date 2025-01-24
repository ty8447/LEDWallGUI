package com.example.ledwallgui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.ledwallgui.ui.alarm.Alarm


class PreferenceUtils(private val context: Context) {
    private val PREFS_NAME = "MyPrefs"
    private val gson = Gson()

    private val sharedPreferences: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Save device address to SharedPreferences
    fun saveDeviceAddress(address: String) {
        val editor = sharedPreferences.edit()
        editor.putString("device_address", address)
        editor.apply()  // Use apply() for asynchronous saving
    }

    // Fetch device address from SharedPreferences
    fun fetchDeviceAddress(): String? {
        return sharedPreferences.getString("device_address", null)  // Return null if no address is saved
    }

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

    fun saveIs12HourTimeFormat(is12HourTimeFormat: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("is_12_hour_time", is12HourTimeFormat)  // Use the same key as in SettingsActivity
        Log.d("PreferenceUtils", "saveIs12HourTimeFormat: $is12HourTimeFormat")
        editor.apply()
    }

    fun getIs12HourTimeFormat(): Boolean {
        Log.d("PreferenceUtils", "getIs12HourTimeFormat called: ${sharedPreferences.getBoolean("is_12_hour_time", true)}")
        return sharedPreferences.getBoolean("is_12_hour_time", true)  // Default to true if not set
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
