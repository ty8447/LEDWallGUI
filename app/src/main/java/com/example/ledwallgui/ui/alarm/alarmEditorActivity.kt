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


class alarmEditorActivity : AppCompatActivity() {
    private var updatedTime: String? = null
    private var alarms: MutableList<Alarm> = mutableListOf()
    private var use12HourTimeFormat = false // Initialize with default value
    private var alarmToEdit: Alarm? = null // Declare it here
    private var selectedDays: BooleanArray = BooleanArray(7) { false }
    private lateinit var dayButtons: List<Button> // Declare the property at the class level



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.alarm_editor)
        hideSystemUI()

        dayButtons = listOf(
            R.id.day1,
            R.id.day2,
            R.id.day3,
            R.id.day4,
            R.id.day5,
            R.id.day6,
            R.id.day7
        ).map { buttonId ->
            findViewById<Button>(buttonId)
        }

        val rootView = findViewById<View>(android.R.id.content)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val rect = Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootView.height
                val keypadHeight = screenHeight - rect.bottom

                // Check if the keyboard is hidden
                if (keypadHeight < screenHeight * 0.15) { // You can adjust this threshold as needed
                    hideSystemUI()
                }
            }
        })

        val isEditMode = intent.getBooleanExtra("isEditMode", false)
        val clickedAlarmIndex = intent.getIntExtra("clickedAlarmIndex", -1)
        val editorNameText = intent.getStringExtra("editorNameText")
        val alarmsList = intent.getParcelableArrayListExtra<Alarm>("alarmsList")
        var updatelist = intent.getBooleanExtra("updatelist", false)
        val alarmName = findViewById<TextView>(R.id.alarmName)
        if (alarmsList != null && alarmsList.isNotEmpty()) {
            alarms.addAll(alarmsList) // Populate the alarms list
        } else {
            // Handle the case where the alarms list is empty
        }
        Log.d("alarmEditorActivity", "isEditMode: $isEditMode")
        Log.d("alarmEditorActivity", "clickedAlarmIndex: $clickedAlarmIndex")
        Log.d("alarmEditorActivity", "alarmsList: $alarmsList")

        if (clickedAlarmIndex != -1 && alarmsList != null && alarmsList.isNotEmpty()) {
            val existingAlarm = alarms[clickedAlarmIndex]
            // Use existingAlarm.time to pre-fill the time picker
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, existingAlarm.hours)
            calendar.set(Calendar.MINUTE, existingAlarm.minutes)
            val timePicker = findViewById<TimePicker>(R.id.timePicker) // Add this line
            timePicker.hour = calendar.get(Calendar.HOUR_OF_DAY)
            timePicker.minute = calendar.get(Calendar.MINUTE)
            alarmToEdit = existingAlarm // Initialize alarmToEdit here
            alarmName.text = alarmToEdit?.alarmName ?: ""
            selectedDays = alarmToEdit?.selectedDays!!
            val selectedDaysLog = selectedDays.joinToString(", ") { it.toString() }
            Log.d("alarmEditorActivity", "selectedDays1: $selectedDaysLog")
            dayButtons.forEach {
                updateDayButtonVisuals() // Update button appearance
            }

        }
        use12HourTimeFormat = intent.getBooleanExtra("use12HourTimeFormat", false)
        val alarmToEdit = if (isEditMode && clickedAlarmIndex != -1) alarms[clickedAlarmIndex] else null
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        if (use12HourTimeFormat) {
            timePicker.setIs24HourView(false)
        } else {
            timePicker.setIs24HourView(true)
        }
        val editorNameTextView = findViewById<TextView>(R.id.editor_name)
        editorNameTextView.text = editorNameText
        val rampBrightSwitch = findViewById<Switch>(R.id.rampBright)
        val brightnessSeekBar = findViewById<SeekBar>(R.id.brightness)
        val brightVal = findViewById<TextView>(R.id.brightVal)
        val cancelButton = findViewById<Button>(R.id.cancel)
        val saveButton = findViewById<Button>(R.id.save)
        val grabCurrentButton = findViewById<Button>(R.id.grabCurrent)
        val alarmTime = intent.getStringExtra("alarmTime")
        val clickedAlarmTime = intent.getStringExtra("clickedAlarmTime")
        if (clickedAlarmTime != null) {
            val parsedTime = SimpleDateFormat("h:mm a", Locale.US).parse(clickedAlarmTime)
            val calendar = Calendar.getInstance()
            calendar.time = parsedTime
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            timePicker.hour = hour
            timePicker.minute = minute
            selectedDays = BooleanArray(7) {false}
            val selectedDaysLog = selectedDays.joinToString(", ") { it.toString() }
            Log.d("alarmEditorActivity", "selectedDays4: $selectedDaysLog")
        }

        if (alarmTime != null) {
            val parsedTime = SimpleDateFormat("h:mm a", Locale.US).parse(alarmTime)
            val calendar = Calendar.getInstance()
            calendar.time = parsedTime
            selectedDays = alarmToEdit?.selectedDays!!
            timePicker.hour = alarmToEdit?.hours ?: 0
            timePicker.minute = alarmToEdit?.minutes ?: 0
            alarmName.text = alarmToEdit?.alarmName ?: ""
            val selectedDaysLog = selectedDays.joinToString(", ") { it.toString() }
            Log.d("alarmEditorActivity", "selectedDays5: $selectedDaysLog")
        }

        dayButtons.forEachIndexed { index, dayButton ->
            dayButton.setOnClickListener {
                selectedDays[index] = !selectedDays[index]
                updateDayButtonVisuals() // Update button appearance
            }
        }

        if (clickedAlarmIndex != -1) {
            val existingAlarm = alarms[clickedAlarmIndex]
            // Use existingAlarm.time to pre-fill the time picker
            // ...
            // When save button is clicked, update the existingAlarm and pass it back
            saveButton.setOnClickListener {
                // Update existingAlarm with new values
                // ...
                if (isEditMode && clickedAlarmIndex != -1) {
                    if (alarmToEdit != null) {
                        alarms[clickedAlarmIndex] = alarmToEdit
                    }  // Update the edited alarm in the alarms list
                }
                val resultIntent = Intent()
                resultIntent.putExtra("updatedAlarm", existingAlarm as Parcelable)
                resultIntent.putExtra("alarmIndex", clickedAlarmIndex)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        } else {
            // Handle creating a new alarm
            // ...
        }

        brightVal.setOnClickListener {
            val initialValue = brightVal.text.toString().replace("%", "").toIntOrNull() ?: 0
            val numberPadDialog = BrightnessPadDialog(this, initialValue) { value ->
                brightVal.text = "$value%"
                brightnessSeekBar.progress = value // Update SeekBar progress
            }
            numberPadDialog.show()
        }

        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val progressWithPercentage = "$progress%"
                brightVal.text = progressWithPercentage
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Not needed for this implementation
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Not needed for this implementation
            }
        })

        // Set click listeners
        cancelButton.setOnClickListener {
            // Handle cancel button click, go back to previous activity
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        saveButton.setOnClickListener {
            // Extract user inputs
            val hour = timePicker.hour
            val minute = timePicker.minute
            val showCheckbox: Boolean = false
            val days = getSelectedDays(dayButtons)
            val alarmNameText = alarmName.text.toString()
            val brightness = brightnessSeekBar.progress
            val rampBright = rampBrightSwitch.isChecked
            val updatedTimeString = updatedTime
            val isEnabled = true
            val isSelected = false
            val newAlarm = Alarm(
                hour,
                minute,
                alarmName.text.toString(),
                isEnabled,
                showCheckbox,
                isSelected,
                rampBright,
                getSelectedDays(dayButtons),
                selectedDays
            )
            updatedTime = SimpleDateFormat("h:mm a", Locale.US).format(getCalendar(hour, minute).time)
            alarmToEdit?.hours = hour
            alarmToEdit?.minutes = minute
            alarmToEdit?.alarmName = alarmName.text.toString()
            alarmToEdit?.selectedDays = selectedDays
            Log.d("Debug", "updatedTime: $updatedTime")
            Log.d("Debug", "alarmToEdit: $alarmToEdit")
            Log.d("Debug", "alarmIndex: $clickedAlarmIndex")
                if (isEditMode && clickedAlarmIndex != -1) {
                    if (alarmToEdit != null) {
                        alarms[clickedAlarmIndex] = alarmToEdit

                    }
                    val resultIntent = Intent()
                    resultIntent.putExtra("updatedAlarmIndex", clickedAlarmIndex)
                    resultIntent.putExtra("updatedAlarm", alarmToEdit as Parcelable)
                    setResult(Activity.RESULT_OK, resultIntent)
                } else {
                    // Creating a new alarm
                    alarms.add(newAlarm)
                    val resultIntent = Intent()
                    resultIntent.putExtra("updatelist", true)
                    resultIntent.putExtra("alarmsList", ArrayList(alarms))
                    resultIntent.putExtra("newAlarm", newAlarm as Parcelable)
                    setResult(Activity.RESULT_OK, resultIntent)
                }
            val selectedDaysLog = selectedDays.joinToString(", ") { it.toString() }
            Log.d("alarmEditorActivity", "selectedDays2: $selectedDaysLog")
            Log.d("Debug", "Alarms list after save: $alarms")
                Log.d("Debug", "Save button pressed")
                // Finish the activity
                finish()
        }


        grabCurrentButton.setOnClickListener {
            // Implement grabbing current settings logic
        }

        // Calculate scaling factors and apply them to TimePicker
        val screenWidth = resources.displayMetrics.widthPixels
        val referenceWidth = 2000 // Adjust the reference width as needed
        val inverseScaleX = referenceWidth.toFloat() / screenWidth
        timePicker.scaleX = inverseScaleX
        timePicker.scaleY =
            inverseScaleX // You can use the same scale factor for Y if maintaining the aspect ratio
    }

    private fun toggleDayButtonState(dayIndex: Int) {
        selectedDays[dayIndex] = !selectedDays[dayIndex]
        updateDayButtonVisuals() // Update the button appearance based on the array
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


    private fun getCalendar(hour: Int, minute: Int): Calendar {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        return calendar
    }

    private fun getSelectedDays(dayButtons: List<Button>): BooleanArray {
        val days = BooleanArray(7) { false }
        dayButtons.forEachIndexed { index, dayButton ->
            days[index] =
                dayButton.background.constantState == resources.getDrawable(R.drawable.dow_button_shape_on).constantState
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
