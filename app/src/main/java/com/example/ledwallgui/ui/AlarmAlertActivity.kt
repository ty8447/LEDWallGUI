package com.example.ledwallgui.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.content.SharedPreferences
import android.util.Log
import com.example.ledwallgui.R
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.ui.alarm.Alarm
import com.example.ledwallgui.ui.modes.ModeManager
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.util.Calendar

class AlarmAlertActivity : Activity() {

    private lateinit var alarmTimeTextView: TextView
    private lateinit var alarmNameTextView: TextView
    private lateinit var snoozeButton: Button
    private lateinit var dismissButton: Button
    private val sharedPreferencesName = "alarm_prefs"
    private val alarmsKey = "alarms_list"
    private val snoozeDurationMillis: Long = 5 * 60 * 1000 // Snooze for 5 minutes
    private val alarmHandler = Handler()
    private var alarmsList: List<Alarm> = emptyList()
    private val REQUEST_CODE_ALARM_UPDATE = 1001
    private lateinit var alarmRunnable: Runnable
    // Use a property for alarmId so that dismissAlarm() uses the correct value
    private var alarmId: Int = -1
    private var use12HourTimeFormat = false
    private var gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_alert)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        alarmTimeTextView = findViewById(R.id.textViewAlarmTime)
        alarmNameTextView = findViewById(R.id.textViewAlarmName)
        snoozeButton = findViewById(R.id.buttonSnooze)
        dismissButton = findViewById(R.id.buttonDismiss)

        val alarmTime = intent.getStringExtra("alarmTime")
        val alarmName = intent.getStringExtra("alarmName")

        val preferenceUtils = PreferenceUtils(this)
        use12HourTimeFormat = preferenceUtils.getIs12HourTimeFormat()
        Log.d("AlarmAlertActivity", "handleIntentExtras: use12HourTimeFormat = $use12HourTimeFormat")

        alarmTimeTextView.text = formatAlarmTime(alarmTime)
        alarmNameTextView.text = alarmName
        // *** IMPORTANT: assign to the property, not a local variable ***
        alarmId = intent.getIntExtra("alarmId", -1)
        Log.d("AlarmAlertActivity", "Dismissed Alarm Id: $alarmId")

        // Retrieve the list of alarms from shared preferences
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val alarmsJson = sharedPreferences.getString(alarmsKey, "[]")

        alarmsList = try {
            gson.fromJson(alarmsJson, object : TypeToken<List<Alarm>>() {}.type)
        } catch (e: JsonSyntaxException) {
            emptyList()
        }

        // Log the list of alarms being compared
        Log.d("AlarmAlertActivity", "Alarms List: $alarmsList")

        // Log matching alarms by searching for alarm.id == alarmId
        for (alarm in alarmsList) {
            Log.d("AlarmDebug", "Checking alarm: $alarm")
            if (alarm.id == alarmId) {
                Log.d("AlarmDebug", "Alarm matched with id: $alarmId")
            }
        }

        val previousMode = ModeManager.currentMode
        val previousParameters = ModeManager.getModeParameters(previousMode)

        Log.d("AlarmAlertActivity", "Previous Mode: $previousMode, Parameters: $previousParameters")

        val previousParamsJson = gson.toJson(previousParameters)
        intent.putExtra("previousMode", previousMode)
        intent.putExtra("previousParams", previousParamsJson)

        ModeManager.currentMode = "Color"
        ModeManager.setModeParameter("Color", "Color", "255,0,0")
        GlobalBluetoothManager.setLedMode("Color")
        GlobalBluetoothManager.setLedParameter("Color", "Color", "255,0,0")
        ModeManager.setModeParameter("Color", "Brightness", "255")
        GlobalBluetoothManager.setBrightness(100)

        alarmRunnable = Runnable {
            restorePreviousMode()
            Log.d("ALARM ALERT", "Stopped")
            finish()
        }

        snoozeButton.setOnClickListener {
            snoozeAlarm()
            finish()
        }

        dismissButton.setOnClickListener {
            Log.d("AlarmAlertActivity", "Dismiss button clicked with id $alarmId")
            if (alarmId != -1) {
                Log.d("AlarmAlertActivity", "Dismissing alarm with id $alarmId")
                val resultIntent = Intent()
                resultIntent.putExtra("alarmId", alarmId)
                setResult(RESULT_OK, resultIntent)
            }
            dismissAlarm()
            finish()
        }

        alarmHandler.postDelayed(alarmRunnable, snoozeDurationMillis)
        Log.d("ALARM ALERT", "Running")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_ALARM_UPDATE && resultCode == Activity.RESULT_OK) {
            val updatedAlarmsJson = data?.getStringExtra("alarmsList")
            if (!updatedAlarmsJson.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<Alarm>>() {}.type
                    val updatedAlarms: List<Alarm> = Gson().fromJson(updatedAlarmsJson, type)
                    alarmsList = updatedAlarms
                } catch (e: JsonSyntaxException) {
                    Log.e("AlarmAlertActivity", "Error parsing updated alarms: ${e.message}")
                }
            }
        }
    }

    private fun formatAlarmTime(alarmTime: String?): String {
        if (use12HourTimeFormat) {
            val calendar = Calendar.getInstance()
            val timeParts = alarmTime?.split(":")
            if (timeParts?.size == 2) {
                val hours = timeParts[0].toInt()
                val minutes = timeParts[1].toInt()
                calendar.set(Calendar.HOUR_OF_DAY, hours)
                calendar.set(Calendar.MINUTE, minutes)

                val hour = calendar.get(Calendar.HOUR)
                val minute = calendar.get(Calendar.MINUTE)
                val period = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"

                return String.format("%02d:%02d %s", hour, minute, period)
            }
        } else {
            return alarmTime ?: ""
        }
        return ""
    }

    private fun restorePreviousMode() {
        Log.d("AlarmAlertActivity", "Restoring previous mode")

        val previousMode = intent.getStringExtra("previousMode")
        val previousParamsJson = intent.getStringExtra("previousParams")

        if (previousMode.isNullOrEmpty() || previousParamsJson.isNullOrEmpty()) {
            Log.e("AlarmAlertActivity", "Failed to restore previous mode due to null values")
            return
        }

        val previousParams: Map<String, String>? = try {
            Gson().fromJson(previousParamsJson, object : TypeToken<Map<String, String>>() {}.type)
        } catch (e: Exception) {
            Log.e("AlarmAlertActivity", "Failed to parse previousParamsJson: ${e.message}")
            null
        }

        if (previousParams == null) {
            Log.e("AlarmAlertActivity", "Parsed previousParams is null")
            return
        }

        Log.d("AlarmAlertActivity", "Restoring Mode: $previousMode with Parameters: $previousParams")

        ModeManager.currentMode = previousMode
        GlobalBluetoothManager.setLedMode(previousMode)

        previousParams.forEach { (param, value) ->
            ModeManager.setModeParameter(previousMode, param, value)
            GlobalBluetoothManager.setLedParameter(previousMode, param, value)
        }
    }

    private fun snoozeAlarm() {
        println("Snooze button clicked")
        restorePreviousMode()
        finish()
    }

    // Update dismissAlarm() to search for the alarm by id instead of using it as an index
    private fun dismissAlarm() {
        Log.d("AlarmAlertActivity", "dismissAlarm called with id $alarmId")
        val alarms = getAlarmsFromSharedPreferences()
        if (alarms.isNullOrEmpty()) {
            Log.e("AlarmAlertActivity", "Alarms list is empty")
            return
        }
        // Find the alarm by its unique id
        val alarm = alarms.find { it.id == alarmId }
        if (alarm == null) {
            Log.e("AlarmAlertActivity", "Invalid alarm id: $alarmId")
            return
        }

        // Update isEnabled based on the selectedDays array:
        if (alarm.selectedDays.any { it }) {
            // At least one day is true → keep the alarm enabled.
            alarm.isEnabled = true
            Log.d("AlarmAlertActivity", "Alarm has repeating days; keeping enabled")
        } else {
            // No days are selected → disable the alarm.
            alarm.isEnabled = false
            Log.d("AlarmAlertActivity", "No days selected; disabling alarm")
        }

        saveAlarmsToSharedPreferences(alarms)

        // Update the alarm time based on the alarm’s id
        updateAlarmTimeForDismissedAlarm(alarmId)
    }


    // Update updateAlarmTimeForDismissedAlarm() to search by id
    private fun updateAlarmTimeForDismissedAlarm(alarmId: Int) {
        val alarms = getAlarmsFromSharedPreferences()
        val alarm = alarms.find { it.id == alarmId } ?: return

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, alarm.hours)
        calendar.set(Calendar.MINUTE, alarm.minutes)

        val currentDayIndex = calendar.get(Calendar.DAY_OF_WEEK) - 1
        var daysToAdd = 0
        while (!alarm.selectedDays[(currentDayIndex + daysToAdd) % 7]) {
            daysToAdd++
            if (daysToAdd > 7) return // No valid day found
        }

        calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
        saveAlarmsToSharedPreferences(alarms)
    }

    override fun onBackPressed() {
        // Disable back button during alarm
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun getAlarmsFromSharedPreferences(): List<Alarm> {
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val alarmsJson = sharedPreferences.getString(alarmsKey, null)

        if (alarmsJson.isNullOrEmpty()) {
            Log.e("AlarmAlertActivity", "Alarms list not found in shared preferences.")
            return emptyList()
        }

        return try {
            val type = object : TypeToken<List<Alarm>>() {}.type
            Gson().fromJson(alarmsJson, type) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e("AlarmAlertActivity", "Error parsing alarms: ${e.message}")
            emptyList()
        }
    }

    private fun saveAlarmsToSharedPreferences(alarms: List<Alarm>) {
        val sharedPreferences: SharedPreferences = getSharedPreferences(sharedPreferencesName, MODE_PRIVATE)
        val alarmsJson = Gson().toJson(alarms)
        sharedPreferences.edit().putString(alarmsKey, alarmsJson).apply()
    }
}
