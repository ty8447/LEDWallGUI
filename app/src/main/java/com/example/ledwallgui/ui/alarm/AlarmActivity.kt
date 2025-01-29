package com.example.ledwallgui.ui.alarm

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.R
import com.example.ledwallgui.TimeMonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.google.gson.Gson
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
            Log.d("AlarmActivity", "onReceive called via LocalBroadcastManager")
            if (intent?.action == "com.example.ledwallgui.TIME_UPDATE") {
                val currentTimeMillis = intent.getLongExtra("current_time", 0L)
                Log.d("AlarmActivity", "Received time update: $currentTimeMillis")
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
        Log.d("AlarmActivity", "time format: $use12HourTimeFormat")
        updateEmptyState()
        val filter = IntentFilter("com.example.ledwallgui.TIME_UPDATE")
        LocalBroadcastManager.getInstance(this).registerReceiver(timeUpdateReceiver, filter)
    }

    private fun checkAndRequestPermission() {
        Log.d("AlarmActivity", "Checking permissions...")
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC
            ) == PackageManager.PERMISSION_GRANTED) {
            Log.d("AlarmActivity", "Permission granted.")
        } else {
            // Permission is not granted, so request it
            Log.d("AlarmActivity", "Permission not granted. Requesting...")
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
                    Log.d("AlarmActivity", "Permission granted for foreground service data sync.")
                } else {
                    // Permission denied
                    Log.d("AlarmActivity", "Permission denied for foreground service data sync.")
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
            { updateTimeTillNextAlarm()} //Pass the updateTimeTillNextAlarm method
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
            isDeleteMode = false
            alarmAdapter.isDeleteMode = isDeleteMode // Update delete mode in the adapter
            alarmAdapter.notifyDataSetChanged() // Refresh the RecyclerView
            doneButton.visibility = View.GONE // Hide Done button after exiting delete mode
        }
    }

    private fun updateTimeTillNextAlarm() {
        val currentTime = Calendar.getInstance()
        Log.d("AlarmDebug", "Current time: ${currentTime.time}")

        // Sort alarms to find the next one based on current time
        val nextAlarm = alarms
            .filter {
                Log.d("AlarmDebug", "Checking alarm: $it")
                it.isEnabled
            } // Only consider enabled alarms
            .mapNotNull { alarm ->
                val alarmTime = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, alarm.hours)
                    set(Calendar.MINUTE, alarm.minutes)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                Log.d("AlarmDebug", "Initial alarm time for ${alarm.alarmName}: ${alarmTime.time}")

                val currentDayIndex = currentTime.get(Calendar.DAY_OF_WEEK) - 1
                Log.d("AlarmDebug", "Current day index: $currentDayIndex")
                val selectedDays = alarm.selectedDays
                Log.d("AlarmDebug", "Selected days for ${alarm.alarmName}: ${selectedDays.joinToString()}")

                if (!selectedDays.contains(true)) {
                    // Handle the case where no repeating days are selected
                    if (alarmTime.before(currentTime)) {
                        Log.d("AlarmDebug", "Skipping ${alarm.alarmName}, time has passed")
                        alarmTime.add(Calendar.DAY_OF_YEAR, 1) // Set for the next day
                    }
                    Log.d("AlarmDebug", "Skipping ${alarm.alarmName}, no days selected")
                    return@mapNotNull alarmTime
                }

                // Adjust the alarm to the next available day based on selectedDays
                var daysToAdd = 0
                while (!selectedDays[(currentDayIndex + daysToAdd) % 7]) {
                    Log.d("AlarmDebug", "Incrementing daysToAdd for ${alarm.alarmName}: $daysToAdd")
                    daysToAdd++
                    if (daysToAdd > 7) {
                        Log.d("AlarmDebug", "No valid day found for ${alarm.alarmName}, skipping")
                        return@mapNotNull null
                    } // Prevent infinite loop
                }

                if (daysToAdd == 0 && alarmTime.before(currentTime)) {
                    Log.d("AlarmDebug", "${alarm.alarmName} is for today, but the time has passed")
                    daysToAdd = 1
                    while (!selectedDays[(currentDayIndex + daysToAdd) % 7]) {
                        daysToAdd++
                        Log.d("AlarmDebug", "Incrementing daysToAdd for passed time on ${alarm.alarmName}: $daysToAdd")
                        if (daysToAdd > 7) {
                            Log.d("AlarmDebug", "No valid future day found for ${alarm.alarmName}, skipping")
                            return@mapNotNull null
                        } // Prevent infinite loop
                    }
                }
                Log.d("AlarmDebug", "Final daysToAdd for ${alarm.alarmName}: $daysToAdd")
                alarmTime.add(Calendar.DAY_OF_YEAR, daysToAdd)
                Log.d("AlarmDebug", "Adjusted alarm time for ${alarm.alarmName}: ${alarmTime.time}")
                alarmTime
            }
            .minByOrNull { it.timeInMillis } // Find the earliest alarm

        // Update the TextView with the time difference
        if (nextAlarm != null) {
            var timeDifference = nextAlarm.timeInMillis - currentTime.timeInMillis
            timeDifference += 59_000
            val hours = timeDifference / (1000 * 60 * 60)
            val minutes = (timeDifference / (1000 * 60)) % 60
            Log.d("AlarmDebug", "Next alarm time: ${nextAlarm.time}")
            Log.d("AlarmDebug", "Time difference: $hours hours and $minutes minutes")
            timeTillNext.text = getString(R.string.time_till_next_alarm, hours, minutes)
        } else {
            Log.d("AlarmDebug", "No upcoming alarms found")
            timeTillNext.text = getString(R.string.no_upcoming_alarms)
        }
    }

    private fun configureSharedPreferences() {
        val preferenceUtils = PreferenceUtils(this)
        use12HourTimeFormat = preferenceUtils.getIs12HourTimeFormat() // Retrieve time format preference
        Log.d("AlarmActivity", "time format1: $use12HourTimeFormat")
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
        Log.d("AlarmActivity", "Updating time... format: $use12HourTimeFormat")
        val timeFormat = if (use12HourTimeFormat) "hh:mm aaa" else "HH:mm" // Change "KK" to "hh"
        Log.d("AlarmActivity", "Time format: $timeFormat")
        val formattedTime = SimpleDateFormat(timeFormat, Locale.getDefault()).format(calendar.time)
        currentTime.text = formattedTime
        updateTimeTillNextAlarm()
    }

    private fun viewAlarmDetails(clickedAlarm: Alarm) {
        val alarmIndex = alarms.indexOf(clickedAlarm)
        Log.d("AlarmActivity", "viewAlarmDetails: Selected alarm at index $alarmIndex")

        if (alarmIndex != -1) {
            val intent = Intent(this, AlarmEditorActivity::class.java).apply {
                putExtra("clickedAlarmIndex", alarmIndex) // Pass the valid index
                putParcelableArrayListExtra("alarmsList", ArrayList(alarms)) // Pass all alarms list
                putExtra("use12HourTimeFormat", use12HourTimeFormat) // Pass the time format preference
            }
            startActivityForResult(intent, EDIT_ALARM_REQUEST_CODE)
        } else {
            Log.d("AlarmActivity", "viewAlarmDetails: Alarm not found")
            Toast.makeText(this, "Alarm not found", Toast.LENGTH_SHORT).show()
        }
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
                }
            }
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
        emptyTextView.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
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
    private val updateTimeTillNextAlarm: () -> Unit // Add the updateTimeTillNextAlarm function as a parameter
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
            // Format time based on the 12-hour or 24-hour preference
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, alarm.hours)
                set(Calendar.MINUTE, alarm.minutes)
            }
            val timeFormat = if (use12HourTimeFormat) "hh:mm aaa" else "HH:mm"
            val formattedTime = SimpleDateFormat(timeFormat, Locale.getDefault()).format(calendar.time)

            alarmTime.text = formattedTime
            alarmName.text = alarm.alarmName

            deleteButtonContainer.visibility = if (isDeleteMode) View.VISIBLE else View.GONE

            // Dynamically hide alarm parameters if space is insufficient
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

            // Convert selectedDays to a list and set selected days
            val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            val selectedDaysList = alarm.selectedDays.toList() // Convert BooleanArray to List<Boolean>

            val selectedDaysText = if (selectedDaysList.all { it }) {
                // If all days are selected, show "Everyday"
                "Everyday"
            } else {
                // Otherwise, show selected days
                selectedDaysList
                    .mapIndexedNotNull { index, isSelected -> if (isSelected) daysOfWeek[index] else null }
                    .joinToString(" ")
            }

            if (selectedDaysText.isNotEmpty()) {
                repeatDaysTextView.text = selectedDaysText
                repeatDaysTextView.visibility = View.VISIBLE
            } else {
                repeatDaysTextView.visibility = View.GONE
            }

            // Set the switch state based on alarm.isEnabled
            alarmSwitch.isChecked = alarm.isEnabled

            // Listen for toggle changes to update the alarm's enabled state
            alarmSwitch.setOnCheckedChangeListener { _, isChecked ->
                alarm.isEnabled = isChecked
                updateTimeTillNextAlarm()
                // You may want to save the alarms after toggling
                onAlarmStateChanged()
            }

            // Show delete button only in delete mode
            deleteButton.visibility = if (isDeleteMode) View.VISIBLE else View.GONE

            // Don't shrink item in delete mode, only show delete button
            itemView.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT

            // Handle delete button click
            deleteButton.setOnClickListener {
                onDeleteClicked(adapterPosition)
                updateTimeTillNextAlarm()
            }

            // Handle long click to enable delete mode
            itemView.setOnLongClickListener {
                onItemLongClickListener?.invoke(adapterPosition)
                true
            }

            // Handle click
            itemView.setOnClickListener { onAlarmClick(alarm) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.alarm_item, parent, false)
        return AlarmViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        holder.bind(alarms[position])
    }

    override fun getItemCount(): Int = alarms.size

    // Handle delete button click
    private fun onDeleteClicked(position: Int) {
        alarms.removeAt(position) // Remove alarm from the list
        notifyItemRemoved(position) // Notify adapter
        updateEmptyState() // Check if the list is empty
        saveAlarms() // Save the updated alarms list to SharedPreferences
//        updateTimeTillNextAlarm() // Update the time till next alarm
    }
}
