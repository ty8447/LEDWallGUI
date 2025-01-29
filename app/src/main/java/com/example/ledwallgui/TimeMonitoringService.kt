package com.example.ledwallgui

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.Calendar

class TimeMonitoringService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var firstRun = true

    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            val currentTime = System.currentTimeMillis()
            Log.d("TimeMonitoringService", "Running timeUpdateRunnable at $currentTime")

            sendTimeUpdate()

            val nextInterval = if (firstRun) {
                firstRun = false
                Log.d("TimeMonitoringService", "First run complete. Switching to 15-second interval.")
                15_000L // Set future updates to 15 seconds
            } else {
                15_000L
            }

            Log.d("TimeMonitoringService", "Next update scheduled in ${nextInterval / 1000} seconds")
            handler.postDelayed(this, nextInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("TimeMonitoringService", "Service Created.")

        val initialDelay = calculateInitialDelay()
        Log.d("TimeMonitoringService", "Initial delay calculated: $initialDelay ms before first broadcast.")

        // Start the first execution with the calculated delay
        handler.postDelayed(timeUpdateRunnable, initialDelay)
    }

    private fun sendTimeUpdate() {
        val currentTime = System.currentTimeMillis()
        Log.d("TimeMonitoringService", "Sending time update broadcast: $currentTime")

        val intent = Intent("com.example.ledwallgui.TIME_UPDATE")
        intent.putExtra("current_time", currentTime)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        Log.d("TimeMonitoringService", "Broadcast sent successfully.")
    }

    private fun calculateInitialDelay(): Long {
        val calendar = Calendar.getInstance()
        val seconds = calendar.get(Calendar.SECOND)
        val milliseconds = calendar.get(Calendar.MILLISECOND)
        val remainingMillis = ((60 - seconds) * 1000L) - milliseconds

        Log.d("TimeMonitoringService", "Current time: ${calendar.time}")
        Log.d("TimeMonitoringService", "Current seconds: $seconds, Remaining milliseconds until next minute: $remainingMillis")

        return remainingMillis
    }

    override fun onDestroy() {
        Log.d("TimeMonitoringService", "Service is being destroyed. Removing callbacks.")
        handler.removeCallbacks(timeUpdateRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
