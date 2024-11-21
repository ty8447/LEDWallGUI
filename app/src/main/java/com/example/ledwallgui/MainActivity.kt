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
import androidx.core.content.ContextCompat
import com.example.ledwallgui.ui.MotionDetectionService
import com.example.ledwallgui.ui.alarm.AlarmActivity
import com.example.ledwallgui.ui.modes.ModeManager
import com.example.ledwallgui.ui.modes.modesActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.example.ledwallgui.ui.settings.ConnectionActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager.bluetoothSocket
import com.example.ledwallgui.ui.settings.settingsActivity


class MainActivity : AppCompatActivity() {
    private var use12HourTimeFormat = true // Initialize with default value ***CHANGE IN SETTINGS***
    private var powerState = false
    private var connectState = false
    private var timeFormatOption: Int = 0 // Initialize with default value
    val mode = ColorMode()
    private lateinit var preferenceUtils: PreferenceUtils

    companion object {
        private const val SETTINGS_REQUEST_CODE = 100 // A constant for the request code
      //  private const val PERMISSIONS_REQUEST_BLUETOOTH = 101
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

        val layout = generateLayout(this, mode.subscribedParameters)
        val container = findViewById<FrameLayout>(R.id.dynamicLayoutContainer)
        container.addView(layout)

        preferenceUtils = PreferenceUtils(this) // Initialize preferenceUtils
        Log.d("INTRO","SWITCHED TO MAIN ACTIVITY!")
        // Check if Bluetooth permissions are granted, and if not, request them
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        val motionDetectionEnabled = preferenceUtils.getMotionDetectionEnabled()
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
            println("Bluetooth is not supported on this device.")
        } else {
            // Device supports Bluetooth
            println("Bluetooth is supported on this device.")
            if (bluetoothAdapter.isEnabled) {
                // Bluetooth is enabled
                println("Bluetooth is currently enabled.")
            } else {
                // Bluetooth is disabled
                println("Bluetooth is currently disabled.")
                // Request to enable Bluetooth using enableBluetoothLauncher as before
            }
        }

        val settingModeDivider = findViewById<View>(R.id.settingModeDivider)
        val dynamicLayoutContainer = findViewById<FrameLayout>(R.id.dynamicLayoutContainer)

        // Check if there are any child views in the container
        if (dynamicLayoutContainer.childCount > 0) {
            settingModeDivider.visibility = View.VISIBLE
        } else {
            settingModeDivider.visibility = View.GONE
        }

        val imageButton1: ImageButton = findViewById(R.id.imageButton1)
        val button2: ImageButton = findViewById(R.id.modesbutton)
        val imageButton2: ImageButton = findViewById(R.id.imageButton2)
        val imageButton3: ImageButton = findViewById(R.id.imageButton3)

        imageButton1.setOnClickListener {
            powerState = !powerState
            Log.d("PowerState", "Power state clicked: $powerState")
            updatePowerButtonImage()
            sendPowerStateToArduino(powerState) // Send the power state to Arduino
            saveSharedPreferences()
        }

        button2.setOnClickListener {
            if (GlobalBluetoothManager.isConnected) {
                // Device is connected, start ModesActivity
                showModesMenu()
            } else {
                // Device is not connected, show Snackbar
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

                // Customize Snackbar appearance
                val snackbarView = snackbar.view
                val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                textView.setTextColor(Color.WHITE) // Set text color to white

                val actionButton = snackbarView.findViewById<Button>(com.google.android.material.R.id.snackbar_action)
                actionButton.setTextColor(ContextCompat.getColor(this, R.color.light_blue)) // Set button text color to light blue

                snackbar.show()
            }
        }
        imageButton2.setOnClickListener {
            showAlarmMenu()
        }
        imageButton3.setOnClickListener {
            showSettingsMenu()
        }

        updatePowerButtonImage()
        updateConnectIcon()

        if (motionDetectionEnabled) {
            val intent = Intent(this, MotionDetectionService::class.java)
            intent.putExtra("source", "MainActivity") // Add extra data
            startService(intent)
            Log.d("Motion Detection", "Detecting Motion")
        } else {
            val intent = Intent(this, MotionDetectionService::class.java)
            intent.putExtra("source", "MainActivity") // Add extra data
            stopService(intent)
            Log.d("Motion Detection", "Not Detecting Motion")
        }

    }
    fun generateLayout(context: Context, parameters: List<Parameter>): View {
        val layout = FlexboxLayout(context).apply {
            flexWrap = FlexWrap.WRAP // Allow wrapping of child views
            justifyContent = JustifyContent.SPACE_AROUND // Align tiles with equal spacing
            setBackgroundColor(Color.TRANSPARENT) // Background for entire layout
        }

        val tileMargin = 16 // Margin around tiles
        parameters.forEach { parameter ->
            when (parameter.type) {
                ParameterType.COLOR -> {
                    // Create a color tile
                    val colorTile = CardView(context).apply {
                        radius = 16f // Rounded corners
                        setCardBackgroundColor(Color.BLACK) // Black background for tiles
                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT,
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
                            flexBasisPercent = 0.4f // Allow tiles to take up to 40% width
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

                    layout.addView(colorTile)
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
                    }

                    val slider = SeekBar(context).apply {
                        max = 100
                        progress = parameter.value as Int // Assuming slider values are integers
                        thumb.setTint(Color.LTGRAY) // Thumb color
                        progressDrawable = ContextCompat.getDrawable(context, R.drawable.custom_slider_drawable)
                    }

                    val valueTextView = TextView(context).apply {
                        text = "Value: ${slider.progress}"
                        textSize = 14f
                        setTextColor(Color.LTGRAY)
                        gravity = Gravity.CENTER
                    }

                    slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            valueTextView.text = "Value: $progress"
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })

                    sliderContainer.addView(slider)
                    sliderContainer.addView(valueTextView)

                    sliderTile.addView(titleTextView)
                    sliderTile.addView(sliderContainer)

                    sliderTile.setOnClickListener {
                        showPopup(context, parameter, ParameterType.SLIDER)
                    }

                    layout.addView(sliderTile)
                }

                ParameterType.SLIDER -> {
                    // Handle SLIDER (if not already done)
                }
            }
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

    private fun sendPowerStateToArduino(state: Boolean) {
        // Determine the message based on the state
        val message = if (state) "ON" else "OFF"

        // Define UUIDs for the service and characteristic
        val serviceUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")  // Your service UUID
        val characteristicUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")  // Your characteristic UUID

        // Get the GATT connection
        val gatt = GlobalBluetoothManager.getGatt()

        if (GlobalBluetoothManager.isConnected && gatt != null) {
            // Find the characteristic for the service
            val characteristic = gatt.getService(serviceUUID)?.getCharacteristic(characteristicUUID)

            if (characteristic != null) {
                // Ensure the characteristic value is cleared before writing the new message
                characteristic.value = ByteArray(0)  // Clear any previous data in the characteristic

                // Convert the message to a byte array
                val byteArray = message.toByteArray(Charsets.UTF_8)

                // Log the byte array content to ensure it's correct before sending
                Log.d("Bluetooth", "Sending message: $message, Byte array: ${byteArray.joinToString(", ")}")

                // Set the characteristic's value and write it
                characteristic.value = byteArray
                val success = gatt.writeCharacteristic(characteristic)

                // Log the result of the write operation
                if (success) {
                    Log.d("Bluetooth", "Sent power state to Arduino: $message")
                } else {
                    Log.w("Bluetooth", "Failed to write characteristic.")
                }
            } else {
                Log.w("Bluetooth", "Characteristic not found.")
            }
        } else {
            Log.w("Bluetooth", "Not connected or GATT is null.")
        }
    }

    @SuppressLint("ResourceType")
    private fun showModesMenu() {
        val intentm = Intent(this, modesActivity::class.java)
        startActivity(intentm)
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
            // Handle landscape orientation
            // For example, adjust UI layout, update views, etc.
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            // Handle portrait orientation
            // For example, adjust UI layout, update views, etc.
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
                    // Print log statement
                    if (is12Hour) {
                        // Device time format is 12-hour
                        ("Device time format: 12-hour")
                    } else {
                        // Device time format is 24-hour
                        println("Device time format: 24-hour")
                    }
                    is12Hour // Set use12HourTimeFormat based on device's time format
                }
                else -> use12HourTimeFormat // Default value
            }

            // Now you can use the use12HourTimeFormat value in MainActivity
        }
    }

    private fun isDeviceTimeIn12HourFormat(): Boolean {
        // Implement the logic to determine if the device's time format is 12-hour
        // For example, you can use the system settings or other methods
        // Return true if it's 12-hour, false if it's 24-hour
        return false // Default value
    }


}