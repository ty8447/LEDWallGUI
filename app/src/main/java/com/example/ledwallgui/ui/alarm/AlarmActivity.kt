package com.example.ledwallgui.ui.alarm

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gson = Gson() // Initialize Gson
        preferenceUtils = PreferenceUtils(this) // Initialize preferenceUtils here
        use12HourTimeFormat = preferenceUtils.getIs12HourTimeFormat() // Load preference
        setupUI()
        configureSharedPreferences()

        // Load alarms on activity start
        val loadedAlarms = loadAlarms()
        alarms.clear() // Clear the current alarms list
        alarms.addAll(loadedAlarms) // Add loaded alarms
        alarmAdapter.notifyDataSetChanged() // Notify the adapter that the data has changed
        Log.d("AlarmActivity", "time format: $use12HourTimeFormat")
        registerTimeChangeReceiver()
        startService()
        updateEmptyState()
    }

    override fun onResume() {
        super.onResume()
        use12HourTimeFormat = preferenceUtils.getIs12HourTimeFormat() // Reload preference
        alarmAdapter.use12HourTimeFormat = use12HourTimeFormat // Update adapter
        refreshAlarms()
        updateCurrentTime()
        updateEmptyState()
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
            { saveAlarms() } // Pass the saveAlarms method
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

    private fun registerTimeChangeReceiver() {
        val timeChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateCurrentTime()
            }
        }
        val filter = IntentFilter(TimeMonitoringService.ACTION_TIME_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeChangeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timeChangeReceiver, filter)
        }
    }


    private fun startService() {
        val serviceIntent = Intent(this, TimeMonitoringService::class.java)
        startService(serviceIntent)
    }

    private fun hideSystemUI() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun updateCurrentTime() {
        val calendar = Calendar.getInstance()
        Log.d("AlarmActivity", "time format2: $use12HourTimeFormat")
        val timeFormat = if (use12HourTimeFormat) "hh:mm aaa" else "HH:mm" // Change "KK" to "hh"
        Log.d("AlarmActivity", "time formatct: $timeFormat")
        val formattedTime = SimpleDateFormat(timeFormat, Locale.getDefault()).format(calendar.time)
        currentTime.text = formattedTime
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
    private val saveAlarms: () -> Unit  // Reference to saveAlarms()
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
    }
}
