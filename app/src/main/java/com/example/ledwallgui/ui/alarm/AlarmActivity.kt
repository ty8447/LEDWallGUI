package com.example.ledwallgui.ui.alarm

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledwallgui.R
import com.example.ledwallgui.TimeMonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AlarmActivity : AppCompatActivity() {

    private lateinit var alarmAdapter: AlarmAdapter
    private lateinit var cancelButton: Button
    private lateinit var offButton: Button
    private lateinit var deleteButton: Button
    private lateinit var emptyTextView: TextView
    private lateinit var alarmsRecyclerView: RecyclerView
    private lateinit var timeTillNext: TextView
    private lateinit var currentTime: TextView

    private val selectedAlarmIndices = mutableListOf<Int>()
    private val alarms = mutableListOf<Alarm>()

    private var use12HourTimeFormat = false
    private val updateIntervalMillis: Long = 30000 // Update every 30 seconds
    private val handler = Handler()

    companion object {
        const val EDIT_ALARM_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        configureSharedPreferences()
        setupAlarms()
        registerTimeChangeReceiver()
        startService()
    }

    private fun setupUI() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_alarm_menu)

        alarmsRecyclerView = findViewById(R.id.alarmsRecyclerView)
        timeTillNext = findViewById(R.id.timeTillNext)
        currentTime = findViewById(R.id.currentTime)
        cancelButton = findViewById(R.id.cancelButton)
        offButton = findViewById(R.id.offButton)
        deleteButton = findViewById(R.id.deleteButton)
        emptyTextView = findViewById(R.id.emptyTextView)

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
    }

    private fun configureSharedPreferences() {
        val sharedPreferences = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        use12HourTimeFormat = !android.text.format.DateFormat.is24HourFormat(this)
    }

    private fun setupAlarms() {
        alarmsRecyclerView.post {
            loadData()
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val loadedAlarms = withContext(Dispatchers.IO) { loadAlarms() }
            alarms.addAll(loadedAlarms)
            alarmAdapter.notifyDataSetChanged()
        }
    }

    private fun loadAlarms(): List<Alarm> {
        // Replace with your actual data loading logic
        return listOf() // Placeholder
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
        val intent = Intent(this, AlarmEditorActivity::class.java).apply {
            putExtra("clickedAlarmIndex", alarmIndex)
        }
        startActivityForResult(intent, EDIT_ALARM_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EDIT_ALARM_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            // Handle the updated alarm list
        }
    }

    private fun onCancel() {
        // Your logic here
    }

    private fun onTurnOff() {
        // Your logic here
    }

    private fun onDelete() {
        // Your logic here
    }
}

class AlarmAdapter(
    private val alarms: List<Alarm>,
    private val onAlarmClick: (Alarm) -> Unit
) : RecyclerView.Adapter<AlarmAdapter.AlarmViewHolder>() {

    inner class AlarmViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(alarm: Alarm) {
            // Bind data to your views here
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
}

