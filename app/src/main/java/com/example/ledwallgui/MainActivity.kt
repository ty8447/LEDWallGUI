package com.example.ledwallgui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.app.Dialog
import android.content.ContentValues.TAG
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.example.ledwallgui.ui.MotionDetectionService
import com.example.ledwallgui.ui.alarm.AlarmActivity
import com.example.ledwallgui.ui.modes.ModeManager
import com.example.ledwallgui.ui.modes.ModeManager.currentMode
import com.example.ledwallgui.ui.modes.ModesActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.example.ledwallgui.ui.settings.ConnectionActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager.sendPowerStateToArduino
import com.example.ledwallgui.ui.settings.settingsActivity


class MainActivity : AppCompatActivity() {
    private var use12HourTimeFormat = true // Initialize with default value ***CHANGE IN SETTINGS***
    private var powerState = false
    private var connectState = false
    private var timeFormatOption: Int = 0 // Initialize with default value
    val mode = ColorMode()
    private lateinit var preferenceUtils: PreferenceUtils
    private lateinit var dynamicLayoutContainer: FrameLayout // Declare without initialization
    private lateinit var settingModeDivider: View // Declare without initialization
    private lateinit var flexboxLayout: FlexboxLayout

    companion object {
        private const val SETTINGS_REQUEST_CODE = 100
        private const val MODES_REQUEST_CODE = 101 // Add this line
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        setContentView(R.layout.activity_main)
        hideSystemUI()

        val sliderBackground = findViewById<View>(R.id.sliderBackground)
        val sliderThumb = findViewById<View>(R.id.sliderThumb)
        dynamicLayoutContainer = findViewById(R.id.dynamicLayoutContainer)

        settingModeDivider = findViewById(R.id.settingModeDivider)
        if (settingModeDivider == null) {
            Log.e("MainActivity", "Failed to find settingModeDivider. Check layout.")
        }

        flexboxLayout = FlexboxLayout(this).apply {
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.SPACE_AROUND
            setBackgroundColor(Color.TRANSPARENT)
            id = View.generateViewId()
        }

        val layout = generateLayout(this, mode.subscribedParameters, flexboxLayout)

        dynamicLayoutContainer.removeAllViews()
        dynamicLayoutContainer.addView(layout)

        for (i in 0 until dynamicLayoutContainer.childCount) {
            val child = dynamicLayoutContainer.getChildAt(i)
            if (child is CardView && (child.tag == "Color" || child.tag == "SColor" || child.tag == "Speed" || child.tag == "Frequency" || child.tag == "Division")) {
                child.visibility = View.GONE
            }
        }

        // Initialize preferenceUtils
        preferenceUtils = PreferenceUtils(this)
        Log.d("INTRO", "SWITCHED TO MAIN ACTIVITY!")

        // Check Bluetooth permissions and connectivity
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        val motionDetectionEnabled = preferenceUtils.getMotionDetectionEnabled()
        if (bluetoothAdapter == null) {
            println("Bluetooth is not supported on this device.")
        } else {
            println("Bluetooth is supported on this device.")
            if (bluetoothAdapter.isEnabled) {
                println("Bluetooth is currently enabled.")
            } else {
                println("Bluetooth is currently disabled.")
            }
        }

        // Handle button clicks
        val imageButton1: ImageButton = findViewById(R.id.imageButton1)
        val button2: ImageButton = findViewById(R.id.modesbutton)
        val imageButton2: ImageButton = findViewById(R.id.imageButton2)
        val imageButton3: ImageButton = findViewById(R.id.imageButton3)

        // Handle PowerState button click
        imageButton1.setOnClickListener {
            if (GlobalBluetoothManager.isConnected) {
                powerState = !powerState
                Log.d("PowerState", "Power state clicked: $powerState")
                updatePowerButtonImage()
                sendPowerStateToArduino(powerState)
//                saveSharedPreferences()
            } else {
                showConnectionSnackbar()
            }
        }

        sliderBackground.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val y = event.y
                    updateThumbPosition(sliderBackground, sliderThumb, y)

                    // Calculate brightness percentage
                    val sliderHeight = sliderBackground.height
                    val adjustedY = y.coerceIn(0f, sliderHeight.toFloat())
                    val percentage = ((sliderHeight - adjustedY) / sliderHeight * 100).toInt()

                    // Write brightness to BLE characteristic
                    GlobalBluetoothManager.setBrightness(percentage)

                    true
                }
                else -> false
            }
        }

        // Handle Mode button click
        button2.setOnClickListener {
            if (GlobalBluetoothManager.isConnected) {
                showModesMenu()
            } else {
                showConnectionSnackbar()
            }
        }

        // Handle other button clicks
        imageButton2.setOnClickListener { showAlarmMenu() }
        imageButton3.setOnClickListener { showSettingsMenu() }

        updatePowerButtonImage()
        updateConnectIcon()

        // Handle motion detection service
        if (motionDetectionEnabled) {
            val intent = Intent(this, MotionDetectionService::class.java)
            intent.putExtra("source", "MainActivity")
            startService(intent)
            Log.d("Motion Detection", "Detecting Motion")
        } else {
            val intent = Intent(this, MotionDetectionService::class.java)
            intent.putExtra("source", "MainActivity")
            stopService(intent)
            Log.d("Motion Detection", "Not Detecting Motion")
        }
    }

    private fun updateThumbPosition(sliderBackground: View, sliderThumb: View, touchY: Float) {
        val sliderHeight = sliderBackground.height
        val minHeight = 20 // Minimum height for the thumb in pixels
        val adjustedY = touchY.coerceIn(0f, sliderHeight.toFloat()) // Keep touch within bounds
        val percentage = ((sliderHeight - adjustedY) / sliderHeight * 100).toInt()

        // Calculate the new thumb height
        val newHeight = (sliderHeight * (percentage / 100f)).toInt().coerceAtLeast(minHeight)

        // Update the thumb's height while maintaining its width
        val thumbParams = sliderThumb.layoutParams
        thumbParams.height = newHeight
        sliderThumb.layoutParams = thumbParams

        sliderThumb.requestLayout()

        // Log or handle the percentage
        Log.d("Slider", "Current Percentage: $percentage")
    }



    fun showConnectionSnackbar() {
        val snackbar = Snackbar.make(
            findViewById(android.R.id.content),
            "No Device is Connected",
            Snackbar.LENGTH_LONG
        )
        snackbar.setAction("Connect") {
            // Navigate to ConnectionActivity
            val intent = Intent(this, ConnectionActivity::class.java)
            startActivity(intent)
        }

        val snackbarView = snackbar.view
        val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(Color.WHITE)

        val actionButton = snackbarView.findViewById<Button>(com.google.android.material.R.id.snackbar_action)
        actionButton.setTextColor(ContextCompat.getColor(this, R.color.light_blue))

        snackbar.show()
    }

    fun generateLayout(context: Context, parameters: List<Parameter>, flexboxLayout: FlexboxLayout): View {

        parameters.forEach { parameter ->
            Log.d("MainActivity", "Creating view for parameter: ${parameter.name} of type ${parameter.type}")
            val parameterView = when (parameter.type) {
                "PCOLOR", "SCOLOR" -> {
                    val colorTile = CardView(context).apply {
                        tag = parameter.name // Assign tag for visibility control
                        radius = 16f
                        setCardBackgroundColor(Color.BLACK)
                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT,
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(16, 16, 16, 16)
                            flexBasisPercent = 0.4f
                        }

                        // Add click listener for color selection
                        setOnClickListener {
                            showPopup(context, parameter, parameter.type) // parameter.type is now String
                        }
                    }

                    val colorDropdown = Spinner(context).apply {
                        // Set adapter for color options
                        adapter = ArrayAdapter(
                            context,
                            android.R.layout.simple_spinner_dropdown_item,
                            arrayOf("Red", "Green", "Blue") // Example color options
                        )
                        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                val selectedItem = parent?.getItemAtPosition(position) as? String
                                Log.d("Dropdown", "Selected item: $selectedItem")
                                if (selectedItem != null) {
                                    GlobalBluetoothManager.setLedParameter(ModeManager.currentMode, parameter.name, selectedItem)
                                }
                            }

                            override fun onNothingSelected(parent: AdapterView<*>?) {
                                // Do nothing
                            }
                        }
                    }
                    val initialColorIndex = (colorDropdown.adapter as ArrayAdapter<String>).getPosition(parameter.value.toString())
                    colorDropdown.setSelection(initialColorIndex)
                    // Set visibility to VISIBLE
                    colorTile.visibility = View.VISIBLE
                    Log.d("MainActivity", "Setting visibility for ${parameter.name}: ${colorTile.visibility}")
                    colorTile.addView(colorDropdown)
                    colorTile
                }

                "FREQUENCY", "SPEED", "DIVISION" -> {
                    val sliderTile = CardView(context).apply {
                        tag = parameter.name // Assign tag for visibility control
                        radius = 16f
                        setCardBackgroundColor(Color.BLACK)
                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.MATCH_PARENT,
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(16, 16, 16, 16)
                        }
                    }

                    val seekBar = SeekBar(context).apply {
                        max = 100
                        progress = parameter.value as Int
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                parameter.value = progress
                                Log.d("MainActivity", "${parameter.name} updated to $progress")
                                GlobalBluetoothManager.setLedParameter(ModeManager.currentMode, parameter.name, progress.toString())
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                        })
                    }

                    sliderTile.addView(seekBar)
                    seekBar.progress = (parameter.value as? String)?.toIntOrNull() ?: 0
                    // Set visibility to VISIBLE
                    sliderTile.visibility = View.VISIBLE
                    Log.d("MainActivity", "Setting visibility for ${parameter.name}: ${sliderTile.visibility}")

                    sliderTile
                }

                else -> View(context) // Default case, if no match
            }

            // Add view to layout
            flexboxLayout.addView(parameterView)

            // Optionally log the visibility status of the added view
            Log.d("MainActivity", "Added view for ${parameter.name} with visibility: ${parameterView.visibility}")
        }

// Check the number of visible children in FlexboxLayout
        val visibleChildrenCount = flexboxLayout.children.count { it.visibility == View.VISIBLE }
        if (::settingModeDivider.isInitialized) {
            if (visibleChildrenCount > 0) {
                settingModeDivider.visibility = View.VISIBLE
                Log.d("MainActivity", "Setting mode divider visible, subscribed parameters exist")
            } else {
                settingModeDivider.visibility = View.GONE
                Log.d("MainActivity", "Setting mode divider hidden, no subscribed parameters")
            }
        } else {
            Log.e("generateLayout", "settingModeDivider not initialized.")
        }

        return flexboxLayout
    }

    fun showPopup(context: Context, parameter: Parameter, type: String) {
        val popup = Dialog(context).apply {
            setContentView(R.layout.parameter_popup) // Ensure this file exists
            window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.8).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.25).toInt()
            )
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // Transparent background for sleek look
            setCancelable(true)
        }

        Log.d("MainActivity", "Showing popup for parameter: ${parameter.name} of type: ${type}")
        val titleTextView = popup.findViewById<TextView>(R.id.parameterTitle)
        titleTextView.text = parameter.name

        when (type) {
            "PCOLOR" -> {
            }
            "SCOLOR" -> {
            }
            "FREQUENCY" -> {
            }
            "SPEED" -> {}
            "DIVISION" -> {}
        }

        popup.show()
    }

    class ColorMode {
        val subscribedParameters: List<Parameter> = listOf(
        )
    }

    data class Parameter(
        val name: String,
        val type: String,
        var value: Any
    )

    enum class ParameterType {
        PCOLOR,
        SCOLOR,
        FREQUENCY,
        SPEED,
        DIVISION
    }

    @SuppressLint("ResourceType")
    private fun showModesMenu() {
        val intent = Intent(this, ModesActivity::class.java)
        startActivityForResult(intent, MODES_REQUEST_CODE)
    }

    override fun onStop() {
        super.onStop()
        // Save variables to SharedPreferences when the activity stops
        saveSharedPreferences()
    }
    private fun saveSharedPreferences() {
        Log.d("PowerState", "Saving power state: $powerState")
        preferenceUtils.savePowerState(powerState)
        preferenceUtils.saveConnectState(connectState)
        preferenceUtils.saveTimeFormatOption(timeFormatOption)
        preferenceUtils.saveUse12HourTimeFormat(use12HourTimeFormat)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Check if the orientation has changed
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.d("MainActivity", "Orientation changed to LANDSCAPE")
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.d("MainActivity", "Orientation changed to PORTRAIT")
        }
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

    private fun updatePowerButtonImage() {
        val imageButton1: ImageButton = findViewById(R.id.imageButton1)
        if (powerState) {
            imageButton1.setImageResource(R.drawable.power_button_on_selector)
        } else {
            imageButton1.setImageResource(R.drawable.power_button_off_selector)
        }
    }

    private fun updateConnectIcon() {
        val connectButton: ImageView = findViewById(R.id.imageView)
        if (GlobalBluetoothManager.isConnected) { // Use GlobalBluetoothManager.isConnected
            connectButton.setImageResource(R.drawable.stat_con_button)
        } else {
            connectButton.setImageResource(R.drawable.stat_dis_button)
        }
    }

    override fun onResume() {
        super.onResume()

        // Update connectState based on GlobalBluetoothManager
        connectState = GlobalBluetoothManager.isConnected
        Log.d("MainActivity", "Current mode: ${ModeManager.currentMode}")
//         Update UI based on modeInfo
            val selectedMode = currentMode
            val modeInfo = GlobalBluetoothManager.getModeInfo(selectedMode)
            if (modeInfo != null) {
                Log.d("MainActivity", "Mode info found for mode: $selectedMode")
                GlobalBluetoothManager.requestParameterValues(selectedMode)
                updateLayoutWithModeInfo(modeInfo)
            } else {
                Log.w("MainActivity", "Mode info not found for mode: $selectedMode")
            }
        // Update UI based on connectState
        updateConnectIcon()
    }

    @SuppressLint("ResourceType")
    private fun showAlarmMenu() {
        val intenta = Intent(this, AlarmActivity::class.java)
        intenta.putExtra("use12HourTimeFormat", use12HourTimeFormat)
        startActivity(intenta)
    }

    @SuppressLint("ResourceType")
    private fun showSettingsMenu() {
        val intents = Intent(this, settingsActivity::class.java)
        intents.putExtra("use12HourTimeFormat", use12HourTimeFormat)
        intents.putExtra("timeFormatOption", timeFormatOption)
        startActivityForResult(intents, SETTINGS_REQUEST_CODE) // Start settings activity for result
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS_REQUEST_CODE && resultCode == RESULT_OK) {
            // Get the selected time format option from settings activity
            timeFormatOption = data?.getIntExtra("timeFormatOption", 0) ?: 0

            // Update use12HourTimeFormat based on the timeFormatOption
            use12HourTimeFormat = when (timeFormatOption) {
                1 -> true
                2 -> false
                0 -> {
                    // Logic to determine the device's time format
                    val is12Hour = isDeviceTimeIn12HourFormat()
                    if (is12Hour) {
                        ("Device time format: 12-hour")
                    } else {
                        println("Device time format: 24-hour")
                    }
                    is12Hour
                }
                else -> use12HourTimeFormat // Default value
            }

        } else if (requestCode == MODES_REQUEST_CODE && resultCode == RESULT_OK) {
            val selectedMode = currentMode
            Log.d("MainActivity", "Selected mode: $selectedMode")
        }
    }


    private fun isDeviceTimeIn12HourFormat(): Boolean {
        // Implement the logic to determine if the device's time format is 12-hour
        // For example, you can use the system settings or other methods
        // Return true if it's 12-hour, false if it's 24-hour
        return false // Default value
    }

    private fun getParameterType(parameterName: String): String {
        // Replace with your logic to determine parameter type based on parameterName
        return when (parameterName) {
            "Color" -> "PCOLOR"
            "SColor" -> "SCOLOR"
            "Speed" -> "SPEED"
            "Frequency" -> "FREQUENCY"
            "Division" -> "DIVISION"
            else -> "SLIDER" // or any other default parameter type string
        }
    }

    private fun getParameterValue(parameterName: String): Any {
        // Replace with your logic to determine parameter value based on parameterName
        return when (parameterName) {
            "Color" -> Color.RED // Default color
            "Speed" -> 50 // Default speed
            // ... other parameter names
            else -> 0 // Default value
        }
    }

    private fun updateLayoutWithModeInfo(modeInfo: GlobalBluetoothManager.ModeInfo) {
        val container = findViewById<FrameLayout>(R.id.dynamicLayoutContainer)

        Log.d("MainActivity", "Updating layout for mode: ${modeInfo.name}, Parameters: ${modeInfo.parameters.joinToString(", ")}")

        flexboxLayout.removeAllViews()

        val parameters = modeInfo.parameters.map { parameterName ->
            val parameterType = getParameterType(parameterName)
            val parameterValue = getParameterValue(parameterName)
            Parameter(parameterName, parameterType, parameterValue)
        }

        val dynamicLayout = generateLayout(this, parameters, flexboxLayout)

        // Check if dynamicLayout already has a parent
        val parent = dynamicLayout.parent as? ViewGroup
        if (parent != null) {
            parent.removeView(dynamicLayout) // Remove it from the previous parent
        }

        // Now it's safe to add it to the container
        container.addView(dynamicLayout)

        if (dynamicLayout is ViewGroup) { // Check if the layout is a ViewGroup
            for (i in 0 until dynamicLayout.childCount) {
                val child = dynamicLayout.getChildAt(i)
                Log.d("MainActivity", "Child $i - Tag: ${child.tag}, Visibility: ${child.visibility}")
                val tag = child.tag
                if (tag is String && modeInfo.parameters.contains(tag)) {
                    child.visibility = View.VISIBLE
                    Log.d("MainActivity", "Setting visibility for parameter ${tag}: ${child.visibility}")
                }
            }
        }
        Log.d("MainActivity", "Parameters passed to generateLayout: ${parameters.joinToString(", ") { it.name }}")

        container.invalidate()
        container.requestLayout()

        for (i in modeInfo.parameters.indices) {
            Log.d(TAG, "Setting parameter ${modeInfo.parameters[i]} to UI component ${i + 1}")
        }

        Log.d("MainActivity", "Layout updated successfully for mode: ${modeInfo.name}")
    }
}