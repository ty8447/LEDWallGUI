package com.example.ledwallgui.ui.alarm

import BrightnessPadDialog
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import com.example.ledwallgui.R

class AlarmEditorActivity : AppCompatActivity() {
    private var alarms: MutableList<Alarm> = mutableListOf()
    private var use12HourTimeFormat = false
    private var alarmToEdit: Alarm? = null
    // Holds the selected days state; its initial value will be updated either from the alarm or by toggling
    private var selectedDays: BooleanArray = BooleanArray(7) { false }
    private lateinit var dayButtons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.alarm_editor)
        Log.d("alarmEditorActivity", "onCreate: Activity started")
        hideSystemUI()

        initializeViews()
        handleIntentExtras()
        setUpDayButtons()
        setUpSaveButton()
        setUpCancelButton()
        setUpBrightnessControls()
        handleTimePicker()
    }

    override fun onResume() {
        super.onResume()
        handleTimePicker()
    }

    private fun initializeViews() {
        dayButtons = listOf(
            R.id.day1, R.id.day2, R.id.day3, R.id.day4,
            R.id.day5, R.id.day6, R.id.day7
        ).map { buttonId ->
            val btn = findViewById<Button>(buttonId)
            // Initialize the tag to false (unselected)
            btn.tag = false
            btn
        }
    }

    private fun handleIntentExtras() {
        val isEditMode = intent.getBooleanExtra("isEditMode", false)
        Log.d("AlarmEditorActivity", "handleIntentExtras: isEditMode = $isEditMode")

        val clickedAlarmId = intent.getIntExtra("clickedAlarmId", -1)
        Log.d("AlarmEditorActivity", "handleIntentExtras: clickedAlarmId = $clickedAlarmId")

        val alarmsList = intent.getParcelableArrayListExtra<Alarm>("alarmsList")
        if (alarmsList != null) {
            alarms.clear()
            alarms.addAll(alarmsList)
            Log.d("AlarmEditorActivity", "handleIntentExtras: Alarms list size = ${alarms.size}")
        }

        if (clickedAlarmId != -1) {
            alarmToEdit = alarms.find { it.id == clickedAlarmId }
            if (alarmToEdit != null) {
                populateAlarmDetails(alarmToEdit!!)
            } else {
                Log.e("AlarmEditorActivity", "handleIntentExtras: Alarm with ID $clickedAlarmId not found")
            }
        } else {
            Log.d("AlarmEditorActivity", "handleIntentExtras: No alarm to edit or invalid ID")
            alarmToEdit = null
        }

        use12HourTimeFormat = intent.getBooleanExtra("use12HourTimeFormat", false)
        Log.d("AlarmEditorActivity", "handleIntentExtras: use12HourTimeFormat = $use12HourTimeFormat")

        handleTimePicker()
    }

    private fun populateAlarmDetails(existingAlarm: Alarm) {
        Log.d("AlarmEditorActivity", "populateAlarmDetails: Populating alarm with name: ${existingAlarm.alarmName}")

        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        Log.d("AlarmEditorActivity", "populateAlarmDetails: Setting time - Hour: ${existingAlarm.hours}, Minute: ${existingAlarm.minutes}")
        timePicker.hour = existingAlarm.hours
        timePicker.minute = existingAlarm.minutes

        val alarmNameTextView = findViewById<TextView>(R.id.alarmName)
        alarmNameTextView.text = existingAlarm.alarmName
        Log.d("AlarmEditorActivity", "populateAlarmDetails: Setting alarm name: ${existingAlarm.alarmName}")

        // Update selectedDays from the existing alarm and also update each button's tag
        selectedDays = existingAlarm.selectedDays.copyOf()
        selectedDays.forEachIndexed { index, isSelected ->
            dayButtons[index].tag = isSelected
        }
        Log.d("AlarmEditorActivity", "populateAlarmDetails: Selected days: ${selectedDays.joinToString(", ")}")

        updateDayButtonVisuals()
    }

    private fun setUpDayButtons() {
        dayButtons.forEachIndexed { index, dayButton ->
            dayButton.setOnClickListener {
                toggleDayButtonState(index)
            }
        }
    }

    private fun setUpSaveButton() {
        findViewById<Button>(R.id.save).setOnClickListener {
            saveAlarm()
        }
    }

    private fun setUpCancelButton() {
        findViewById<Button>(R.id.cancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun setUpBrightnessControls() {
        val brightVal = findViewById<TextView>(R.id.brightVal)
        val brightnessSeekBar = findViewById<SeekBar>(R.id.brightness)

        brightVal.setOnClickListener {
            val initialValue = brightVal.text.toString().replace("%", "").toIntOrNull() ?: 0
            val numberPadDialog = BrightnessPadDialog(this, initialValue) { value ->
                brightVal.text = "$value%"
                brightnessSeekBar.progress = value
            }
            numberPadDialog.show()
        }

        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                brightVal.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun handleTimePicker() {
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        Log.d("AlarmEditorActivity", "handleTimePicker: use12HourTimeFormat = $use12HourTimeFormat")

        alarmToEdit?.let {
            timePicker.hour = it.hours
            timePicker.minute = it.minutes
            if (use12HourTimeFormat) {
                timePicker.setIs24HourView(false)
            } else {
                timePicker.setIs24HourView(true)
            }
        }
    }

    private fun toggleDayButtonState(dayIndex: Int) {
        // Toggle the value in the array
        selectedDays[dayIndex] = !selectedDays[dayIndex]

        // Update the corresponding button's tag
        dayButtons[dayIndex].tag = selectedDays[dayIndex]

        // Log the change
        val dayNames = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val status = if (selectedDays[dayIndex]) "selected" else "deselected"
        Log.d("AlarmEditorActivity", "Day ${dayNames[dayIndex]} was $status")

        // Update UI to reflect changes
        updateDayButtonVisuals()
    }

    private fun updateDayButtonVisuals() {
        dayButtons.forEachIndexed { index, dayButton ->
            val drawableResId = if (selectedDays[index]) {
                R.drawable.dow_button_shape_on
            } else {
                R.drawable.dow_button_shape_off
            }
            dayButton.setBackgroundResource(drawableResId)
        }
    }

    // Optionally, if you want to ensure you have the latest state from the UI,
    // you can read each button's tag and update selectedDays accordingly.
    private fun getSelectedDaysFromButtons(): BooleanArray {
        val selectedDaysArray = dayButtons.map { (it.tag as? Boolean) ?: false }.toBooleanArray()

        // Log the final selection
        val dayNames = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val selectedDaysList = dayNames.indices.filter { selectedDaysArray[it] }.joinToString(", ") { dayNames[it] }
        Log.d("AlarmEditorActivity", "Final selected days: $selectedDaysList")

        return selectedDaysArray
    }

    private fun saveAlarm() {
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        val alarmNameText = findViewById<TextView>(R.id.alarmName).text.toString()
        val hours = timePicker.hour
        val minutes = timePicker.minute
        val rampBright = findViewById<Switch>(R.id.rampBright).isChecked

        // Get selected days from buttons
        val newSelectedDays = getSelectedDaysFromButtons()
        Log.d("AlarmEditorActivity", "saveAlarm: Selected days: ${newSelectedDays.joinToString(", ")}")

        if (alarmToEdit != null) {
            // Update existing alarm
            alarmToEdit?.apply {
                this.hours = hours
                this.minutes = minutes
                this.alarmName = alarmNameText
                this.rampBright = rampBright
                this.selectedDays = newSelectedDays.copyOf() // Ensure a new copy is created
                if (!this.isEnabled) {
                    this.isEnabled = true
                }
            }
            Log.d("alarmEditorActivity", "Updated alarm: $alarmToEdit")
        } else {
            // Create new alarm
            val newAlarm = Alarm(
                hours,
                minutes,
                alarmNameText,
                true,  // New alarms are enabled by default
                false,
                rampBright,
                newSelectedDays.copyOf(), // Copy the array to ensure it's properly set
                id = System.currentTimeMillis().toInt() // Generate a unique ID
            )
            alarms.add(newAlarm)
            Log.d("alarmEditorActivity", "Added new alarm: $newAlarm")
        }

        val resultIntent = Intent().apply {
            putExtra("alarmsList", ArrayList(alarms))
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }
}