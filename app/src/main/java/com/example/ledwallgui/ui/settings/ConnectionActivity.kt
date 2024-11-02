package com.example.ledwallgui.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ContentValues.TAG
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledwallgui.R


class ConnectionActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceRecyclerView: RecyclerView
    private lateinit var adapter: BluetoothDeviceAdapter

    companion object {
        private const val PERMISSIONS_REQUEST_BLUETOOTH = 1
        private const val REQUEST_ENABLE_BT = 2 // Add this constant
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE) // Hide the title bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, // Hide the status bar
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_connection_menu)
        hideSystemUI()

        val backButton: ImageButton = findViewById(R.id.cBackButton)

        backButton.setOnClickListener {
            finish()
        }

        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.getAdapter()
        if (bluetoothAdapter == null) {
            Log.d(TAG,"Bluetooth is not available on this device!!!")
        } else {
            if (!bluetoothAdapter.isEnabled) {
                // Bluetooth is not enabled, request to enable it
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                Log.d(TAG,"Bluetooth is not enabled. Sent Request")
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }

        deviceRecyclerView = findViewById(R.id.connectionlist)
        deviceRecyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BluetoothDeviceAdapter()
        deviceRecyclerView.adapter = adapter


        // Initialize BluetoothAdapter and request permissions as you were doing before
        requestBluetoothPermission()
    }

    private fun requestBluetoothPermission() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )

        requestPermissions(permissions, PERMISSIONS_REQUEST_BLUETOOTH)
    }

    private fun setUpBluetoothDeviceList() {
        // Set up Bluetooth device list here
        // ...
    }

    private fun hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }
}

class BluetoothDeviceAdapter : RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<BluetoothDevice>()

    fun addDevice(device: BluetoothDevice) {
        devices.add(device)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
    }

    override fun getItemCount(): Int {
        return devices.size
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceNameTextView: TextView = itemView.findViewById(R.id.deviceNameTextView)

        fun bind(device: BluetoothDevice) {
            deviceNameTextView.text = device.name ?: "Unknown Device"
        }
    }
}

