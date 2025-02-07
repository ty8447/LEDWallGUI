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

    private val CHANNEL_ID = "BluetoothReconnectionChannel"
    private val TAG = "BluetoothReconnectionService"
    private lateinit var handler: Handler

    private var isRetrying = false
    private var nrTries = 0
    private val MAX_TRIES = 20

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        Log.d(TAG, "Service started")
        handler = Handler(Looper.getMainLooper())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread {
            while (true) {
                try {
                    val isConnected = GlobalBluetoothManager.isConnected
                    if (!isConnected) {
                        Log.d(TAG, "Device disconnected. Attempting to reconnect...")
                        attemptReconnect(applicationContext)
                    }
                    Thread.sleep(5000)
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Reconnection thread interrupted", e)
                    break
                }
            }
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
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
            .setContentText("Reconnecting to Bluetooth devices...")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .build()
    }

    private fun attemptReconnect(context: Context) {
        if (!::handler.isInitialized) {
            // Initialize the handler if it hasn't been initialized yet
            handler = Handler(Looper.getMainLooper()) // Use the main looper or whichever looper is appropriate
        }

        if (nrTries >= MAX_TRIES) {
            Log.w(TAG, "Max reconnection attempts reached.")
            return
        }

        if (isRetrying) {
            Log.d(TAG, "Reconnection already in progress. Skipping this attempt.")
            return
        }

        nrTries++
        isRetrying = true
        Log.d(TAG, "Reconnection attempt $nrTries/$MAX_TRIES")

        // Fetch device address from preferences
        val deviceAddress = PreferenceUtils(context).fetchDeviceAddress()

        if (deviceAddress.isNullOrEmpty()) {
            isRetrying = false // Reset `isRetrying` if the address is null or empty
            Log.e(TAG, "No device address found in preferences")
            return
        }

        // Fetch the device from the address
        val device = GlobalBluetoothManager.getDeviceFromAddress(deviceAddress)
        device?.let {
            // Proceed with connection attempt
            GlobalBluetoothManager.connectToDevice(context, it, handler) { success ->
                isRetrying = false // Ensure this is reset regardless of success or failure
                if (success) {
                    Log.d(TAG, "Reconnected successfully")
                    nrTries = 0
                } else {
                    Log.e(TAG, "Reconnection failed")
                    attemptReconnect(context) // Retry reconnection if failed
                }
            }
        } ?: run {
            isRetrying = false // Reset `isRetrying` if the device is not found
            Log.e(TAG, "Device not found for address: $deviceAddress")
        }
    }



}
