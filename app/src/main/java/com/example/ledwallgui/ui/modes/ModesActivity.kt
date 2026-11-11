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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.ledwallgui.ui.WeatherUpdater

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
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        modesContainer.columnCount = if (portrait) 2 else 5
        findViewById<Button>(R.id.animationStudioButton).setOnClickListener {
            startActivity(Intent(this, AnimationStudioActivity::class.java))
        }

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
        modeInfoList.filterNot { it.name == "Custom Animation" }.forEach { modeInfo ->
            val button = AppCompatButton(this).apply {
                text = modeInfo.name   // Use mode name from ModeInfo.
                textSize = 20f
                isEnabled = true
                background = ContextCompat.getDrawable(this@ModesActivity, R.drawable.mode_button_shape)
                setPadding(16, 16, 16, 16)
                setOnClickListener { selectedButton ->
                    // Log the parameters that will be made visible.
                    Log.d(
                        "ModesActivity",
                        "Mode button clicked for '${modeInfo.name}'. " +
                                "Parameter Names: ${modeInfo.parameterNames.joinToString(", ")}. " +
                                "Current Values: ${modeInfo.parameterValues}"
                    )

                    if (!globalBluetoothManager.isConnected) {
                        Toast.makeText(this@ModesActivity, "Connect to the LED controller first", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    selectedButton.isEnabled = false
                    lifecycleScope.launch {
                        // Wait for the Arduino's acknowledgement rather than displaying
                        // a mode that may have been rejected or overwritten elsewhere.
                        val answer = globalBluetoothManager.controlAndAwait("Mode", "None", modeInfo.name)
                        if (answer == "OK") {
                            val latest = globalBluetoothManager.refreshState()
                            ModeManager.currentMode = latest?.mode ?: modeInfo.name
                            if (ModeManager.currentMode == "Weather") {
                                WeatherUpdater(applicationContext, globalBluetoothManager).refreshNow()
                            }
                            setResult(RESULT_OK)
                            finish()
                        } else {
                            selectedButton.isEnabled = true
                            Toast.makeText(this@ModesActivity,
                                "Controller did not accept mode change", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = if (portrait) ((resources.displayMetrics.widthPixels - 48 * resources.displayMetrics.density) / 2).toInt() else 0
                height = if (portrait) (82 * resources.displayMetrics.density).toInt() else 0
                setMargins(8, 8, 8, 8)
                columnSpec = if (portrait) GridLayout.spec(GridLayout.UNDEFINED) else GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = if (portrait) GridLayout.spec(GridLayout.UNDEFINED) else GridLayout.spec(GridLayout.UNDEFINED, 1f)
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
