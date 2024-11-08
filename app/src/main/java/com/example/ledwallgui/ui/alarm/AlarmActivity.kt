package com.example.ledwallgui.ui.alarm

import com.example.ledwallgui.TimeMonitoringService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledwallgui.MainActivity
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.AlarmAlertActivity
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.time.Clock
import java.time.Instant


class AlarmActivity : AppCompatActivity() {
    private lateinit var alarmAdapter: AlarmAdapter
    private lateinit var cancelButton: Button
    private lateinit var offButton: Button
    private lateinit var deleteButton: Button
    private lateinit var emptyTextView: TextView
    private var shouldUpdateList = intent?.getBooleanExtra("updatelist", false) ?: false // Retrieve the updatelist value
    private val selectedAlarmIndices = mutableListOf<Int>() // Array to store selected alarm indices
    private var use12HourTimeFormat = false // Initialize with default value
    private val daysOfWeek = mutableListOf<String>()
    private val mondayStart = true
    val clock: Clock = Clock.systemDefaultZone()
    private var previousInstant: Instant = clock.instant()
    private val alarms: MutableList<Alarm> = mutableListOf(
        Alarm(6, 30, "Demo 1", true, false, false, false, BooleanArray(7), BooleanArray(7)),
        Alarm(8, 0, "Demo 2", false, false, false, false, BooleanArray(7), BooleanArray(7)),
        // Add more alarms
    )
    private val updateIntervalMillis: Long = 30000 // Update every minute
    private val handler = Handler()
    private lateinit var timeTillNext: TextView
    private lateinit var currentTime: TextView
    private val timeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TimeMonitoringService.ACTION_TIME_CHANGED) {
                // Update the UI or perform any necessary actions
                updateCurrentTime()
                findNextAlarm()
            }
        }
    }

    private fun onAlarmToggle(position: Int) {
        if (!alarmAdapter.isToggling) {
            alarms[position].isEnabled = !alarms[position].isEnabled
            alarmAdapter.notifyItemChanged(position)
            findNextAlarm()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, // Hide the status bar
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_alarm_menu)
        hideSystemUI()
        timeTillNext = findViewById(R.id.timeTillNext)
        currentTime = findViewById(R.id.currentTime)
        updateCurrentTime()

        val sharedPreferences = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

// Get time format option
        val timeFormatOption = sharedPreferences.getInt("time_format_option", 0) // Default to 0 if not found

// Set use12HourTimeFormat based on time format option
        use12HourTimeFormat = when (timeFormatOption) {
            0 -> { // Match Device
                !android.text.format.DateFormat.is24HourFormat(this)
            }
            1 -> { // 12 Hour
                true
            }
            2 -> { // 24 Hour
                false
            }
            else -> { // Default to Match Device
                !android.text.format.DateFormat.is24HourFormat(this)
            }
        }

        val backButton: ImageButton = findViewById(R.id.aBackButton)
        cancelButton = findViewById(R.id.cancelButton)
        offButton = findViewById(R.id.offButton)
        deleteButton = findViewById(R.id.deleteButton)
        emptyTextView = findViewById(R.id.emptyTextView)
        val addAlarm: Button = findViewById(R.id.addAlarm)
        val alarmsRecyclerView = findViewById<RecyclerView>(R.id.alarmsRecyclerView)
        alarmAdapter = AlarmAdapter(
            alarms,
            ::viewAlarmDetails,
            use12HourTimeFormat,
            //shouldUpdateList,
            cancelButton,
            offButton,
            deleteButton,
            selectedAlarmIndices,
            daysOfWeek,
            ::findNextAlarm,
            ::toggleAlarmEnabled,
            this::onAlarmToggle)

        alarmsRecyclerView.itemAnimator = SlideInUpAnimator()
        alarmsRecyclerView.adapter = alarmAdapter
        alarmsRecyclerView.layoutManager = LinearLayoutManager(this)

        updateEmptyTextViewVisibility()
        Log.d("ALARM UPDATER","List Updated? 68: $shouldUpdateList")

        backButton.setOnClickListener {
            returnHome()
        }



        if (mondayStart) {
            daysOfWeek.addAll(listOf("M", "T", "W", "T", "F", "S", "S"))
        } else {
            daysOfWeek.addAll(listOf("S", "M", "T", "W", "T", "F", "S"))
        }

        Log.d("Days of Week Order", "$daysOfWeek")
        cancelButton.setOnClickListener {
            offButton.visibility = View.GONE
            cancelButton.visibility = View.GONE
            deleteButton.visibility = View.GONE
            alarms.forEach { it.showCheckbox = false }
            Log.d("Array Notif", "List of Selected Items: $selectedAlarmIndices")
          //  selectedAlarmIndices.clear() // Clear the list of selected indice
            updateEmptyTextViewVisibility()
            Log.d("ALARM UPDATER","List Updated? 83: $shouldUpdateList")
        }
        offButton.setOnClickListener {
            alarms.forEachIndexed { index, alarm ->
                if (alarm.isSelected) {
                    alarm.isEnabled = false
                }
            }
            offButton.visibility = View.GONE
            cancelButton.visibility = View.GONE
            deleteButton.visibility = View.GONE
            alarms.forEach { it.showCheckbox = false }
            alarmAdapter.notifyDataSetChanged() // Update RecyclerView
        }
        deleteButton.setOnClickListener {
            selectedAlarmIndices.sortedDescending().forEach { index ->
                alarms.removeAt(index)
            }

            // Adjust indices in the selectedAlarmIndices list after removing alarms
            selectedAlarmIndices.clear()
            alarms.forEachIndexed { index, alarm ->
                if (alarm.isSelected) {
                    selectedAlarmIndices.add(index)
                }
            }

            offButton.visibility = View.GONE
            cancelButton.visibility = View.GONE
            deleteButton.visibility = View.GONE
            alarms.forEach { it.showCheckbox = false }
            Log.d("ALARM UPDATER","List Updated? 116: $shouldUpdateList")
            updateEmptyTextViewVisibility()
            findNextAlarm()
        }
        addAlarm.setOnClickListener {
                val intent = Intent(this, alarmEditorActivity::class.java)
                intent.putParcelableArrayListExtra("alarmsList", ArrayList(alarms)) // Pass the alarms list
                intent.putExtra("use12HourTimeFormat", use12HourTimeFormat)
                intent.putExtra("isEditMode", false) // Indicate it's for creating a new alarm
                intent.putExtra("editorNameText", "Create Alarm") // Pass the text to display in the editor
                Log.d("AlarmActivity","Add Alarm Button Pressed")
                offButton.visibility = View.GONE
                cancelButton.visibility = View.GONE
                deleteButton.visibility = View.GONE
                alarms.forEach { it.showCheckbox = false }
                startActivityForResult(intent, EDIT_ALARM_REQUEST_CODE)
                alarmAdapter.notifyDataSetChanged()
            Log.d("ALARM UPDATER","List Updated? 132: $shouldUpdateList")
                updateEmptyTextViewVisibility()
        }
        val filter = IntentFilter(TimeMonitoringService.ACTION_TIME_CHANGED)
        registerReceiver(timeChangeReceiver, filter)
        startService()
    }

    private fun startService() {
        val serviceIntent = Intent(this, TimeMonitoringService::class.java)
        startService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter(TimeMonitoringService.ACTION_TIME_CHANGED)
        registerReceiver(updateUIReceiver, intentFilter)
        // Update the adapter and any other necessary UI updates
        alarmAdapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        // Unregister the BroadcastReceiver when the activity is paused
        unregisterReceiver(updateUIReceiver)
    }

    fun updateCurrentTime() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
       // Log.d("Time:","Current Time Updated")
        if (use12HourTimeFormat) {
            // Format time as 12-hour format with AM/PM
            val formattedTime = SimpleDateFormat("hh:mm", Locale.US).format(calendar.time)
            val amPm = SimpleDateFormat("a", Locale.US).format(calendar.time)

            val formattedText = "$formattedTime <small><small>$amPm</small></small>"
                currentTime.text = Html.fromHtml(formattedText, Html.FROM_HTML_MODE_COMPACT)
        } else {
            // Format time as 24-hour format
            val formattedTime = String.format("%02d:%02d", hour, minute)
            currentTime.text = formattedTime
        }
        findNextAlarm()
    }
    fun findNextAlarm() {
        val currentTimeInMillis = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000 // 24 hours in milliseconds
        val oneWeekInMillis = 7 * oneDayInMillis // One week in milliseconds
        var currentDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        var nextEnabledAlarm: Alarm? = null
        var nextTimeDifference: Long = Long.MAX_VALUE
        var anyEnabledAlarm = false // Flag to check if any enabled alarms are found
        if (mondayStart == true) {
            currentDayOfWeek--
        }
        //Log.d("Alarm Day:","$currentDayOfWeek")
        for (alarm in alarms) {
            if (alarm.isEnabled) {
                var alarmTimeInMillis = alarm.getTimeAsCalendar().timeInMillis

                val firstTrueDayIndex = alarm.selectedDays.indexOfFirst { it } + 1
                val allFalseSelectedDays = alarm.selectedDays.all { !it }
                if (firstTrueDayIndex != -1 && !allFalseSelectedDays) {
                    if (currentDayOfWeek > firstTrueDayIndex) {
                        alarmTimeInMillis += oneWeekInMillis
                        alarmTimeInMillis += oneDayInMillis * (firstTrueDayIndex - currentDayOfWeek + 7)
                       // Log.d("Adding Time 1", "Add time to $alarm")
                    } else if (currentDayOfWeek < firstTrueDayIndex) {
                        alarmTimeInMillis += oneDayInMillis * (firstTrueDayIndex - currentDayOfWeek)
                        //Log.d("Adding Time 2", "Add time to $alarm")
                    } else if (alarmTimeInMillis <= currentTimeInMillis) {
                        alarmTimeInMillis += oneDayInMillis
                        //Log.d("Adding Time 3", "Add time to $alarm")
                    }
                } else if (allFalseSelectedDays && alarmTimeInMillis <= currentTimeInMillis) {
                    alarmTimeInMillis += oneDayInMillis
                    //Log.d("Adding Time 4", "Add time to $alarm")
                }

                val timeDifference = alarmTimeInMillis - currentTimeInMillis

                if (timeDifference in 1 until nextTimeDifference) {
                    nextTimeDifference = timeDifference
                    nextEnabledAlarm = alarm
                }
                anyEnabledAlarm = true
            }
        }

        if (anyEnabledAlarm) {
            nextEnabledAlarm?.let {
                val nextAlarmTime = it.getTimeAsCalendar()
                val formattedTime = if (use12HourTimeFormat) {
                    SimpleDateFormat("hh:mm a", Locale.US).format(nextAlarmTime.time)
                } else {
                    SimpleDateFormat("HH:mm", Locale.US).format(nextAlarmTime.time)
                }
                timeTillNext.text = "Next Alarm: $formattedTime"
                timeTillNext.visibility = View.VISIBLE

                // Check if the next alarm's time matches the current time
                val currentTimeMillis = System.currentTimeMillis()
                if (nextTimeDifference < updateIntervalMillis && nextTimeDifference > 0) {
                    // Start AlarmAlertActivity here
                    triggerAlarm(formattedTime, it.alarmName, alarms.indexOf(it))
                }
            }
        } else {
            timeTillNext.visibility = View.GONE
        }


    }

    private val updateUIReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Update the time in your UI here
            updateCurrentTime()
            // ...
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove the update Runnable when the activity is destroyed to prevent memory leaks
       // handler.removeCallbacks(updateRunnable)
        unregisterReceiver(timeChangeReceiver)
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateEmptyTextViewVisibility() {
        if (alarms.isEmpty()) {
            emptyTextView.visibility = View.VISIBLE
            Log.d("AlarmActivity", "Empty text view is visible")
        } else {
            emptyTextView.visibility = View.GONE
            Log.d("AlarmActivity", "Empty text view is hidden")
        }
        alarmAdapter.notifyDataSetChanged() // Update RecyclerView
    }

    private fun returnHome() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun viewAlarmDetails(clickedAlarm: Alarm) {
        Log.d("AlarmActivity", "Alarms before update: $alarms")

        val alarmIndex = alarms.indexOf(clickedAlarm)
        val intent = Intent(this, alarmEditorActivity::class.java)
        intent.putExtra("clickedAlarmIndex", alarmIndex)
        intent.putExtra("use12HourTimeFormat", use12HourTimeFormat)
        intent.putExtra("isEditMode", true) // Set isEditMode to true for editing
        intent.putExtra("editorNameText", "Edit Alarm") // Pass the text to display in the editor
        intent.putParcelableArrayListExtra("alarmsList", ArrayList(alarms)) // Pass the alarms list
        startActivityForResult(intent, EDIT_ALARM_REQUEST_CODE)
    }

    private fun hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d("AlarmActivity", "onActivityResult called")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == EDIT_ALARM_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val updatedAlarm = data?.getParcelableExtra<Alarm>("updatedAlarm")
            val updatedAlarmIndex = data?.getIntExtra("alarmIndex", -1)
            val newAlarm = data?.getParcelableExtra<Alarm>("newAlarm")
            shouldUpdateList = data?.getBooleanExtra("updatelist", false) ?: false // Retrieve the updatelist value
            Log.d("ALARM UPDATER","List Updated? 197: $shouldUpdateList")

            if (updatedAlarm != null && updatedAlarmIndex != -1) {
                alarms[updatedAlarmIndex!!] = updatedAlarm
                alarmAdapter.notifyItemChanged(updatedAlarmIndex)
            } else if (newAlarm != null) {
                alarms.add(newAlarm)
                alarmAdapter.notifyItemInserted(alarms.size - 1)
            }

            if (shouldUpdateList) {
                findNextAlarm()
                updateEmptyTextViewVisibility()
            }
            Log.d("AlarmActivity", "Request Codes Good")
            if (shouldUpdateList) {
                // Run your function here to update the list
                updateEmptyTextViewVisibility()
            }
        }

        if (requestCode == ALARM_ALERT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val dismissedAlarmIndex = data?.getIntExtra("alarmIndex", -1)
            Log.d("Alert","$dismissedAlarmIndex")
            if (dismissedAlarmIndex != -1) {
                Log.d("AlarmAlert", "Updating Dismissed Alarm")
                alarms[dismissedAlarmIndex!!].isEnabled = false
                Log.d("AlarmAlert", "Second Thing for Alarm")
                alarmAdapter.notifyItemChanged(dismissedAlarmIndex)
                Log.d("AlarmAlert", "Done")
            }
        } else {
            Log.d("AlarmActivity", "Nope")
        }
    }

    private fun triggerAlarm(alarmTime: String, alarmName: String, alarmIndex: Int) {
        val intent = Intent(this, AlarmAlertActivity::class.java)
        alarms[alarmIndex].isEnabled = false
        intent.putExtra("alarmTime", alarmTime)
        intent.putExtra("alarmName", alarmName)
        intent.putExtra("dismissedAlarmIndex", alarmIndex) // Pass the index of the alarm
        startActivityForResult(intent, ALARM_ALERT_REQUEST_CODE)
     }

    private fun toggleAlarmEnabled(position: Int) {
        // Add your logic to toggle the alarm's enabled state
        alarms[position].isEnabled = !alarms[position].isEnabled

        // Notify the adapter that the item has changed
        alarmAdapter.notifyItemChanged(position)

        // After updating the alarm state, find the next alarm
        findNextAlarm()
    }


    companion object {
        const val EDIT_ALARM_REQUEST_CODE = 1
        const val ALARM_ALERT_REQUEST_CODE = 2
    }
}

class AlarmAdapter(
    private var alarms: MutableList<Alarm>,
    private val clickListener: (Alarm) -> Unit,
    private val use12HourTimeFormat: Boolean,
    private val cancelButton: Button,
    private val offButton: Button,
    private val deleteButton: Button,
    private val selectedAlarmIndices: MutableList<Int>,
    private val daysOfWeek: List<String>,
    private val findNextAlarm: () -> Unit,
    private val toggleAlarmEnabled: (Int) -> Unit,
    private val onAlarmToggle: (position: Int) -> Unit
) : RecyclerView.Adapter<AlarmAdapter.AlarmViewHolder>() {
    private var lastClickedPosition = RecyclerView.NO_POSITION
    private val handler = Handler()
    private var selectedAlarmIndex = RecyclerView.NO_POSITION
    var isToggling = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm, parent, false)
        return AlarmViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        val currentAlarm = alarms[position]
        holder.bind(currentAlarm)
        holder.circularCheckbox.visibility =
            if (currentAlarm.showCheckbox) View.VISIBLE else View.GONE
        holder.circularCheckbox.isChecked = currentAlarm.isSelected
        holder.itemView.isSelected = position == selectedAlarmIndex
        holder.switchAlarmEnabled.setOnCheckedChangeListener { _, isChecked ->
            toggleAlarmEnabled(position)
        }

    }

    override fun getItemCount(): Int {
        return alarms.size
    }

    fun notifyAdapterItemClicked(position: Int) {
        selectedAlarmIndex = position
        lastClickedPosition = position
        notifyItemChanged(selectedAlarmIndex)
    }

    inner class AlarmViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val alarmTimeTextView: TextView = itemView.findViewById(R.id.textViewAlarmTime)
        val alarmSwitch: Switch = itemView.findViewById(R.id.toggleAlarm)
        val circularCheckbox: CheckBox =
            itemView.findViewById(R.id.circularCheckbox) // Add this line
        val alarmName: TextView = itemView.findViewById(R.id.alarmName)
        val alarmDay0: TextView = itemView.findViewById(R.id.alarmDay0)
        val alarmDay1: TextView = itemView.findViewById(R.id.alarmDay1)
        val alarmDay2: TextView = itemView.findViewById(R.id.alarmDay2)
        val alarmDay3: TextView = itemView.findViewById(R.id.alarmDay3)
        val alarmDay4: TextView = itemView.findViewById(R.id.alarmDay4)
        val alarmDay5: TextView = itemView.findViewById(R.id.alarmDay5)
        val alarmDay6: TextView = itemView.findViewById(R.id.alarmDay6)
        val switchAlarmEnabled: Switch = itemView.findViewById(R.id.toggleAlarm)

        init {
            // Set the background selector for the item view
            val dayTextViews = List(7) { index ->
                itemView.findViewById<TextView>(
                    itemView.resources.getIdentifier(
                        "alarmDay$index",
                        "id",
                        itemView.context.packageName
                    )
                )
                alarmSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (!isToggling) {
                        isToggling = true
                        onAlarmToggle(adapterPosition)
                        isToggling = false
                    }
                }
            }
            itemView.setBackgroundResource(R.drawable.item_alarm_selector)

            itemView.setOnLongClickListener {
                val clickedAlarm = alarms[adapterPosition]
                if (!offButton.isShown) {
                    alarms.forEach { it.isSelected = false }
                    clickedAlarm.isSelected = !clickedAlarm.isSelected
                    alarms.forEach { it.showCheckbox = true }
                    offButton.visibility = View.VISIBLE
                    cancelButton.visibility = View.VISIBLE
                    deleteButton.visibility = View.VISIBLE
                    notifyDataSetChanged()
                    val slideUpAnimation =
                        AnimationUtils.loadAnimation(itemView.context, R.anim.slide_up)
                    cancelButton.startAnimation(slideUpAnimation)
                    offButton.startAnimation(slideUpAnimation)
                    deleteButton.startAnimation(slideUpAnimation)
                }
                true // Consume the long press event
            }

            circularCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedAlarmIndices.add(adapterPosition)
                } else {
                    selectedAlarmIndices.remove(adapterPosition)
                }
                handler.post {
                    notifyDataSetChanged()
                }
            }
        }

        fun bind(alarm: Alarm) {
            if (use12HourTimeFormat) {
                // Format time as 12-hour format with AM/PM
                val formattedTime = SimpleDateFormat("hh:mm", Locale.US).format(alarm.getTimeAsCalendar().time)
                val amPm = SimpleDateFormat("a", Locale.US).format(alarm.getTimeAsCalendar().time)

                val formattedText = "$formattedTime <small><small>$amPm</small></small>"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    alarmTimeTextView.text = Html.fromHtml(formattedText, Html.FROM_HTML_MODE_COMPACT)
                } else {
                    @Suppress("DEPRECATION")
                    alarmTimeTextView.text = Html.fromHtml(formattedText)
                }
            } else {
                // Format time as 24-hour format
                val formattedTime = String.format("%02d:%02d", alarm.hours, alarm.minutes)
                alarmTimeTextView.text = formattedTime
            }

            alarmName.text = alarm.alarmName
            circularCheckbox.visibility = if (alarm.showCheckbox) View.VISIBLE else View.GONE
            circularCheckbox.isChecked = alarm.isSelected
            alarmSwitch.isChecked = alarm.isEnabled
            val dayTextViews = arrayOf(
                alarmDay0, alarmDay1, alarmDay2,
                alarmDay3, alarmDay4, alarmDay5, alarmDay6
            )
            var removableDays = alarm.selectedDays
            for (i in dayTextViews.indices) {
                dayTextViews[i].text = daysOfWeek[i]
                dayTextViews[i].setTextColor(
                    if (removableDays[i]) Color.parseColor("#03dac6") else Color.WHITE)
            }
            itemView.isSelected = adapterPosition == lastClickedPosition

            itemView.setOnClickListener {
                if (!offButton.isShown) {
                    clickListener(alarm)
                } else {
                    circularCheckbox.isChecked = !circularCheckbox.isChecked
                }
            }
        }
    }
}



