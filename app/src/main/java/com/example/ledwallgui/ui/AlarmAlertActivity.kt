package com.example.ledwallgui.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.ledwallgui.ui.alarm.AlarmPlayback
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

class AlarmAlertActivity : androidx.activity.ComponentActivity() {
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
    private val alarmHandler = Handler(Looper.getMainLooper())
    private var previousControllerState: Map<String, String>? = null
    private var playbackJob: kotlinx.coroutines.Job? = null
    private var closingAfterRestore = false
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

        // The controller's live state is authoritative. ModeManager alone only changes UI text.
        // A snoozed alarm has a synthetic ID. Resolve it to the original alarm's
        // captured effect rather than silently doing nothing on the next ring.
        val snoozedSourceId = try {
            val json = sharedPreferences.getString(snoozedAlarmsKey, "[]")
            val list: List<SnoozedAlarm> = gson.fromJson(
                json, object : TypeToken<List<SnoozedAlarm>>() {}.type
            ) ?: emptyList()
            list.firstOrNull { it.id == alarmId }?.originalId
        } catch (e: Exception) {
            Log.w("AlarmAlertActivity", "Could not load snoozed alarm linkage", e)
            null
        }
        val triggeredAlarm = alarmsList.find { it.id == (snoozedSourceId ?: alarmId) }
        if (triggeredAlarm != null) {
            playbackJob = lifecycleScope.launch {
                previousControllerState = AlarmPlayback.capture(globalBluetoothManager)
                if (closingAfterRestore) return@launch
                val captured = triggeredAlarm.modeInfo.toMutableMap()
                // Alarm is supposed to illuminate: an Off capture should not suppress playback.
                captured["Power"] = "1"
                if (!captured.containsKey("Brightness")) captured["Brightness"] = "100"
                val failure = AlarmPlayback.apply(globalBluetoothManager, captured)
                if (failure != null) {
                    Log.e("AlarmAlertActivity", "Alarm $alarmId failed to update controller: $failure")
                    Toast.makeText(this@AlarmAlertActivity, failure, Toast.LENGTH_LONG).show()
                } else ModeManager.currentMode = captured["Name"] ?: ModeManager.currentMode
            }
        } else {
            Log.w("AlarmAlertActivity", "No saved effect for alarm ID $alarmId")
        }

        alarmRunnable = Runnable {
            closeWithRestore { Log.d("ALARM ALERT", "Stopped") }
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
            closeWithRestore { snoozeAlarm() }
        }

        dismissButton.setOnClickListener {
            Log.d("AlarmAlertActivity", "Dismiss button clicked with id $alarmId")
            if (alarmId != -1) {
                Log.d("AlarmAlertActivity", "Dismissing alarm with id $alarmId")
                val resultIntent = Intent()
                resultIntent.putExtra("alarmId", alarmId)
                setResult(RESULT_OK, resultIntent)
            }
            closeWithRestore { dismissAlarm() }
        }

        alarmHandler.postDelayed(alarmRunnable, snoozeDurationMillis)
        Log.d("ALARM ALERT", "Running")
    }

    private fun updateSnoozeButtonText() {
        snoozeButton.text = "Snooze $snoozeDurationMinutes Minute(s)"
    }

    override fun onDestroy() {
        alarmHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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

    /** Snooze/dismiss waits for playback to settle before restoring its exact snapshot.
     * Never detach a background restore coroutine that can race the next alarm. */
    private fun closeWithRestore(afterRestore: () -> Unit) {
        if (closingAfterRestore) return
        closingAfterRestore = true
        snoozeButton.isEnabled = false
        dismissButton.isEnabled = false
        alarmHandler.removeCallbacksAndMessages(null)
        lifecycleScope.launch {
            playbackJob?.join()
            val saved = previousControllerState
            if (saved != null) {
                val failure = AlarmPlayback.apply(globalBluetoothManager, saved)
                if (failure == null) {
                    ModeManager.currentMode = saved["Name"] ?: ModeManager.currentMode
                } else {
                    Log.e("AlarmAlertActivity", "Could not restore pre-alarm effect: $failure")
                    Toast.makeText(this@AlarmAlertActivity,
                        "Could not restore LED settings: $failure", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.w("AlarmAlertActivity", "No pre-alarm state was captured; leaving controller unchanged")
            }
            afterRestore()
            finish()
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
                                rgbValues
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
            globalBluetoothManager.setLedParameter("Color", listOf(255, 0, 0))
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
