package com.example.ledwallgui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.time.Clock
import java.time.Instant

class TimeMonitoringService : Service() {
    private val clock: Clock = Clock.systemDefaultZone()
    private var previousInstant: Instant = clock.instant()

    private val serviceScope = CoroutineScope(Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TimeMonitoringService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check if the device is running Android Q or higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasForegroundServicePermission = checkForegroundServicePermission()

            if (!hasForegroundServicePermission) {
                // Request the FOREGROUND_SERVICE permission
                requestForegroundServicePermission()
                // Return START_NOT_STICKY or other appropriate value
                return START_NOT_STICKY
            }
        }

        createNotificationChannel() // Create notification channel
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        startMonitoringTimeChanges()
        return START_STICKY
    }

    private fun checkForegroundServicePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.FOREGROUND_SERVICE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestForegroundServicePermission() {
        val permissionIntent = Intent(this, PermissionRequestActivity::class.java)
        permissionIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(permissionIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "TimeMonitoringService onDestroy")
    }

    private fun startMonitoringTimeChanges() {
        serviceScope.launch {
            while (true) {
                val currentInstant = clock.instant()
                if (currentInstant != previousInstant) {
                    updateUI()
                    // Update previousInstant
                    previousInstant = currentInstant
                }
                delay(5000) // Adjust the delay as needed
            }
        }
    }

    private fun updateUI() {
        // Update UI logic here, e.g., send a broadcast or update a LiveData value
        // You can use LocalBroadcastManager or ViewModel for UI updates
        Log.d(TAG, "TimeMonitoringService updateUI")
        val intent = Intent(ACTION_TIME_CHANGED)
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification {
        // Create and return a foreground notification
        // Customize the notification content based on your requirements
        // Make sure to set a valid notification channel for Android 8.0+
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Foreground Service")
            .setContentText("Monitoring Time Changes")
            .setSmallIcon(R.drawable.icon) // Replace with your notification icon
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // Add visibility setting
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Time Monitoring Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "TimeMonitoringService"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "TimeMonitoringChannel"
        const val ACTION_TIME_CHANGED = "com.example.ledwallgui.ui.alarm.ACTION_TIME_CHANGED"
    }
}
