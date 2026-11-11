package com.example.ledwallgui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.provider.Settings
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
import com.example.ledwallgui.ui.TabletMotionWake
import com.example.ledwallgui.ui.AudioReactiveService
import com.example.ledwallgui.services.BluetoothReconnectionService
import com.example.ledwallgui.ui.alarm.AlarmActivity
import com.example.ledwallgui.ui.modes.ModeManager
import com.example.ledwallgui.ui.modes.ModesActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.example.ledwallgui.ui.settings.ConnectionActivity
import com.example.ledwallgui.ui.settings.settingsActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResult
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.ledwallgui.ui.settings.ColorWheel
import com.example.ledwallgui.ui.settings.ModeInfo
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.ranges.coerceAtLeast
import kotlin.ranges.coerceIn


class MainActivity : AppCompatActivity() {
    private lateinit var globalBluetoothManager: GlobalBluetoothManager
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
    private var roomReconcileChecked = false
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
    private var motionWake: TabletMotionWake? = null
    private var recordLedState = false
    private var powerCommandPending = false
    var sliderThumb: View? = null

    companion object {
        private const val TRACE_SLIDER = false  // Enable only for interactive gesture diagnostics.
        private const val SETTINGS_REQUEST_CODE = 100
        private const val MODES_REQUEST_CODE = 101 // Add this line
    }

    private lateinit var startupPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android allows one permission request at a time. Request the dangerous
        // permissions together instead of racing a BLE popup with notifications.
        startupPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            // Don't display a Toast over a permission sheet. Connections screen
            // handles denied BLE access by returning to the parent page.
            if (results[Manifest.permission.BLUETOOTH_CONNECT] == false) {
                Log.w("MainActivity", "Bluetooth permission denied")
            }
            if (results[Manifest.permission.POST_NOTIFICATIONS] == false) {
                // The system dialog has to finish before our own UI feedback.
                // Use an unobtrusive Snackbar after the permission result, not a Toast.
                window.decorView.postDelayed({
                    if (!isFinishing && !isDestroyed) showNotificationSettingsHint()
                }, 600L)
            }
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        setContentView(R.layout.activity_main)
        configurePortraitHomeControls()

        globalBluetoothManager = GlobalBluetoothManager(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                globalBluetoothManager.liveState.collect { remote ->
                    if (remote == null) return@collect
                    val modeChanged = ModeManager.currentMode != remote.mode
                    ModeManager.currentMode = remote.mode
                    if (modeChanged || remote.parameters.isNotEmpty()) updateModeDisplay()
                    if (modeChanged && remote.mode == "Weather") {
                        // Also refresh when a second tablet selects Weather mode.
                        // The updater shares an in-flight guard across Activities.
                        com.example.ledwallgui.ui.WeatherUpdater(applicationContext, globalBluetoothManager).refreshNow()
                    }
                    powerState = remote.power
                    updatePowerButtonImage()
                    if (powerCommandPending) {
                        powerCommandPending = false
                        findViewById<ImageButton>(R.id.imageButton1).isEnabled = true
                    }
                    if (!isDragging) {
                        // Even when the numeric brightness matches a saved value, the
                        // slider may not have been measured when it was first drawn.
                        currentBrightness = remote.brightness.coerceIn(0, 100)
                        sliderThumb?.let { updateThumbPosition(it, currentBrightness, false) }
                    }
                }
            }
        }

        modesActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == RESULT_OK) {
                    Log.d("MainActivity", "Mode selected result received.")
                    val ledParametersJson = result.data?.getStringExtra("led_parameters")
                    Log.d("MainActivity", "Received LED parameters: $ledParametersJson")
                    // Call a helper function to update the display based on the selected mode.
                    updateModeDisplay()
                    // Mode changes are acknowledged by the controller; do not
                    // overwrite its live power state with a stale local value.
                    val latest = globalBluetoothManager.liveState.value
                    if (latest != null) {
                        ModeManager.currentMode = latest.mode
                        powerState = latest.power
                        currentBrightness = latest.brightness
                    }
                    updatePowerButtonImage()
                } else {
                    Log.w("MainActivity", "No mode result received.")
                }
            }


        settingsActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == RESULT_OK) {
                    // Handle the result here
                    val data = result.data

                    // Retrieve the stored boolean from shared preferences for 12-hour time format
                    val prefs = getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
                    val use12HourTimeFormat =
                        prefs.getBoolean("is_12_hour_time", true) // Default to true if not set

                    // Retrieve the time format option (defaults to 0)
                    val timeFormatOption = data?.getIntExtra("timeFormatOption", 0) ?: 0

                    // Log the updated settings for debugging
                    Log.d(
                        "Settings",
                        "use12HourTimeFormat: $use12HourTimeFormat, timeFormatOption: $timeFormatOption"
                    )

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
        sliderThumb = findViewById<View>(R.id.sliderThumb)
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
            if (!globalBluetoothManager.isConnected) {
                showConnectionSnackbar()
            } else if (!powerCommandPending) {
                // The controller is authoritative. Don't optimistically rewrite
                // the button or let a stale preference undo an incoming state.
                val nextPower = !(globalBluetoothManager.liveState.value?.power ?: powerState)
                powerCommandPending = true
                imageButton1.isEnabled = false
                globalBluetoothManager.sendPowerStateToArduino(nextPower)
                handler.postDelayed({
                    if (!isDestroyed && !isFinishing && powerCommandPending) {
                        powerCommandPending = false
                        imageButton1.isEnabled = true
                        // If the notification was lost, fetch the controller's state.
                        lifecycleScope.launch { globalBluetoothManager.refreshState() }
                    }
                }, 2500L)
            }
        }

        // Defer the single Android runtime-permission request until UI setup
        // finishes. This prevents the "only one set of permissions" warning.
        window.decorView.post { requestStartupPermissions() }

        // Get the sliderHeight after the layout is complete
        val viewTreeObserver = sliderBackground.viewTreeObserver
        viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // Wait for a non-zero measured height; a controller snapshot
                // can arrive before this view has been laid out.
                if (sliderBackground.height <= 0) return
                sliderBackground.viewTreeObserver.removeOnGlobalLayoutListener(this)
                sliderHeight = sliderBackground.height
                Log.d("Slider", "sliderHeight: $sliderHeight")

                // Re-render after measurement using the controller's current state,
                // not the stale brightness previously saved on this device.
                if (!isDragging) {
                    currentBrightness = (globalBluetoothManager.liveState.value?.brightness
                        ?: currentBrightness).coerceIn(0, 100)
                    sliderThumb?.let { updateThumbPosition(it, currentBrightness, false) }
                }

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
                                    sliderThumb?.let {
                                        updateThumbPosition(
                                            it,
                                            currentBrightness,
                                            isDragged = false
                                        )
                                    }
                                    Log.d("Slider", "User tapped. Jumping to: $currentBrightness")

                                    // Set the brightness if connected
                                    if (connectState) {
                                        globalBluetoothManager.setBrightness(currentBrightness)
                                        preferenceUtils.setBrightness(currentBrightness.toString())
                                    }
                                }
                            }, 200) // 200ms delay to detect tap vs hold

                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val currentY = event.y
                            val deltaY = currentY - lastY
                            // Check if there's no movement AND no brightness change
                            if (isDragging == false && deltaY == 0f) {
                                lastMoveTime = initialInteractTime
                            }
                            if (deltaY != 0f) {
                                isDragging = true
                                if (TRACE_SLIDER) Log.d("Slider", "MotionEvent.ACTION_MOVE")
                                if (TRACE_SLIDER) Log.d("Slider", "sliderHeight: $sliderHeight")
                                if (TRACE_SLIDER) Log.d("Slider", "isDragging set to true")
                                if (TRACE_SLIDER) Log.d("Slider", "isDragging: $isDragging")
                                if (TRACE_SLIDER) Log.d("Slider", "currentY: $currentY")
                                if (TRACE_SLIDER) Log.d("Slider", "deltaY: $deltaY")
                                val currentTime = System.currentTimeMillis()
                                if (TRACE_SLIDER) Log.d("Slider", "currentTime: $currentTime")
                                val deltaTime = currentTime - lastMoveTime
                                if (TRACE_SLIDER) Log.d("Slider", "deltaTime: $deltaTime")

                                // Adjust sensitivity based on speed
                                brightnessSensitivity = when {
                                    deltaTime < 50 -> 0.5f // Fast movement
                                    deltaTime < 100 -> 0.25f // Medium movement
                                    else -> 0.1f // Slow movement
                                }
                                if (TRACE_SLIDER) Log.d("Slider", "brightnessSensitivity: $brightnessSensitivity")

                                // Calculate brightness change
                                var brightnessChange = (deltaY * brightnessSensitivity).toInt()
                                if (TRACE_SLIDER) Log.d(
                                    "Slider",
                                    "brightnessChange before coerceIn: $brightnessChange"
                                )
                                brightnessChange =
                                    brightnessChange.coerceIn(
                                        -maxBrightnessChange,
                                        maxBrightnessChange
                                    )
                                if (TRACE_SLIDER) Log.d(
                                    "Slider",
                                    "brightnessChange after coerceIn: $brightnessChange"
                                )

                                // Apply brightness change
                                currentBrightness =
                                    (currentBrightness - brightnessChange).coerceIn(0, 100)
                                if (TRACE_SLIDER) Log.d("Slider", "currentBrightness: $currentBrightness")
                                sliderThumb?.let {
                                    updateThumbPosition(
                                        it,
                                        currentBrightness,
                                        isDragged = true
                                    )
                                }
                                if (TRACE_SLIDER) Log.d("Slider", "Update Thumb Position 1")

                                if (connectState) {
                                    if (currentBrightness != previousBrightness) {
                                        globalBluetoothManager.setBrightness(currentBrightness)
                                        if (TRACE_SLIDER) Log.d(
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
                                if (TRACE_SLIDER) Log.d("Slider", "brightness: $currentBrightness")

                                // Update for next move
                                lastY = currentY
                                if (TRACE_SLIDER) Log.d("Slider", "lastY: $lastY")
                                lastMoveTime = currentTime
                                if (TRACE_SLIDER) Log.d("Slider", "lastMoveTime: $lastMoveTime")
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            Log.d("Slider", "MotionEvent.ACTION_UP")
                            Log.d("Slider", "isDragging: $isDragging")
                            Log.d(
                                "Slider",
                                "Delta Time: ${System.currentTimeMillis() - initialInteractTime}"
                            )
                            if (!isDragging || System.currentTimeMillis() - initialInteractTime < 120) {
                                // If it wasn't a drag (i.e., it's a tap), update the brightness
                                val adjustedY = event.y.coerceIn(0f, sliderHeight.toFloat())
                                val percentage =
                                    ((sliderHeight - adjustedY) / sliderHeight * 100).toInt()
                                currentBrightness = percentage
                                sliderThumb?.let {
                                    updateThumbPosition(
                                        it,
                                        currentBrightness,
                                        isDragged = false
                                    )
                                }

                                // Set the brightness if connected
                                if (connectState) {
                                    globalBluetoothManager.setBrightness(currentBrightness)
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
            if (connectState) {
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

        // Camera motion analysis runs only while this Activity is visible (Android 14).
        motionWake = TabletMotionWake(this)
        recordLedState = intent.getBooleanExtra("record_led_state", false)
        if (recordLedState) {
            val recordOverlay = findViewById<View>(R.id.recordOverlay)
            val recordDoneButton = findViewById<Button>(R.id.recordDoneButton)
            recordOverlay.visibility = View.VISIBLE
            recordDoneButton.visibility = View.VISIBLE

            // Hide the settings and alarm buttons to avoid loops.
            imageButton2.visibility = View.GONE  // Hide Alarm button
            imageButton3.visibility = View.GONE  // Hide Settings button

            // Optionally hide other buttons that may lead to mode changes.
            // For example, if you have any additional navigation that should be disabled.

            // Set up the done button click listener.
            recordDoneButton.setOnClickListener {
                lifecycleScope.launch {
                    val snapshot = com.example.ledwallgui.ui.alarm.AlarmPlayback.capture(globalBluetoothManager)
                    if (snapshot == null) {
                        Toast.makeText(this@MainActivity, "Controller unavailable; can't capture actual effect settings", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    // The editor restores the pre-capture controller state when this Activity closes.
                    val response = Intent().putExtra("led_parameters", Gson().toJson(snapshot))
                    setResult(Activity.RESULT_OK, response)
                    finish()
                }
            }
        }
    }

    private fun startConnectionCheck() {
        stopConnectionCheck() // Never create two self-reposting runnables on activity recreation.
        connectionCheckHandler.post(object : Runnable {
            override fun run() {
                val (isConnected, deviceAddress, deviceName) = preferenceUtils.loadConnectionState()

                if (isConnected && !connectState) {
                    connectState = true
                    // Re-read the controller as the BLE connection becomes ready.
                    // This only updates the UI and never sends local brightness
                    // settings to the controller on connection.
                    lifecycleScope.launch { globalBluetoothManager.refreshState() }
                    if (!roomReconcileChecked) {
                        roomReconcileChecked = true
                        com.example.ledwallgui.ui.settings.RoomSync.reconcile(this@MainActivity, globalBluetoothManager)
                    }
                } else if (!isConnected && connectState) {
                    roomReconcileChecked = false
                    connectState = false
                    Log.d("MainActivity", "Bluetooth connection is not active")
                } else {
                    //Nothing Happens
                }

                updateConnectIcon()
                if (!isFinishing && !isDestroyed) connectionCheckHandler.postDelayed(this, connectionCheckInterval)
            }
        })
    }

    private fun stopConnectionCheck() {
        connectionCheckHandler.removeCallbacksAndMessages(null)
    }

    private val weatherPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var weatherPollRunnable: Runnable? = null
    private val weatherPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last_weather_poll_ms" || key == "weather_code" ||
            key == "weather_location_label" || key == "weather_last_error" ||
            key == "weather_is_day" || key == "last_weather_attempt_ms" ||
            key == "weather_display_refresh_at_ms") {
            if (!isFinishing && !isDestroyed && ModeManager.currentMode == "Weather") {
                runOnUiThread { if (!isFinishing && !isDestroyed) updateModeDisplay() }
            }
        }
    }
    private fun startWeatherPolling() {
        weatherPollRunnable?.let(weatherPollHandler::removeCallbacks)
        val updater = com.example.ledwallgui.ui.WeatherUpdater(applicationContext, globalBluetoothManager)
        val task = object : Runnable {
            override fun run() {
                if (!isFinishing && !isDestroyed) {
                    updater.updateIfDue()
                    updater.updateSunriseLevel()
                    if (ModeManager.currentMode == "Weather") updateModeDisplay()
                    // Refresh cached weather readout while network polling
                    // remains rate-limited to every 30 minutes.
                    weatherPollHandler.postDelayed(this, 30_000L)
                }
            }
        }
        weatherPollRunnable = task
        weatherPollHandler.post(task)
    }
    private fun stopWeatherPolling() {
        weatherPollRunnable?.let(weatherPollHandler::removeCallbacks)
        weatherPollRunnable = null
    }

    private var lastModePresentation = ""
    private fun updateModeDisplay() {
        val currentMode = ModeManager.currentMode
        textView.text = "$currentMode Parameters"
        Log.d("MainActivity", "Updating display for mode: $currentMode")

        // Try to get mode info from GlobalBluetoothManager.
        var modeInfo = globalBluetoothManager.getModeInfo(currentMode)
        if (modeInfo == null) {
            // Fallback: retrieve persisted mode info from preferences.
            val persistedModes = preferenceUtils.getModeInfoList() // returns List<ModeInfo>
            modeInfo = persistedModes.find { it.name == currentMode }
            if (modeInfo != null) {
                Log.d("MainActivity", "Loaded mode info from preferences: ${modeInfo.parameterValues}")
            } else {
                Log.w("MainActivity", "No mode info found for mode: $currentMode")
                return
            }
        } else {
            Log.d("MainActivity", "Mode info from Bluetooth: ${modeInfo.parameterValues}")
        }

        val remoteState = globalBluetoothManager.liveState.value
        if (remoteState?.mode == currentMode && remoteState.parameters.isNotEmpty()) {
            modeInfo.parameterValues.putAll(remoteState.parameters)
        }

        // Defaults only apply while offline. An empty live snapshot means that
        // the matching parameter notification has not arrived yet.
        if (modeInfo.parameterValues.isEmpty() && remoteState == null) {
            Log.d("MainActivity", "Parameter values are empty. Setting default values for mode: $currentMode")
            modeInfo.parameterNames.forEach { param ->
                modeInfo.parameterValues[param] = getDefaultForParameter(currentMode, param)
            }
            Log.d("MainActivity", "Mode info after setting defaults: ${modeInfo.parameterValues}")
        }

        val waitingForLiveParameters = remoteState?.mode == currentMode &&
            remoteState.parameters.isEmpty() && modeInfo.parameterNames.isNotEmpty()
        val weatherStatus = if (currentMode == "Weather") weatherStatusText() else ""
        val signature = currentMode + "|" + waitingForLiveParameters + "|" + weatherStatus + "|" + modeInfo.parameterNames.joinToString { param ->
            param + "=" + (modeInfo.parameterValues[param] ?: "")
        }
        if (signature == lastModePresentation) return
        lastModePresentation = signature

        // Convert the mode's parameter info into a list of Parameter objects.
        val parameterList = modeInfo.parameterNames.filterNot { currentMode == "Weather" && it == "Color" }.map { paramName ->
            Parameter(
                name = paramName,
                value = parseModeParameter(paramName, modeInfo.parameterValues[paramName]),
                type = getParameterTypeFromName(paramName)
            )
        }

        val shownParameters = if (currentMode == "Weather")
            parameterList + Parameter("Weather", "TEXT", weatherStatus)
        else if (waitingForLiveParameters) listOf(Parameter("Sync", "TEXT", "Syncing mode values..."))
        else parameterList
        Log.d("MainActivity", "Constructed Parameter List: $shownParameters")

        // Regenerate the layout using your generateLayout() method.
        val layout = generateLayout(this, shownParameters, flexboxLayout)
        dynamicLayoutContainer.removeAllViews()
        dynamicLayoutContainer.addView(layout)
        Log.d("MainActivity", "Dynamic layout updated for mode: $currentMode")
    }

    private fun weatherStatusText(): String {
        val settings = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        if (!settings.getBoolean("location_enabled", false)) {
            return "Location: Disabled\nEnable Location in Settings to receive current weather.\nNext update: Paused"
        }
        val stamp = settings.getLong("last_weather_poll_ms", 0L)
        val time = if (stamp > 0L) java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(stamp)) else "Not checked"
        val code = settings.getInt("weather_code", -1)
        val desc = when {
            code < 0 -> "Waiting for weather"
            code <= 1 -> "Clear"
            code <= 3 -> "Cloudy"
            code <= 48 -> "Fog"
            code <= 67 -> "Rain / drizzle"
            code <= 77 -> "Snow"
            code <= 82 -> "Rain showers"
            code <= 86 -> "Snow showers"
            else -> "Thunderstorms"
        }
        val day = if (settings.getBoolean("weather_is_day", true)) "Day" else "Night"
        val location = settings.getString("weather_location_label", "Location unavailable")
        val lastAttempt = settings.getLong("last_weather_attempt_ms", 0L)
        val lastError = settings.getString("weather_last_error", null)
        val nextDue = com.example.ledwallgui.ui.WeatherSchedule.nextDue(
            stamp, lastAttempt, lastError != null)
        val next = when {
            com.example.ledwallgui.ui.WeatherUpdater.isChecking() -> "Checking now..."
            stamp <= 0L && lastError == null -> "As soon as location is available"
            nextDue == null -> "As soon as location is available"
            System.currentTimeMillis() >= nextDue -> "Due now"
            else -> java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(nextDue))
        }
        val error = lastError?.let { "\nStatus: $it" } ?: ""
        return "Location: $location\nConditions: $desc ($day)\nLast checked: $time\nNext update: $next$error"
    }

    private fun parseModeParameter(name: String, raw: String?): Any = when (name) {
        "Slot" -> "Animation slot ${(raw?.toIntOrNull() ?: 0) + 1}"
        "Color", "SColor" -> {
            val components = raw?.removePrefix("{")?.removeSuffix("}")?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
            if (components?.size == 3) Color.rgb(
                components[0].coerceIn(0, 255), components[1].coerceIn(0, 255), components[2].coerceIn(0, 255)
            ) else Color.GRAY
        }
        else -> raw?.toIntOrNull() ?: 0
    }

    private fun getParameterTypeFromName(name: String): String {
        return when (name.lowercase()) {
            "color" -> "PCOLOR"      // or "SCOLOR" if that’s more appropriate
            "scolor" -> "SCOLOR"
            "speed" -> "SPEED"
            "frequency" -> "FREQUENCY"
            "division" -> "DIVISION"
            "sensitivity" -> "SENSITIVITY"
            "reaction" -> "REACTION"
            "pattern" -> "PATTERN"
            "direction" -> "DIRECTION"
            "palette" -> "PALETTE"
            "bounce" -> "BOUNCE"
            "fade" -> "FADE"
            "spawn" -> "SPAWN"
            "decay" -> "DECAY"
            "length" -> "LENGTH"
            "size" -> "SIZE"
            "collision" -> "COLLISION"
            else -> "TEXT"
        }
    }

    // Helper function to provide default values for a given mode and parameter.
    private fun getDefaultForParameter(mode: String, parameter: String): String {
        // New effect controls retain a usable default while live settings load.
        if (parameter == "Spawn") return if (mode == "Alternating Phaser") "1" else "40"
        if (parameter == "Decay") return "55"
        if (parameter == "Length") return "20"
        if (parameter == "Size") return "35"
        if (parameter == "Collision") return "1"
        return when (mode) {
            "Rainbow" -> {
                if (parameter == "Speed") "50" else ""
            }
            "Color" -> {
                if (parameter == "Color") Color.RED.toString() else ""
            }
            // Add defaults for other modes as needed.
            else -> ""
        }
    }

    /** Portrait-only guard: keep the portrait dashboard aligned with the
     * landscape slider style without affecting the original landscape dashboard. */
    private fun configurePortraitHomeControls() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return

        // ColorWheel belongs in the Custom Color dialog, not on Home.
        findViewById<View>(R.id.rgbColorWheelContainer)?.visibility = View.GONE

        val holder = findViewById<FrameLayout>(R.id.sliderContainer) ?: return
        val track = findViewById<View>(R.id.sliderBackground) ?: return
        val fill = findViewById<View>(R.id.sliderThumb) ?: return
        val sun = findViewById<ImageView>(R.id.sunIcon)
        val d = resources.displayMetrics.density
        fun pixels(dp: Int) = (dp * d + 0.5f).toInt()

        holder.minimumWidth = pixels(62)
        holder.minimumHeight = pixels(190)
        holder.setBackgroundColor(Color.TRANSPARENT)

        track.background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.slider_background)
        if (track is FrameLayout) track.clipToOutline = true
        (track.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.setMargins(pixels(8), pixels(8), pixels(8), pixels(8))
            track.layoutParams = params
        }

        (fill.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = pixels(7)
            params.gravity = android.view.Gravity.BOTTOM
            params.setMargins(0, 0, 0, 0)
            fill.layoutParams = params
        }
        fill.background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.slider_thumb)

        findViewById<View>(R.id.blackBorder)?.visibility = View.GONE

        sun?.let {
            (it.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = pixels(32)
                params.height = pixels(32)
                params.gravity = android.view.Gravity.CENTER
                params.setMargins(0, 0, 0, 0)
                it.layoutParams = params
            }
            // The sun icon must be transparent so the slider can show through it.
            it.setPadding(0, 0, 0, 0)
            it.background = null
            it.bringToFront()
        }
        fill.requestLayout()
        track.requestLayout()
    }

    private fun updateThumbPosition(sliderThumb: View, brightness: Int, isDragged: Boolean) {
        Log.d("Slider", "brightness: $brightness")

        val percentage = brightness.coerceIn(0, 100)
        findViewById<TextView>(R.id.brightnessValueLabel)?.text = "$percentage%"

        // A live BLE state can be delivered before the portrait rail is laid out.
        // Keep the percentage label up to date and render the fill once measured.
        val measuredHeight = findViewById<View>(R.id.sliderBackground)?.height ?: sliderHeight
        if (measuredHeight <= 0) return
        sliderHeight = measuredHeight

        val minVisibleHeight = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
            (6 * resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1) else 1
        val newHeight = if (percentage == 0) 0 else
            (measuredHeight * (percentage / 100f)).toInt()
                .coerceAtLeast(minVisibleHeight).coerceAtMost(measuredHeight)

        val thumbParams = sliderThumb.layoutParams
        if (thumbParams.height != newHeight) {
            thumbParams.height = newHeight
            sliderThumb.layoutParams = thumbParams
            sliderThumb.requestLayout()
        }
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
        val textView =
            snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(Color.WHITE)

        // Use your app's R for color references
        val actionButton =
            snackbarView.findViewById<Button>(com.google.android.material.R.id.snackbar_action)
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
                String.format(java.util.Locale.US, "#%06X", colorValue and 0xFFFFFF)
            }
        }
    }

    fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }


    private fun modeChoices(type: String): Array<String>? = when (type) {
        "REACTION" -> arrayOf("Spectrum fill", "Whole-room brightness", "Peak color pulse")
        "PATTERN" -> if (ModeManager.currentMode == "Phaser")
            arrayOf("One traveling bar", "Random bars")
        else arrayOf("Normal", "Reversed", "Square / all walls")
        "DIRECTION" -> arrayOf("Clockwise", "Counterclockwise", "Both / alternating")
        "PALETTE" -> arrayOf("Single chosen color", "Random colors")
        "BOUNCE" -> arrayOf("No bounce", "Bounce at landing")
        "SPAWN" -> if (ModeManager.currentMode == "Alternating Phaser")
            arrayOf("Alternate corners", "Both corners", "Random corner") else null
        "COLLISION" -> arrayOf("Pass through", "White burst and remove")
        else -> null
    }

    fun generateLayout(
        context: Context,
        parameters: List<Parameter>,
        flexboxLayout: FlexboxLayout
    ): View {
        flexboxLayout.removeAllViews()
        val configLocked = preferenceUtils.getBoolean(context, "led_config_locked", false)

        parameters.forEach { parameter ->
            Log.d(
                "MainActivity",
                "Creating view for parameter: ${parameter.name} of type ${parameter.type}"
            )

            val parameterView = when (parameter.type) {
                "PCOLOR", "SCOLOR" -> {
                    val colorBox = CardView(context).apply {
                        tag = parameter.name // Assign tag for visibility control
                        radius = 16f
                        val colorValue = parameter.value as? Int
                            ?: Color.GRAY // Default to gray if no color value is set
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
                            if (configLocked) {
                                Toast.makeText(
                                    context,
                                    "LED Config is Locked. Please unlock to change modes.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
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
                "FREQUENCY", "SPEED", "DIVISION", "SENSITIVITY", "REACTION", "PATTERN", "DIRECTION", "PALETTE", "BOUNCE", "FADE", "SPAWN", "DECAY", "LENGTH", "SIZE", "COLLISION" -> {
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
                            val choices = modeChoices(parameter.type)
                            text = if (choices != null) {
                                "${parameter.name}: " + choices.getOrElse((parameter.value as? Int ?: 0).coerceIn(0, choices.lastIndex)) { choices[0] }
                            } else "${parameter.name}: ${parameter.value}"
                            setTextColor(Color.WHITE)  // Text color is white
                            setBackgroundColor(Color.TRANSPARENT)
                            textSize = 18f
                            setPadding(60, 60, 60, 60)  // Add padding inside the box
                        }

                        // Create a dummy colorPreview view as a placeholder
                        val colorPreview =
                            View(context) // Dummy View since no color is involved for this type

                        // Add TextView to the CardView
                        addView(textView)

                        // Set click listener to show the popup with the slider
                        setOnClickListener {
                            if (configLocked) {
                                Toast.makeText(
                                    context,
                                    "LED Config is Locked. Please unlock to change modes.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else if (modeChoices(parameter.type) != null) {
                                val options = requireNotNull(modeChoices(parameter.type))
                                androidx.appcompat.app.AlertDialog.Builder(context)
                                    .setTitle(parameter.name)
                                    .setSingleChoiceItems(options, (parameter.value as? Int ?: 0).coerceIn(0, options.lastIndex)) { dialog, which ->
                                        parameter.value = which
                                        textView.text = "${parameter.name}: ${options[which]}"
                                        globalBluetoothManager.setLedParameter(parameter.name, listOf(which))
                                        dialog.dismiss()
                                    }.setNegativeButton("Cancel", null).show()
                            } else {
                                showPopup(
                                    context,
                                    parameter,
                                    parameter.type,
                                    textView,
                                    this,
                                    colorPreview
                                )
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
                    Log.d(
                        "MainActivity",
                        "TextView created and visibility set to ${textView.visibility}"
                    )
                    textView
                }

                else -> View(context)  // Default case, if no match
            }

            // Add view to layout
            flexboxLayout.addView(parameterView)

            // Optionally log the visibility status of the added view
            Log.d(
                "MainActivity",
                "Added view for ${parameter.name} with visibility: ${parameterView.visibility}"
            )
        }

        return flexboxLayout
    }

    fun showPopup(
        context: Context,
        parameter: Parameter,
        type: String,
        textView: TextView,
        cardView: CardView,
        colorPreview: View
    ) {
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
                val rgbColorWheel =
                    popup.findViewById<ColorWheel>(R.id.rgbColorWheel) // Cast to ColorWheel
                rgbColorWheel.visibility = View.GONE // Hide by default

                val colorMap = mapOf(
                    "Red" to Color.RED,
                    "Orange" to Color.parseColor("#FF2F00"),
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

                val initialColorIndex =
                    (colorDropdown.adapter as ArrayAdapter<String>).getPosition(currentColorName)
                colorDropdown.setSelection(initialColorIndex)

                // Set the preview color based on the current value
                if (currentColorName == "Custom" && parameter.value is Int) {
                    val customColor = parameter.value as Int
                    colorPreview.setBackgroundColor(customColor)
                } else {
                    // Use standard color from the color map
                    colorPreview.setBackgroundColor(colorMap[currentColorName] ?: Color.GREEN)
                }

                // Set the listener for the color wheel to notify when a new color is selected
                rgbColorWheel.setOnColorSelectedListener(object :
                    ColorWheel.OnColorSelectedListener {
                    override fun onColorSelected(color: Int) {
                        colorPreview.setBackgroundColor(color) // Update the preview color
                        parameter.value = color // Update the parameter value
                        textView.text =
                            "${parameter.name}: #${Integer.toHexString(color)}" // Update the textView
                        cardView.setCardBackgroundColor(color) // Update the card view background color
                        // Send the new color to the Bluetooth manager
                        val red = Color.red(color)
                        val green = Color.green(color)
                        val blue = Color.blue(color)
                        globalBluetoothManager.setLedParameter(
                            parameter.name,
                            "$red,$green,$blue".split(",").map { it.toInt() }
                        )
                    }
                })

                colorDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
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
                                globalBluetoothManager.setLedParameter(
                                    parameter.name,
                                    "$red,$green,$blue".split(",").map { it.toInt() }
                                )
                            }
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            "FREQUENCY", "SPEED", "DIVISION", "SENSITIVITY", "REACTION", "PATTERN", "DIRECTION", "PALETTE", "BOUNCE", "FADE", "SPAWN", "DECAY", "LENGTH", "SIZE", "COLLISION" -> {
                val seekBar = popup.findViewById<SeekBar>(R.id.parameterSlider)
                val valueTextView = popup.findViewById<TextView>(R.id.parameterValue)

                seekBar.visibility = View.VISIBLE
                valueTextView.visibility = View.VISIBLE
                seekBar.max = when {
                    parameter.type == "REACTION" -> 2
                    parameter.type == "COLLISION" -> 1
                    parameter.type == "SPAWN" && ModeManager.currentMode == "Alternating Phaser" -> 2
                    parameter.type == "PATTERN" && ModeManager.currentMode == "Phaser" -> 1
                    else -> 100
                }

                // Set initial progress based on the parameter value
                val initialProgress = parameter.value as? Int ?: 0
                seekBar.progress = initialProgress

                // Set initial position and text
                valueTextView.text = initialProgress.toString()

                seekBar.post {
                    val thumbOffset = seekBar.thumb.bounds.left + (seekBar.thumb.bounds.width() / 2)
                    valueTextView.x =
                        seekBar.left + thumbOffset.toFloat() - (valueTextView.width / 2)
                }

                // Listen for SeekBar changes
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        valueTextView.text = progress.toString()

                        // Update the position of the value text
                        val thumbOffset =
                            seekBar.thumb.bounds.left + (seekBar.thumb.bounds.width() / 2)
                        valueTextView.x =
                            seekBar.left + thumbOffset.toFloat() - (valueTextView.width / 2)

                        // Update the parameter value
                        parameter.value = progress

                        Log.d(
                            "MainActivity",
                            "Parameter value updated: $progress.toString().split(\",\").map { it.toInt() }"
                        )

                        // Update the Bluetooth parameter
                        globalBluetoothManager.setLedParameter(
                            parameter.name,
                            progress.toString().split(",").map { it.toInt() }
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
            Toast.makeText(
                this,
                "LED Config is Locked. Please unlock to change modes.",
                Toast.LENGTH_SHORT
            ).show()
            return
        } else {
            val intent = Intent(this, ModesActivity::class.java)
            intent.putExtra("record_led_state", recordLedState)
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
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestStartupPermissions() {
        if (isFinishing || isDestroyed) return
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !checkBluetoothPermission()) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // The notification dialog is shown at most once automatically.
            // After a denial, let the user choose Settings instead of showing
            // a fake "permanently denied" message or repeating the dialog.
            val prefs = getSharedPreferences("ledwall_runtime_permissions", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notification_requested_v5", false)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
                prefs.edit().putBoolean("notification_requested_v5", true).apply()
            }
        }
        if (missing.isNotEmpty()) {
            startupPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun showNotificationSettingsHint() {
        if (isFinishing || isDestroyed) return
        Snackbar.make(findViewById(android.R.id.content),
            "Notifications are off. You can enable them in Android settings.",
            Snackbar.LENGTH_LONG)
            .setAction("Settings") {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }.show()
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
        if (connectState) {
            connectButton.setImageResource(R.drawable.stat_con_button)
        } else {
            connectButton.setImageResource(R.drawable.stat_dis_button)
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("settings_prefs", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(weatherPreferenceListener)
        startWeatherPolling()
        startConnectionCheck() // Lifecycle owned: stop in onPause, restart onResume.
        val panelPrefs = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        if (panelPrefs.getBoolean("motion_detection_enabled", false)) motionWake?.start()
        try {
            if (checkBluetoothPermission() && !preferenceUtils.fetchDeviceAddress().isNullOrBlank()) {
                ContextCompat.startForegroundService(this, Intent(this, BluetoothReconnectionService::class.java))
            }
            if (panelPrefs.getBoolean("mic_enabled", false) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                ContextCompat.startForegroundService(this, Intent(this, AudioReactiveService::class.java))
            }
        } catch (e: Exception) { Log.w("MainActivity", "Could not start control services", e) }


        if (checkBluetoothPermission()) {
            Log.d("MainActivity", "Bluetooth permission is already granted")
        }

        val currentMode = ModeManager.currentMode
        textView.text = "$currentMode Parameters"
        Log.d("MainActivity", "Current mode: ${ModeManager.currentMode}")

        // Treat the controller snapshot as authoritative for all devices.
        // Local preferences are only the disconnected/offline fallback.
        val remote = globalBluetoothManager.liveState.value
        if (remote != null) {
            ModeManager.currentMode = remote.mode
            powerState = remote.power
            currentBrightness = remote.brightness
        } else {
            powerState = preferenceUtils.getPowerState()
            currentBrightness = preferenceUtils.getBrightness()
        }
        updatePowerButtonImage()
        sliderThumb?.let { updateThumbPosition(it, currentBrightness, false) }
        updateModeDisplay()
        if (globalBluetoothManager.isConnected) {
            lifecycleScope.launch { globalBluetoothManager.refreshState() }
        }
        globalBluetoothManager.startConnectionCheck(this)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) motionWake?.wake()
        return super.dispatchTouchEvent(event)
    }

    override fun onPause() {
        getSharedPreferences("settings_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(weatherPreferenceListener)
        stopWeatherPolling()
        motionWake?.stop()
        stopConnectionCheck()
        super.onPause()
        Log.d("MainActivity", "onPause called")
        globalBluetoothManager.stopConnectionCheck()
    }

    override fun onDestroy() {
        stopWeatherPolling()
        stopConnectionCheck() // Must cancel before destroyed Activity can be retained by the Handler.
        handler.removeCallbacksAndMessages(null)
        motionWake?.close()
        super.onDestroy()
        Log.d("MainActivity", "onDestroy called")
        globalBluetoothManager.stopConnectionCheck()
    }

    @SuppressLint("ResourceType")
    private fun showAlarmMenu() {
        // Check if alarms are locked
        val alarmsLocked = preferenceUtils.getBoolean(this, "alarms_locked", false)

        if (alarmsLocked) {
            // Show a toast to inform the user that the alarms menu is locked
            Toast.makeText(
                this,
                "Alarms menu is locked. Please unlock to proceed.",
                Toast.LENGTH_SHORT
            ).show()
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
            "Spawn" -> "SPAWN"
            "Decay" -> "DECAY"
            "Length" -> "LENGTH"
            "Size" -> "SIZE"
            "Collision" -> "COLLISION"
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

    private fun updateLayoutWithModeInfo(modeInfo: ModeInfo) {

        val container = findViewById<FrameLayout>(R.id.dynamicLayoutContainer)

        Log.d(
            "MainActivity",
            "Updating layout for mode: ${modeInfo.name}, Parameters: ${
                modeInfo.parameterNames.joinToString(", ")
            }"
        )

        flexboxLayout.removeAllViews()

        val parameters = modeInfo.parameterNames.map { parameterName: String ->
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
                Log.d(
                    "MainActivity",
                    "Child $i - Tag: ${child.tag}, Visibility: ${child.visibility}"
                )
                val tag = child.tag
                if (tag is String && modeInfo.parameterNames.contains(tag)) {
                    child.visibility = View.VISIBLE
                    Log.d(
                        "MainActivity",
                        "Setting visibility for parameter ${tag}: ${child.visibility}"
                    )
                }
            }
        }
        Log.d(
            "MainActivity",
            "Parameters passed to generateLayout: ${parameters.joinToString(", ") { it.name }}"
        )

        container.invalidate()
        container.requestLayout()

        for (i in modeInfo.parameterNames.indices) {
            Log.d(TAG, "Setting parameter ${modeInfo.parameterNames[i]} to UI component ${i + 1}")
        }

        Log.d("MainActivity", "Layout updated successfully for mode: ${modeInfo.name}")
    }
}