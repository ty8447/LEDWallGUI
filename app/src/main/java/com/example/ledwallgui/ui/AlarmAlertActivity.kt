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
import com.example.ledwallgui.ui.alarm.Alarm
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken


class AlarmAlertActivity : Activity() {

    private lateinit var alarmTimeTextView: TextView
    private lateinit var alarmNameTextView: TextView
    private lateinit var snoozeButton: Button
    private lateinit var dismissButton: Button
    private val snoozeDurationMillis: Long = 5 * 60 * 1000 // Snooze for 5 minutes
    private val alarmHandler = Handler()
    private lateinit var alarmRunnable: Runnable // Declare the variable here
    private var dismissedAlarmIndex: Int = -1 // Declare the variable here


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

        alarmTimeTextView.text = alarmTime
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

    private fun snoozeAlarm() {
        // Stop the media player
        println("Snooze button clicked")
        // Remove the pending alarm to stop the scheduled alarm
      //  alarmHandler.removeCallbacks(alarmRunnable)
Log.d("Dismiss", "button Pressed")
        // Finish this activity to go back to the previous activity (e.g., MainActivity)
        finish()
    }

    private fun dismissAlarm(dismissedAlarmIndex: Int) {
        if (dismissedAlarmIndex != -1) {
            val alarms = getAlarmsFromSharedPreferences() // Get the list of alarms from shared preferences
            alarms[dismissedAlarmIndex].isEnabled = false // Update the isEnabled property
            saveAlarmsToSharedPreferences(alarms) // Save the updated list back to shared preferences
        }
        println("Dismiss button clicked")
        val intent = Intent()
        intent.putExtra("dismissedAlarmIndex", dismissedAlarmIndex)
        setResult(RESULT_OK, intent)
        finish() // Finish the activity to trigger reverse transition animation
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
