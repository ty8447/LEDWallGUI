package com.example.ledwallgui.ui.modes

import android.content.Intent
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.example.ledwallgui.MainActivity
import com.example.ledwallgui.R

object ModeManager {
    var currentMode: String = "" // Global variable to store current mode
}

class modesActivity : AppCompatActivity() {
    val buttonNames = arrayOf("Rainbow","Color","Alternating Colors","Fade","Pulse","Breathe","Paint","Strobe","Time","Cylon","Police","Fire","Twinkle","Running Lights","Theatre Chase")
    val maxButtonSize = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, // Hide the status bar
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_mode_menu)
        hideSystemUI()
        val backButton: ImageButton = findViewById(R.id.mBackButton)

        val buttonIds = Array(maxButtonSize) { index ->
            resources.getIdentifier("mode${index + 1}", "id", packageName)
        }

        for (i in buttonIds.indices) {
            val button = findViewById<AppCompatButton>(buttonIds[i])
            Log.d("ModesActivity","Button: ${button}")
            if (i < minOf(buttonNames.size, buttonIds.size)) {
                button.text = buttonNames[i]
                button.textSize = 20F
                button.isEnabled = true
                button.background = ContextCompat.getDrawable(this, R.drawable.mode_button_shape)
                button.setOnClickListener {
                    ModeManager.currentMode = buttonNames[i]

                    // Show toast notification
                    Toast.makeText(this, "Mode changed to ${buttonNames[i]}", Toast.LENGTH_SHORT).show()

                    // Finish the activity to return to MainActivity
                    finish()
                }
            } else {
                button.text = ""
                button.setBackgroundResource(android.R.color.transparent)
                button.isEnabled = false
                button.setOnClickListener(null) // Disable click event handler
            }
        }

        backButton.setOnClickListener {
            returnHome()
        }
    }

    private fun returnHome() {
        startActivity(Intent(this, MainActivity::class.java))
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
}