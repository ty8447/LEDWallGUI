package com.example.ledwallgui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson


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

    // For saving the PIN dynamically based on actionType
    fun setString(context: Context, actionType: String, value: String) {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("${actionType}_pin", value).apply()
    }

    // For retrieving the PIN dynamically based on actionType
    fun getString(context: Context, actionType: String, defaultValue: String): String {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        return prefs.getString("${actionType}_pin", defaultValue) ?: defaultValue
    }

    // For saving the lock state dynamically based on actionType
    fun setBoolean(context: Context, actionType: String, value: Boolean) {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("${actionType}_locked", value).apply()
    }

    // For retrieving the lock state dynamically based on actionType
    fun getBoolean(context: Context, actionType: String, defaultValue: Boolean): Boolean {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("${actionType}_locked", defaultValue)
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_MOTION_DETECTION_ENABLED = "motion_detection_enabled"
    }
}
