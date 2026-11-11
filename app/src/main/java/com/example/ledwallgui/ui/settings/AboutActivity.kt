package com.example.ledwallgui.ui.settings

import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.ledwallgui.R

/** Stable version information; no timers, polling or BLE connections. */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<ImageButton>(R.id.aboutBackButton).setOnClickListener { finish() }
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val buildNumber = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        findViewById<TextView>(R.id.aboutVersion).text = "Version ${info.versionName ?: "unknown"}  •  Build $buildNumber"
        findViewById<TextView>(R.id.aboutDevice).text = "Android ${Build.VERSION.RELEASE}  •  API ${Build.VERSION.SDK_INT}"
        findViewById<TextView>(R.id.aboutPackage).text = packageName
    }
}
