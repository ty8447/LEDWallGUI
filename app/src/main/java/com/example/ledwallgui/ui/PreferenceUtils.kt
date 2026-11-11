package com.example.ledwallgui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.ledwallgui.ui.settings.ModeInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PreferenceUtils(private val context: Context) {
    private val PREFS_NAME = "MyPrefs"
    private val gson = Gson()

    private val sharedPreferences: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Save device address to SharedPreferences
    fun knownDeviceName(address: String?): String? {
        if (address.isNullOrBlank()) return null
        return sharedPreferences.getString("led_name_" + address.uppercase(), null)
    }
    fun saveKnownDeviceName(address: String?, name: String) {
        if (address.isNullOrBlank() || name.isBlank() || name.equals(address, true)) return
        sharedPreferences.edit().putString("led_name_" + address.uppercase(), name).apply()
        val (_, currentAddress, _) = loadConnectionState()
        if (address.equals(currentAddress, true)) {
            sharedPreferences.edit().putString(KEY_DEVICE_NAME, name).apply()
        }
    }
    fun saveControllerMode(name: String) = sharedPreferences.edit().putString("controller_mode", name).apply()
    fun controllerMode(): String? = sharedPreferences.getString("controller_mode", null)
    fun markRemoteConfigurationChanged() {
        context.getSharedPreferences("room_config_cache_v3", Context.MODE_PRIVATE)
            .edit().putBoolean("remote_changed", true).apply()
    }
    fun saveDeviceAddress(address: String) {
        val editor = sharedPreferences.edit()
        editor.putString(KEY_DEVICE_ADDRESS, address)
        editor.apply()
    }

    // Fetch device address from SharedPreferences
    fun fetchDeviceAddress(): String? {
        return sharedPreferences.getString(KEY_DEVICE_ADDRESS, null)
    }

    fun savePowerState(powerState: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("powerState", powerState)
        editor.apply()
    }

    fun getPowerState(): Boolean {
        return sharedPreferences.getBoolean("powerState", false)
    }

    fun setBrightness(brightness: String) {
        val editor = sharedPreferences.edit()
        editor.putString("brightness", brightness)
        editor.apply()
    }

    fun getBrightness(): Int {
        return sharedPreferences.getString("brightness", "0")?.toIntOrNull() ?: 0
    }

    fun getMotionDetectionEnabled(): Boolean {
        return context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_MOTION_DETECTION_ENABLED, false)
    }

    fun saveConnectState(connectState: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean(KEY_CONNECT_STATE, connectState)
        editor.apply()
    }

    fun saveTimeFormatOption(timeFormatOption: Int) {
        val editor = sharedPreferences.edit()
        editor.putInt("timeFormatOption", timeFormatOption)
        editor.apply()
    }

    fun saveIs12HourTimeFormat(is12HourTimeFormat: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("is_12_hour_time", is12HourTimeFormat)
        Log.d("PreferenceUtils", "saveIs12HourTimeFormat: $is12HourTimeFormat")
        editor.apply()
    }

    fun getIs12HourTimeFormat(): Boolean {
        Log.d("PreferenceUtils", "getIs12HourTimeFormat called: ${sharedPreferences.getBoolean("is_12_hour_time", true)}")
        return sharedPreferences.getBoolean("is_12_hour_time", true)
    }

    fun saveModeInfoList(modeInfoList: List<ModeInfo>) {
        val json = gson.toJson(modeInfoList)
        sharedPreferences.edit().putString("mode_info_list", json).apply()
    }

    fun getModeInfoList(): List<ModeInfo> {
        val json = sharedPreferences.getString("mode_info_list", null)
        return if (json.isNullOrEmpty()) {
            emptyList()
        } else {
            val type = object : TypeToken<List<ModeInfo>>() {}.type
            gson.fromJson(json, type)
        }
    }

    // Save connection state (isConnected, deviceAddress, deviceName)
    fun saveConnectionState(isConnected: Boolean, deviceAddress: String?, deviceName: String?) {
        val editor = sharedPreferences.edit()
        editor.putBoolean(KEY_IS_CONNECTED, isConnected)
        editor.putString(KEY_DEVICE_ADDRESS, deviceAddress)
        editor.putString(KEY_DEVICE_NAME, knownDeviceName(deviceAddress) ?: deviceName)
        editor.apply()
//        Log.d("PreferenceUtils", "Connection state saved: isConnected=$isConnected, deviceAddress=$deviceAddress, deviceName=$deviceName")
    }

    // Load connection state
    fun loadConnectionState(): Triple<Boolean, String?, String?> {
        val isConnected = sharedPreferences.getBoolean(KEY_IS_CONNECTED, false)
        val deviceAddress = sharedPreferences.getString(KEY_DEVICE_ADDRESS, null)
        val deviceName = sharedPreferences.getString(KEY_DEVICE_NAME, null)
//        Log.d("PreferenceUtils", "Connection state loaded: isConnected=$isConnected, deviceAddress=$deviceAddress, deviceName=$deviceName")
        return Triple(isConnected, deviceAddress, deviceName)
    }

    // For saving the PIN dynamically based on actionType
    fun setPIN(context: Context, actionType: String, value: String) {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("${actionType}_pin", value).apply()
    }

    // For retrieving the PIN dynamically based on actionType
    fun getPIN(context: Context, actionType: String, defaultValue: String): String {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        return prefs.getString("${actionType}_pin", defaultValue) ?: defaultValue
    }

    fun saveUUID(key: String, value: String?) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    // For saving the lock state dynamically based on actionType
    fun setBoolean(context: Context, actionType: String, value: Boolean) {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("${actionType}_locked", value).apply()
    }

    // Method to remove a specific preference by key
    fun remove(context: Context, actionType: String) {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("${actionType}_pin").apply()
        Log.d("PreferenceUtils", "${actionType}_pin removed successfully.")
    }

    // For retrieving the lock state dynamically based on actionType
    fun getBoolean(context: Context, actionType: String, defaultValue: Boolean): Boolean {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("${actionType}_locked", defaultValue)
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_MOTION_DETECTION_ENABLED = "motion_detection_enabled"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_IS_CONNECTED = "is_connected"
        private const val KEY_CONNECT_STATE = "connectState"
    }
}
