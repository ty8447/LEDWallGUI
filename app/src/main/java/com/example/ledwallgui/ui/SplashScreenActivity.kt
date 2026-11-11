package com.example.ledwallgui.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.ledwallgui.MainActivity
import com.example.ledwallgui.R

class SplashScreenActivity : AppCompatActivity() {
    private val splashDelay: Long = 3000 // 5 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_screen)

        val splashIcon = findViewById<ImageView>(R.id.splash_icon)
        val rootLayout = findViewById<RelativeLayout>(R.id.splash_root)

        // Set initial icon to off_icon
        splashIcon.setImageResource(R.drawable.off_icon)

        // Create a scale animation for the "button press" effect
        val scaleDownAnimation = ScaleAnimation(
            1f, 0.85f, // Shrink slightly
            1f, 0.85f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 400
            fillAfter = true
            interpolator = AccelerateDecelerateInterpolator()
        }
        // Create a scale animation for the "button press" effect
        val scaleUpAnimation = ScaleAnimation(
            0.85f, 1f, // Shrink slightly
            0.85f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 400
            fillAfter = true
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Create a rotation animation (360° spin)
        val rotateAnimation = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 800 // Increased duration for a more noticeable spin
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Set up animation listeners
        scaleDownAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                // Start the rotate animation after the scale animation ends
                splashIcon.startAnimation(scaleUpAnimation)
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })
        scaleUpAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                // Start the rotate animation after the scale animation ends
                splashIcon.startAnimation(rotateAnimation)
                // Gradually brighten the background (simulate lights turning on)
                rootLayout.animate()
                    .alpha(1f) // Full brightness
                    .setDuration(800)
                    .start()
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })

        rotateAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                // Switch to normal icon after rotation
                Handler(Looper.getMainLooper()).postDelayed({
                    splashIcon.setImageResource(R.drawable.icon)
                }, 200)
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })

        // Start the initial scale animation
        splashIcon.startAnimation(scaleDownAnimation)

        // Delay before launching main activity
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences(WelcomeTourActivity.PREF, MODE_PRIVATE)
            // Existing users shouldn't get an unsolicited tour just for upgrading.
            // Fresh installs (empty settings) and explicit app resets (force flag) do.
            val force = prefs.getBoolean("welcome_force_v59", false)
            val complete = prefs.getBoolean(WelcomeTourActivity.DONE, false)
            val isExistingInstallation = prefs.all.isNotEmpty() && !force
            startActivity(Intent(this,
                if (complete || isExistingInstallation) MainActivity::class.java
                else WelcomeTourActivity::class.java))
            finish()
        }, splashDelay)
    }
}