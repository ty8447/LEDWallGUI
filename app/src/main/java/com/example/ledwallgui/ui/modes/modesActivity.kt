package com.example.ledwallgui.ui.modes

import android.content.Intent
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.example.ledwallgui.MainActivity
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager

object ModeManager {
    var currentMode: String = "" // Global variable to store current mode
}

class modesActivity : AppCompatActivity() {
//    val buttonNames = arrayOf("Rainbow","Color","Alternating Colors","Fade","Pulse","Breathe","Paint","Strobe","Time","Cylon","Police","Fire","Twinkle","Running Lights","Theatre Chase")
//    val maxButtonSize = 20
//    private lateinit var buttonIds: Array<Int>

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
        val modesContainer: GridLayout = findViewById(R.id.modesContainer)

        // Get mode names from GlobalBluetoothManager
        val modeNames = GlobalBluetoothManager.modeNames

        Log.d("ModesActivity", "Mode Names: ${modeNames.joinToString(", ")}")

        // Dynamically create buttons
        modeNames.forEach { modeName ->
            val button = AppCompatButton(this).apply {
                text = modeName
                textSize = 20f
                isEnabled = true
                minimumWidth = 100
                minimumHeight = 100
                background = ContextCompat.getDrawable(this@modesActivity, R.drawable.mode_button_shape)
                setPadding(16, 16, 16, 16) // Padding (left, top, right, bottom)

                setOnClickListener {
                    ModeManager.currentMode = modeName
                    GlobalBluetoothManager.setLedMode(modeName)

                    // Show toast notification
                    Toast.makeText(this@modesActivity, "Mode changed to $modeName", Toast.LENGTH_SHORT).show()

                    // Finish the activity to return to MainActivity
                    setResult(RESULT_OK)
                    finish()
                }
            }

            val params = GridLayout.LayoutParams().apply {
                // Set width and height to match parent with equal weight
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT

                // Specify margins for the button (outside padding)
                setMargins(8, 8, 8, 8) // Margins (left, top, right, bottom)

                // Align to grid position (1f weight to make buttons equally spaced in the grid)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }

            // Add the button to the container
            modesContainer.addView(button, params)
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