package com.example.ledwallgui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.system.exitProcess
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ledwallgui.ui.MotionDetectionService
import com.example.ledwallgui.ui.alarm.AlarmActivity
import com.example.ledwallgui.ui.modes.modesActivity
import com.example.ledwallgui.ui.settings.ConnectionActivity
import com.example.ledwallgui.ui.settings.settingsActivity


class MainActivity : AppCompatActivity() {
    private var use12HourTimeFormat = true // Initialize with default value ***CHANGE IN SETTINGS***
    private var powerState = false
    private var connectState = false
    private var timeFormatOption: Int = 0 // Initialize with default value
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


        val imageButton1: ImageButton = findViewById(R.id.imageButton1)
        val button2: ImageButton = findViewById(R.id.modesbutton)
        val imageButton2: ImageButton = findViewById(R.id.imageButton2)
        val imageButton3: ImageButton = findViewById(R.id.imageButton3)

        imageButton1.setOnClickListener {
            powerState = !powerState
            Log.d("PowerState", "Power state clicked: $powerState")
            updatePowerButtonImage()
            saveSharedPreferences()
        }

        button2.setOnClickListener {
            if (connectState) {
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
        if (connectState) {
            connectButton.setImageResource(R.drawable.stat_con_button)
        } else {
            connectButton.setImageResource(R.drawable.stat_dis_button)
        }

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