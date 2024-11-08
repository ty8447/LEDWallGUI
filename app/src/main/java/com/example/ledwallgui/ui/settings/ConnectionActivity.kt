package com.example.ledwallgui.ui.settings

import android.Manifest
import java.util.UUID
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledwallgui.R

class ConnectionActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceRecyclerView: RecyclerView
    private lateinit var adapter: BluetoothDeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private lateinit var statusTextView: TextView
    private lateinit var rescanButton: ImageButton
    private lateinit var scanProgressBar: ProgressBar

    companion object {
        private const val SCAN_PERIOD: Long = 30000 // 1 minute timeout
        private const val PERMISSIONS_REQUEST_BLUETOOTH = 1
//        private const val REQUEST_ENABLE_BLUETOOTH = 2 //
//        private const val REQUEST_ENABLE_BT = 2 // Also used to request enabling Bluetooth
    }

    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_menu)
        hideSystemUI()

        rescanButton = findViewById(R.id.rescanButton)
        scanProgressBar = findViewById(R.id.scanProgressBar)

        rescanButton.setOnClickListener { startBluetoothScan() }

        // Initialize bluetoothAdapter first
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        // Initialize adapter here
        deviceRecyclerView = findViewById(R.id.connectionlist)
        deviceRecyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BluetoothDeviceAdapter { device ->
            connectToDevice(device)
        }
        deviceRecyclerView.adapter = adapter

        // Initialize statusTextView here
        statusTextView = findViewById(R.id.textView3) // Assuming textView3 is the ID of your status TextView
        statusTextView.text = "Scanning for Devices" // Set initial text

        // Now you can safely access bluetoothAdapter
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    checkPermissionsAndStartScan()
                } else {
                    Toast.makeText(this, "Bluetooth needs to be enabled", Toast.LENGTH_SHORT).show()
                }
            }.launch(enableBtIntent)
            return // Don't proceed with scanning if Bluetooth is not enabled
        } else {
            checkPermissionsAndStartScan() // Start scan if Bluetooth is already enabled
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        deviceRecyclerView = findViewById(R.id.connectionlist)
        deviceRecyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BluetoothDeviceAdapter { device ->
            connectToDevice(device)
        }
        deviceRecyclerView.adapter = adapter

        val backButton: ImageButton = findViewById(R.id.cBackButton)
        backButton.setOnClickListener { finish() }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Toast.makeText(this, "Connecting to Device...", Toast.LENGTH_SHORT).show() // Connecting message
        try {
            val socket = device.createRfcommSocketToServiceRecord(UUID.fromString("YOUR_UUID"))
            socket.connect()
            Toast.makeText(this, "Connected to Device", Toast.LENGTH_SHORT).show() // Success message
            // Connection established
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to connect to Device", Toast.LENGTH_SHORT).show() // Failure message
            // Handle connection errors
        }
    }

    private fun checkPermissionsAndStartScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Check if we have the necessary permissions
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                startBluetoothScan()
            } else {
                // Request permissions
                requestPermissions(bluetoothPermissions, PERMISSIONS_REQUEST_BLUETOOTH)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startBluetoothScan()
            } else {
                // Request location permission
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSIONS_REQUEST_BLUETOOTH)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        if (isScanning) return // If already scanning, do nothing

        adapter.clearDevices() // Clear any previously found devices
        Toast.makeText(this, "Starting Bluetooth scan...", Toast.LENGTH_SHORT).show()
        isScanning = true
        scanProgressBar.visibility = View.VISIBLE
        statusTextView.text = "Scanning for Devices"

        // Register a receiver to listen for found devices
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        Log.d("ConnectionActivity", "Device found: ${it.name ?: "Unknown Device"} - ${it.address}")
                        adapter.addDevice(it)
                    }
                }
            }
        }, filter)

        bluetoothAdapter.startDiscovery() // Start scanning for devices

        // Stop scanning after a timeout
        handler.postDelayed({
            stopBluetoothScan()
        }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        if (!isScanning) return

        bluetoothAdapter.cancelDiscovery()
        isScanning = false
        scanProgressBar.visibility = View.GONE
        rescanButton.visibility = View.VISIBLE
        statusTextView.text = "Rescan for Devices"
        Log.d("ConnectionActivity", "Scan completed.")
        Toast.makeText(this, "Scan completed", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_BLUETOOTH) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBluetoothScan()
            } else {
                Toast.makeText(this, "Bluetooth permissions required for scanning.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    // Bluetooth Device Adapter
    class BluetoothDeviceAdapter(private val onDeviceSelected: (BluetoothDevice) -> Unit) :
        RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

        private val devices = mutableListOf<BluetoothDevice>()

        fun addDevice(device: BluetoothDevice) {
            if (!devices.contains(device)) {
                devices.add(device)
                Log.d("ConnectionActivity", "Device added: ${device.name ?: "Unknown Device"} - ${device.address}")
                notifyDataSetChanged()
            }
        }

        fun clearDevices() {
            devices.clear()
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
                val deviceName = device.name
                deviceNameTextView.text = if (deviceName.isNullOrEmpty() || deviceName == "Unknown Device") {
                    device.address // Display MAC address if name is unknown
                } else {
                    deviceName // Display device name if available
                }
            }

            init {
                itemView.setOnClickListener {
                    val position = adapterPosition // Use adapterPosition instead of bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onDeviceSelected(devices[position])
                    }
                }
            }
        }
    }
}
