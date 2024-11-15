package com.example.ledwallgui.ui

import android.annotation.TargetApi
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
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
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
import android.os.PowerManager



class MotionDetectionService : Service(), LifecycleOwner {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private val CHANNELID = "motion_detection_channel" // Define
    private val handler = Handler(Looper.getMainLooper())
    private var inactivityTimer: Runnable? = null
    private val inactivityTimeout = 6000L // 1 minute
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA // Initial camera
    val actionFlipCamera = "com.example.ledwallgui.FLIP_CAMERA"
    private lateinit var mPowerManager: PowerManager
    private var mWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        Log.d("MotionDetectionService", "Service Running")
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Create a notification channel (for Android 8.0 and above)
        createNotificationChannel()

        mPowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Optional: Log to track WakeLock behavior
        Log.d("MotionDetectionService", "WakeLocks initialized.")

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

        // Update shared preferences with the new camera selection
        val prefs = getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("currentCameraSelector", currentCameraSelector.lensFacing ?: CameraSelector.LENS_FACING_BACK).apply()
        Log.d("MotionDetectionService", "Current camera set to: ${currentCameraSelector.lensFacing}")
        // Rebind the camera with the new cameraSelector
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
        turnOffScreen()
        val cameraLensFacing = intent?.getIntExtra("currentCameraSelector", CameraSelector.LENS_FACING_BACK) ?: CameraSelector.LENS_FACING_BACK
        currentCameraSelector = if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        // Example of how to call the methods
        turnOffScreen()

        // After 10 seconds, turn the screen back on
        handler.postDelayed({
            turnOnScreen()
        }, 10000) // Turn screen back on after 10 seconds
        startCamera()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        mWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        cameraExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer({ luma ->
                    }, ::turnOnScreen, ::resetInactivityTimer, handler, threshold = 5.0))
                }

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

    private fun resetInactivityTimer() {
        inactivityTimer?.let { handler.removeCallbacks(it) }
        inactivityTimer = Runnable {
//            if (!isScreenDimmed) {
//                dimScreen()
//            }
        }
        handler.postDelayed(inactivityTimer!!, inactivityTimeout)
    }

//    private fun restoreScreenBrightness() {
//        Log.e("MotionDetectionService", "Screen brightness restoration in progress")
//        if (!isScreenDimmed) return
//        // Restore the screen brightness to default
//        try {
//            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (systemBrightness * 255).toInt()) // Set the system's brightness
//            isScreenDimmed = false
//            Log.d("MotionDetectionService", "Screen brightness restored")
//        } catch (e: Exception) {
//            Log.e("MotionDetectionService", "Error restoring screen brightness: ${e.message}")
//        }
//    }

    // Method to turn the screen on
    private fun turnOnScreen() {
        Log.v("MotionDetectionService", "Turning screen ON!")
        if (mWakeLock == null || !mWakeLock!!.isHeld) {
            mWakeLock = mPowerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MotionDetectionService:tag"
            )
            mWakeLock?.acquire()
        }
    }

    // Method to turn the screen off
    @TargetApi(Build.VERSION_CODES.LOLLIPOP) // To suppress the lint error
    private fun turnOffScreen() {
        Log.v("MotionDetectionService", "Turning screen OFF!")
        if (mWakeLock == null || !mWakeLock!!.isHeld) {
            mWakeLock = mPowerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "MotionDetectionService:tag"
            )
            mWakeLock?.acquire()

            // Automatically release after some time (e.g., 10 seconds)
            handler.postDelayed({
                if (mWakeLock?.isHeld == true) {
                    mWakeLock?.release()
                    Log.v("MotionDetectionService", "Screen off WakeLock released.")
                }
            }, 10000) // 10 seconds delay to keep the screen off
        }
    }

//    private fun dimScreen() {
//        // Dim screen
//        Log.e("MotionDetectionService", "Screen dim in progress")
//        try {
//            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (0.1f * 255).toInt()) // Dim screen to 10%
//            systemBrightness = 0.1f
//            isScreenDimmed = true
//            Log.d("MotionDetectionService", "Screen brightness dimmed")
//        } catch (e: Exception) {
//            Log.e("MotionDetectionService", "Error dimming screen brightness: ${e.message}")
//        }
//    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}