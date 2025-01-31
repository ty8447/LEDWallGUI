package com.example.ledwallgui

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
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
import com.example.ledwallgui.ui.modes.ModesActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.example.ledwallgui.ui.settings.ConnectionActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager.sendPowerStateToArduino
import com.example.ledwallgui.ui.settings.settingsActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResult
import androidx.core.app.ActivityCompat
import com.example.ledwallgui.ui.settings.ColorWheel
import kotlin.ranges.coerceAtLeast
import kotlin.ranges.coerceIn


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
    private lateinit var textView: TextView
    private val handler = Handler(Looper.getMainLooper()) // Define the handler
    private lateinit var modesActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var settingsActivityResultLauncher: ActivityResultLauncher<Intent>
    private val connectionCheckHandler = Handler(Looper.getMainLooper())
    private val connectionCheckInterval = 5000L // Check every 5 seconds
    private var isDragging = false
    private var lastY = 0f
    private var initialInteractTime = 0L
    private var lastMoveTime = 0L
    private var brightnessSensitivity = 1.0f
    private var currentBrightness = 0
    private var previousBrightness = -1
    private var minBrightnessChange = 1
    private var maxBrightnessChange = 10
    private var sliderHeight = 0

    companion object {
        private const val BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE = 102
        private const val SETTINGS_REQUEST_CODE = 100
        private const val MODES_REQUEST_CODE = 101 // Add this line
    }
    private lateinit var serviceIntent: Intent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        setContentView(R.layout.activity_main)

        if (!checkBluetoothPermission()) {
            requestBluetoothPermission()
        }

        modesActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) {
                // Handle the result here
                Toast.makeText(this, "Mode selected", Toast.LENGTH_SHORT).show()
            }
        }

        settingsActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) {
                // Handle the result here
                val data = result.data

                // Retrieve the stored boolean from shared preferences for 12-hour time format
                val prefs = getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
                val use12HourTimeFormat = prefs.getBoolean("is_12_hour_time", true) // Default to true if not set

                // Retrieve the time format option (defaults to 0)
                val timeFormatOption = data?.getIntExtra("timeFormatOption", 0) ?: 0

                // Log the updated settings for debugging
                Log.d("Settings", "use12HourTimeFormat: $use12HourTimeFormat, timeFormatOption: $timeFormatOption")

                // Optionally update UI or preferences based on the returned data
                // Save the updated boolean and time format option to SharedPreferences
                val editor = prefs.edit()
                editor.putBoolean("is_12_hour_time", use12HourTimeFormat)
                editor.putInt("timeFormatOption", timeFormatOption)
                editor.apply()
            }
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        hideSystemUI()
        val sliderBackground = findViewById<View>(R.id.sliderBackground)
        val sliderThumb = findViewById<View>(R.id.sliderThumb)
        dynamicLayoutContainer = findViewById(R.id.dynamicLayoutContainer)

        settingModeDivider = findViewById(R.id.settingModeDivider)
        textView = findViewById(R.id.textView5)

        flexboxLayout = FlexboxLayout(this).apply {
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.SPACE_AROUND
            setBackgroundColor(Color.TRANSPARENT)
            id = View.generateViewId()
        }
        preferenceUtils = PreferenceUtils(this)
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

        // Get the sliderHeight after the layout is complete
        val viewTreeObserver = sliderBackground.viewTreeObserver
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // Remove the listener to avoid multiple calls
                sliderBackground.viewTreeObserver.removeOnGlobalLayoutListener(this)
                sliderHeight = sliderBackground.height
                Log.d("Slider", "sliderHeight: $sliderHeight")

                // Set the touch listener after sliderHeight is known
                sliderBackground.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            // Reset dragging state on ACTION_DOWN
                            Log.d("Slider", "MotionEvent.ACTION_DOWN")
                            isDragging = false
                            Log.d("Slider", "isDragging: $isDragging")
                            lastY = event.y
                            initialInteractTime = System.currentTimeMillis()
                            Log.d("Slider", "lastY: $lastY, lastMoveTime: $lastMoveTime")
//
//                            // Calculate brightness percentage for initial position
//                            val adjustedY = event.y.coerceIn(0f, sliderHeight.toFloat())
//                            val percentage = ((sliderHeight - adjustedY) / sliderHeight * 100).toInt()
//                            currentBrightness = percentage
//                            Log.d("Slider", "currentBrightness: $currentBrightness")

                            // Set a delay to check for hold/tap after the touch starts
                            handler.postDelayed({
                                if (!isDragging) {
                                    // If it's not dragging after the delay, it's a tap
                                    updateThumbPosition(
                                        sliderThumb,
                                        currentBrightness,
                                        isDragged = false
                                    )
                                    Log.d("Slider", "User tapped. Jumping to: $currentBrightness")

                                    // Set the brightness if connected
                                    if (GlobalBluetoothManager.isConnected) {
                                        GlobalBluetoothManager.setBrightness(currentBrightness)
                                    }
                                }
                            }, 200) // 200ms delay to detect tap vs hold

                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val currentY = event.y
                            val deltaY = currentY - lastY
                            // Check if there's no movement AND no brightness change
                            if (isDragging == false && deltaY == 0f){
                                lastMoveTime = initialInteractTime
                            }
                            if (deltaY != 0f) {
                                isDragging = true
                            Log.d("Slider", "MotionEvent.ACTION_MOVE")
                            Log.d("Slider", "sliderHeight: $sliderHeight")
                            Log.d("Slider", "isDragging set to true")
                            Log.d("Slider", "isDragging: $isDragging")
                            Log.d("Slider", "currentY: $currentY")
                            Log.d("Slider", "deltaY: $deltaY")
                            val currentTime = System.currentTimeMillis()
                            Log.d("Slider", "currentTime: $currentTime")
                            val deltaTime = currentTime - lastMoveTime
                            Log.d("Slider", "deltaTime: $deltaTime")

                            // Adjust sensitivity based on speed
                            brightnessSensitivity = when {
                                deltaTime < 50 -> 0.5f // Fast movement
                                deltaTime < 100 -> 0.25f // Medium movement
                                else -> 0.1f // Slow movement
                            }
                            Log.d("Slider", "brightnessSensitivity: $brightnessSensitivity")

                            // Calculate brightness change
                            var brightnessChange = (deltaY * brightnessSensitivity).toInt()
                            Log.d("Slider", "brightnessChange before coerceIn: $brightnessChange")
                            brightnessChange =
                                brightnessChange.coerceIn(-maxBrightnessChange, maxBrightnessChange)
                            Log.d("Slider", "brightnessChange after coerceIn: $brightnessChange")

                            // Apply brightness change
                            currentBrightness =
                                (currentBrightness - brightnessChange).coerceIn(0, 100)
                            Log.d("Slider", "currentBrightness: $currentBrightness")
                                updateThumbPosition(
                                    sliderThumb,
                                    currentBrightness,
                                    isDragged = true
                                )
                                Log.d("Slider", "Update Thumb Position 1")

                            if (GlobalBluetoothManager.isConnected) {
                                if (currentBrightness != previousBrightness) {
                                    GlobalBluetoothManager.setBrightness(currentBrightness)
                                    Log.d(
                                        "Slider",
                                        "GlobalBluetoothManager.setBrightness($currentBrightness)"
                                    )
                                    previousBrightness = currentBrightness
                                }
                            } else {
                                if (currentBrightness != previousBrightness) {
                                    previousBrightness = currentBrightness
                                }
                            }
                            Log.d("Slider", "brightness: $currentBrightness")

                            // Update for next move
                            lastY = currentY
                            Log.d("Slider", "lastY: $lastY")
                            lastMoveTime = currentTime
                            Log.d("Slider", "lastMoveTime: $lastMoveTime")
                        }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            Log.d("Slider", "MotionEvent.ACTION_UP")
                            Log.d("Slider", "isDragging: $isDragging")
                            Log.d("Slider", "Delta Time: ${System.currentTimeMillis()-initialInteractTime}")
                            if (!isDragging || System.currentTimeMillis() - initialInteractTime < 120) {
                                // If it wasn't a drag (i.e., it's a tap), update the brightness
                                val adjustedY = event.y.coerceIn(0f, sliderHeight.toFloat())
                                val percentage = ((sliderHeight - adjustedY) / sliderHeight * 100).toInt()
                                currentBrightness = percentage
                                updateThumbPosition(
                                    sliderThumb,
                                    currentBrightness,
                                    isDragged = false
                                )

                                // Set the brightness if connected
                                if (GlobalBluetoothManager.isConnected) {
                                    GlobalBluetoothManager.setBrightness(currentBrightness)
                                }

                                Log.d("Slider", "User tapped. Final brightness: $currentBrightness")
                            }
                            // Reset the dragging state for next touch
                            isDragging = false
                            true
                        }

                        else -> {
                            Log.d("Slider", "MotionEvent: Other")
                            false
                        }
                    }
                }
            }
        })

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
        startConnectionCheck()

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
        serviceIntent = Intent(this, TimeMonitoringService::class.java)
        startService(serviceIntent)
    }

    private fun startConnectionCheck() {
        connectionCheckHandler.post(object : Runnable {
            override fun run() {
                    updateConnectIcon()
                connectionCheckHandler.postDelayed(this, connectionCheckInterval)
            }
        })
    }

    private fun stopConnectionCheck() {
        connectionCheckHandler.removeCallbacksAndMessages(null)
    }

    private fun updateThumbPosition(sliderThumb: View, brightness: Int, isDragged: Boolean) {
        Log.d("Slider", "brightness: $brightness")

        val minHeight = 1 // Minimum height for the thumb in pixels
        val percentage = brightness.coerceIn(0, 100)
        sliderHeight - (percentage.toFloat() / 100f * sliderHeight)

        // Calculate the new thumb height based on the brightness percentage
        val newHeight = (sliderHeight * (percentage / 100f)).toInt().coerceAtLeast(minHeight)

        val thumbParams = sliderThumb.layoutParams

        // If it's being dragged, adjust the thumb's position relatively
        if (isDragged) {
            thumbParams.height = newHeight
        } else {
            // If it's tapped, jump to the new position by updating the height directly
            thumbParams.height = newHeight
        }

        // Apply the thumb parameters to update the layout
        sliderThumb.layoutParams = thumbParams
        sliderThumb.requestLayout()

        // Log the updated thumb height
        Log.d("Slider", "Updated thumb height: $newHeight")
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

        // Use Material Components R for snackbars
        val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(Color.WHITE)

        // Use your app's R for color references
        val actionButton = snackbarView.findViewById<Button>(com.google.android.material.R.id.snackbar_action)
        actionButton.setTextColor(ContextCompat.getColor(this, R.color.light_blue))

        snackbar.show()
    }


    fun getColorName(colorValue: Int): String {
        return when (colorValue) {
            Color.RED -> "Red"
            Color.GREEN -> "Green"
            Color.BLUE -> "Blue"
            Color.BLACK -> "Black"
            Color.WHITE -> "White"
            Color.YELLOW -> "Yellow"
            Color.CYAN -> "Cyan"
            Color.MAGENTA -> "Magenta"
            else -> {
                // To provide a more consistent fallback, you can map any unknown colors to their hex values:
                if (colorValue in 0xFF000000..0xFFFFFFFF) {
                    String.format("#%06X", (0xFFFFFF and colorValue)) // Return hex without alpha
                } else {
                    "Unknown Color"
                }
            }
        }
    }

    fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }


    fun generateLayout(context: Context, parameters: List<Parameter>, flexboxLayout: FlexboxLayout): View {

        val configLocked = preferenceUtils.getBoolean(context, "led_config_locked", false)

        parameters.forEach { parameter ->
            Log.d("MainActivity", "Creating view for parameter: ${parameter.name} of type ${parameter.type}")

            val parameterView = when (parameter.type) {
                "PCOLOR", "SCOLOR" -> {
                    val colorBox = CardView(context).apply {
                        tag = parameter.name // Assign tag for visibility control
                        radius = 16f
                        val colorValue = parameter.value as? Int ?: Color.GRAY // Default to gray if no color value is set
                        setCardBackgroundColor(colorValue)  // Set initial color

                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT, // Change to WRAP_CONTENT
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(5, 16, 3, 8)
                        }

                        // Create a TextView to show the parameter type and its value
                        val textView = TextView(context).apply {
                            val colorName = getColorName(colorValue)
                            text = "${parameter.name}: $colorName"
                            setTextColor(Color.WHITE)  // Text color is white
                            setBackgroundColor(Color.TRANSPARENT)
                            textSize = 16f
                            setPadding(16, 16, 16, 16)  // Add padding inside the box
                        }

                        // Create a View to show the color preview
                        val colorPreview = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                50.dpToPx(context), // Adjust size as needed, e.g., 50dp for the preview
                                50.dpToPx(context)  // Same for height
                            ).apply {
                                setMargins(8, 16, 16, 16)
                            }
                            setBackgroundColor(colorValue)
                        }

                        // Add TextView and color preview to the CardView
                        val container = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,  // Use WRAP_CONTENT instead of MATCH_PARENT
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(16, 16, 16, 16)
                            }
                            addView(textView)
                            addView(colorPreview)  // Add color preview to the right
                        }

                        // Add container (which holds both text and color preview) to the CardView
                        addView(container)

                        // Set click listener to show the popup with the slider
                        setOnClickListener {
                            if (configLocked){
                                Toast.makeText(context, "LED Config is Locked. Please unlock to change modes.", Toast.LENGTH_SHORT).show()
                            }
                            else {
                                showPopup(
                                    context,
                                    parameter,
                                    parameter.type,
                                    textView,
                                    this,
                                    colorPreview
                                )  // Pass colorPreview as well
                            }
                        }
                    }

                    colorBox
                }

                // Similar changes for other parameter types like "FREQUENCY", "SPEED", etc.
                "FREQUENCY", "SPEED", "DIVISION" -> {
                    val parameterBox = CardView(context).apply {
                        tag = parameter.name // Assign tag for visibility control
                        radius = 16f
                        setCardBackgroundColor(Color.GRAY) // Gray background for the box
                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT,  // Set width to wrap content
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(5, 16, 3, 8)
                        }

                        // Create a TextView to show the parameter type and its value
                        val textView = TextView(context).apply {
                            text = "${parameter.name}: ${parameter.value}"
                            setTextColor(Color.WHITE)  // Text color is white
                            setBackgroundColor(Color.TRANSPARENT)
                            textSize = 18f
                            setPadding(60, 60, 60, 60)  // Add padding inside the box
                        }

                        // Create a dummy colorPreview view as a placeholder
                        val colorPreview = View(context) // Dummy View since no color is involved for this type

                        // Add TextView to the CardView
                        addView(textView)

                        // Set click listener to show the popup with the slider
                        setOnClickListener {
                            if (configLocked){
                                Toast.makeText(context, "LED Config is Locked. Please unlock to change modes.", Toast.LENGTH_SHORT).show()
                            }
                            else {
                                showPopup(
                                    context,
                                    parameter,
                                    parameter.type,
                                    textView,
                                    this,
                                    colorPreview
                                )  // Pass dummy colorPreview
                            }
                        }
                    }

                    parameterBox
                }

                "TEXT" -> {  // If parameter is of type TEXT
                    val textView = TextView(context).apply {
                        text = parameter.value.toString()  // Set the text
                        setTextColor(Color.WHITE)  // Set text color
                        textSize = 16f  // Set text size
                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT,  // Set width to wrap content
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(16, 16, 16, 16)
                        }
                    }

                    textView.visibility = View.VISIBLE
                    Log.d("MainActivity", "TextView created and visibility set to ${textView.visibility}")
                    textView
                }

                else -> View(context)  // Default case, if no match
            }

            // Add view to layout
            flexboxLayout.addView(parameterView)

            // Optionally log the visibility status of the added view
            Log.d("MainActivity", "Added view for ${parameter.name} with visibility: ${parameterView.visibility}")
        }

        return flexboxLayout
    }

    fun showPopup(context: Context, parameter: Parameter, type: String, textView: TextView, cardView: CardView, colorPreview: View) {
        val popup = Dialog(context).apply {
            setContentView(R.layout.parameter_popup) // The updated layout with a SeekBar and Spinner
            window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.8).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.25).toInt()
            )
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // Keep dialog background transparent
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) // Add dim effect behind the popup
                attributes = attributes?.apply {
                    dimAmount = 0.5f // Dim level (0.0 to 1.0)
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                }
            }
            setCancelable(true)
        }

        Log.d("MainActivity", "Showing popup for parameter: ${parameter.name} of type: ${type}")
        val titleTextView = popup.findViewById<TextView>(R.id.parameterTitle)
        titleTextView.text = "${parameter.name}"

        when (type) {
            "PCOLOR", "SCOLOR" -> {
                val colorDropdown = popup.findViewById<Spinner>(R.id.colorDropdown)
                colorDropdown.visibility = View.VISIBLE
                val rgbColorWheel = popup.findViewById<ColorWheel>(R.id.rgbColorWheel) // Cast to ColorWheel
                rgbColorWheel.visibility = View.GONE // Hide by default

                val colorMap = mapOf(
                    "Red" to Color.RED,
                    "Orange" to Color.parseColor("#FFA500"),
                    "Yellow" to Color.YELLOW,
                    "Green" to Color.GREEN,
                    "Blue" to Color.BLUE,
                    "Indigo" to Color.parseColor("#4B0082"),
                    "Violet" to Color.parseColor("#EE82EE"),
                    "Custom" to null
                )

                colorDropdown.adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    colorMap.keys.toList()
                )
                val currentColorName = when (val currentColor = parameter.value) {
                    is Int -> {
                        // Check if the current color is a custom RGB value, which doesn't match predefined colors
                        val matchedColor = colorMap.entries.find { it.value == currentColor }
                        matchedColor?.key ?: "Custom" // If no match, set as "Custom"
                    }
                    else -> "Custom" // Default to custom if the value is not a recognized color
                }

                Log.d("MainActivity", "Current color name: $currentColorName")

                val initialColorIndex = (colorDropdown.adapter as ArrayAdapter<String>).getPosition(currentColorName)
                colorDropdown.setSelection(initialColorIndex)

                // Set the preview color based on the current value
                if (currentColorName == "Custom" && parameter.value is Int) {
                    val customColor = parameter.value as Int
                    colorPreview.setBackgroundColor(customColor)
//
                } else {
                    // Use standard color from the color map
                    colorPreview.setBackgroundColor(colorMap[currentColorName] ?: Color.GREEN)
                }

                // Set the listener for the color wheel to notify when a new color is selected
                rgbColorWheel.setOnColorSelectedListener(object : ColorWheel.OnColorSelectedListener {
                    override fun onColorSelected(color: Int) {
                        colorPreview.setBackgroundColor(color) // Update the preview color
                        parameter.value = color // Update the parameter value
                        textView.text = "${parameter.name}: #${Integer.toHexString(color)}" // Update the textView
                        cardView.setCardBackgroundColor(color) // Update the card view background color
                        // Send the new color to the Bluetooth manager
                        val red = Color.red(color)
                        val green = Color.green(color)
                        val blue = Color.blue(color)
                        GlobalBluetoothManager.setLedParameter(
                            ModeManager.currentMode,
                            "Color",
                            "$red,$green,$blue"
                        )
                    }
                })

                colorDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val selectedColorName = parent?.getItemAtPosition(position) as? String
                        val selectedColor = colorMap[selectedColorName]

                        if (selectedColorName == "Custom") {
                            // Show the RGB color wheel and hide the dropdown
                            rgbColorWheel.visibility = View.VISIBLE

                            // Update the preview with the current custom color if available
                            if (parameter.value is Int) {
                                val customColor = parameter.value as Int
                                colorPreview.setBackgroundColor(customColor)
                            }

                            // Calculate the popup dimensions with 20% margin on each side
                            val screenWidth = context.resources.displayMetrics.widthPixels
                            val screenHeight = context.resources.displayMetrics.heightPixels
                            val popupWidth = (screenWidth * 0.8).toInt() // 80% of screen width
                            val popupHeight = (screenHeight * 0.8).toInt() // 80% of screen height

                            popup.window?.setLayout(
                                popupWidth,
                                popupHeight
                            )
                        } else {
                            if (selectedColor != null) {
                                rgbColorWheel.visibility = View.GONE // Hide RGB wheel
                                Log.d("MainActivity", "Selected color: $selectedColorName");
                                val red = Color.red(selectedColor)
                                val green = Color.green(selectedColor)
                                val blue = Color.blue(selectedColor)

                                popup.window?.setLayout(
                                    (context.resources.displayMetrics.widthPixels * 0.8).toInt(),
                                    (context.resources.displayMetrics.heightPixels * 0.25).toInt()
                                )

                                // Update the parameter value and preview for non-custom colors
                                parameter.value = selectedColor
                                textView.text = "${parameter.name}: $selectedColorName"
                                cardView.setCardBackgroundColor(selectedColor)
                                colorPreview.setBackgroundColor(selectedColor)

                                // Send the color to Bluetooth manager
                                GlobalBluetoothManager.setLedParameter(
                                    ModeManager.currentMode,
                                    "Color",
                                    "$red,$green,$blue"
                                )
                            }
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            "FREQUENCY", "SPEED", "DIVISION" -> {
                val seekBar = popup.findViewById<SeekBar>(R.id.parameterSlider)
                val valueTextView = popup.findViewById<TextView>(R.id.parameterValue)

                seekBar.visibility = View.VISIBLE
                valueTextView.visibility = View.VISIBLE

                // Set initial progress based on the parameter value
                val initialProgress = parameter.value as? Int ?: 0
                seekBar.progress = initialProgress

                // Set initial position and text
                valueTextView.text = initialProgress.toString()

                seekBar.post {
                    val thumbOffset = seekBar.thumb.bounds.left + (seekBar.thumb.bounds.width() / 2)
                    valueTextView.x = seekBar.left + thumbOffset.toFloat() - (valueTextView.width / 2)
                }

                // Listen for SeekBar changes
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        valueTextView.text = progress.toString()

                        // Update the position of the value text
                        val thumbOffset = seekBar.thumb.bounds.left + (seekBar.thumb.bounds.width() / 2)
                        valueTextView.x = seekBar.left + thumbOffset.toFloat() - (valueTextView.width / 2)

                        // Update the parameter value
                        parameter.value = progress

                        // Update the Bluetooth parameter
                        GlobalBluetoothManager.setLedParameter(
                            ModeManager.currentMode,
                            parameter.name,
                            progress.toString()
                        )

                        // Update the main TextView
                        textView.text = "${parameter.name}: $progress"
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            else -> {
                // Handle other types if necessary
            }
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
        val configLocked = preferenceUtils.getBoolean(this, "led_config_locked", false)
        if (configLocked) {
            Toast.makeText(this, "LED Config is Locked. Please unlock to change modes.", Toast.LENGTH_SHORT).show()
            return
        } else {
            val intent = Intent(this, ModesActivity::class.java)
            modesActivityResultLauncher.launch(intent)
        }
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
//        preferenceUtils.saveTimeFormatOption(timeFormatOption)
//        preferenceUtils.saveIs12HourTimeFormat(use12HourTimeFormat)
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

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Bluetooth connect permission granted")
            } else {
                Toast.makeText(this, "Bluetooth permission is required for app functionality", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initUI() {
        dynamicLayoutContainer = findViewById(R.id.dynamicLayoutContainer)
        settingModeDivider = findViewById(R.id.settingModeDivider)
        textView = findViewById(R.id.textView5)
        flexboxLayout = FlexboxLayout(this).apply {
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.SPACE_AROUND
            setBackgroundColor(Color.TRANSPARENT)
            id = View.generateViewId()
        }
        hideSystemUI()
    }

    private fun initBluetooth() {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e("MainActivity", "Bluetooth is not supported on this device.")
        } else if (!bluetoothAdapter.isEnabled) {
            Log.w("MainActivity", "Bluetooth is currently disabled.")
        } else {
            Log.d("MainActivity", "Bluetooth is supported and enabled.")
        }
    }

    private fun hideSystemUI() {
        // Use WindowInsetsController for Android 11+
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars()) // Hides status and navigation bars
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE // Allows bars to reappear with a swipe
        }
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
//        Log.d("MainActivity", "Updating connect icon: $connectState")
        if (GlobalBluetoothManager.isConnected) { // Use GlobalBluetoothManager.isConnected
            connectButton.setImageResource(R.drawable.stat_con_button)
        } else {
            connectButton.setImageResource(R.drawable.stat_dis_button)
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkBluetoothPermission()) {
            Log.d("MainActivity", "Bluetooth permission is already granted")
        }

        val currentMode = ModeManager.currentMode
        // Update the textView5 with the current mode name
        textView.text = "$currentMode Parameters"

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
        GlobalBluetoothManager.startConnectionCheck(this)

        // Check if the service is running and start it if not
        if (!isServiceRunning(TimeMonitoringService::class.java)) {
            Log.d("MainActivity", "TimeMonitoringService is not running, starting it now.")
            serviceIntent = Intent(this, TimeMonitoringService::class.java)
            startService(serviceIntent)
        } else {
            Log.d("MainActivity", "TimeMonitoringService is already running.")
        }

    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause called")
        GlobalBluetoothManager.stopConnectionCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy called")
        GlobalBluetoothManager.stopConnectionCheck()
    }

    @SuppressLint("ResourceType")
    private fun showAlarmMenu() {
        // Check if alarms are locked
        val alarmsLocked = preferenceUtils.getBoolean(this, "alarms_locked", false)

        if (alarmsLocked) {
            // Show a toast to inform the user that the alarms menu is locked
            Toast.makeText(this, "Alarms menu is locked. Please unlock to proceed.", Toast.LENGTH_SHORT).show()
            return // Prevent the activity from launching
        } else {
            // Create an intent for starting the AlarmActivity
            val intent = Intent(this, AlarmActivity::class.java).apply {
                val use12HourTimeFormat = getUse12HourTimeFormat() // Retrieve the preference
                putExtra(
                    "use12HourTimeFormat",
                    use12HourTimeFormat
                ) // Pass the time format preference
            }
            // Launch the AlarmActivity
            startActivity(intent)
        }
    }

    @SuppressLint("ResourceType")
    private fun showSettingsMenu() {
        val intent = Intent(this, settingsActivity::class.java)
        val use12HourTimeFormat = getUse12HourTimeFormat() // Retrieve the preference
        intent.putExtra("use12HourTimeFormat", use12HourTimeFormat)

        settingsActivityResultLauncher.launch(intent)
    }

    private fun getUse12HourTimeFormat(): Boolean {
        val prefs = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_12_hour_time", true) // Retrieve the saved time format
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