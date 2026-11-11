package com.example.ledwallgui.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.TabletMotionWake

/** Display preferences live on the tablet, not in controller flash. */
class DisplaySettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_settings)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        findViewById<ImageButton>(R.id.displayBackButton).setOnClickListener { finish() }

        val choices = TabletMotionWake.DIM_CHOICES_SECONDS
        val labels = choices.map { seconds ->
            if (seconds < 60) "$seconds seconds"
            else "${seconds / 60} minute${if (seconds == 60) "" else "s"}"
        }
        val picker = findViewById<Spinner>(R.id.dimTimeoutSpinner)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(Color.WHITE)
                    it.setPadding(18, 8, 18, 8)
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(Color.WHITE)
                    it.setBackgroundColor(LedSettingsUi.PANEL)
                }
        }
        picker.adapter = adapter
        picker.setPopupBackgroundDrawable(ColorDrawable(LedSettingsUi.PANEL))
        val prefs = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val current = TabletMotionWake.timeoutSeconds(this)
        picker.setSelection(choices.indexOf(current).coerceAtLeast(0), false)
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(TabletMotionWake.PREF_DIM_TIMEOUT_SECONDS, choices[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val unitPicker = findViewById<Spinner>(R.id.roomUnitsSpinner)
        unitPicker.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, listOf("Meters (m)", "Feet (ft)")) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as? TextView)?.setTextColor(Color.WHITE) }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(Color.WHITE); it.setBackgroundColor(LedSettingsUi.PANEL)
                }
        }
        unitPicker.setPopupBackgroundDrawable(ColorDrawable(LedSettingsUi.PANEL))
        unitPicker.setSelection(if (DisplayUnits.feet(this)) 1 else 0, false)
        unitPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                DisplayUnits.set(this@DisplaySettingsActivity, position == 1)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        findViewById<TextView>(R.id.displayMotionStatus).text =
            if (prefs.getBoolean("motion_detection_enabled", false))
                "Camera motion wake is enabled. The timeout takes effect when the inactivity timer resets."
            else "Motion detection is disabled. Enable it from Settings to wake the screen with camera motion."
    }
}
