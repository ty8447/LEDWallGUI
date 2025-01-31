package com.example.ledwallgui.ui.modes

import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager

object ModeManager {
    var currentMode: String = "" // Store the active mode

    // Map to store parameters for each mode (e.g., "Color" -> {"Color" -> "255,0,0", "Brightness" -> "100"})
    private val modeParameters: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    // Set a parameter for the current mode
    fun setModeParameter(mode: String, parameter: String, value: String) {
        if (!modeParameters.containsKey(mode)) {
            modeParameters[mode] = mutableMapOf()
        }
        modeParameters[mode]?.set(parameter, value)
    }

    // Retrieve a parameter for the current mode
    fun getModeParameter(mode: String, parameter: String): String? {
        return modeParameters[mode]?.get(parameter)
    }
}

class ModesActivity : AppCompatActivity() {

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

        val modeInfoList = GlobalBluetoothManager.modeInfoList // Fetch from GlobalBluetoothManager

        if (modeInfoList.isEmpty()) {
            Log.w("ModesActivity", "No modes available in GlobalBluetoothManager")
        } else {
            Log.d("ModesActivity", "Mode Names: ${modeInfoList.joinToString(", ") { it.name }}")
        }

            // Dynamically create buttons based on the mode names
        modeInfoList.forEach { modeInfo ->
            val button = AppCompatButton(this).apply {
                text = modeInfo.name   // Use mode name from ModeInfo
                textSize = 20f
                isEnabled = true
                background = ContextCompat.getDrawable(this@ModesActivity, R.drawable.mode_button_shape)
                setPadding(16, 16, 16, 16) // Padding (left, top, right, bottom)

                setOnClickListener {
                    // Use modeInfo.name for the current mode
                    ModeManager.currentMode = modeInfo.name
                    GlobalBluetoothManager.setLedMode(modeInfo.name)

                    // Show toast notification
                    Toast.makeText(this@ModesActivity, "Mode changed to ${modeInfo.name}", Toast.LENGTH_SHORT).show()

                    // Finish the activity to return to MainActivity
                    setResult(RESULT_OK)
                    finish()
                }
            }

            val params = GridLayout.LayoutParams().apply {
                // Set width and height to match parent with equal weight
                width = 0
                height = 0

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
        finish()
    }

    private fun hideSystemUI() {
        // Use WindowInsetsController for Android 11+
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars()) // Hides status and navigation bars
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE // Allows bars to reappear with a swipe
        }
    }
}