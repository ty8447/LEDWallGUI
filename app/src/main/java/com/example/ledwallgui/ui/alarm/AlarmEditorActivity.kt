package com.example.ledwallgui.ui.alarm

import BrightnessPadDialog
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.ledwallgui.R

class AlarmEditorActivity : AppCompatActivity() {
    private var alarms: MutableList<Alarm> = mutableListOf()
    private var use12HourTimeFormat = false
    private var alarmToEdit: Alarm? = null
    private lateinit var hourPicker: NumberPicker
    private lateinit var minutePicker: NumberPicker
    private lateinit var amPmPicker: NumberPicker
    private lateinit var rampBrightnessSwitch: SwitchCompat
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
        setUpTimePicker()

        rampBrightnessSwitch = findViewById(R.id.ramp_brightness)
    }

    override fun onResume() {
        super.onResume()
        setUpTimePicker()
    }

    private fun initializeViews() {
        dayButtons = listOf(
            R.id.day1, R.id.day2, R.id.day3, R.id.day4,
            R.id.day5, R.id.day6, R.id.day7
        ).map { buttonId ->
            val btn = findViewById<Button>(buttonId)
            btn.tag = false
            btn
        }

        hourPicker = findViewById(R.id.hour_picker)
        minutePicker = findViewById(R.id.minute_picker)
        amPmPicker = findViewById(R.id.ampm_picker)
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

        setUpTimePicker()
    }

    private fun populateAlarmDetails(existingAlarm: Alarm) {
        Log.d("AlarmEditorActivity", "Populating alarm with name: ${existingAlarm.alarmName}")

        val hour = existingAlarm.hours
        val minute = existingAlarm.minutes
        val isPm = hour >= 12

        if (use12HourTimeFormat) {
            hourPicker.value = if (hour % 12 == 0) 12 else hour % 12
            amPmPicker.value = if (isPm) 1 else 0
        } else {
            hourPicker.value = hour
        }

        minutePicker.value = minute

        val alarmNameTextView = findViewById<TextView>(R.id.alarmName)
        if (alarmNameTextView == null) {
            Log.e("AlarmEditorActivity", "editor_name TextView is null!")
        } else {
            alarmNameTextView.text = existingAlarm.alarmName
        }
        selectedDays = existingAlarm.selectedDays.copyOf()
        selectedDays.forEachIndexed { index, isSelected ->
            dayButtons[index].tag = isSelected
        }

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

    private fun setUpTimePicker() {
        hourPicker.minValue = if (use12HourTimeFormat) 1 else 0
        hourPicker.maxValue = if (use12HourTimeFormat) 12 else 23
        hourPicker.wrapSelectorWheel = true

        minutePicker.minValue = 0
        minutePicker.maxValue = 59
        minutePicker.wrapSelectorWheel = true
        minutePicker.setFormatter { value -> String.format("%02d", value) }

        amPmPicker.visibility = if (use12HourTimeFormat) View.VISIBLE else View.GONE
        amPmPicker.minValue = 0
        amPmPicker.maxValue = 1
        amPmPicker.displayedValues = arrayOf("AM", "PM")
        amPmPicker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS

        alarmToEdit?.let {
            val hour = it.hours
            val minute = it.minutes
            val isPm = hour >= 12

            if (use12HourTimeFormat) {
                hourPicker.value = if (hour % 12 == 0) 12 else hour % 12
                amPmPicker.value = if (isPm) 1 else 0
            } else {
                hourPicker.value = hour
            }
            minutePicker.value = minute
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
        val alarmNameText = findViewById<TextView>(R.id.alarmName).text.toString()
        val rampBright = rampBrightnessSwitch.isChecked
        val minute = minutePicker.value

        val hour: Int = if (use12HourTimeFormat) {
            if (amPmPicker.value == 1) {
                if (hourPicker.value == 12) 12 else hourPicker.value + 12
            } else {
                if (hourPicker.value == 12) 0 else hourPicker.value
            }
        } else {
            hourPicker.value
        }

        // Get selected days from buttons
        val newSelectedDays = getSelectedDaysFromButtons()
        Log.d("AlarmEditorActivity", "saveAlarm: Selected days: ${newSelectedDays.joinToString(", ")}")

        if (alarmToEdit != null) {
            // Update existing alarm
            alarmToEdit?.apply {
                hours = hour
                minutes = minute
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
                hours = hour,
                minutes = minute,
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