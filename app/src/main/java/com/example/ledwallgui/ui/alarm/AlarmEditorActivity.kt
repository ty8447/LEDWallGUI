package com.example.ledwallgui.ui.alarm

import BrightnessPadDialog
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.TimePicker
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.ledwallgui.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale


class AlarmEditorActivity : AppCompatActivity() {
    private var updatedTime: String? = null
    private var alarms: MutableList<Alarm> = mutableListOf()
    private var use12HourTimeFormat = false
    private var alarmToEdit: Alarm? = null
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

    private fun initializeViews() {
        dayButtons = listOf(
            R.id.day1, R.id.day2, R.id.day3, R.id.day4,
            R.id.day5, R.id.day6, R.id.day7
        ).map { buttonId -> findViewById<Button>(buttonId) }
    }

    private fun handleIntentExtras() {
        val isEditMode = intent.getBooleanExtra("isEditMode", false)
        Log.d("AlarmEditorActivity", "handleIntentExtras: isEditMode = $isEditMode")

        val clickedAlarmIndex = intent.getIntExtra("clickedAlarmIndex", -1)
        Log.d("AlarmEditorActivity", "handleIntentExtras: clickedAlarmIndex = $clickedAlarmIndex")

        val alarmsList = intent.getParcelableArrayListExtra<Alarm>("alarmsList")
        if (alarmsList != null && alarmsList.isNotEmpty()) {
            alarms.addAll(alarmsList)
            Log.d("AlarmEditorActivity", "handleIntentExtras: Alarms list size = ${alarmsList.size}")
        }

        if (clickedAlarmIndex != -1 && alarms.isNotEmpty()) {
            alarmToEdit = alarms[clickedAlarmIndex]
            populateAlarmDetails(clickedAlarmIndex)
        } else {
            Log.d("AlarmEditorActivity", "handleIntentExtras: No alarm to edit or invalid index")
        }

        use12HourTimeFormat = intent.getBooleanExtra("use12HourTimeFormat", false)
        Log.d("AlarmEditorActivity", "handleIntentExtras: use12HourTimeFormat = $use12HourTimeFormat")
    }

    private fun populateAlarmDetails(clickedAlarmIndex: Int) {
        val existingAlarm = alarms[clickedAlarmIndex]
        alarmToEdit = existingAlarm

        // Log to verify the alarm details
        Log.d("AlarmEditorActivity", "populateAlarmDetails: Populating alarm with name: ${existingAlarm.alarmName}")

        // Set the time picker to the existing alarm's time
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        Log.d("AlarmEditorActivity", "populateAlarmDetails: Setting time - Hour: ${existingAlarm.hours}, Minute: ${existingAlarm.minutes}")
        timePicker.hour = existingAlarm.hours
        timePicker.minute = existingAlarm.minutes

        // Set the alarm name
        val alarmNameTextView = findViewById<TextView>(R.id.alarmName)
        alarmNameTextView.text = existingAlarm.alarmName
        Log.d("AlarmEditorActivity", "populateAlarmDetails: Setting alarm name: ${existingAlarm.alarmName}")

        // Set the selected days
        selectedDays = existingAlarm.selectedDays
        Log.d("AlarmEditorActivity", "populateAlarmDetails: Selected days: ${selectedDays.joinToString(", ")}")

        // Update the day buttons based on the selected days
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
        if (use12HourTimeFormat) {
            // Set TimePicker to 12-hour format
            timePicker.setIs24HourView(false)
            // If the alarm has an existing time, adjust it for 12-hour format
            alarmToEdit?.let {
                val hourIn12Hr = if (it.hours % 12 == 0) 12 else it.hours % 12
                timePicker.hour = hourIn12Hr
                timePicker.minute = it.minutes
            }
        } else {
            // Set TimePicker to 24-hour format
            timePicker.setIs24HourView(true)
            // If the alarm has an existing time, set it for 24-hour format
            alarmToEdit?.let {
                timePicker.hour = it.hours
                timePicker.minute = it.minutes
            }
        }
    }

    private fun toggleDayButtonState(dayIndex: Int) {
        selectedDays[dayIndex] = !selectedDays[dayIndex]
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

    private fun saveAlarm() {
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        val alarmNameText = findViewById<TextView>(R.id.alarmName).text.toString()

        val newAlarm = Alarm(
            timePicker.hour,
            timePicker.minute,
            alarmNameText,
            true, // isEnabled
            false, // showCheckbox
            false, // isSelected
            findViewById<Switch>(R.id.rampBright).isChecked,
            getSelectedDays(dayButtons), // This method already gets the selected days correctly
            selectedDays // Ensure the selectedDays array is used here
        )

        if (alarmToEdit != null) {
            alarms[alarms.indexOf(alarmToEdit)] = newAlarm
            Log.d("alarmEditorActivity", "Updated alarm: $newAlarm")
        } else {
            alarms.add(newAlarm)
            Log.d("alarmEditorActivity", "Added new alarm: $newAlarm")
        }

        val resultIntent = Intent()
        resultIntent.putExtra("alarmsList", ArrayList(alarms))
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun getSelectedDays(dayButtons: List<Button>): BooleanArray {
        val days = BooleanArray(7) { false }
        dayButtons.forEachIndexed { index, dayButton ->
            days[index] = dayButton.background.constantState == resources.getDrawable(R.drawable.dow_button_shape_on).constantState
        }
        return days
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
