package com.example.ledwallgui.ui.settings

import android.app.Activity
import android.Manifest
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ledwallgui.R
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.TimeMonitoringService
import com.example.ledwallgui.ui.AudioReactiveService
import com.example.ledwallgui.ui.TabletMotionWake
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class settingsActivity : AppCompatActivity() {
    private lateinit var micButton: AppCompatButton
    private lateinit var locationButton: AppCompatButton
    private lateinit var motionButton: AppCompatButton
    private var snackBar: Snackbar? = null
    private var micEnabled = false
    private var lockConfigEnabled = false
    private var motionDetectionEnabled = false
    private var locationEnabled = false
    private var timeFormatOption = 0 // 0: Match Device, 1: 12 Hour, 2: 24 Hour
    private val timeFormatOptions = arrayOf("Match Device", "12 Hour", "24 Hour")
    val actionFlipCamera = "com.example.ledwallgui.FLIP_CAMERA"

    private fun checkMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkLocationPermission(): Boolean {
        // Approximate location is sufficient for the weather forecast.
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicrophonePermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE_PERMISSION)
        } else {
            toggleMicrophone() // Permission already granted, toggle microphone
        }
    }

    private fun requestLocationPermission() {
        if (!checkLocationPermission()) {
            // Request both levels so Android 12+ can offer approximate location.
            // Don't show any success/denial Toast before Android returns a result.
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION_PERMISSION)
        } else {
            toggleLocation()
        }
    }

    private fun requestCameraPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            toggleMotionDetection() // Permission already granted, toggle motion detection
        }
    }

    private fun toggleMicrophone() {
        micEnabled = !micEnabled
        getSharedPreferences().edit().putBoolean("mic_enabled", micEnabled).apply()
        val audioIntent = Intent(this, AudioReactiveService::class.java)
        if (micEnabled) audioIntent.action = "com.example.ledwallgui.ACTIVATE_AUDIO"
        if (micEnabled) {
            try {
                ContextCompat.startForegroundService(this, audioIntent)
                Toast.makeText(this, "Microphone driving Audio reactive LED mode", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                micEnabled = false
                getSharedPreferences().edit().putBoolean("mic_enabled", false).apply()
                Toast.makeText(this, "Cannot start microphone: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else stopService(audioIntent)
        // Update button text and background
        micButton.text = if (micEnabled) {
            "Mic\n\nOn"
        } else {
            "Mic\n\nOff"
        }
        micButton.setBackgroundResource(
            if (micEnabled) {
                R.drawable.settings_button_background_enabled
            } else {
                R.drawable.settings_button_background
            }
        )
    }

    private fun toggleLocation() {
        locationEnabled = !locationEnabled
        getSharedPreferences().edit().putBoolean("location_enabled", locationEnabled).apply()
        Toast.makeText(this, if (locationEnabled)
            "Location enabled for Weather mode"
            else "Location disabled", Toast.LENGTH_LONG).show()
        // Update button text and background
        locationButton.text = if (locationEnabled) {
            "Location\n\nOn"
        } else {
            "Location\n\nOff"
        }
        locationButton.setBackgroundResource(
            if (locationEnabled) {
                R.drawable.settings_button_background_enabled
            } else {
                R.drawable.settings_button_background
            }
        )
    }

    private fun toggleMotionDetection() {
        motionDetectionEnabled = !motionDetectionEnabled
        getSharedPreferences().edit().putBoolean("motion_detection_enabled", motionDetectionEnabled).apply()
        motionButton.text = if (motionDetectionEnabled) "Motion Detection\n\nOn" else "Motion Detection\n\nOff"
        motionButton.setBackgroundResource(if (motionDetectionEnabled)
            R.drawable.settings_button_background_enabled else R.drawable.settings_button_background)
        Toast.makeText(this, if (motionDetectionEnabled)
            "Camera motion dimming starts on the main panel" else "Motion dimming disabled", Toast.LENGTH_SHORT).show()
    }
    override fun onResume(){
        super.onResume()
        setupButtons()
    }

    private fun showDevInfoPopup() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.popup_dev_info)
        dialog.setCancelable(true)

        // Set dialog width to 60% of the screen width
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.6).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // Rounded corners

        // Get UI elements
        val versionText = dialog.findViewById<TextView>(R.id.versionText)
        val permissionsText = dialog.findViewById<TextView>(R.id.permissionsText)
        val servicesText = dialog.findViewById<TextView>(R.id.servicesText)
        val dateTimeText = dialog.findViewById<TextView>(R.id.currentDateTime)
        val closeButton = dialog.findViewById<Button>(R.id.closeButton)

        // Get app version info
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName
        versionText.text = "App Version: $versionName"

        // Get granted permissions
        val grantedPermissions = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.filter { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
            ?.joinToString("\n") ?: "None"
        permissionsText.text = "Granted Permissions:\n$grantedPermissions"

        // Get running services
        servicesText.text = "Running Services:\n" + getRunningServices()

        // Auto-update system time every second
        val handler = Handler(Looper.getMainLooper())
        val updateTimeRunnable = object : Runnable {
            override fun run() {
                dateTimeText.text = "System Time: ${getCurrentTime()}"
                handler.postDelayed(this, 1000) // Update every second
            }
        }
        handler.post(updateTimeRunnable)
        // Also stop the one-second runnable on Back/outside-tap or Activity destruction.
        dialog.setOnDismissListener { handler.removeCallbacks(updateTimeRunnable) }

        // Close button listener
        closeButton.setOnClickListener {
            handler.removeCallbacks(updateTimeRunnable)
            dialog.dismiss()
        }

        dialog.show()
    }

    // Get current system time
    private fun getCurrentTime(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
    }

    // Get list of running services
    private fun getRunningServices(): String {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = activityManager.getRunningServices(Int.MAX_VALUE)
        return if (services.isNotEmpty()) {
            services.joinToString("\n") { it.service.className }
        } else {
            "No active services"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleMicrophone() // Permission granted, toggle microphone
            } else {
                // Permission denied, handle accordingly (e.g., show a message)
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (checkLocationPermission()) {
                toggleLocation() // Fine OR approximate location is sufficient.
            } else {
                // Permission denied, handle accordingly (e.g., show a message)
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleMotionDetection()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getSharedPreferences(): SharedPreferences {
        return getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, // Hide the status bar
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_settings_menu)
        hideSystemUI()
        micButton = findViewById(R.id.micButton)
        locationButton = findViewById(R.id.locationButton)
        motionButton = findViewById(R.id.motionButton)
        timeFormatOption = intent.getIntExtra("timeFormatOption", 0)
        val backButton: ImageButton = findViewById(R.id.sBackButton)
        val lockConfigButton = findViewById<AppCompatButton>(R.id.lockConfigButton)
        val motionButton = findViewById<AppCompatButton>(R.id.motionButton)
        val locationButton = findViewById<AppCompatButton>(R.id.locationButton)
        val timeButton: Button = findViewById(R.id.timeButton)
        val syncButton: Button = findViewById(R.id.syncButton)
        val displayButton: Button = findViewById(R.id.displayButton)
        val connectButton = findViewById<AppCompatButton>(R.id.connectButton)
        val resetButton: Button = findViewById(R.id.resetButton)
        val textView2: TextView = findViewById(R.id.textView2)
        val prefs = getSharedPreferences()
        micEnabled = prefs.getBoolean("mic_enabled", false)
        lockConfigEnabled = prefs.getBoolean("lock_config_enabled", false)
        motionDetectionEnabled = prefs.getBoolean("motion_detection_enabled", false)
        locationEnabled = prefs.getBoolean("location_enabled", false)
        timeFormatOption = prefs.getInt("time_format_option", 0)

        micButton.setBackgroundResource(if (micEnabled) R.drawable.settings_button_background_enabled else R.drawable.settings_button_background)
        lockConfigButton.setBackgroundResource(if (lockConfigEnabled) R.drawable.settings_button_background_enabled else R.drawable.settings_button_background)
        motionButton.setBackgroundResource(if (motionDetectionEnabled) R.drawable.settings_button_background_enabled else R.drawable.settings_button_background)
        locationButton.setBackgroundResource(if (locationEnabled) R.drawable.settings_button_background_enabled else R.drawable.settings_button_background)

        updateTimeButtonState()

        // onResume binds the Settings controls once, avoiding redundant setup on startup.

        textView2.setOnLongClickListener {
            showDevInfoPopup()
            true // Consume the event
        }

        lockConfigButton.setOnClickListener {
            // Create a dialog for the popup
            val popupDialog = Dialog(this)
            popupDialog.setContentView(R.layout.popup_lock_config) // Use a custom layout
            popupDialog.setCancelable(true) // Allow dismissing by clicking outside

            // Access the buttons from the dialog layout
            val lockUnlockLEDConfigButton = popupDialog.findViewById<Button>(R.id.lockUnlockLEDConfigButton)
            val lockUnlockSettingsMenuButton = popupDialog.findViewById<Button>(R.id.lockUnlockSettingsMenuButton)
            val lockUnlockAlarmsButton = popupDialog.findViewById<Button>(R.id.lockUnlockAlarmsButton)

            // Function to update the button text based on the state
            fun updateButtonText() {
                val preferenceUtils = PreferenceUtils(this)
                val isLEDConfigLocked = preferenceUtils.getBoolean(this, "led_config_locked", false)
                val isSettingsMenuLocked = preferenceUtils.getBoolean(this, "settings_menu_locked", false)
                val isAlarmsLocked = preferenceUtils.getBoolean(this, "alarms_locked", false)

                lockUnlockLEDConfigButton.text = if (isLEDConfigLocked) "Unlock LED Config" else "Lock LED Config"
                lockUnlockSettingsMenuButton.text = if (isSettingsMenuLocked) "Unlock Settings Menu" else "Lock Settings Menu"
                lockUnlockAlarmsButton.text = if (isAlarmsLocked) "Unlock Alarms" else "Lock Alarms"
            }

            // Initially update button text
            updateButtonText()

            // Function to show PIN dialog
            fun showPinDialog(isLockAction: Boolean, actionType: String) {
                val pinDialog = Dialog(this)
                val preferenceUtils = PreferenceUtils(this)
                pinDialog.setContentView(R.layout.popup_pin_entry)
                pinDialog.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.8).toInt(),  // 80% of the screen width
                    (resources.displayMetrics.heightPixels * 0.8).toInt()  // 25% of the screen height
                )
                pinDialog.setCancelable(true)

                val enteredPin = StringBuilder() // To store the entered PIN

                // Access the pinDisplayText TextView to display the PIN
                val pinDisplayText = pinDialog.findViewById<TextView>(R.id.pinDisplayText)

                // Set the default text for pinDisplayText
                if (isLockAction) {
                    pinDisplayText.text = "Set New 4-Digit Pin"
                    Log.d("SettingsActivity", "Setting a new PIN for $actionType")
                } else {
                    pinDisplayText.text = "Enter Set 4-Digit Pin"
                    Log.d("SettingsActivity", "Unlocking $actionType")
                }

                // Set up the keypad buttons
                val numButtons = arrayOf(
                    pinDialog.findViewById<Button>(R.id.num1),
                    pinDialog.findViewById<Button>(R.id.num2),
                    pinDialog.findViewById<Button>(R.id.num3),
                    pinDialog.findViewById<Button>(R.id.num4),
                    pinDialog.findViewById<Button>(R.id.num5),
                    pinDialog.findViewById<Button>(R.id.num6),
                    pinDialog.findViewById<Button>(R.id.num7),
                    pinDialog.findViewById<Button>(R.id.num8),
                    pinDialog.findViewById<Button>(R.id.num9),
                    pinDialog.findViewById<Button>(R.id.num0)
                )

                // Add listeners for each button
                numButtons.forEach { button ->
                    button.setOnClickListener {
                        if (enteredPin.length < 4) { // Limit to 4 digits
                            enteredPin.append(button.text)
                            pinDisplayText.text = enteredPin.toString() // Update the TextView with the entered PIN
                        }
                    }
                }

                // Clear button functionality
                pinDialog.findViewById<Button>(R.id.clearButton).setOnClickListener {
                    enteredPin.clear() // Reset the PIN input
                    pinDisplayText.text = "" // Clear the displayed PIN
                }

                // Submit button functionality
                pinDialog.findViewById<Button>(R.id.pinSubmitButton).setOnClickListener {

                    // Check if the entered PIN is blank
                    if (enteredPin.isNullOrEmpty()) {
                        Toast.makeText(this, "PIN cannot be blank. Please enter a valid PIN.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    // If setting a new PIN for a locked option
                    if (isLockAction) {
                        // Save the new PIN for the button using actionType
                        preferenceUtils.setPIN(this, "${actionType}_pin", enteredPin.toString())
                        Log.d("SettingsActivity", "$actionType PIN set: ${enteredPin.toString()}")
                        setupButtons()
                        // After setting the PIN, lock the button
                        preferenceUtils.setBoolean(this, "${actionType}_locked", true)
                        updateButtonText()  // Update button text to reflect the locked state

                        pinDialog.dismiss()
                    } else {
                        // Verify the entered PIN using actionType
                        val savedPin = preferenceUtils.getPIN(this, "${actionType}_pin", "")
                        if (enteredPin.toString() == savedPin) {
                            // If the PIN matches, toggle the lock state
                            val newState = !preferenceUtils.getBoolean(this, "${actionType}_locked", false)
                            preferenceUtils.setBoolean(this, "${actionType}_locked", newState)

                            // If unlocking, delete the saved PIN
                            if (!newState) {
                                preferenceUtils.remove(this, "${actionType}_pin")
                                Log.d("SettingsActivity", "$actionType PIN removed as it's unlocked.")
                            }

                            // Update the button text immediately
                            updateButtonText()

                            // Force UI refresh
                            lockUnlockLEDConfigButton.invalidate()  // Refresh button view
                            Log.d("SettingsActivity", "$actionType Lock state changed to: $newState")
                            pinDialog.dismiss()
                        } else {
                            // If the PIN does not match, show an error
                            Toast.makeText(this, "Incorrect PIN. Please try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    setupButtons()
                }

                pinDialog.show()
            }

            // Set up button click listeners to toggle states and update preferences
            lockUnlockLEDConfigButton.setOnClickListener {
                val preferenceUtils = PreferenceUtils(this)
                val isLEDConfigLocked = preferenceUtils.getBoolean(this, "led_config_locked", false)
                val existingPin = preferenceUtils.getPIN(this, "led_config_pin", "")

                if (existingPin.isEmpty()) {
                    // If no PIN exists, allow setting a new PIN
                    showPinDialog(isLockAction = true, actionType = "led_config")
                } else {
                    // If a PIN exists, show the PIN entry to unlock
                    if (isLEDConfigLocked) {
                        showPinDialog(isLockAction = false, actionType = "led_config")
                    } else {
                        showPinDialog(isLockAction = true, actionType = "led_config")
                    }
                }
            }

            lockUnlockSettingsMenuButton.setOnClickListener {
                val preferenceUtils = PreferenceUtils(this)
                val isSettingsMenuLocked = preferenceUtils.getBoolean(this, "settings_menu_locked", false)
                val existingPin = preferenceUtils.getPIN(this, "settings_menu_pin", "")

                if (existingPin.isEmpty()) {
                    // If no PIN exists, allow setting a new PIN
                    showPinDialog(isLockAction = true, actionType = "settings_menu")
                } else {
                    // If a PIN exists, show the PIN entry to unlock
                    if (isSettingsMenuLocked) {
                        showPinDialog(isLockAction = false, actionType = "settings_menu")
                    } else {
                        showPinDialog(isLockAction = true, actionType = "settings_menu")
                    }
                }
            }

            lockUnlockAlarmsButton.setOnClickListener {
                val preferenceUtils = PreferenceUtils(this)
                val isAlarmsLocked = preferenceUtils.getBoolean(this, "alarms_locked", false)
                val existingPin = preferenceUtils.getPIN(this, "alarms_pin", "")

                if (existingPin.isEmpty()) {
                    // If no PIN exists, allow setting a new PIN
                    showPinDialog(isLockAction = true, actionType = "alarms")
                } else {
                    // If a PIN exists, show the PIN entry to unlock
                    if (isAlarmsLocked) {
                        showPinDialog(isLockAction = false, actionType = "alarms")
                    } else {
                        showPinDialog(isLockAction = true, actionType = "alarms")
                    }
                }
            }

            // Show the dialog
            popupDialog.show()
        }

        if (!checkMicrophonePermission()) {
            micButton.text = "Mic\n\nRequest Permission"
        } else {
            micButton.text = if (micEnabled) {
                "Mic\n\nOn"
            } else {
                "Mic\n\nOff"
            }
        }

        if (!checkCameraPermission()) {
            motionButton.text = "Motion Detection\n\nRequest Permission"
        } else {
            motionButton.text = if (motionDetectionEnabled) {
                "Motion Detection\n\nOn"
            } else {
                "Motion Detection\n\nOff"
            }
        }

        if (!checkLocationPermission()) {
            locationButton.text = "Location\n\nRequest Permission"
        } else {
            locationButton.text = if (locationEnabled) {
                "Location\n\nOn"
            } else {
                "Location\n\nOff"
            }
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun setupButtons() {
        val motionButton = findViewById<AppCompatButton>(R.id.motionButton)
        val locationButton = findViewById<AppCompatButton>(R.id.locationButton)
        val timeButton: Button = findViewById(R.id.timeButton)
        val syncButton: Button = findViewById(R.id.syncButton)
        val displayButton: Button = findViewById(R.id.displayButton)
        val aboutButton: Button = findViewById(R.id.aboutButton)
        val connectButton = findViewById<AppCompatButton>(R.id.connectButton)
        val resetButton: Button = findViewById(R.id.resetButton)
        val preferenceUtils = PreferenceUtils(this)
        val prefs = getSharedPreferences()

        val isSettingsMenuLocked = preferenceUtils.getBoolean(this, "settings_menu_locked", false)
        if (isSettingsMenuLocked) {
            connectButton.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            connectButton.setOnClickListener(null)
            micButton.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            micButton.setOnClickListener(null)
            timeButton.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            timeButton.setOnClickListener(null)
            locationButton.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            locationButton.setOnClickListener(null)
            syncButton.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            syncButton.setOnClickListener(null)
            displayButton.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            displayButton.setOnClickListener(null)
            aboutButton.setOnClickListener(null)
            motionButton.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            motionButton.setOnClickListener(null)
            motionButton.setOnLongClickListener(null)
            resetButton.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            resetButton.setOnClickListener(null)
            Toast.makeText(this, "Settings Menu is locked. Please unlock to access other settings.", Toast.LENGTH_SHORT).show()
        } else {
            connectButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            connectButton.setOnClickListener{
                val intent = Intent(this, ConnectionActivity::class.java)
                Log.d("Settings Activity", "Connection Button Pressed")
                startActivity(intent)
            }
            micButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            micButton.setOnClickListener {
                if (!checkMicrophonePermission()) {
                    requestMicrophonePermission() // Request permission if not granted
                } else {
                    toggleMicrophone() // Toggle microphone if permission is granted
                }
                val editor = prefs.edit()
                editor.putBoolean("mic_enabled", micEnabled)
                editor.apply()
            }
            timeButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            timeButton.setOnClickListener {
                // Log the current time format before changing
                Log.d(
                    "Settings Activity",
                    "Current time format option before change: $timeFormatOption"
                )

                // Switch the time format option
                timeFormatOption = (timeFormatOption + 1) % timeFormatOptions.size

                // Log the new time format after change
                Log.d("Settings Activity", "New time format option after change: $timeFormatOption")

                // Update the button text
                updateTimeButtonState()
                updateTimeFormatSetting()

                // Pass the new option to the parent activity
                val intent = Intent()
                intent.putExtra("timeFormatOption", timeFormatOption)
                setResult(Activity.RESULT_OK, intent)

                // Save the new time format setting in SharedPreferences
                val editor = prefs.edit()
                editor.putInt("time_format_option", timeFormatOption)
                editor.apply()

                // Log the SharedPreferences update
                Log.d(
                    "Settings Activity",
                    "Time format option saved to SharedPreferences: $timeFormatOption"
                )
            }
            locationButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            locationButton.setOnClickListener {
                if (!checkLocationPermission()) {
                    requestLocationPermission() // Request permission if not granted
                } else {
                    toggleLocation() // Toggle location if permission is granted
                }
                // toggleLocation() saves the preference only after permission succeeds.
            }
            syncButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            syncButton.text = "Configuration"
            syncButton.setOnClickListener {
                startActivity(Intent(this, ControllerSetupActivity::class.java))
            }
            displayButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            displayButton.setOnClickListener { startActivity(Intent(this, DisplaySettingsActivity::class.java)) }
            aboutButton.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
            motionButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            motionButton.setOnClickListener {
                if (!checkCameraPermission()) {
                    requestCameraPermission() // Request permission if not granted
                } else {
                    toggleMotionDetection() // Toggle motion detection if permission is granted
                }
                val editor = prefs.edit()
                editor.putBoolean("motion_detection_enabled", motionDetectionEnabled)
                editor.apply()
            }
            motionButton.setOnLongClickListener {
                Log.d("Settings Activity", "Flip Camera")
                val intent = Intent(actionFlipCamera)

                // Retrieve the current camera selector from shared preferences
                val prefs = getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
                val currentCameraLensFacing = prefs.getInt("currentCameraSelector", CameraSelector.LENS_FACING_FRONT)

                // Determine which camera it will switch to
                val newCameraFacing = if (currentCameraLensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                Log.d("Settings Activity", "Current camera lens facing: $currentCameraLensFacing")
                Log.d("Settings Activity", "New camera facing after flip: $newCameraFacing")

                // Display the new camera facing in the toast message
                val cameraFacingString = if (newCameraFacing == CameraSelector.LENS_FACING_FRONT) {
                    "Front"
                } else {
                    "Back"
                }

                prefs.edit().putInt("currentCameraSelector", newCameraFacing).apply()
                Toast.makeText(this, "Motion camera set to $cameraFacingString (applies when main screen opens)", Toast.LENGTH_SHORT).show()
                true // Consume the long click event
            }
            resetButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            resetButton.setOnClickListener {
                // Create confirmation popup
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Reset Application")
                builder.setMessage("Are you sure you want to reset the application? This will wipe all data and settings.")
                builder.setPositiveButton("Yes, Reset Application") { dialog: DialogInterface, which: Int ->
                    // Wipe data
                    val prefs = getSharedPreferences()
                    prefs.edit().clear().putBoolean("welcome_force_v59", true).commit()
                    // An explicit reset forces the guide even if Android repopulates settings.
                    // Show the onboarding flow after the reset, even before the next app launch.
                    startActivity(Intent(this, com.example.ledwallgui.ui.WelcomeTourActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    // Perform other necessary data wiping actions (e.g., clear databases, delete files)

                    // Stop the TimeMonitoringService
                    val serviceIntent = Intent(this, TimeMonitoringService::class.java)
                    stopService(serviceIntent)

                    // Revoke all granted permissions
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        val packageManager = packageManager
                        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                        val requestedPermissions = packageInfo.requestedPermissions

                        if (requestedPermissions != null) {
                            val permissionsToRevoke = requestedPermissions.filter { permission ->
                                // Exclude permissions that cannot be revoked using revokeSelfPermissionsOnKill()
                                val permissionInfo = packageManager.getPermissionInfo(permission, 0)
                                permissionInfo.group != null && // Check if permission belongs to a group
                                        permission != Manifest.permission.BLUETOOTH && // Exclude Bluetooth
                                        permission != Manifest.permission.BLUETOOTH_ADMIN && // Exclude Bluetooth Admin
                                        permission != Manifest.permission.FOREGROUND_SERVICE // Exclude Foreground Service
                            }.toList()

                            try {
                                revokeSelfPermissionsOnKill(permissionsToRevoke)
//                                showRevokeSuccessSnackBar() // FIX THIS !!!!!!!
                            } catch (e: SecurityException) {
                                // Permission revocation failed
                                Toast.makeText(this, "Failed to remove permissions", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // App has no requested permissions
                            // Handle this case as needed
                        }
                    } else {
                        // Android version is not high enough
                        Toast.makeText(this, "Android version is too low to remove permissions automatically", Toast.LENGTH_SHORT).show()
                    }

                }
                builder.setNegativeButton("No, Go Back") { dialog: DialogInterface, which: Int ->
                    // Dismiss the popup
                    dialog.dismiss()
                }
                builder.show()
            }
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

    private fun updateTimeButtonState() {
        val timeButton: Button = findViewById(R.id.timeButton)
        timeButton.text = "Time Format\n\n" + timeFormatOptions[timeFormatOption]
    }

    private fun updateTimeFormatSetting() {
        val preferenceUtils = PreferenceUtils(this)

        // Determine the 12-hour time format based on timeFormatOption
        val is12HourTime = when (timeFormatOption) {
            1 -> true // 12-hour time format
            2 -> false // 24-hour time format
            0 -> isDeviceTimeIn12HourFormat() // Fetch the device's time format
            else -> preferenceUtils.getIs12HourTimeFormat() // Default to stored value
        }

        // Save the 12-hour time format preference using PreferenceUtils
        preferenceUtils.saveIs12HourTimeFormat(is12HourTime)

        // Log for debugging
        Log.d("Settings Activity", "Time format option saved as 12-hour: $is12HourTime")
    }

    private fun isDeviceTimeIn12HourFormat(): Boolean {
        val timeFormat = android.text.format.DateFormat.is24HourFormat(this)
        return !timeFormat // Returns true if 12-hour format, false if 24-hour format
    }

    companion object {
        // ... (other constants) ...
        private const val REQUEST_MICROPHONE_PERMISSION = 100 // Request code for microphone permission
        private const val REQUEST_LOCATION_PERMISSION = 101 // Request code for location permission
        private const val REQUEST_CAMERA_PERMISSION = 102 // Request code for camera permission
    }
}