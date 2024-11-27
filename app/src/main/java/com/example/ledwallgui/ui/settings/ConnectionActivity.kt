package com.example.ledwallgui.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.registerReceiver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager.deviceAddress
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager.isConnected
import java.util.UUID


class ConnectionActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceRecyclerView: RecyclerView
    private lateinit var adapter: BluetoothDeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private lateinit var stateTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var rescanButton: ImageButton
    private lateinit var scanProgressBar: ProgressBar

    companion object {
        private const val SCAN_PERIOD: Long = 30000 // 1 minute timeout
        private const val PERMISSIONS_REQUEST_BLUETOOTH = 1
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_menu)
        hideSystemUI()

        rescanButton = findViewById(R.id.rescanButton)
        scanProgressBar = findViewById(R.id.scanProgressBar)
        stateTextView = findViewById(R.id.textView4)
        statusTextView = findViewById(R.id.textView3)

        Log.d("ConnectionActivity0", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity0", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity0", "Current deviceAddress value: $deviceAddress")

        // Initialize Bluetooth Adapter
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter =
            bluetoothManager.adapter  // Make sure bluetoothAdapter is initialized here
        if (bluetoothAdapter == null) {
            // Handle the case where the Bluetooth adapter is unavailable
            Toast.makeText(this, "Bluetooth not available on this device", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val sharedPreferences = getSharedPreferences("BluetoothConnectionPrefs", Context.MODE_PRIVATE)
        val isFirstRun = sharedPreferences.getBoolean("isFirstRun", true)

        if (isFirstRun) {
            // Reset variables
            isConnected = false
            deviceAddress = null
            GlobalBluetoothManager.deviceName = null

            // Set isFirstRun to false
            with(sharedPreferences.edit()) {
                putBoolean("isFirstRun", false)
                apply()
            }
        }


        // Initialize adapter here
        deviceRecyclerView = findViewById(R.id.connectionlist)
        deviceRecyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BluetoothDeviceAdapter { device ->
            connectAndValidateDevice(device)
        }
        deviceRecyclerView.adapter = adapter

        // Check for Bluetooth permissions and start scanning
        checkPermissionsAndStartScan()

        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        registerReceiver(uuidReceiver, filter)

        Log.d("ConnectionActivity1", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity1", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity1", "Current deviceAddress value: $deviceAddress")

        // Load saved connection status and update UI
        val (deviceAddress, isConnected) = loadBluetoothConnection(this)
        updateConnectionStatus(deviceAddress, isConnected)

        rescanButton.setOnClickListener {
            if(isScanning){
                stopBluetoothScan()
            } else {
            startBluetoothScan()
            }
        }

        // Initialize statusTextView here
        statusTextView =
            findViewById(R.id.textView3) // Assuming textView3 is the ID of your status TextView
        statusTextView.text = "Scanning for Devices" // Set initial text

        // Initialize stateTextView here
        stateTextView =
            findViewById(R.id.textView4) // Assuming textView3 is the ID of your status TextView
        stateTextView.text = "Disconnected" // Set initial text
        Log.d("ConnectionActivity2", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity2", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity2", "Current deviceAddress value: $deviceAddress")

        // Now you can safely access bluetoothAdapter
        if (bluetoothAdapter != null) {
            if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        checkPermissionsAndStartScan()
                    } else {
                        Toast.makeText(this, "Bluetooth needs to be enabled", Toast.LENGTH_SHORT)
                            .show()
                    }
                }.launch(enableBtIntent)
                return // Don't proceed with scanning if Bluetooth is not enabled
            } else {
                checkPermissionsAndStartScan() // Start scan if Bluetooth is already enabled
            }
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device!", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        val backButton: ImageButton = findViewById(R.id.cBackButton)
        backButton.setOnClickListener {
            stopBluetoothScan()
            finish()
        }

        stateTextView.setOnClickListener {
            Log.d("ConnectionActivity", "stateTextView clicked")
            if (GlobalBluetoothManager.isConnected) {
                Log.d("ConnectionActivity", "Disconnecting from device")
                disconnectFromDevice()
            }
        }
    }

    // Register the BroadcastReceiver to listen for the UUIDs
    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothDevice.ACTION_UUID == intent?.action) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID) as? Array<ParcelUuid>
                if (uuids != null) {
                    for (uuid in uuids) {
                        Log.d("ConnectionActivity", "UUID from device: ${uuid.uuid}")
                    }
                } else {
                    Log.d("ConnectionActivity", "No UUIDs found for the device.")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(uuidReceiver)
    }

    fun updateConnectionStatus(deviceAddress: String?, isConnected: Boolean) {
        Log.d("ConnectionActivity3", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity3", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity3", "Current deviceAddress value: $deviceAddress")
        if (isConnected && deviceAddress != null) {
            val deviceName = GlobalBluetoothManager.deviceName ?: deviceAddress
            Log.d("ConnectionActivity", "Updating connection status for device: $deviceName")
            stateTextView.text = "Connected to $deviceName"
            stateTextView.setTextColor(ContextCompat.getColor(this, R.color.green))
        } else {
            stateTextView.text = "Disconnected"
            stateTextView.setTextColor(ContextCompat.getColor(this, R.color.red))
        }
    }


    override fun onResume() {
        super.onResume()
        Log.d("ConnectionActivity4", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity4", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity4", "Current deviceAddress value: $deviceAddress")
        val (deviceAddress, isConnected) = loadBluetoothConnection(this)
        GlobalBluetoothManager.isConnected = isConnected
        GlobalBluetoothManager.deviceAddress = deviceAddress

        updateConnectionStatus(deviceAddress, isConnected)
    }

    fun loadBluetoothConnection(context: Context): Pair<String?, Boolean> {
        Log.d("ConnectionActivity5", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity5", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity5", "Current deviceAddress value: $deviceAddress")
        val sharedPreferences = context.getSharedPreferences("BluetoothConnectionPrefs", Context.MODE_PRIVATE)
        val shouldLoadSavedStatus = GlobalBluetoothManager.shouldLoadSavedStatus
        if (shouldLoadSavedStatus) { // Check the condition
            // Load saved connection status
            val deviceAddress = sharedPreferences.getString("deviceAddress", null)
            val isConnected = sharedPreferences.getBoolean("isConnected", false)
            Log.d("ConnectionActivity5.1", "Current stateTextView value: $stateTextView")
            Log.d("ConnectionActivity5.1", "Current isConnected value: $isConnected")
            Log.d("ConnectionActivity5.1", "Current deviceAddress value: $deviceAddress")
            return Pair(deviceAddress, isConnected)
        } else {
            // Return default values
            Log.d("ConnectionActivity5.2", "Current stateTextView value: $stateTextView")
            Log.d("ConnectionActivity5.2", "Current isConnected value: $isConnected")
            Log.d("ConnectionActivity5.2", "Current deviceAddress value: $deviceAddress")
            return Pair(null, false)
        }

    }

    private fun disconnectFromDevice() {
        Toast.makeText(this, "Disconnecting from Device...", Toast.LENGTH_SHORT).show()

        // Call the GlobalBluetoothManager to disconnect
        GlobalBluetoothManager.disconnect()

        // Update UI to reflect disconnection
        updateConnectionStatus("", false)  // Assume updateConnectionStatus can handle this

        // Optionally, show a toast or update the UI
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun connectAndValidateDevice(device: BluetoothDevice) {
        Toast.makeText(this, "Connecting to Device...", Toast.LENGTH_SHORT).show() // Connecting message
        Log.d("ConnectionActivity6", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity6", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity6", "Current deviceAddress value: $deviceAddress")
        // Call the existing connection logic
        GlobalBluetoothManager.connectToDevice(applicationContext, device, handler) { isConnected ->
            runOnUiThread { // Run the following code on the main thread
                if (isConnected) {
                    updateConnectionStatus(device.address, true)
                    GlobalBluetoothManager.isConnected = true
                    deviceAddress = device.address
                    GlobalBluetoothManager.deviceName = device.name ?: "Unknown Device"

                    Toast.makeText(this, "Connected to ${device.name ?: "Unknown Device"}", Toast.LENGTH_SHORT).show()

                    // Get UUIDs from GlobalBluetoothManager
                    val uuids = GlobalBluetoothManager.getDiscoveredUUIDs()
                    Log.d("ConnectionActivity", "UUIDs from GlobalBluetoothManager: $uuids")
                    if (uuids.isNotEmpty()) {
                        for (uuid in uuids) {
                            Log.d("ConnectionActivity", "UUID from connected device: $uuid")
                        }

                        // Check if a specific UUID is present
                        val targetUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1") // Replace with your target UUID
                        if (uuids.contains(targetUUID)) {
                            Log.d("ConnectionActivity", "Target UUID found!")
                            // Perform actions if the target UUID is found
                        } else {
                            Log.d("ConnectionActivity", "Target UUID not found.")
                            // Perform actions if the target UUID is not found
                        }
                    } else {
                        Log.w("ConnectionActivity", "No UUIDs found on the connected device.")
                    }
                } else {
                    Toast.makeText(this, "Failed to connect", Toast.LENGTH_SHORT).show()
                }
            }
        }
        Log.d("ConnectionActivity7", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity7", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity7", "Current deviceAddress value: $deviceAddress")
    }

    private fun checkPermissionsAndStartScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Request permissions
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    PERMISSIONS_REQUEST_BLUETOOTH
                )
                return // Don't proceed with the scan if permissions are not granted
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Request location permission
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    PERMISSIONS_REQUEST_BLUETOOTH
                )
                return // Don't proceed with the scan if permissions are not granted
            }
        }

        // Permissions are granted, start the scan
        startBluetoothScan()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        Log.d("ConnectionActivity8", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity8", "Current isConnected value: $isConnected")
        Log.d("ConnectionActivity8", "Current deviceAddress value: $deviceAddress")
        if (isScanning) return // If already scanning, do nothing
        Log.d("ConnectionActivity", "Starting Bluetooth scan...")
        adapter.clearDevices() // Clear any previously found devices
//        Toast.makeText(this, "Starting Bluetooth scan...", Toast.LENGTH_SHORT).show()
        isScanning = true
        scanProgressBar.visibility = View.VISIBLE
        statusTextView.text = "Scanning for Devices"

        // Register a receiver to listen for found devices
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(deviceFoundReceiver, filter)

        bluetoothAdapter.startDiscovery() // Start scanning for devices

        handler.removeCallbacksAndMessages(null)
        // Stop scanning after a timeout
        handler.postDelayed({
            stopBluetoothScan()
        }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        if (!isScanning) return

        bluetoothAdapter.cancelDiscovery()
        unregisterReceiver(deviceFoundReceiver)
        isScanning = false
        scanProgressBar.visibility = View.GONE
        rescanButton.visibility = View.VISIBLE
        statusTextView.text = "Rescan for Devices"
        Log.d("ConnectionActivity", "Scan completed.")
//        Toast.makeText(this, "Scan completed", Toast.LENGTH_SHORT).show()
    }

    // Define the BroadcastReceiver as a property
    private val deviceFoundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    Log.d(
                        "ConnectionActivity",
                        "Device found: ${it.name ?: "Unknown Device"} - ${it.address}"
                    )
                    adapter.addDevice(it)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_BLUETOOTH) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBluetoothScan()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth permissions required for scanning.",
                    Toast.LENGTH_LONG
                ).show()
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
        private val deviceConnectionStatus =
            mutableMapOf<String, Boolean>()  // Track connection status by device address

        fun addDevice(device: BluetoothDevice) {
            if (!devices.contains(device)) {
                devices.add(device)
                deviceConnectionStatus[device.address] = false  // Default to disconnected
                Log.d(
                    "ConnectionActivity",
                    "Device added: ${device.name ?: "Unknown Device"} - ${device.address}"
                )
                notifyDataSetChanged()
            }
        }

        fun setDeviceConnectionStatus(device: BluetoothDevice, isConnected: Boolean) {
            deviceConnectionStatus[device.address] = isConnected
            notifyDataSetChanged()
        }

        fun clearDevices() {
            devices.clear()
            deviceConnectionStatus.clear()
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
            private val deviceNameTextView: TextView =
                itemView.findViewById(R.id.deviceNameTextView)
            private val connectionStatusTextView: TextView =
                itemView.findViewById(R.id.connectionStatusTextView)
//            private val deviceLayout: View = itemView.findViewById(R.id.deviceLayout)

            fun bind(device: BluetoothDevice) {
                val deviceName = device?.name ?: "Unknown Device"
                deviceNameTextView.text =
                    if (deviceName.isNullOrEmpty() || deviceName == "Unknown Device") {
                        device.address // Display MAC address if name is unknown
                    } else {
                        deviceName // Display device name if available
                    }

                // Update connection status text and color
                val isConnected = deviceConnectionStatus[device.address] ?: false
                connectionStatusTextView.text = if (isConnected) "Connected" else "Disconnected"
                connectionStatusTextView.setTextColor(
                    if (isConnected) Color.parseColor("#00FFFF") else Color.parseColor("#000000") // Aqua color for connected
                )
            }

            init {
                itemView.setOnClickListener {
                    val position =
                        adapterPosition // Use adapterPosition instead of bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onDeviceSelected(devices[position])
                    }
                }
            }
        }
    }
}
