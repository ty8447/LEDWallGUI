package com.example.ledwallgui.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.example.ledwallgui.R
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.ui.alarm.Alarm
import com.example.ledwallgui.ui.alarm.AlarmReceiver
import com.example.ledwallgui.ui.alarm.SnoozedAlarm
import com.example.ledwallgui.ui.modes.ModeManager
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.util.Calendar

class AlarmAlertActivity : Activity() {
    private lateinit var globalBluetoothManager: GlobalBluetoothManager
    private lateinit var alarmTimeTextView: TextView
    private lateinit var alarmNameTextView: TextView
    private lateinit var snoozeButton: Button
    private lateinit var dismissButton: Button
    private lateinit var snoozeMinusButton: Button
    private lateinit var snoozePlusButton: Button

    private val sharedPreferencesName = "alarm_prefs"
    private val alarmsKey = "alarms_list"
    private val snoozedAlarmsKey = "snoozedAlarms"
    private var snoozeDurationMinutes: Int = 5  // Default snooze duration (5 minutes)
    private val maxSnoozeDuration = 60
    private val minSnoozeDuration = 1
    private var snoozeDurationMillis = snoozeDurationMinutes * 60 * 1000L
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
        globalBluetoothManager = GlobalBluetoothManager(this)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        alarmTimeTextView = findViewById(R.id.textViewAlarmTime)
        alarmNameTextView = findViewById(R.id.textViewAlarmName)
        snoozeButton = findViewById(R.id.buttonSnooze)
        dismissButton = findViewById(R.id.buttonDismiss)
        snoozeMinusButton = findViewById(R.id.buttonSnoozeMinus)
        snoozePlusButton = findViewById(R.id.buttonSnoozePlus)

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

        if (params != null) {
            Log.d("BeforeIntent", "Mode Parameters (onCreate) JSON: ${gson.toJson(params)}")
        }

        Log.d("AlarmAlertActivity", "Previous Mode: $previousMode, Parameters: $previousParameters")

        val previousParamsJson = gson.toJson(previousParameters)
        intent.putExtra("previousMode", previousMode)
        intent.putExtra("previousParams", previousParamsJson)

        // Find the alarm that triggered the alert
        val triggeredAlarm = alarmsList.find { it.id == alarmId }

        if (triggeredAlarm != null) {
            // Set the LED mode and parameters from the alarm
            val alarmMode =
                triggeredAlarm.modeInfo["Name"] ?: "Color" // Default to "Color" if not found
            val alarmParameters =
                triggeredAlarm.modeInfo.filterKeys { it != "Name" } //Filter out the name parameter

            ModeManager.currentMode = alarmMode // Set the current mode

            // Set the parameters using the correct function calls
            alarmParameters.forEach { (paramName, paramValue) ->
                ModeManager.setModeParameter(alarmMode, paramName, paramValue)  // Use ModeManager
                when (paramName) {
                    "Color", "SColor" -> {
                        val rgbValues = paramValue.trim('{', '}').split(",")
                            .map { it.trim().toIntOrNull() ?: 0 }
                        globalBluetoothManager.setLedParameter(
                            alarmMode,
                            rgbValues.toString()
                        ) // Set RGB values
                    }

                    "Brightness" -> {
                        val brightness = paramValue.toIntOrNull() ?: 0
                        globalBluetoothManager.setBrightness(brightness)
                    }
                    // Handle other parameter types as needed
                    else -> {
                        // For other parameter types (Speed, Frequency, Division, etc.)
                        // Call appropriate GlobalBluetoothManager functions if they exist.
                        // Example: GlobalBluetoothManager.setSpeed(alarmMode, paramValue.toIntOrNull() ?: 0)
                    }
                }
            }
            globalBluetoothManager.setLedMode(alarmMode) // Set the mode on the device


            Log.d("AlarmAlertActivity", "Alarm Mode set to: $alarmMode, Parameters: $alarmParameters")

        } else {
            Log.w("AlarmAlertActivity", "Alarm with ID $alarmId not found in the list.")
            // Handle the case where the alarm is not found (e.g., use default mode and parameters)
            ModeManager.currentMode = "Color" // Or some default mode
            globalBluetoothManager.setLedMode("Color")
            globalBluetoothManager.setLedParameter("Color", listOf(255, 0, 0).toString())
            globalBluetoothManager.setBrightness(100)

        }

        alarmRunnable = Runnable {
            restorePreviousMode()
            Log.d("ALARM ALERT", "Stopped")
            finish()
        }

        updateSnoozeButtonText()

        snoozeMinusButton.setOnClickListener {
            if (snoozeDurationMinutes > minSnoozeDuration) {
                snoozeDurationMinutes--
                updateSnoozeButtonText()
            }
        }

        snoozePlusButton.setOnClickListener {
            if (snoozeDurationMinutes < maxSnoozeDuration) {
                snoozeDurationMinutes++
                updateSnoozeButtonText()
            }
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

    private fun updateSnoozeButtonText() {
        snoozeButton.text = "Snooze $snoozeDurationMinutes Minute(s)"
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
        globalBluetoothManager.setLedMode(previousMode)

        previousParams.forEach { (param, value) ->
            ModeManager.setModeParameter(previousMode, param, value)
            globalBluetoothManager.setLedParameter(param,
                value.split(",").map { it.toInt() }.toString()
            )
        }
    }

    private fun snoozeAlarm() {
        Log.d("AlarmAlertActivity", "Snooze button clicked")
        Log.d("AlarmAlertActivity", "Snooze for $snoozeDurationMinutes minute(s)")

        // Calculate the new snooze time.
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, snoozeDurationMinutes)
        val snoozeTimeMillis = calendar.timeInMillis

        // Retrieve existing snoozed alarms.
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val snoozedAlarmsJson = sharedPreferences.getString(snoozedAlarmsKey, "[]")
        val snoozedAlarms: MutableList<SnoozedAlarm> = try {
            gson.fromJson(snoozedAlarmsJson, object : TypeToken<MutableList<SnoozedAlarm>>() {}.type)
        } catch (e: JsonSyntaxException) {
            mutableListOf()
        }

        // Instead of checking by modulo arithmetic, search the stored snoozed alarms
        // to see if the received alarmId is already a snoozed alarm.
        val snoozedEntry = snoozedAlarms.find { it.id == alarmId }
        val isSnoozedAlarm = snoozedEntry != null

        // If it's a snoozed alarm, use its stored originalId; otherwise use alarmId.
        val baseAlarmId = if (isSnoozedAlarm) snoozedEntry!!.originalId else alarmId

        // Find the base (original) alarm using the computed baseAlarmId.
        val originalAlarm = getAlarmsFromSharedPreferences().find { it.id == baseAlarmId }
        if (originalAlarm != null) {
            val originalTimeMillis = originalAlarm.getTimeAsCalendar().timeInMillis

            if (isSnoozedAlarm) {
                // Update the snooze time of the existing snoozed alarm.
                snoozedEntry!!.snoozeTime = snoozeTimeMillis
                Log.d("AlarmAlertActivity", "Updated snoozed alarm id $alarmId with new snooze time: $snoozeTimeMillis")
            } else {
                // Look for an existing snoozed alarm matching the originalId and originalTime.
                val existingIndex = snoozedAlarms.indexOfFirst {
                    it.originalId == originalAlarm.id && it.originalTime == originalTimeMillis
                }
                if (existingIndex != -1) {
                    snoozedAlarms[existingIndex].snoozeTime = snoozeTimeMillis
                    Log.d("AlarmAlertActivity", "Updated existing snoozed alarm (originalId: ${originalAlarm.id}) with new snooze time: $snoozeTimeMillis")
                } else {
                    // Create a new snoozed alarm.
                    // (You can choose your own unique ID generation scheme.
                    // Here we continue to use originalAlarm.id * 1000 + 1.)
                    val newSnoozedAlarm = SnoozedAlarm(
                        id = originalAlarm.id * 1000 + 1,
                        originalId = originalAlarm.id,
                        originalTime = originalTimeMillis,
                        snoozeTime = snoozeTimeMillis
                    )
                    snoozedAlarms.add(newSnoozedAlarm)
                    Log.d("AlarmAlertActivity", "Added new snoozed alarm: $newSnoozedAlarm")
                }
            }

            // Save the updated snoozed alarms list.
            val updatedSnoozedAlarmsJson = gson.toJson(snoozedAlarms)
            sharedPreferences.edit().putString(snoozedAlarmsKey, updatedSnoozedAlarmsJson).apply()

            // Determine the ID for scheduling.
            // If this is a base alarm, we use the new snoozed alarm's id (computed as originalAlarm.id * 1000 + 1).
            // If it’s already a snoozed alarm, we continue using alarmId.
            val snoozeAlarmId = if (!isSnoozedAlarm) {
                originalAlarm.id * 1000 + 1
            } else {
                alarmId
            }
            val snoozeAlarm = Alarm(
                hours = calendar.get(Calendar.HOUR_OF_DAY),
                minutes = calendar.get(Calendar.MINUTE),
                alarmName = originalAlarm.alarmName + " (Snoozed)",
                isEnabled = true,
                id = snoozeAlarmId
            )

            // Schedule the snoozed alarm.
            scheduleAlarm(snoozeAlarm, this)
            Log.d("AlarmAlertActivity", "Scheduled snoozed alarm for ${snoozeAlarm.hours}:${snoozeAlarm.minutes}")
        } else {
            Log.e("AlarmAlertActivity", "Failed to find the alarm to snooze with base id $baseAlarmId")
        }

        // Display a toast with the formatted snooze time.
        val snoozeTimeFormatted = if (use12HourTimeFormat) {
            val hour = calendar.get(Calendar.HOUR)
            val minute = calendar.get(Calendar.MINUTE)
            val period = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
            String.format("%02d:%02d %s", if (hour == 0) 12 else hour, minute, period)
        } else {
            String.format("%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
        }
        Toast.makeText(this, "Alarm will go off at $snoozeTimeFormatted", Toast.LENGTH_LONG).show()

        snoozeDurationMillis = snoozeDurationMinutes * 60 * 1000L
        restorePreviousMode()
        finish()
    }

    private fun scheduleAlarm(alarm: Alarm, context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        Log.d("AlarmAlertActivity", "Attempting to schedule alarm: ${alarm.alarmName} at ${alarm.hours}:${alarm.minutes}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w("AlarmAlertActivity", "Exact alarm permission is missing! Requesting permission.")
            requestExactAlarmPermission(context)
            return
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("alarmTime", "${alarm.hours}:${alarm.minutes}")
            putExtra("alarmName", alarm.alarmName)
            putExtra("alarmId", alarm.id)
        }

        // Compute a unique request code using a non-commutative formula.
        val requestCode = alarm.id * 10000 + alarm.hours * 100 + alarm.minutes

        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = Calendar.getInstance()
        val alarmTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hours)
            set(Calendar.MINUTE, alarm.minutes)
            set(Calendar.SECOND, 0)
        }

        val selectedDays = alarm.selectedDays
        val today = now.get(Calendar.DAY_OF_WEEK)
        Log.d("AlarmAlertActivity", "Today: $today")

        if (selectedDays.any { it }) {
            var closestDay: Int? = null
            var minDiff = Long.MAX_VALUE

            for (i in selectedDays.indices) {
                if (selectedDays[i]) {
                    Log.d("AlarmAlertActivity", "Checking TRUE day: $i")
                    val dayOfWeek = i + 1 // Adjust for Calendar.DAY_OF_WEEK (Sunday=1, Saturday=7)
                    Log.d("AlarmAlertActivity", "Day of week: $dayOfWeek")

                    val tempCalendar = alarmTime.clone() as Calendar
                    tempCalendar.set(Calendar.DAY_OF_WEEK, dayOfWeek)

                    if (dayOfWeek == today && tempCalendar.timeInMillis <= now.timeInMillis) {
                        tempCalendar.add(Calendar.WEEK_OF_YEAR, 1) // Move to next week if today and time has passed
                    } else if (dayOfWeek < today) {
                        tempCalendar.add(Calendar.WEEK_OF_YEAR, 1) // Move past to next week
                    }

                    val diff = tempCalendar.timeInMillis - now.timeInMillis
                    if (diff < minDiff) {
                        minDiff = diff
                        closestDay = dayOfWeek
                    }
                }
            }

            if (closestDay != null) {
                alarmTime.timeInMillis = now.timeInMillis + minDiff // Correctly adjust time based on closest day
                Log.d("AlarmAlertActivity", "Scheduled repeating alarm for: ${alarmTime.time}")
            }
        } else {
            if (alarmTime.timeInMillis <= now.timeInMillis) {
                alarmTime.add(Calendar.DAY_OF_YEAR, 1) // Move to the next day if time has passed
                Log.d("AlarmAlertActivity", "No repeat days selected. Scheduling for the next available day.")
            }
        }

        Log.d("AlarmAlertActivity", "Final scheduled time: ${alarmTime.time}")

        try {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmTime.timeInMillis, pendingIntent)
            Log.d("AlarmAlertActivity", "Alarm successfully scheduled.")
        } catch (e: SecurityException) {
            Log.e("AlarmAlertActivity", "Failed to schedule exact alarm: ${e.message}")
            Toast.makeText(context, "Permission required to schedule exact alarms.", Toast.LENGTH_LONG).show()
            requestExactAlarmPermission(context)
        }
    }

    private fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.w("AlarmAlertActivity", "Redirecting user to grant exact alarm permission.")
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    private fun dismissAlarm() {
        Log.d("AlarmAlertActivity", "dismissAlarm called with id $alarmId")

        // Retrieve the stored alarms.
        val alarms = getAlarmsFromSharedPreferences()
        if (alarms.isEmpty()) {
            Log.e("AlarmAlertActivity", "Alarms list is empty")
            return
        }
        Log.d("AlarmAlertActivity", "Stored Alarms List: ${alarms.joinToString(separator = "\n")}")

        // Retrieve the current snoozed alarms list.
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val snoozedAlarmsJson = sharedPreferences.getString(snoozedAlarmsKey, "[]")
        val snoozedAlarms: MutableList<SnoozedAlarm> = try {
            gson.fromJson(snoozedAlarmsJson, object : TypeToken<MutableList<SnoozedAlarm>>() {}.type)
        } catch (e: JsonSyntaxException) {
            Log.e("AlarmAlertActivity", "Error parsing snoozed alarms JSON", e)
            mutableListOf()
        }

        // Instead of a modulo check, see if the alarmId is in the snoozed alarms list.
        val snoozedEntry = snoozedAlarms.find { it.id == alarmId }
        val originalAlarm: Alarm? = if (snoozedEntry != null) {
            // This is a snoozed alarm; use its stored originalId.
            alarms.find { it.id == snoozedEntry.originalId }
        } else {
            // Otherwise assume it's a base alarm.
            alarms.find { it.id == alarmId }
        }

        if (originalAlarm == null) {
            Log.e("AlarmAlertActivity", "Could not find a matching alarm for id $alarmId")
            return
        }

        Log.d("AlarmAlertActivity", "Matched Alarm: $originalAlarm")

        // Update isEnabled based on the selectedDays.
        // If the alarm repeats (has any true in selectedDays) then keep it enabled; otherwise disable it.
        if (originalAlarm.selectedDays.any { it }) {
            originalAlarm.isEnabled = true
            Log.d("AlarmAlertActivity", "Alarm has repeating days; keeping enabled")
        } else {
            originalAlarm.isEnabled = false
            Log.d("AlarmAlertActivity", "No days selected; disabling alarm")
        }
        saveAlarmsToSharedPreferences(alarms)

        val previousMode = intent.getStringExtra("previousMode")
        val previousParamsJson = intent.getStringExtra("previousParams")

        if (!previousMode.isNullOrEmpty() && !previousParamsJson.isNullOrEmpty()) {
            try {
                val gson = Gson()
                val previousParameters: Map<String, String> = try {
                    gson.fromJson(previousParamsJson ?: "{}", object : TypeToken<Map<String, String>>() {}.type) // Use "{}" as default
                } catch (e: JsonSyntaxException) {
                    Log.e("AlarmAlertActivity", "Error parsing previous parameters JSON", e)
                    emptyMap() // Use empty map as default
                }

                ModeManager.currentMode = previousMode

                previousParameters.forEach { (paramName, paramValue) -> // Correct forEach for Map
                    ModeManager.setModeParameter(previousMode, paramName, paramValue)

                    when (paramName) {
                        "Color", "SColor" -> {
                            val rgbValues = paramValue.trim('{', '}').split(",").map { it.trim().toIntOrNull() ?: 0 }
                            globalBluetoothManager.setLedParameter(previousMode,
                                rgbValues.toString()
                            )
                        }
                        "Brightness" -> {
                            val brightness = paramValue.toIntOrNull() ?: 0
                            globalBluetoothManager.setBrightness(brightness)
                        }
                        // Handle other parameter types as needed
                        else -> {
                            // For other parameter types (Speed, Frequency, Division, etc.)
                            // Call appropriate GlobalBluetoothManager functions if they exist.
                            // Example: GlobalBluetoothManager.setSpeed(previousMode, paramValue.toIntOrNull() ?: 0)
                        }
                    }
                }
                globalBluetoothManager.setLedMode(previousMode)

                Log.d("AlarmAlertActivity", "Restored previous mode: $previousMode, Parameters: $previousParameters")
            } catch (e: JsonSyntaxException) {
                Log.e("AlarmAlertActivity", "Error parsing previous parameters JSON", e)
            }
        } else {
            Log.w("AlarmAlertActivity", "Previous mode or parameters not found in intent extras")
            // Handle the case where previous mode/parameters are missing (e.g., set a default mode)
            ModeManager.currentMode = "Color" // Or some default mode
            globalBluetoothManager.setLedMode("Color")
            globalBluetoothManager.setLedParameter("Color", listOf(255, 0, 0).toString())
            globalBluetoothManager.setBrightness(100)
        }

        Log.d("AlarmAlertActivity", "Snoozed Alarms before removal: ${snoozedAlarms.joinToString(separator = "\n")}")
        // Remove any snoozed alarm that corresponds to the original alarm.
        val removed = snoozedAlarms.removeIf { it.originalId == originalAlarm.id }
        if (removed) {
            Log.d("AlarmAlertActivity", "Snoozed alarm(s) with original id ${originalAlarm.id} removed")
            val updatedJson = gson.toJson(snoozedAlarms)
            sharedPreferences.edit().putString(snoozedAlarmsKey, updatedJson).apply()
        } else {
            Log.d("AlarmAlertActivity", "No snoozed alarm found with original id ${originalAlarm.id}")
        }
        Log.d("AlarmAlertActivity", "Snoozed Alarms after removal: ${snoozedAlarms.joinToString(separator = "\n")}")

        // Finally, update the scheduled alarm time based on the original alarm’s id.
        updateAlarmTimeForDismissedAlarm(originalAlarm.id)
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
