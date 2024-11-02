package com.example.ledwallgui.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.example.ledwallgui.MainActivity
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.alarm.AlarmActivity
import com.example.ledwallgui.ui.alarm.alarmEditorActivity

class settingsActivity : AppCompatActivity() {
    private var micEnabled = false
    private var lockConfigEnabled = false
    private var motionDetectionEnabled = false
    private var locationEnabled = false
    private var timeFormatOption = 0 // 0: Match Device, 1: 12 Hour, 2: 24 Hour
    private val timeFormatOptions = arrayOf("Match Device", "12 Hour", "24 Hour")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, // Hide the status bar
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_settings_menu)
        hideSystemUI()

        timeFormatOption = intent.getIntExtra("timeFormatOption", 0)
        val backButton: ImageButton = findViewById(R.id.sBackButton)
        val micButton = findViewById<AppCompatButton>(R.id.micButton)
        val lockConfigButton = findViewById<AppCompatButton>(R.id.lockConfigButton)
        val motionButton = findViewById<AppCompatButton>(R.id.motionButton)
        val locationButton = findViewById<AppCompatButton>(R.id.locationButton)
        val timeButton: Button = findViewById(R.id.timebutton)
        val connectButton = findViewById<AppCompatButton>(R.id.connectButton)

        updateTimeButtonState()

        micButton.text = if (micEnabled) {
            "Mic\n\nOn"
        } else {
            "Mic\n\nOff"
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
        }

        micButton.setOnClickListener {
            micEnabled = !micEnabled
            micButton.text = if (micEnabled) {
                "Mic\n\nOn"
            } else {
                "Mic\n\nOff"
            }
            micButton.setBackgroundResource(
            if (micEnabled){
            R.drawable.settings_button_background_enabled
            } else {
            R.drawable.settings_button_background
            }
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
}