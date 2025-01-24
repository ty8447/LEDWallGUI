package com.example.ledwallgui.ui.settings

import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.View
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
import com.example.ledwallgui.ui.MotionDetectionService
import com.google.android.material.snackbar.Snackbar

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
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION_PERMISSION)
        } else {
            toggleLocation() // Permission already granted, toggle location
        }
    }

    private fun requestCameraPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            !Settings.System.canWrite(this)) { // Check WRITE_SETTINGS permission
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            toggleMotionDetection() // Permission already granted, toggle motion detection
        }
    }

    private fun toggleMicrophone() {
        micEnabled = !micEnabled
        // ... (Implement microphone control logic here using audio APIs) ...
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
        // ... (Implement location control logic here using location APIs) ...
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
        // ... (Implement motion detection control logic here using camera APIs) ...

        if (motionDetectionEnabled) {
            val intent = Intent(this, MotionDetectionService::class.java)
            intent.putExtra("source", "SettingsActivity") // Add extra data
            startService(intent)
            Log.d("Motion Detection", "Detecting Motion")
        } else {
            val intent = Intent(this, MotionDetectionService::class.java)
            intent.putExtra("source", "SettingsActivity") // Add extra data
            stopService(intent)
            Log.d("Motion Detection", "Not Detecting Motion")
        }

        // Update button text and background
        motionButton.text = if (motionDetectionEnabled) {
            "Motion Detection\n\nOn"
        } else {
            "Motion Detection\n\nOff"
        }
        motionButton.setBackgroundResource(
            if (motionDetectionEnabled) {
                R.drawable.settings_button_background_enabled
            } else {
                R.drawable.settings_button_background
            }
        )
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
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleLocation() // Permission granted, toggle location
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

        window.attributes = window.attributes.also {
            it.screenBrightness = 0f
        }
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
        val timeButton: Button = findViewById(R.id.timebutton)
        val connectButton = findViewById<AppCompatButton>(R.id.connectButton)
        val resetButton: Button = findViewById(R.id.resetButton)
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

        fun showRevokeSuccessSnackBar() {
            snackBar?.dismiss()
            snackBar = Snackbar.make(
                findViewById<View>(android.R.id.content).rootView,
                "App has been reset! Restart app to see the change.",
                Snackbar.LENGTH_INDEFINITE,
            ).apply {
                setAction("Quit App") {
                    finishAffinity()
                    Process.killProcess(Process.myPid())
                }
                // Get the Snackbar's TextView
                val textView = view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                // Set the text color to white
                textView.setTextColor(Color.WHITE)
            }
            snackBar?.show()
        }

        resetButton.setOnClickListener {
            // Create confirmation popup
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Reset Application")
            builder.setMessage("Are you sure you want to reset the application? This will wipe all data and settings.")
            builder.setPositiveButton("Yes, Reset Application") { dialog: DialogInterface, which: Int ->
                // Wipe data
                val prefs = getSharedPreferences()
                prefs.edit().clear().apply() // Clear Shared Preferences
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
                            showRevokeSuccessSnackBar()
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


        if (!checkMicrophonePermission()) {
            micButton.text = "Mic\n\nRequest Permission"
        } else {
            micButton.text = if (micEnabled) {
                "Mic\n\nOn"
            } else {
                "Mic\n\nOff"
            }
        }

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

        lockConfigButton.text = if (lockConfigEnabled) {
            "Lock Config\n\nOn"
        } else {
            "Lock Config\n\nOff"
        }

        connectButton.setOnClickListener{
            val intent = Intent(this, ConnectionActivity::class.java)
            Log.d("Settings Activity", "Connection Button Pressed")
            startActivity(intent)
        }

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

        lockConfigButton.setOnClickListener {
            lockConfigEnabled = !lockConfigEnabled
            lockConfigButton.text = if (lockConfigEnabled) {
                "Lock Config\n\nOn"
            } else {
                "Lock Config\n\nOff"
            }
            lockConfigButton.setBackgroundResource(
            if (lockConfigEnabled){
                R.drawable.settings_button_background_enabled
            } else {
                R.drawable.settings_button_background
            }
            )
            val editor = prefs.edit()
            editor.putBoolean("lock_config_enabled", lockConfigEnabled)
            editor.apply()
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
            val currentCameraLensFacing = prefs.getInt("currentCameraSelector", CameraSelector.LENS_FACING_BACK)

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

            Toast.makeText(this, "Flipped Motion Camera to $cameraFacingString", Toast.LENGTH_SHORT).show()
            sendBroadcast(intent)
            true // Consume the long click event
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

        locationButton.setOnClickListener {
            if (!checkLocationPermission()) {
                requestLocationPermission() // Request permission if not granted
            } else {
                toggleLocation() // Toggle location if permission is granted
            }
            val editor = prefs.edit()
            editor.putBoolean("location_enabled", locationEnabled)
            editor.apply()
        }

        backButton.setOnClickListener {
            finish()
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
        val timeButton: Button = findViewById(R.id.timebutton)
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