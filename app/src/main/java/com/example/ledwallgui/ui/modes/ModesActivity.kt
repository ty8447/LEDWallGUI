package com.example.ledwallgui.ui.modes

import android.app.Activity
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
import android.content.Intent
import android.view.View
import android.widget.Button
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.google.gson.Gson

object ModeManager {
    var currentMode: String = "" // Store the active mode
    private val modeParameters: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    fun setModeParameter(mode: String, parameter: String, value: String) {
        if (!modeParameters.containsKey(mode)) {
            modeParameters[mode] = mutableMapOf()
        }
        modeParameters[mode]?.set(parameter, value)
    }
    fun getModeParameters(mode: String): Map<String, String>? {
        return modeParameters[mode]?.toMap()
    }
}

class ModesActivity : AppCompatActivity() {
    private lateinit var globalBluetoothManager: GlobalBluetoothManager
    private lateinit var prefs: PreferenceUtils

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, // Hide the status bar
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_mode_menu)
        prefs = PreferenceUtils(this)
        globalBluetoothManager = GlobalBluetoothManager(this)
        hideSystemUI()

        val backButton: ImageButton = findViewById(R.id.mBackButton)
        val modesContainer: GridLayout = findViewById(R.id.modesContainer)

        // Check for record mode extra
        val recordLedState = intent.getBooleanExtra("record_led_state", false)
        if (recordLedState) {
            Log.d("ModesActivity", "onCreate: recordLedState is true")
            // Show the overlay and done button.
            val recordOverlay = findViewById<View>(R.id.recordOverlay)
            val recordDoneButton = findViewById<Button>(R.id.recordDoneButton)
            recordOverlay.visibility = View.VISIBLE
            recordDoneButton.visibility = View.VISIBLE

            // Hide navigation buttons to avoid loops.
            backButton.visibility = View.GONE

            // Set the done button click listener.
            recordDoneButton.setOnClickListener {
                val currentMode = ModeManager.currentMode
                val modeInfo = globalBluetoothManager.getModeInfo(currentMode)

                if (modeInfo != null) {
                    val ledParametersMap = mutableMapOf<String, String>()

                    // Store Mode Name
                    ledParametersMap["Name"] = currentMode

                    // Store Parameter Names and Values
                    modeInfo.parameterNames.forEach { parameterName ->
                        val parameterValue = modeInfo.parameterValues[parameterName] // Get the value

                        if (parameterValue != null) { // Check if the value exists
                            ledParametersMap[parameterName] = parameterValue // Store name and value
                        } else {
                            Log.w("RecordMode", "Parameter value not found for: $parameterName in mode: $currentMode")
                        }
                    }

                    val gson = Gson()
                    val ledParametersJson = gson.toJson(ledParametersMap)

                    val resultIntent = Intent()
                    resultIntent.putExtra("led_parameters", ledParametersJson)
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                } else {
                    Log.e("RecordMode", "No mode info found for current mode: $currentMode")
                    Toast.makeText(this, "No LED parameters available", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.d("ModesActivity", "onCreate: recordLedState is false")
        }

        // Retrieve mode info list from SharedPreferences
        val modeInfoList = prefs.getModeInfoList()

        if (modeInfoList.isEmpty()) {
            Log.w("ModesActivity", "No modes available in SharedPreferences")
        } else {
            Log.d("ModesActivity", "Mode Names: ${modeInfoList.joinToString(", ") { it.name }}")
        }

        // Dynamically create buttons based on the mode names.
        modeInfoList.forEach { modeInfo ->
            val button = AppCompatButton(this).apply {
                text = modeInfo.name   // Use mode name from ModeInfo.
                textSize = 20f
                isEnabled = true
                background = ContextCompat.getDrawable(this@ModesActivity, R.drawable.mode_button_shape)
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    // Log the parameters that will be made visible.
                    Log.d(
                        "ModesActivity",
                        "Mode button clicked for '${modeInfo.name}'. " +
                                "Parameter Names: ${modeInfo.parameterNames.joinToString(", ")}. " +
                                "Current Values: ${modeInfo.parameterValues}"
                    )

                    ModeManager.currentMode = modeInfo.name
                    globalBluetoothManager.setLedMode(modeInfo.name)
                    Toast.makeText(this@ModesActivity, "Mode changed to ${modeInfo.name}", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                setMargins(8, 8, 8, 8)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
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
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
