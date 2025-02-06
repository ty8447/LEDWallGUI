package com.example.ledwallgui.ui.alarm

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.Switch
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.text
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.R
import com.example.ledwallgui.TimeMonitoringService
import com.example.ledwallgui.ui.alarm.AlarmActivity.Companion.REQUEST_CODE_ADD_ALARM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

class AlarmActivity : AppCompatActivity() {

    private lateinit var alarmAdapter: AlarmAdapter
    private lateinit var cancelButton: Button
    private lateinit var offButton: Button
    private lateinit var deleteButton: Button
    private lateinit var addAlarmButton: Button
    private lateinit var emptyTextView: TextView
    private lateinit var alarmsRecyclerView: RecyclerView
    private lateinit var timeTillNext: TextView
    private lateinit var currentTime: TextView
    private lateinit var doneButton: Button

    private lateinit var preferenceUtils: PreferenceUtils // Declare preferenceUtils

    private val sharedPreferencesName = "alarm_prefs"
    private val alarmsKey = "alarms_list"
    private val snoozedAlarmsKey = "snoozedAlarms"
    private lateinit var gson: Gson

    private val alarms = mutableListOf<Alarm>()
    private var isDeleteMode = false // Track delete mode

    private var use12HourTimeFormat: Boolean = false

    companion object {
        const val REQUEST_CODE_ADD_ALARM = 101
        const val EDIT_ALARM_REQUEST_CODE = 1001
    }

    // Broadcast receiver to receive time updates
    private val timeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            //Log.d("AlarmActivity", "onReceive called via LocalBroadcastManager")
            if (intent?.action == "com.example.ledwallgui.TIME_UPDATE") {
                val currentTimeMillis = intent.getLongExtra("current_time", 0L)
                //Log.d("AlarmActivity", "Received time update: $currentTimeMillis")
                runOnUiThread {
                    updateCurrentTime()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("AlarmActivity", "onCreate called")
        gson = Gson() // Initialize Gson
        preferenceUtils = PreferenceUtils(this) // Initialize preferenceUtils here
        use12HourTimeFormat = preferenceUtils.getIs12HourTimeFormat() // Load preference
        setupUI()
        configureSharedPreferences()

        // Check and request permission for foreground service data sync
        checkAndRequestPermission()

        // Load alarms on activity start
        val loadedAlarms = loadAlarms()
        alarms.clear() // Clear the current alarms list
        alarms.addAll(loadedAlarms) // Add loaded alarms
        alarmAdapter.notifyDataSetChanged() // Notify the adapter that the data has changed
        //Log.d("AlarmActivity", "time format: $use12HourTimeFormat")
        updateEmptyState()
        val filter = IntentFilter("com.example.ledwallgui.TIME_UPDATE")
        LocalBroadcastManager.getInstance(this).registerReceiver(timeUpdateReceiver, filter)
        removeExpiredSnoozedAlarms()
    }

    private fun removeExpiredSnoozedAlarms() {
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val snoozedAlarmsJson = sharedPreferences.getString(snoozedAlarmsKey, "[]")
        val snoozedAlarms: MutableList<SnoozedAlarm> = try {
            gson.fromJson(snoozedAlarmsJson, object : TypeToken<MutableList<SnoozedAlarm>>() {}.type)
        } catch (e: JsonSyntaxException) {
            mutableListOf()
        }

        val currentTimeMillis = System.currentTimeMillis()
        val iterator = snoozedAlarms.iterator()

        while (iterator.hasNext()) {
            val snoozedAlarm = iterator.next()
            if (currentTimeMillis - snoozedAlarm.snoozeTime >= 5 * 60 * 1000) { // 5 minutes in milliseconds
                Log.d("AlarmActivity", "Removing expired snoozed alarm with ID: ${snoozedAlarm.id}")
                iterator.remove()
            }
        }

        // Save updated snoozed alarms list
        val updatedSnoozedAlarmsJson = gson.toJson(snoozedAlarms)
        sharedPreferences.edit().putString(snoozedAlarmsKey, updatedSnoozedAlarmsJson).apply()
    }


    private fun checkAndRequestPermission() {
        //Log.d("AlarmActivity", "Checking permissions...")
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC
            ) == PackageManager.PERMISSION_GRANTED) {
            //Log.d("AlarmActivity", "Permission granted.")
        } else {
            // Permission is not granted, so request it
            //Log.d("AlarmActivity", "Permission not granted. Requesting...")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC),
                PERMISSIONREQUESTCODE
            )
        }
    }

    private val PERMISSIONREQUESTCODE = 1001

    // Handle permission request result
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONREQUESTCODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted
                    //Log.d("AlarmActivity", "Permission granted for foreground service data sync.")
                } else {
                    // Permission denied
                    //Log.d("AlarmActivity", "Permission denied for foreground service data sync.")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("AlarmActivity", "onResume called")
        Log.d("AlarmActivity", "Registering receiver")
        val filter = IntentFilter("com.example.ledwallgui.TIME_UPDATE")
        LocalBroadcastManager.getInstance(this).registerReceiver(timeUpdateReceiver, filter)
        Log.d("AlarmActivity", "Receiver registered")
        use12HourTimeFormat = preferenceUtils.getIs12HourTimeFormat() // Reload preference
        alarmAdapter.use12HourTimeFormat = use12HourTimeFormat // Update adapter
        // Check and request permission for foreground service data sync
        checkAndRequestPermission()
        refreshAlarms()
        updateCurrentTime()
        updateEmptyState()
        updateTimeTillNextAlarm()

        // Log the list of snoozed alarms
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val snoozedAlarmsJson = sharedPreferences.getString(snoozedAlarmsKey, "[]")
        val snoozedAlarms: MutableList<SnoozedAlarm> = try {
            gson.fromJson(snoozedAlarmsJson, object : TypeToken<MutableList<SnoozedAlarm>>() {}.type)
        } catch (e: JsonSyntaxException) {
            Log.e("AlarmActivity", "Error parsing snoozed alarms JSON", e)
            mutableListOf()
        }

        Log.d("AlarmActivity", "Snoozed Alarms List: ${snoozedAlarms.map { "ID: ${it.id}, Snooze Time: ${it.snoozeTime}" }}")

    }

    override fun onPause() {
        super.onPause()
        Log.d("AlarmActivity", "onPause called")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(timeUpdateReceiver)
    }

    private fun refreshAlarms() {
        lifecycleScope.launch {
            val loadedAlarms = withContext(Dispatchers.IO) { loadAlarms() }
            alarms.clear() // Clear the existing list
            alarms.addAll(loadedAlarms) // Add the new alarms
            alarmAdapter.notifyDataSetChanged() // Notify the adapter
        }
    }

    private fun setupUI() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_alarm_menu)

        alarmsRecyclerView = findViewById(R.id.alarmsRecyclerView)
        timeTillNext = findViewById(R.id.timeTillNext)
        currentTime = findViewById(R.id.currentTime)
        cancelButton = findViewById(R.id.cancelButton)
        offButton = findViewById(R.id.offButton)
        deleteButton = findViewById(R.id.deleteButton)
        emptyTextView = findViewById(R.id.emptyTextView)
        addAlarmButton = findViewById(R.id.addAlarmButton)
        doneButton = findViewById(R.id.doneButton)
        val backButton: ImageButton = findViewById(R.id.aBackButton) // Find the button

        hideSystemUI()
        updateCurrentTime()

        // Slide in animation
        val slideIn = ObjectAnimator.ofFloat(doneButton, "translationY", 1000f, 0f)
        slideIn.duration = 500 // Duration of animation (in milliseconds)
        slideIn.start()

        alarmAdapter = AlarmAdapter(
            alarms,
            { clickedAlarm -> viewAlarmDetails(clickedAlarm) },
            use12HourTimeFormat,
            { saveAlarms() },
            isDeleteMode,
            { updateEmptyState() }, // Pass the updateEmptyState method
            { saveAlarms() }, // Pass the saveAlarms method
            { updateTimeTillNextAlarm()}, //Pass the updateTimeTillNextAlarm method
            { alarm, context -> scheduleAlarm(alarm, context) }
        )

        alarmsRecyclerView.adapter = alarmAdapter
        alarmsRecyclerView.layoutManager = LinearLayoutManager(this)

        cancelButton.setOnClickListener { onCancel() }
        offButton.setOnClickListener { onTurnOff() }
        alarmAdapter.setOnItemLongClickListener { position ->
            enableDeleteMode(position)
        }
        addAlarmButton.setOnClickListener { addNewAlarm() }

        // Set the back button click listener
        backButton.setOnClickListener {
            finish() // End the activity and go back
        }

        doneButton.setOnClickListener {
            doneButton()
        }
    }

    private fun doneButton() {
        isDeleteMode = false
        alarmAdapter.isDeleteMode = isDeleteMode // Update delete mode in the adapter
        alarmAdapter.notifyDataSetChanged() // Refresh the RecyclerView
        doneButton.visibility = View.GONE // Hide Done button after exiting delete mode
    }

    private fun updateTimeTillNextAlarm() {
        Log.d("AlarmActivity", "updateTimeTillNextAlarm: Starting updateTimeTillNextAlarm")
        val currentTime = Calendar.getInstance()
        Log.d("AlarmActivity", "updateTimeTillNextAlarm: Current time: ${currentTime.time}")
        val allAlarms = mutableListOf<Pair<Calendar, String>>() // List to hold alarm times and names

        // Function to process alarms and add them to the list
        fun processAlarms(alarms: List<Alarm>, isSnoozed: Boolean = false) {
            Log.d("AlarmActivity", "processAlarms: Processing ${if (isSnoozed) "snoozed" else "regular"} alarms")
            alarms.filter { it.isEnabled }.forEach { alarm ->
                Log.d("AlarmActivity", "processAlarms: Processing alarm: ${alarm.alarmName}, isEnabled: ${alarm.isEnabled}")
                val alarmTime = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, alarm.hours)
                    set(Calendar.MINUTE, alarm.minutes)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                Log.d("AlarmActivity", "processAlarms: Initial alarm time for ${alarm.alarmName}: ${alarmTime.time}")

                val currentDayIndex = currentTime.get(Calendar.DAY_OF_WEEK) - 1
                Log.d("AlarmActivity", "processAlarms: Current day index: $currentDayIndex")
                val selectedDays = alarm.selectedDays
                Log.d("AlarmActivity", "processAlarms: Selected days for ${alarm.alarmName}: ${selectedDays.joinToString()}")

                if (!selectedDays.contains(true)) {
                    Log.d("AlarmActivity", "processAlarms: No repeating days selected for ${alarm.alarmName}")
                    if (alarmTime.before(currentTime)) {
                        Log.d("AlarmActivity", "processAlarms: Alarm time for ${alarm.alarmName} is before current time, adding 1 day")
                        alarmTime.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    Log.d("AlarmActivity", "processAlarms: Adding ${alarm.alarmName} to allAlarms with time: ${alarmTime.time}")
                    allAlarms.add(Pair(alarmTime, alarm.alarmName))
                    return@forEach
                }

                var daysToAdd = 0
                while (!selectedDays[(currentDayIndex + daysToAdd) % 7]) {
                    Log.d("AlarmActivity", "processAlarms: Incrementing daysToAdd for ${alarm.alarmName}: $daysToAdd")
                    daysToAdd++
                    if (daysToAdd > 7) {
                        Log.d("AlarmActivity", "processAlarms: No valid day found for ${alarm.alarmName}, skipping")
                        return@forEach
                    }
                }

                if (daysToAdd == 0 && alarmTime.before(currentTime)) {
                    Log.d("AlarmActivity", "processAlarms: ${alarm.alarmName} is for today, but the time has passed")
                    daysToAdd = 1
                    while (!selectedDays[(currentDayIndex + daysToAdd) % 7]) {
                        Log.d("AlarmActivity", "processAlarms: Incrementing daysToAdd for passed time on ${alarm.alarmName}: $daysToAdd")
                        daysToAdd++
                        if (daysToAdd > 7) {
                            Log.d("AlarmActivity", "processAlarms: No valid future day found for ${alarm.alarmName}, skipping")
                            return@forEach
                        }
                    }
                }

                Log.d("AlarmActivity", "processAlarms: Final daysToAdd for ${alarm.alarmName}: $daysToAdd")
                alarmTime.add(Calendar.DAY_OF_YEAR, daysToAdd)
                Log.d("AlarmActivity", "processAlarms: Adjusted alarm time for ${alarm.alarmName}: ${alarmTime.time}")
                allAlarms.add(Pair(alarmTime, alarm.alarmName))
            }
        }

        // Process regular alarms
        Log.d("AlarmActivity", "updateTimeTillNextAlarm: Processing regular alarms")
        processAlarms(alarms)

        // Retrieve existing snoozed alarms
        Log.d("AlarmActivity", "updateTimeTillNextAlarm: Retrieving snoozed alarms")
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val snoozedAlarmsJson = sharedPreferences.getString(snoozedAlarmsKey, "[]")
        val snoozedAlarms: MutableList<SnoozedAlarm> = try {
            gson.fromJson(snoozedAlarmsJson, object : TypeToken<MutableList<SnoozedAlarm>>() {}.type)
        } catch (e: JsonSyntaxException) {
            Log.e("AlarmActivity", "updateTimeTillNextAlarm: Error parsing snoozed alarms JSON", e)
            mutableListOf()
        }

        // Process snoozed alarms
        Log.d("AlarmActivity", "updateTimeTillNextAlarm: Processing snoozed alarms")
        snoozedAlarms.forEach { snoozedAlarm ->
            Log.d("AlarmActivity", "updateTimeTillNextAlarm: Processing snoozed alarm: ${snoozedAlarm.id}, snoozeTime: ${snoozedAlarm.snoozeTime}")
            val snoozeTime = Calendar.getInstance().apply {
                timeInMillis = snoozedAlarm.snoozeTime
            }
            Log.d("AlarmActivity", "updateTimeTillNextAlarm: Snooze time for ${snoozedAlarm.id}: ${snoozeTime.time}")
            allAlarms.add(Pair(snoozeTime, "Snoozed: ${snoozedAlarm.id}"))
        }

        // Find the next alarm
        Log.d("AlarmActivity", "updateTimeTillNextAlarm: Finding next alarm from allAlarms: ${allAlarms.map { "${it.second} at ${it.first.time}" }}")
        val nextAlarmPair = allAlarms.minByOrNull { it.first.timeInMillis }

        // Update the TextView with the time difference
        if (nextAlarmPair != null) {
            val (nextAlarm, alarmName) = nextAlarmPair
            Log.d("AlarmActivity", "updateTimeTillNextAlarm: Next alarm: $alarmName at ${nextAlarm.time}")
            var timeDifference = nextAlarm.timeInMillis - currentTime.timeInMillis
            timeDifference += 59_000
            val hours = timeDifference / (1000 * 60 * 60)
            val minutes = (timeDifference / (1000 * 60)) % 60
            Log.d("AlarmActivity", "updateTimeTillNextAlarm: Time difference: $hours hours and $minutes minutes")
            val timeText = when {
                hours > 0 && minutes > 0 -> "Next Alarm in $hours ${if (hours.toInt() == 1) "hour" else "hours"} and $minutes ${if (minutes.toInt() == 1) "minute" else "minutes"}"
                hours > 0 -> "Next Alarm in $hours ${if (hours.toInt() == 1) "hour" else "hours"}"
                minutes > 0 -> "Next Alarm in $minutes ${if (minutes.toInt() == 1) "minute" else "minutes"}"
                else -> ""
            }

            if (alarmName.startsWith("Snoozed:")) {
                val snoozeText = when {
                    minutes > 0 -> "Snoozed for $minutes ${if (minutes.toInt() == 1) "minute" else "minutes"}"
                    else -> ""
                }
                timeTillNext.text = snoozeText
            } else {
                timeTillNext.text = timeText
            }
        } else {
            Log.d("AlarmActivity", "updateTimeTillNextAlarm: No upcoming alarms found")
            timeTillNext.text = getString(R.string.no_upcoming_alarms)
        }
        Log.d("AlarmActivity", "updateTimeTillNextAlarm: Finished updateTimeTillNextAlarm")
    }

    private fun configureSharedPreferences() {
        val preferenceUtils = PreferenceUtils(this)
        use12HourTimeFormat = preferenceUtils.getIs12HourTimeFormat() // Retrieve time format preference
        //Log.d("AlarmActivity", "time format1: $use12HourTimeFormat")
    }

    private fun loadAlarms(): List<Alarm> {
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val alarmsJson = sharedPreferences.getString(alarmsKey, null)

        return if (!alarmsJson.isNullOrEmpty()) {
            val alarmType = object : TypeToken<List<Alarm>>() {}.type
            val loadedAlarms: List<Alarm> = gson.fromJson(alarmsJson, alarmType)
            loadedAlarms // Return the loaded alarms
        } else {
            emptyList() // Return empty if no alarms are found
        }
    }

    private fun saveAlarms() {
        val sharedPreferences = getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        // Convert the list of alarms to JSON
        val alarmsJson = gson.toJson(alarms)
        editor.putString(alarmsKey, alarmsJson)
        editor.apply() // Save the JSON to SharedPreferences
    }

    private fun hideSystemUI() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    fun updateCurrentTime() {
        val calendar = Calendar.getInstance()
        //Log.d("AlarmActivity", "Updating time... format: $use12HourTimeFormat")
        val timeFormat = if (use12HourTimeFormat) "hh:mm aaa" else "HH:mm" // Change "KK" to "hh"
        //Log.d("AlarmActivity", "Time format: $timeFormat")
        val formattedTime = SimpleDateFormat(timeFormat, Locale.getDefault()).format(calendar.time)
        currentTime.text = formattedTime
        updateTimeTillNextAlarm()
    }

    private fun viewAlarmDetails(clickedAlarm: Alarm) {
        // Instead of getting the index, pass the actual ID
        Log.d("AlarmActivity", "viewAlarmDetails: Selected alarm with id ${clickedAlarm.id}")
        val intent = Intent(this, AlarmEditorActivity::class.java).apply {
            putExtra("isEditMode", true) // Make sure to indicate edit mode
            putExtra("clickedAlarmId", clickedAlarm.id) // Pass the alarm's actual id
            putParcelableArrayListExtra("alarmsList", ArrayList(alarms))
            putExtra("use12HourTimeFormat", use12HourTimeFormat)
        }
        startActivityForResult(intent, EDIT_ALARM_REQUEST_CODE)
    }

    private fun addNewAlarm() {
        val intent = Intent(this, AlarmEditorActivity::class.java).apply {
            putExtra("isEditMode", false)
            putParcelableArrayListExtra("alarmsList", ArrayList(alarms))
            putExtra("use12HourTimeFormat", use12HourTimeFormat)
        }
        startActivityForResult(intent, REQUEST_CODE_ADD_ALARM)
        updateEmptyState()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQUEST_CODE_ADD_ALARM || requestCode == EDIT_ALARM_REQUEST_CODE) && resultCode == Activity.RESULT_OK) {
            data?.let {
                val updatedList = it.getParcelableArrayListExtra<Alarm>("alarmsList")
                if (updatedList != null) {
                    alarms.clear() // Clear existing alarms list
                    alarms.addAll(updatedList) // Add the updated list
                    alarmAdapter.notifyDataSetChanged() // Refresh the RecyclerView
                    saveAlarms() // Save the updated alarms to SharedPreferences

                    // Schedule alarms only if enabled
                    for (alarm in alarms) {
                        if (alarm.isEnabled) {  // Check if the alarm is enabled
                            scheduleAlarm(alarm, this) // Call scheduleAlarm here
                        } else {
                            Log.d("AlarmActivity", "Alarm is disabled, not scheduling: ${alarm.alarmName}")
                        }
                    }
                }
            }
        }
    }

    private fun scheduleAlarm(alarm: Alarm, context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        Log.d("AlarmAdapter1", "Attempting to schedule alarm: ${alarm.alarmName} at ${alarm.hours}:${alarm.minutes}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w("AlarmAdapter1", "Exact alarm permission is missing! Requesting permission.")
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
        Log.d("AlarmAdapter1", "Today: $today")

        if (selectedDays.any { it }) {
            var closestDay: Int? = null
            var minDiff = Long.MAX_VALUE

            for (i in selectedDays.indices) {
                if (selectedDays[i]) {
                    Log.d("AlarmAdapter1", "Checking TRUE day: $i")
                    val dayOfWeek = i + 1 // Adjust for Calendar.DAY_OF_WEEK (Sunday=1, Saturday=7)
                    Log.d("AlarmAdapter1", "Day of week: $dayOfWeek")

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
                Log.d("AlarmAdapter1", "Scheduled repeating alarm for: ${alarmTime.time}")
            }
        } else {
            if (alarmTime.timeInMillis <= now.timeInMillis) {
                alarmTime.add(Calendar.DAY_OF_YEAR, 1) // Move to the next day if time has passed
                Log.d("AlarmAdapter1", "No repeat days selected. Scheduling for the next available day.")
            }
        }

        Log.d("AlarmAdapter1", "Final scheduled time: ${alarmTime.time}")

        try {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmTime.timeInMillis, pendingIntent)
            Log.d("AlarmAdapter1", "Alarm successfully scheduled.")
        } catch (e: SecurityException) {
            Log.e("AlarmAdapter1", "Failed to schedule exact alarm: ${e.message}")
            Toast.makeText(context, "Permission required to schedule exact alarms.", Toast.LENGTH_LONG).show()
            requestExactAlarmPermission(context)
        }
    }

    private fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.w("AlarmAdapter2", "Redirecting user to grant exact alarm permission.")
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    private fun onCancel() {
        // Your logic here
    }

    private fun onTurnOff() {
        // Your logic here
    }

    private fun enableDeleteMode(position: Int) {
        isDeleteMode = true
        alarmAdapter.isDeleteMode = isDeleteMode  // Update delete mode in adapter
        alarmAdapter.notifyDataSetChanged()
        doneButton.visibility = View.VISIBLE
    }

    private fun updateEmptyState() {
        if (alarms.isEmpty()) {
            emptyTextView.visibility = View.VISIBLE
            doneButton()
        } else {
            emptyTextView.visibility = View.GONE
        }
    }

}

class AlarmAdapter(
    private val alarms: MutableList<Alarm>,  // Use MutableList for modification
    private val onAlarmClick: (Alarm) -> Unit,
    var use12HourTimeFormat: Boolean,
    private val onAlarmStateChanged: () -> Unit,
    var isDeleteMode: Boolean = false,  // Accept isDeleteMode as a parameter
    private val updateEmptyState: () -> Unit,  // Reference to updateEmptyState()
    private val saveAlarms: () -> Unit,  // Reference to saveAlarms()
    private val updateTimeTillNextAlarm: () -> Unit, // Add the updateTimeTillNextAlarm function as a parameter
    private val scheduleAlarm: (Alarm, Context) -> Unit
) : RecyclerView.Adapter<AlarmAdapter.AlarmViewHolder>() {

    private var onItemLongClickListener: ((Int) -> Unit)? = null

    // Set long press listener
    fun setOnItemLongClickListener(listener: (Int) -> Unit) {
        onItemLongClickListener = listener
    }

    inner class AlarmViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val alarmTime: TextView = itemView.findViewById(R.id.alarmTimeTextView)
        private val alarmName: TextView = itemView.findViewById(R.id.alarmNameTextView)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        private val deleteButtonContainer: FrameLayout = itemView.findViewById(R.id.deleteButtonContainer)
        private val alarmParametersContainer: ViewGroup = itemView.findViewById(R.id.alarmParametersContainer)
        private val alarmToggleContainer: ViewGroup = itemView.findViewById(R.id.alarmToggleContainer)
        private val repeatDaysTextView: TextView = itemView.findViewById(R.id.repeatDaysTextView)
        private val alarmSwitch: Switch = itemView.findViewById(R.id.alarmSwitch)

        fun bind(alarm: Alarm) {
            // Define text colors for enabled and disabled states.
            // You can also use ContextCompat.getColor if you have defined colors in your resources.
            val enabledColor = Color.WHITE
            val disabledColor = Color.GRAY

            // Format time based on the 12-hour or 24-hour preference.
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, alarm.hours)
                set(Calendar.MINUTE, alarm.minutes)
            }
            val timeFormat = if (use12HourTimeFormat) "hh:mm aaa" else "HH:mm"
            val formattedTime = SimpleDateFormat(timeFormat, Locale.getDefault()).format(calendar.time)

            alarmTime.text = formattedTime
            alarmName.text = alarm.alarmName

            // Set the text color based on the alarm's enabled state.
            val textColor = if (alarm.isEnabled) enabledColor else disabledColor
            alarmTime.setTextColor(textColor)
            alarmName.setTextColor(textColor)

            // Set the repeat days text (if any) and update its color.
            val daysOfWeek = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val selectedDaysList = alarm.selectedDays.toList() // Convert BooleanArray to List<Boolean>
            val selectedDaysText = if (selectedDaysList.all { it }) {
                "Everyday"
            } else {
                selectedDaysList
                    .mapIndexedNotNull { index, isSelected -> if (isSelected) daysOfWeek[index] else null }
                    .joinToString(" ")
            }
            if (selectedDaysText.isNotEmpty()) {
                repeatDaysTextView.text = selectedDaysText
                repeatDaysTextView.visibility = View.VISIBLE
                repeatDaysTextView.setTextColor(textColor)
            } else {
                repeatDaysTextView.visibility = View.GONE
            }

            // Set the switch state based on alarm.isEnabled.
            alarmSwitch.isChecked = alarm.isEnabled

            // Show delete button if in delete mode.
            deleteButtonContainer.visibility = if (isDeleteMode) View.VISIBLE else View.GONE

            // Dynamically hide alarm parameters if space is insufficient.
            itemView.post {
                val layoutWidth = itemView.measuredWidth
                val containerWidth = alarmParametersContainer.measuredWidth
                val toggleWidth = alarmToggleContainer.measuredWidth

                if (layoutWidth < containerWidth + toggleWidth) {
                    alarmParametersContainer.visibility = View.GONE
                } else {
                    alarmParametersContainer.visibility = View.VISIBLE
                }
            }

            // Listen for toggle changes to update the alarm's enabled state.
            alarmSwitch.setOnCheckedChangeListener { _, isChecked ->
                alarm.isEnabled = isChecked
                Log.d("AlarmAdapter", "Switch state changed: $isChecked for alarm: ${alarm.alarmName}")
                // Update the text color when the enabled state changes.
                val newTextColor = if (isChecked) enabledColor else disabledColor
                alarmTime.setTextColor(newTextColor)
                alarmName.setTextColor(newTextColor)
                repeatDaysTextView.setTextColor(newTextColor)

                if (isChecked) {
                    scheduleAlarm(alarm, itemView.context)
                } else {
                    cancelAlarm(alarm, itemView.context)
                }
                updateTimeTillNextAlarm()
                onAlarmStateChanged()
            }

            // Show delete button only in delete mode.
            deleteButton.visibility = if (isDeleteMode) View.VISIBLE else View.GONE

            // Ensure the item view fills the width.
            itemView.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT

            // Handle delete button click.
            deleteButton.setOnClickListener {
                onDeleteClicked(adapterPosition)
                updateTimeTillNextAlarm()
            }

            // Handle long click to enable delete mode.
            itemView.setOnLongClickListener {
                onItemLongClickListener?.invoke(adapterPosition)
                true
            }

            // Handle click.
            itemView.setOnClickListener { onAlarmClick(alarm) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.alarm_item, parent, false)
        return AlarmViewHolder(view)
    }

    private fun cancelAlarm(alarm: Alarm, context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        // Use the same unique request code formula as scheduleAlarm().
        val requestCode = alarm.id * 10000 + alarm.hours * 100 + alarm.minutes

        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d("AlarmAdapter", "Canceled alarm: ${alarm.alarmName}")
    }

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        holder.bind(alarms[position])
    }

    override fun getItemCount(): Int = alarms.size

    // Handle delete button click.
    private fun onDeleteClicked(position: Int) {
        alarms.removeAt(position) // Remove alarm from the list.
        notifyItemRemoved(position) // Notify adapter.
        updateEmptyState() // Check if the list is empty.
        saveAlarms() // Save the updated alarms list to SharedPreferences.
        // updateTimeTillNextAlarm() // Uncomment if needed.
    }
}

