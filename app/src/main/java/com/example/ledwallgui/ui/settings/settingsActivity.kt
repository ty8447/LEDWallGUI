package com.example.ledwallgui.ui.settings

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.text
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ledwallgui.MainActivity
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.alarm.AlarmActivity
import com.example.ledwallgui.ui.alarm.alarmEditorActivity
import androidx.appcompat.app.AlertDialog

class settingsActivity : AppCompatActivity() {
    private lateinit var micButton: AppCompatButton
    private var micEnabled = false
    private var lockConfigEnabled = false
    private var motionDetectionEnabled = false
    private var locationEnabled = false
    private var timeFormatOption = 0 // 0: Match Device, 1: 12 Hour, 2: 24 Hour
    private val timeFormatOptions = arrayOf("Match Device", "12 Hour", "24 Hour")

    private fun checkMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicrophonePermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE_PERMISSION)
        } else {
            toggleMicrophone() // Permission already granted, toggle microphone
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleMicrophone() // Permission granted, toggle microphone
            } else {
                // Permission denied, handle accordingly (e.g., show a message)
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
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

                // Revoke all granted permissions
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val packageManager = packageManager
                    val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                    val requestedPermissions = packageInfo.requestedPermissions
                    if (requestedPermissions != null) {
                        // Convert Array<String> to List<String>
                        val permissionsList = requestedPermissions.toList()
                        revokeSelfPermissionsOnKill(permissionsList)
                    }
                }

                // Restart app
                val intent = Intent(this, MainActivity::class.java) // Replace MainActivity with your main activity class
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
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

        locationButton.text = if (locationEnabled) {
            "Location\n\nOn"
        } else {
            "Location\n\nOff"
        }

        motionButton.text = if (motionDetectionEnabled) {
            "Motion Detection\n\nOn"
        } else {
            "Motion Detection\n\nOff"
        }
        connectButton.setOnClickListener{
            val intent = Intent(this, ConnectionActivity::class.java)
            Log.d("Settings Activity", "Connection Button Pressed")
            startActivity(intent)
        }

        timeButton.setOnClickListener {
            timeFormatOption = (timeFormatOption + 1) % timeFormatOptions.size
            updateTimeButtonState()
            val intent = Intent()
            intent.putExtra("timeFormatOption", timeFormatOption)
            setResult(Activity.RESULT_OK, intent)

            val editor = prefs.edit()
            editor.putInt("time_format_option", timeFormatOption)
            editor.apply()
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

        motionButton.setOnClickListener {
            motionDetectionEnabled = !motionDetectionEnabled
            motionButton.text = if (motionDetectionEnabled) {
                "Motion Detection\n\nOn"
            } else {
                "Motion Detection\n\nOff"
            }
            motionButton.setBackgroundResource(
            if (motionDetectionEnabled){
                R.drawable.settings_button_background_enabled
            } else {
                R.drawable.settings_button_background
            }
            )
            val editor = prefs.edit()
            editor.putBoolean("motion_detection_enabled", motionDetectionEnabled)
            editor.apply()
        }

        locationButton.setOnClickListener {
            locationEnabled = !locationEnabled
            locationButton.text = if (locationEnabled) {
                "Location\n\nOn"
            } else {
                "Location\n\nOff"
            }
            locationButton.setBackgroundResource(
            if (locationEnabled){
                R.drawable.settings_button_background_enabled
            } else {
                R.drawable.settings_button_background
            }
            )
            val editor = prefs.edit()
            editor.putBoolean("location_enabled", locationEnabled)
            editor.apply()
        }

        backButton.setOnClickListener {
            finish()
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

    private fun updateTimeButtonState() {
        val timeButton: Button = findViewById(R.id.timebutton)
        timeButton.text = "Time Format\n\n" + timeFormatOptions[timeFormatOption]
    }

    companion object {
        // ... (other constants) ...
        private const val REQUEST_MICROPHONE_PERMISSION = 100 // Request code for microphone permission
    }
}