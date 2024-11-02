package com.example.ledwallgui.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import com.example.ledwallgui.R
import android.os.Looper
import android.widget.ImageView
import com.example.ledwallgui.MainActivity

class SplashScreenActivity : AppCompatActivity() {
    private val splashDelay: Long = 2000 // 2 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_screen)

        val firstIcon = findViewById<ImageView>(R.id.first_icon)

        // Delay for splashDelay milliseconds before moving to the next activity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this@SplashScreenActivity, MainActivity::class.java))
            finish()
        }, splashDelay)
    }
}
