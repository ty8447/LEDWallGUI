package com.example.ledwallgui.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager

class BluetoothReconnectionService : Service() {
    private lateinit var globalBluetoothManager: GlobalBluetoothManager
    private lateinit var preferenceUtils: PreferenceUtils
    private val CHANNEL_ID = "BluetoothReconnectionChannel"
    private val TAG = "BluetoothReconnectionService"
    private lateinit var handler: Handler
    private var isRetrying = false
    private var nrTries = 0
    private val MAX_TRIES = 20
    private val RECONNECT_INTERVAL = 5000L // 5 seconds

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        Log.d(TAG, "Service started")
        handler = Handler(Looper.getMainLooper())
        globalBluetoothManager = GlobalBluetoothManager(this)
        preferenceUtils = PreferenceUtils(this)

        // Start monitoring connection state
        startReconnectionLoop()
    }

    private fun startReconnectionLoop() {
        handler.post(object : Runnable {
            override fun run() {
                val isConnected = PreferenceUtils(applicationContext).loadConnectionState().first
//                Log.d(TAG, "Connection check - isConnected: $isConnected")

                if (!isConnected) {
                    Log.d(TAG, "Device disconnected. Attempting to reconnect...")
                    attemptReconnect(applicationContext)
                }

                handler.postDelayed(this, RECONNECT_INTERVAL) // Re-run after interval
            }
        })
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        handler.removeCallbacksAndMessages(null) // Stop the reconnection loop
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bluetooth Reconnection Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluetooth Reconnection Service")
            .setContentText("Monitoring Bluetooth connection...")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .build()
    }

    private fun attemptReconnect(context: Context) {
        if (nrTries >= MAX_TRIES) {
            Log.w(TAG, "Max reconnection attempts reached. Stopping retries.")
            return
        }

        if (isRetrying) {
            Log.d(TAG, "Reconnection already in progress. Skipping this attempt.")
            return
        }

        isRetrying = true
        nrTries++
        Log.d(TAG, "Reconnection attempt $nrTries/$MAX_TRIES")

        val deviceAddress = PreferenceUtils(context).fetchDeviceAddress()
        if (deviceAddress.isNullOrEmpty()) {
            isRetrying = false
//            Log.e(TAG, "No device address found in preferences")
            return
        }

        val device = globalBluetoothManager.getDeviceFromAddress(deviceAddress)
        if (device == null) {
            isRetrying = false
            Log.e(TAG, "Device not found for address: $deviceAddress")
            return
        }

        globalBluetoothManager.connectToDevice(context, device, handler) { success ->
            isRetrying = false
            if (success) {
                Log.d(TAG, "Reconnected successfully")
                nrTries = 0
            } else {
                Log.e(TAG, "Reconnection failed. Will retry in $RECONNECT_INTERVAL ms")
            }
        }
    }
}
