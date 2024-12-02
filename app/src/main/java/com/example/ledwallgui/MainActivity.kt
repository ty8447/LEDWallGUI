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
import android.view.Gravity
import android.view.View
import java.util.UUID
import android.view.Window
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import com.example.ledwallgui.CustomSeekBar
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.app.Dialog
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.compose.ui.semantics.text
import androidx.core.content.ContextCompat
import androidx.core.text.color
import com.example.ledwallgui.ui.MotionDetectionService
import com.example.ledwallgui.ui.alarm.AlarmActivity
import com.example.ledwallgui.ui.modes.ModeManager
import com.example.ledwallgui.ui.modes.ModeManager.currentMode
import com.example.ledwallgui.ui.modes.modesActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.example.ledwallgui.ui.settings.ConnectionActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager.bluetoothSocket
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager.sendPowerStateToArduino
import com.example.ledwallgui.ui.settings.settingsActivity


class MainActivity : AppCompatActivity() {
    private var use12HourTimeFormat = true // Initialize with default value ***CHANGE IN SETTINGS***
    private var powerState = false
    private var connectState = false
    private var timeFormatOption: Int = 0 // Initialize with default value
    val mode = ColorMode()
    private lateinit var preferenceUtils: PreferenceUtils
    private lateinit var speedSeekBar: SeekBar // Declare the variable here
    private lateinit var frequencySeekBar: SeekBar // Declare the variable here
    private lateinit var divSeekBar: SeekBar // Declare the variable here
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

        speedSeekBar = findViewById(R.id.speedSeekBar)
        frequencySeekBar = findViewById(R.id.frequencySeekBar)
        divSeekBar = findViewById(R.id.divSeekBar)

        val layout = generateLayout(this, mode.subscribedParameters)
        val dynamicLayoutContainer = findViewById<FrameLayout>(R.id.dynamicLayoutContainer)

        // Add the dynamically generated layout to the container
        dynamicLayoutContainer.addView(layout)

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

        // Log the visibility of dynamicLayoutContainer before checking child count
        Log.d("MainActivity", "dynamicLayoutContainer visibility before check: ${dynamicLayoutContainer.visibility}")

        val settingModeDivider = findViewById<View>(R.id.settingModeDivider)
        Log.d("MainActivity", "Child count of dynamicLayoutContainer: ${dynamicLayoutContainer.childCount}")
        // Check if there are any child views in the container
        if (dynamicLayoutContainer.childCount > 0) {
            settingModeDivider.visibility = View.VISIBLE
            Log.d("MainActivity", "Setting mode divider visible, child count > 0")
        } else {
            settingModeDivider.visibility = View.GONE
            Log.d("MainActivity", "Setting mode divider hidden, child count <= 0")
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
                saveSharedPreferences()
            } else {
                showConnectionSnackbar()
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

    fun generateLayout(context: Context, parameters: List<Parameter>): View {
        val layout = FlexboxLayout(context).apply {
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.SPACE_AROUND
            setBackgroundColor(Color.TRANSPARENT)
        }

        val tileMargin = 16
        parameters.forEach { parameter ->
            val parameterView = when (parameter.type) {
                ParameterType.COLOR -> {
                    // Create a color tile
                    val colorTile = CardView(context).apply {
                        radius = 16f
                        setCardBackgroundColor(Color.BLACK)
                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT,
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
                            flexBasisPercent = 0.4f
                        }
                    }
                    val tileContent = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(16, 16, 16, 16)
                    }

                    val bulbImageView = ImageView(context).apply {
                        setImageResource(R.drawable.bulb)
                        layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    }

                    val titleTextView = TextView(context).apply {
                        text = parameter.name
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        gravity = Gravity.CENTER
                    }

                    tileContent.addView(bulbImageView)
                    tileContent.addView(titleTextView)
                    colorTile.addView(tileContent)

                    colorTile.setOnClickListener {
                        showPopup(context, parameter, ParameterType.COLOR)
                    }

                    colorTile
                }

                ParameterType.FREQUENCY, ParameterType.SPEED, ParameterType.DIVISION -> {
                    // Create a slider tile
                    val sliderTile = CardView(context).apply {
                        radius = 16f
                        setCardBackgroundColor(Color.BLACK)
                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.MATCH_PARENT,
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
                        }
                    }

                    val titleTextView = TextView(context).apply {
                        text = parameter.name
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        setPadding(16, 16, 16, 8)
                    }

                    val sliderContainer = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(16, 8, 16, 16)
                        addView(titleTextView)

                        // Add SeekBar based on the type of parameter
                        val seekBar = SeekBar(context).apply {
                            max = 100 // Set the max value, this can vary based on parameter
                            setProgress(50) // Set a default value
                        }
                        addView(seekBar)
                    }
                    sliderTile.addView(sliderContainer)

                    sliderTile
                }

                else -> {
                    // If the parameter type is unsupported, return an empty view
                    View(context)
                }
            }
            layout.addView(parameterView)
        }

        return layout
    }


    fun showPopup(context: Context, parameter: Parameter, type: ParameterType) {
        val popup = Dialog(context).apply {
            setContentView(R.layout.parameter_popup) // Ensure this file exists
            window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.8).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.25).toInt()
            )
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // Transparent background for sleek look
            setCancelable(true)
        }

        val titleTextView = popup.findViewById<TextView>(R.id.parameterTitle)
        titleTextView.text = parameter.name

        when (type) {
            ParameterType.COLOR -> {
                val dropdown = popup.findViewById<Spinner>(R.id.colorDropdown).apply {
                    visibility = View.VISIBLE
                    val colorsAdapter = ArrayAdapter(
                        context,
                        android.R.layout.simple_spinner_item,
                        arrayOf("Red", "Blue", "Green", "Yellow", "White") // Example colors
                    )
                    adapter = colorsAdapter
                }
            }

            ParameterType.SLIDER -> {
                val slider = popup.findViewById<SeekBar>(R.id.parameterSlider).apply {
                    visibility = View.VISIBLE
                    progress = parameter.value as Int
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            parameter.value = progress
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })
                }
            }

            ParameterType.FREQUENCY -> {

            }
            ParameterType.SPEED -> {}
            ParameterType.DIVISION -> {}
        }

        popup.show()
    }




    private val colorOptions = arrayOf("Off", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "White")

    class ColorMode {
        val subscribedParameters: List<Parameter> = listOf(
            Parameter("Primary Color", ParameterType.COLOR, Color.RED),
            Parameter("Secondary Color", ParameterType.COLOR, Color.BLUE),
            Parameter("Speed", ParameterType.SPEED, 50)
        )
                    init {
                // Subscribe to parameters
                ParameterList.parameters.filter { it.type == ParameterType.COLOR }.forEach {
                    // Add parameter to mode's control list
                }
            }
    }

    data class Parameter(
        val name: String,
        val type: ParameterType,
        var value: Any
    )

    enum class ParameterType {
        COLOR,
        FREQUENCY,
        SPEED,
        DIVISION,
        SLIDER
    }

    object ParameterList {
        val parameters = mutableListOf<Parameter>(
            Parameter("LED Color", ParameterType.COLOR, Color.RED),
            Parameter("Frequency", ParameterType.FREQUENCY, 100),
            Parameter("Speed", ParameterType.SPEED, 50),
            // ... other parameters
        )
    }

    @SuppressLint("ResourceType")
    private fun showModesMenu() {
        val intent = Intent(this, modesActivity::class.java)
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

        // Find the dynamicLayoutContainer view
        val dynamicLayoutContainer: View = findViewById(R.id.dynamicLayoutContainer)

        // Set dynamicLayoutContainer visibility to VISIBLE by default
        dynamicLayoutContainer.visibility = View.VISIBLE

        // Update connectState based on GlobalBluetoothManager
        connectState = GlobalBluetoothManager.isConnected
        Log.d("MainActivity", "Current mode: $currentMode")

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

            // Update visibility based on mode selected
            val dynamicLayoutContainer: View = findViewById(R.id.dynamicLayoutContainer)
            if (currentMode != null) {
                dynamicLayoutContainer.visibility = View.VISIBLE
            }

        } else if (requestCode == MODES_REQUEST_CODE && resultCode == RESULT_OK) {
            val selectedMode = currentMode
            Log.d("MainActivity", "Selected mode: $selectedMode")

            // Get mode info from GlobalBluetoothManager
            val modeInfo = GlobalBluetoothManager.getModeInfo(selectedMode)

            // Update UI based on modeInfo
            if (modeInfo != null) {
                Log.d("MainActivity", "Mode info found for mode: $selectedMode")
                updateLayoutWithModeInfo(modeInfo)
                GlobalBluetoothManager.requestParameterValues(selectedMode)

                // Ensure dynamicLayoutContainer is made visible when a mode is selected
                val dynamicLayoutContainer: View = findViewById(R.id.dynamicLayoutContainer)
                dynamicLayoutContainer.visibility = View.VISIBLE
            } else {
                Log.w("MainActivity", "Mode info not found for mode: $selectedMode")
            }
        }
    }


    private fun isDeviceTimeIn12HourFormat(): Boolean {
        // Implement the logic to determine if the device's time format is 12-hour
        // For example, you can use the system settings or other methods
        // Return true if it's 12-hour, false if it's 24-hour
        return false // Default value
    }

    private fun getParameterType(parameterName: String): ParameterType {
        // Replace with your logic to determine ParameterType based on parameterName
        return when (parameterName) {
            "Color" -> ParameterType.COLOR
            "Speed" -> ParameterType.SPEED
            // ... other parameter names
            else -> ParameterType.SLIDER // Default
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
        // Remove existing views from the dynamic layout container
        val container = findViewById<FrameLayout>(R.id.dynamicLayoutContainer)

        Log.d("MainActivity", "Updating layout for mode: ${modeInfo.name}, Parameters: ${modeInfo.parameters.joinToString(", ")}")

        container.removeAllViews()

        // Log the parameters that will be used for the layout
        Log.d("MainActivity", "Mode: ${modeInfo.name}, Parameters: ${modeInfo.parameters.joinToString(", ")}")

        // Convert modeInfo.parameters to List<MainActivity.Parameter>
        val parameters = modeInfo.parameters.map { parameterName ->
            // Assuming you have a way to get the ParameterType and value for each parameterName
            val parameterType = getParameterType(parameterName) // Replace with your logic
            val parameterValue = getParameterValue(parameterName) // Replace with your logic
            Parameter(parameterName, parameterType, parameterValue)
        }
        // Create the updated layout
        val updatedLayout = generateLayout(this, parameters)

        // Add the new layout to the container
        container.addView(updatedLayout)

        // Invalidate and refresh the container to ensure UI updates
        container.invalidate()
        container.requestLayout()

        Log.d("MainActivity", "Layout updated successfully for mode: ${modeInfo.name}")
    }

}