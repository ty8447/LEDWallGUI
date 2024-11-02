package com.example.ledwallgui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class PermissionRequestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServicePermission = arrayOf(Manifest.permission.FOREGROUND_SERVICE)
            ActivityCompat.requestPermissions(
                this,
                foregroundServicePermission,
                REQUEST_FOREGROUND_SERVICE_PERMISSION
            )
        } else {
            // Handle permission request for lower API levels if needed
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_FOREGROUND_SERVICE_PERMISSION) {
            // Handle permission result here if needed
            finish()
        }
    }

    companion object {
        const val REQUEST_FOREGROUND_SERVICE_PERMISSION = 123
    }
}
