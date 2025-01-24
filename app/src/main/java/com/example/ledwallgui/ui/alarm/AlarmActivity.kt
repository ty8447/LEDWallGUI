package com.example.ledwallgui.ui.alarm

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private val sharedPreferencesName = "alarm_prefs"
    private val alarmsKey = "alarms_list"
    private lateinit var gson: Gson

    private val selectedAlarmIndices = mutableListOf<Int>()
    private val alarms = mutableListOf<Alarm>()

    private var use12HourTimeFormat: Boolean = false
    private val updateIntervalMillis: Long = 30000 // Update every 30 seconds
    private val handler = Handler()

    companion object {
        const val REQUEST_CODE_ADD_ALARM = 101
        const val EDIT_ALARM_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gson = Gson() // Initialize Gson
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
        refreshAlarms()
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
        val backButton: ImageButton = findViewById(R.id.aBackButton) // Find the button

        hideSystemUI()
        updateCurrentTime()

        alarmAdapter = AlarmAdapter(alarms) { clickedAlarm ->
            viewAlarmDetails(clickedAlarm)
        }
        alarmsRecyclerView.adapter = alarmAdapter
        alarmsRecyclerView.layoutManager = LinearLayoutManager(this)

        cancelButton.setOnClickListener { onCancel() }
        offButton.setOnClickListener { onTurnOff() }
        deleteButton.setOnClickListener { onDelete() }
        addAlarmButton.setOnClickListener { addNewAlarm() }

        // Set the back button click listener
        backButton.setOnClickListener {
            finish() // End the activity and go back
        }
    }

    private fun configureSharedPreferences() {
        val preferenceUtils = PreferenceUtils(this)
        use12HourTimeFormat = preferenceUtils.getUse12HourTimeFormat() // Retrieve time format preference
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
        val formattedTime = if (use12HourTimeFormat) {
            SimpleDateFormat("hh:mm a", Locale.US).format(calendar.time)
        } else {
            SimpleDateFormat("HH:mm", Locale.US).format(calendar.time)
        }
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

    private fun onDelete() {
        val selectedAlarms = alarms.filter { it.isSelected }
        alarms.removeAll(selectedAlarms)
        alarmAdapter.notifyDataSetChanged()
        updateEmptyState()
        saveAlarms() // Save after deletion
    }

    private fun updateEmptyState() {
        emptyTextView.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
    }

}

class AlarmAdapter(
    private val alarms: List<Alarm>,
    private val onAlarmClick: (Alarm) -> Unit
) : RecyclerView.Adapter<AlarmAdapter.AlarmViewHolder>() {

    inner class AlarmViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val alarmTime: TextView = itemView.findViewById(R.id.alarmTimeTextView)
        private val alarmName: TextView = itemView.findViewById(R.id.alarmNameTextView)
//        private val alarmSwitch: Switch = itemView.findViewById(R.id.alarmSwitch)

        fun bind(alarm: Alarm) {
            // Set time and name
            val time = String.format("%02d:%02d", alarm.hours, alarm.minutes)
            alarmTime.text = time
            alarmName.text = alarm.alarmName

            // Set enabled state
//            alarmSwitch.isChecked = alarm.isEnabled

            // Handle click
            itemView.setOnClickListener { onAlarmClick(alarm) }

            // Handle switch toggle
//            alarmSwitch.setOnCheckedChangeListener { _, isChecked ->
//                alarm.isEnabled = isChecked
//            }
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
}


