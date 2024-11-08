package com.example.ledwallgui.ui

import android.app.Notification
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Window
import android.util.Log
import android.view.Display
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.camera.core.Camera
import androidx.camera.core.ImageProxy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.camera.core.UseCase
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.get
import com.example.ledwallgui.ui.LuminosityAnalyzer
import com.example.ledwallgui.R
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MotionDetectionService : Service(), LifecycleOwner {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private val CHANNELID = "motion_detection_channel" // Define
    private var isScreenOn = true
    private val handler = Handler(Looper.getMainLooper())
    private var inactivityTimer: Runnable? = null
    private val inactivityTimeout = 6000L // 1 minute
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA // Initial camera
    val actionFlipCamera = "com.example.ledwallgui.FLIP_CAMERA"

    override fun onCreate() {
        Log.d("MotionDetectionService", "Service Running")
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Create a notification channel (for Android 8.0 and above)
        createNotificationChannel()

        // Create a notification
        val notification = createNotification()
        // Create an IntentFilter for the broadcast
        val filter = IntentFilter(actionFlipCamera)
        // Register a broadcast receiver in your activity or fragment
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                flipCamera()
            }
        }
        registerReceiver(receiver, filter)

        // Create LifecycleRegistry
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // Start the service in the foreground
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun flipCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Rebind camera with new cameraSelector
        startCamera()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNELID,
                "Motion Detection Channel",
                NotificationManager.IMPORTANCE_LOW // Or higher if needed
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNELID)
            .setContentTitle("Motion Detection")
            .setContentText("Motion detection is running")
            .setSmallIcon(R.drawable.icon)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra("source") ?: "Unknown"
        Log.d("MotionDetectionService", "Started from: $source")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        startCamera()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    // ... (Set preview surface here) ...
                }

            if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                // ... (rest of the camera binding code) ...
            } else {
                // Handle case where front camera is not available
                Log.e("MotionDetectionService", "Front camera not available")
            }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer({ luma ->
                    }, ::keepScreenOn, ::resetInactivityTimer, handler, threshold = 5.0))
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    currentCameraSelector,
                    preview,
                    imageAnalyzer
                )

                // Log camera binding success
                Log.d("MotionDetectionService", "Camera bound successfully: ${camera.cameraInfo}")
            } catch (exc: Exception) {
                // ... (Handle camera binding exceptions here) ...
                Log.e("MotionDetectionService", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun keepScreenOn() {
        // ... (Implementation for keeping the screen on) ...
    }

    private fun resetInactivityTimer() {
        // Cancel any existing timer
        inactivityTimer?.let { handler.removeCallbacks(it) }

        // Schedule a new timer
        inactivityTimer = Runnable {
            // Call turnScreenOff() when the timer expires
            turnScreenOff()
        }
        handler.postDelayed(inactivityTimer!!, inactivityTimeout)
    }

    private fun turnScreenOff() {
        // Set system brightness to 15% (0-255 scale)
        val brightnessValue = (0.15 * 255).toInt() // Convert 15% to 0-255 scale
        Settings.System.putInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            brightnessValue
        )
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}