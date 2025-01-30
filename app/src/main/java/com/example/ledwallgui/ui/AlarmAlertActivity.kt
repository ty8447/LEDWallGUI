package com.example.ledwallgui.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.example.ledwallgui.R
import android.content.SharedPreferences
import android.util.Log
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.ui.alarm.Alarm
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar


class AlarmAlertActivity : Activity() {

    private lateinit var alarmTimeTextView: TextView
    private lateinit var alarmNameTextView: TextView
    private lateinit var snoozeButton: Button
    private lateinit var dismissButton: Button
    private val snoozeDurationMillis: Long = 5 * 60 * 1000 // Snooze for 5 minutes
    private val alarmHandler = Handler()
    private lateinit var alarmRunnable: Runnable // Declare the variable here
    private var dismissedAlarmIndex: Int = -1 // Declare the variable here
    private var use12HourTimeFormat = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_alert)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        // Initialize views
        alarmTimeTextView = findViewById(R.id.textViewAlarmTime)
        alarmNameTextView = findViewById(R.id.textViewAlarmName)
        snoozeButton = findViewById(R.id.buttonSnooze)
        dismissButton = findViewById(R.id.buttonDismiss)

        // Get alarm details from intent
        val alarmTime = intent.getStringExtra("alarmTime")
        val alarmName = intent.getStringExtra("alarmName")


        val preferenceUtils = PreferenceUtils(this)
        use12HourTimeFormat = preferenceUtils.getIs12HourTimeFormat() // Retrieve time format preference
        Log.d("AlarmAlertActivity", "handleIntentExtras: use12HourTimeFormat = $use12HourTimeFormat")

        alarmTimeTextView.text = formatAlarmTime(alarmTime) // Use the helper function to format the time
        alarmNameTextView.text = alarmName

        // Get the index of the dismissed alarm from intent
        dismissedAlarmIndex = intent.getIntExtra("dismissedAlarmIndex", -1)

        alarmRunnable = Runnable {
            dismissAlarm(dismissedAlarmIndex) // Pass the index of the dismissed alarm
            finish()
        }

        // Set click listeners for snooze and dismiss buttons
        snoozeButton.setOnClickListener {
            snoozeAlarm()
            finish()
        }

        dismissButton.setOnClickListener {
            val dismissedAlarmIndex = intent.getIntExtra("dismissedAlarmIndex", -1)
            Log.d("INDEX", "$dismissedAlarmIndex")
            if (dismissedAlarmIndex != -1) {
                val resultIntent = Intent()
                resultIntent.putExtra("alarmIndex", dismissedAlarmIndex)
                setResult(RESULT_OK, resultIntent)
            }
            finish()
        }

        // Schedule alarm to stop after snooze duration
        alarmHandler.postDelayed(alarmRunnable, snoozeDurationMillis)
        Log.d("ALARM ALERT", "Running")
    }

    private fun formatAlarmTime(alarmTime: String?): String {
        if (use12HourTimeFormat) {
            // Convert the time to 12-hour format
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
            // Return the time in 24-hour format
            return alarmTime ?: ""
        }
        return ""
    }

    private fun snoozeAlarm() {
        // Stop the media player
        println("Snooze button clicked")
        // Remove the pending alarm to stop the scheduled alarm
        // alarmHandler.removeCallbacks(alarmRunnable)
        Log.d("Dismiss", "button Pressed")
        // Finish this activity to go back to the previous activity (e.g., MainActivity)
        finish()
    }

    private fun dismissAlarm(dismissedAlarmIndex: Int) {
        if (dismissedAlarmIndex != -1) {
            val alarms = getAlarmsFromSharedPreferences()
            val alarm = alarms[dismissedAlarmIndex]

            // Check if there are no repeating days (all selectedDays are false)
            if (alarm.selectedDays.all { !it }) {
                // No repeating days, disable the alarm
                alarm.isEnabled = false
            }

            saveAlarmsToSharedPreferences(alarms) // Save updated alarms

            // Update the alarm time only when it's dismissed
            updateAlarmTimeForDismissedAlarm(dismissedAlarmIndex)
        }
        finish()
    }

    private fun updateAlarmTimeForDismissedAlarm(dismissedAlarmIndex: Int) {
        val alarms = getAlarmsFromSharedPreferences()
        val alarm = alarms[dismissedAlarmIndex]

        // Check if the alarm is dismissed, and update the time if needed
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, alarm.hours)
        calendar.set(Calendar.MINUTE, alarm.minutes)

        // Adjust alarm for the next valid selected day
        val currentDayIndex = calendar.get(Calendar.DAY_OF_WEEK) - 1
        var daysToAdd = 0
        while (!alarm.selectedDays[(currentDayIndex + daysToAdd) % 7]) {
            daysToAdd++
            if (daysToAdd > 7) return // No valid day found
        }

        // Add days and update the alarm time
        calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
        saveAlarmsToSharedPreferences(alarms)
    }

    override fun onBackPressed() {
        // Disable back button during alarm
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the media player resources
    }

    private fun getAlarmsFromSharedPreferences(): List<Alarm> {
        val sharedPreferences: SharedPreferences = getSharedPreferences("your_prefs_name", MODE_PRIVATE)
        val alarmsJson = sharedPreferences.getString("alarms", "")
        return Gson().fromJson(alarmsJson, object : TypeToken<List<Alarm>>() {}.type)
    }

    private fun saveAlarmsToSharedPreferences(alarms: List<Alarm>) {
        val sharedPreferences: SharedPreferences = getSharedPreferences("your_prefs_name", MODE_PRIVATE)
        val alarmsJson = Gson().toJson(alarms)
        sharedPreferences.edit().putString("alarms", alarmsJson).apply()
    }
}
