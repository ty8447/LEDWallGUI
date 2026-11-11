package com.example.ledwallgui.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.R
import no.nordicsemi.android.kotlin.ble.core.RealServerDevice
import java.util.UUID
import java.lang.ref.WeakReference
import android.bluetooth.le.BluetoothLeScanner


class ConnectionActivity : AppCompatActivity() {
    private lateinit var globalBluetoothManager: GlobalBluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceRecyclerView: RecyclerView
    private lateinit var preferenceUtils: PreferenceUtils
    private var connectState = false
    private lateinit var adapter: BluetoothDeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val connectionCheckHandler = Handler(Looper.getMainLooper())
    private val connectionCheckInterval = 15000L // Check every 5 seconds
    private var isScanning = false
    // Android BLE scanner can retain its callback even after stopScan(). The callback
    // MUST NOT have an implicit strong reference to a destroyed Activity.
    private val leCallback: ScanCallback by lazy { SafeScanCallback(this) }
    private var currentScanner: BluetoothLeScanner? = null

    private class SafeScanCallback(owner: ConnectionActivity) : ScanCallback() {
        private val activity = WeakReference(owner)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val a = activity.get() ?: return
            a.runOnUiThread {
                if (!a.isScanning || a.isDestroyed) return@runOnUiThread
                val found = result.device
                if (a.showUnnamedDevices || !result.scanRecord?.deviceName.isNullOrEmpty() ||
                    result.scanRecord?.deviceName?.contains("LED", ignoreCase = true) == true) {
                    val advertised = result.scanRecord?.deviceName
                    if (!advertised.isNullOrBlank()) a.preferenceUtils.saveKnownDeviceName(found.address, advertised)
                    a.adapter.addDevice(found, advertised)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            val a = activity.get() ?: return
            a.runOnUiThread {
                if (a.isDestroyed) return@runOnUiThread
                Log.e("ConnectionActivity", "BLE scan failed: $errorCode")
                a.stopBluetoothScan()
                a.statusTextView.text = "BLE scan failed ($errorCode)"
            }
        }
    }
    private lateinit var stateTextView: TextView
    private lateinit var toggleUnnamedDevices: TextView
    private lateinit var statusTextView: TextView
    private lateinit var rescanButton: ImageButton
    private lateinit var scanProgressBar: ProgressBar
    private var showUnnamedDevices = false
    private var currentConnectionStatus = false
    private var deviceAddress: String? = null

    companion object {
        private const val SCAN_PERIOD: Long = 30000
    }

    // Register once; never open a second permission dialog while Android is
    // resolving the first. Denied or partially granted => leave this page.
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasRequiredBluetoothPermissions()) {
            enableBluetoothOrScan()
        } else {
            leaveWhenPermissionDenied()
        }
    }
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && hasRequiredBluetoothPermissions()) {
            startBluetoothScan()
        } else {
            leaveWhenPermissionDenied()
        }
    }

    private fun leaveWhenPermissionDenied() {
        // No Toast before/over a system permission dialog. Do not leave an
        // empty Connections screen or a scanner callback running.
        if (::statusTextView.isInitialized) stopBluetoothScan()
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("bluetooth_permission_denied", true))
        finish()
    }

    private fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    private fun hasRequiredBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_menu)
        // Back navigation must remain available even when Bluetooth is disabled.
        findViewById<ImageButton>(R.id.cBackButton).setOnClickListener {
            if (::statusTextView.isInitialized) stopBluetoothScan()
            finish()
        }
        globalBluetoothManager = GlobalBluetoothManager(this)
        hideSystemUI()
        preferenceUtils = PreferenceUtils(this)
        rescanButton = findViewById(R.id.rescanButton)
        scanProgressBar = findViewById(R.id.scanProgressBar)
        toggleUnnamedDevices = findViewById(R.id.textView2)
        stateTextView = findViewById(R.id.textView4)
        statusTextView = findViewById(R.id.textView3)
        currentConnectionStatus = connectState
        deviceAddress = globalBluetoothManager.deviceAddress

        Log.d("ConnectionActivity0", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity0", "Current isConnected value: $currentConnectionStatus")
        Log.d("ConnectionActivity0", "Current deviceAddress value: $deviceAddress")

        // Initialize Bluetooth Adapter
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val availableAdapter = bluetoothManager.adapter
        if (availableAdapter == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        bluetoothAdapter = availableAdapter

        val sharedPreferences = getSharedPreferences("BluetoothConnectionPrefs", Context.MODE_PRIVATE)
        val isFirstRun = sharedPreferences.getBoolean("isFirstRun", true)

        if (isFirstRun) {
            // Reset variables
            currentConnectionStatus = false
            deviceAddress = null
            globalBluetoothManager.deviceName = null

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

        // Scan begins after all views and the receiver are initialized below.
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        registerReceiver(uuidReceiver, filter)

        Log.d("ConnectionActivity1", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity1", "Current isConnected value: $currentConnectionStatus")
        Log.d("ConnectionActivity1", "Current deviceAddress value: $deviceAddress")

        startConnectionCheck()

        rescanButton.setOnClickListener {
            if(isScanning){
                stopBluetoothScan()
            } else {
            startBluetoothScan()
            }
        }

        toggleUnnamedDevices.setOnLongClickListener {
            // Toggle the state of the variable
            showUnnamedDevices = !showUnnamedDevices

            // Show a toast notification with the updated state
            val status = if (showUnnamedDevices) "ON" else "OFF"
            Toast.makeText(this, "Toggled Unnamed Devices to $status", Toast.LENGTH_SHORT).show()

            true // Indicate the event has been handled
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
        Log.d("ConnectionActivity2", "Current isConnected value: $connectState")
        Log.d("ConnectionActivity2", "Current deviceAddress value: $deviceAddress")

        // Permissions come before any Bluetooth state check or scan. In particular,
        // Android 12+ may throw if adapter.isEnabled is queried without CONNECT.
        checkPermissionsAndStartScan()

        stateTextView.setOnClickListener {
            Log.d("ConnectionActivity", "stateTextView clicked")
            if (connectState) {
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
        stopBluetoothScan()
        stopConnectionCheck()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(uuidReceiver) } catch (_: IllegalArgumentException) { }
        super.onDestroy()
    }

    fun updateConnectionStatus(deviceAddress: String?, isConnected: Boolean) {
        Log.d("ConnectionActivity3", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity3", "Current isConnected value: $connectState")
        Log.d("ConnectionActivity3", "Current deviceAddress value: $deviceAddress")
        if (isConnected && deviceAddress != null) {
            val deviceName = globalBluetoothManager.deviceName ?: deviceAddress
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
        if (isFinishing) return
        startConnectionCheck()
        Log.d("ConnectionActivity4", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity4", "Current isConnected value: $currentConnectionStatus")
        Log.d("ConnectionActivity4", "Current deviceAddress value: $deviceAddress")
        val (deviceAddress, connectState) = loadBluetoothConnection(this)
        currentConnectionStatus = connectState
        globalBluetoothManager.deviceAddress = deviceAddress
        updateConnectionStatus(deviceAddress, connectState)
    }

    override fun onPause() {
        stopBluetoothScan()
        super.onPause()
        stopConnectionCheck()
    }

    private fun startConnectionCheck() {
        stopConnectionCheck()
        connectionCheckHandler.post(object : Runnable {
            override fun run() {
                val (isConnected, deviceAddress, deviceName) = preferenceUtils.loadConnectionState()

                Log.d("MainActivity", "Saved connection state: isConnected=$isConnected, deviceAddress=$deviceAddress, deviceName=$deviceName")

                if (isConnected && !connectState && deviceAddress != null) {
                    connectState = true
                    val deviceName = globalBluetoothManager.deviceName ?: deviceAddress
                    updateConnectionStatus(deviceName, true)
                    Log.d("ConnectionActivity", "Updating connection status for device: $deviceName")
                } else if (!isConnected && connectState){
                    connectState = false
                    updateConnectionStatus("", false)
                    Log.d("MainActivity", "Bluetooth connection is not active")
                } else {
                    //Nothing Happens
                }

                connectionCheckHandler.postDelayed(this, connectionCheckInterval)
            }
        })
    }

    private fun stopConnectionCheck() {
        connectionCheckHandler.removeCallbacksAndMessages(null)
    }

    fun loadBluetoothConnection(context: Context): Pair<String?, Boolean> {
        val (connected, savedAddress, _) = preferenceUtils.loadConnectionState()
        return Pair(savedAddress, connected)
    }

    private fun disconnectFromDevice() {
        Toast.makeText(this, "Disconnecting from Device...", Toast.LENGTH_SHORT).show()

        // Call the GlobalBluetoothManager to release the connection resources.
        globalBluetoothManager.release(this)  // Keeps last-known device name for the next scan

        // Update UI to reflect disconnection.
        updateConnectionStatus("", false)  // Assume updateConnectionStatus can handle disconnection state

        // Optionally, show a toast or update additional UI elements.
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
    }



    @SuppressLint("MissingPermission")
    private fun connectAndValidateDevice(device: BluetoothDevice) {
        if (!hasRequiredBluetoothPermissions()) {
            leaveWhenPermissionDenied()
            return
        }
        Toast.makeText(this, "Connecting to Device...", Toast.LENGTH_SHORT).show()
        Log.d("ConnectionActivity6", "Current stateTextView value: $stateTextView")
        Log.d("ConnectionActivity6", "Current isConnected value: $currentConnectionStatus")
        Log.d("ConnectionActivity6", "Current deviceAddress value: $deviceAddress")

        // Wrap the BluetoothDevice into a ServerDevice using RealServerDevice.
        val serverDevice = RealServerDevice(device)

        globalBluetoothManager.connectToDevice(applicationContext, serverDevice, handler) { success ->
            runOnUiThread {
                if (success) {
                    updateConnectionStatus(device.address, true)
                    currentConnectionStatus = true
                    deviceAddress = device.address
                    val resolvedName = preferenceUtils.knownDeviceName(device.address)
                        ?: device.name?.takeIf { it.isNotBlank() } ?: "Room LED Controller"
                    globalBluetoothManager.deviceName = resolvedName
                    stopBluetoothScan()

                    Toast.makeText(
                        this,
                        "Connected to ${device.name ?: "Unknown Device"}",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Since GlobalBluetoothManager already validates the device via required characteristics,
                    // there's no need to further check for discovered UUIDs here.
                    // You can update your UI or save the connection state as needed.
                } else {
                    Toast.makeText(this, "Failed to connect", Toast.LENGTH_SHORT).show()
                    Log.e("ConnectionActivity", "Device connection failed or validation unsuccessful.")
                }
            }
        }
    }


    private fun checkPermissionsAndStartScan() {
        val missing = requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        } else {
            enableBluetoothOrScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableBluetoothOrScan() {
        if (!hasRequiredBluetoothPermissions() || isFinishing || isDestroyed) {
            leaveWhenPermissionDenied()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startBluetoothScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        if (!hasRequiredBluetoothPermissions()) { leaveWhenPermissionDenied(); return }
        if (isScanning || isFinishing) return
        if (!bluetoothAdapter.isEnabled) {
            statusTextView.text = "Enable Bluetooth to scan"
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            statusTextView.text = "BLE scanning unavailable"
            return
        }
        adapter.clearDevices()
        isScanning = true
        // Keep the dark rescan-button background from showing behind the spinner.
        rescanButton.visibility = View.INVISIBLE
        scanProgressBar.visibility = View.VISIBLE
        statusTextView.text = "Scanning for BLE LED controllers"
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            // Do not filter by local name: some nRF peripherals put it in scan response only.
            currentScanner = scanner
            scanner.startScan(emptyList<ScanFilter>(), settings, leCallback)
        } catch (e: Exception) {
            Log.e("ConnectionActivity", "BLE scan error", e)
            isScanning = false
            currentScanner = null
            scanProgressBar.visibility = View.GONE
            rescanButton.visibility = View.VISIBLE
            statusTextView.text = "BLE scanning failed: ${e.message}"
            return
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopBluetoothScan() }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        val wasScanning = isScanning
        isScanning = false
        val scanner = currentScanner
        currentScanner = null
        // Stop using the same scanner instance which was used to start scanning.
        if (scanner != null) {
            try { scanner.stopScan(leCallback) }
            catch (e: Exception) { Log.w("ConnectionActivity", "Stop BLE scan failed", e) }
        }
        handler.removeCallbacksAndMessages(null)
        scanProgressBar.visibility = View.GONE
        rescanButton.visibility = View.VISIBLE
        statusTextView.text = "Rescan for BLE devices"
    }

    // Define the BroadcastReceiver as a property
    private val deviceFoundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    if (showUnnamedDevices || !it.name.isNullOrEmpty()) {
                        adapter.addDevice(it) // Add to the adapter if it matches the filter
                        adapter.notifyDataSetChanged()
                        Log.d(
                            "ConnectionActivity",
                            "Device found: ${it.name ?: "Unknown Device"} - ${it.address}"
                        )
                    }
                }
            }
        }
    }

    private fun hideSystemUI() {
        // Use WindowInsetsController for Android 11+
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars()) // Hides status and navigation bars
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE // Allows bars to reappear with a swipe
        }
    }

    // Bluetooth Device Adapter
    class BluetoothDeviceAdapter(private val onDeviceSelected: (BluetoothDevice) -> Unit) :
        RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

        private val devices = mutableListOf<BluetoothDevice>()
        private val names = mutableMapOf<String, String>()
        private val deviceConnectionStatus =
            mutableMapOf<String, Boolean>()  // Track connection status by device address

        fun addDevice(device: BluetoothDevice, advertisedName: String? = null) {
            if (!advertisedName.isNullOrBlank()) names[device.address] = advertisedName
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
            // Keep names across rescan; name can be missing in later advertisements.
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
//            private val connectionStatusTextView: TextView =
//                itemView.findViewById(R.id.connectionStatusTextView)
//            private val deviceLayout: View = itemView.findViewById(R.id.deviceLayout)

            fun bind(device: BluetoothDevice) {
                @SuppressLint("MissingPermission")
                val deviceName = names[device.address]
                    ?: PreferenceUtils(itemView.context).knownDeviceName(device.address)
                    ?: try { device.name } catch (_: SecurityException) { null }
                    ?: "Unknown Device"
                deviceNameTextView.text =
                    if (deviceName.isNullOrEmpty() || deviceName == "Unknown Device") {
                        device.address // Display MAC address if name is unknown
                    } else {
                        deviceName // Display device name if available
                    }

                // Update connection status text and color
//                val isConnected = deviceConnectionStatus[device.address] ?: false
//                connectionStatusTextView.text = if (isConnected) "Connected" else "Disconnected"
//                connectionStatusTextView.setTextColor(
//                    if (isConnected) Color.parseColor("#00FFFF") else Color.parseColor("#000000") // Aqua color for connected
//                )
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
