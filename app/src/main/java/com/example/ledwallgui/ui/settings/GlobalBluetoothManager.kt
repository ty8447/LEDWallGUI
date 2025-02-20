package com.example.ledwallgui.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.services.BluetoothReconnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.* // General coroutine functions
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattService
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattServices
import no.nordicsemi.android.kotlin.ble.core.RealServerDevice
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.data.BleGattProperty
import no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray
import java.util.*  // e.g. for LinkedList if still needed
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList




// Define your custom response type.
class RawReadResponse(val value: ByteArray)


// A simple data class to hold mode information.
data class ModeInfo(
    val name: String,
    val parameterNames: List<String>, // List of parameter *names*
    var parameterValues: MutableMap<String, String> = mutableMapOf() // Map of parameter name to value
)

class GlobalBluetoothManager(
    private val context: Context,
    var clientBleGatt: ClientBleGatt? = null
) {

    companion object {
        private const val TAG = "GlobalBluetoothManager"

        // Define UUIDs for the LED service and its characteristics.
        val LED_CONNECTION_SERVICE: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef0") // LED service UUID
        val LED_MODE_LIST_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef1") // LED mode list read
        val PARAMETER_REQUEST_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef2") // LED parameter request (if used)
        val PARAMETER_RESPONSE_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef3") // LED parameter response
        val PARAMETER_CONTROL_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef4") // LED control (e.g. turn on)

        // Standard Client Characteristic Configuration Descriptor UUID
        val CLIENT_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var _isConnected: Boolean = false
    private lateinit var handler: Handler
    var deviceAddress: String? = null
    var deviceName: String? = null
    var shouldLoadSavedStatus: Boolean = false
    val discoveredUUIDs = CopyOnWriteArrayList<UUID>()
    private var currentGatt: BluetoothGatt? = null
    private val prefs = PreferenceUtils(context)

    // References to characteristics acquired during service discovery.
    // Change these declarations in your GlobalBluetoothManager (or relevant class):
    private var ledModeListCharacteristic: ClientBleGattCharacteristic? = null
    private var parameterResponseCharacteristic: ClientBleGattCharacteristic? = null
    private var parameterControlCharacteristic: ClientBleGattCharacteristic? = null

    // Cache the LED service for later use.
    private var ledService: ClientBleGattService? = null
    private var cachedBleGattServices: ClientBleGattServices? = null

    // Additional state fields.
    private var ledParameters: Map<String, Any> = mutableMapOf()
    private var isRetrying: Boolean = false
    private var nrTries: Int = 0
    private val MAX_TRIES = 5

    // List of parsed mode information, maintained for high-level use.
    val modeInfoList: MutableList<ModeInfo> = mutableListOf()

    // (Optional) Track the current mode if needed.
    private var currentMode: String? = null

    // Logging methods – using Log.VERBOSE for detailed output.
    private fun getMinLogPriority(): Int = Log.VERBOSE
    private fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    init {
        // Load the saved connection state when the manager is initialized.
        val (savedIsConnected, savedDeviceAddress, savedDeviceName) = prefs.loadConnectionState()
        _isConnected = savedIsConnected
        deviceAddress = savedDeviceAddress
        deviceName = savedDeviceName
    }

    /**
     * isRequiredServiceSupported should search the discovered services for our LED connection service
     * and acquire references to its important characteristics. Return true only if all required characteristics
     * are available.
     *
     * (Reference: :contentReference[oaicite:1]{index=1})
     */
    suspend fun isRequiredServiceSupported(): Boolean {
        // Discover services using the Nordic API.
        val services = clientBleGatt?.discoverServices()
        val service = services?.findService(LED_CONNECTION_SERVICE)

        if (service != null) {
            // Use the Nordic findCharacteristic() method.
            ledModeListCharacteristic = service.findCharacteristic(LED_MODE_LIST_UUID)
            parameterResponseCharacteristic = service.findCharacteristic(PARAMETER_RESPONSE_UUID)
            parameterControlCharacteristic = service.findCharacteristic(PARAMETER_CONTROL_UUID)

            log(Log.DEBUG, "Found Service: ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                log(Log.DEBUG, "Characteristic Found: ${characteristic.uuid} " +
                        "Properties: ${characteristic.properties}")
            }
        } else {
            log(Log.WARN, "LED_CONNECTION_SERVICE not found")
        }

        val supported = (ledModeListCharacteristic != null &&
                parameterResponseCharacteristic != null &&
                parameterControlCharacteristic != null)
        log(Log.DEBUG, "isRequiredServiceSupported: $supported")
        return supported
    }

    /**
     * initialize() is called after services have been discovered.
     * Here we enable notifications on the parameter response characteristic
     * and request an increased MTU if needed.
     *
     * (Reference: :contentReference[officiate:2]{index=2})
     */
    suspend fun initialize() {
        log(Log.DEBUG, "Initializing device")
        parameterResponseCharacteristic?.getNotifications()?.onEach { data ->
            val response = String(data.value, Charsets.UTF_8).trim()
            log(Log.DEBUG, "Notification received: $response")
            processParameterResponse(response)
        }?.launchIn(CoroutineScope(Dispatchers.IO))
        clientBleGatt?.requestMtu(517)
    }

    /**
     * onServicesInvalidated() is called when the device disconnects or services are no longer valid.
     * Clear the characteristic references and reset internal state.
     */
    fun onServicesInvalidated() {
        log(Log.DEBUG, "Invalidating services")
        ledModeListCharacteristic = null
        parameterResponseCharacteristic = null
        parameterControlCharacteristic = null
        modeInfoList.clear()
    }

    /**
     * Processes the raw LED mode list (expected as a string) and updates modeInfoList.
     */
    private fun processLedModeList(rawData: String) {
        log(Log.DEBUG, "Processing LED mode list data: $rawData")

        if (rawData.isBlank()) {
            log(Log.ERROR, "Received raw data is empty or blank.")
            return
        }

        val modeData = rawData.split(";").filter { it.isNotBlank() }
        for (mode in modeData) {
            val modeParts = mode.split(":")
            if (modeParts.size >= 2) {
                val modeName = modeParts[0].trim()
                val parametersString = modeParts[1].trim()
                val parameterNames = parametersString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val modeInfo = ModeInfo(modeName, parameterNames, mutableMapOf())
                modeInfoList.add(modeInfo)
                log(Log.DEBUG, "Successfully parsed mode: $modeInfo")
            } else {
                log(Log.ERROR, "Invalid mode format: $mode")
            }
        }

        val modeNames = modeInfoList.map { it.name }
        log(Log.DEBUG, "Final Parsed Mode Names: ${modeNames.joinToString(", ")}")

        // Persist the mode list
        prefs.saveModeInfoList(modeInfoList)
    }


    /**
     * Processes parameter response notifications.
     * This example assumes the response is a comma‑separated list of key=value pairs.
     */
    private fun processParameterResponse(response: String) {
        log(Log.DEBUG, "Processing parameter response: $response")
        val parametersMap = mutableMapOf<String, String>()
        response.split(",").forEach { pair ->
            val keyValue = pair.split("=")
            if (keyValue.size == 2) {
                parametersMap[keyValue[0].trim()] = keyValue[1].trim()
            }
        }
        val modeName = parametersMap["Name"]
        if (modeName != null) {
            val modeInfo = modeInfoList.find { it.name == modeName }
            if (modeInfo != null) {
                modeInfo.parameterValues.clear()
                modeInfo.parameterValues.putAll(parametersMap)
                if (currentMode == modeName) {
                    log(
                        Log.DEBUG,
                        "Updated parameters for current mode $modeName: ${modeInfo.parameterValues}"
                    )
                }
            } else {
                log(Log.WARN, "ModeInfo not found for mode: $modeName")
            }
        } else {
            log(Log.WARN, "Parameter response missing mode name")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendDataToDevice(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        data: String,
        clearBuffer: Boolean = true,
        appendNullTerminator: Boolean = false
    ) {
        // Check connection state from PreferenceUtils.
        val (prefsConnected, _, _) = prefs.loadConnectionState()
        if (!prefsConnected) {
            log(Log.WARN, "Not connected (according to preferences). Cannot send data.")
            return
        }

        // Attempt to recover the service from the cached services list.
        val service = cachedBleGattServices?.services?.find { it.uuid == serviceUUID }
        if (service == null) {
            log(
                Log.WARN,
                "Service $serviceUUID not found among discovered services: ${
                    cachedBleGattServices?.services?.map { it.uuid }
                }"
            )
            return
        }

        // Look up the desired characteristic.
        val characteristic = service.findCharacteristic(characteristicUUID)
        if (characteristic == null) {
            log(Log.WARN, "Characteristic $characteristicUUID not found in service $serviceUUID")
            return
        }

        // Launch on Main dispatcher for UI-safe operations.
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Optionally clear any previous buffer.
                if (clearBuffer) {
                    characteristic.write(DataByteArray.from(*byteArrayOf()))
                    log(Log.DEBUG, "Buffer cleared for characteristic ${characteristic.uuid}")
                }
                // Prepare the data payload.
                var messageBytes = data.toByteArray(Charsets.UTF_8)
                if (appendNullTerminator) messageBytes += 0.toByte()
                val dataByteArray = DataByteArray.from(*messageBytes)
                // Write the data.
                characteristic.write(dataByteArray)
                log(Log.DEBUG, "Data sent successfully: $data to characteristic ${characteristic.uuid}")
            } catch (e: Exception) {
                log(Log.WARN, "Failed to send data: ${e.message}")
            }
        }
    }

    /**
     * Generalized function for sending commands to the device.
     */
    fun sendCommandToDevice(type: String, name: String, values: String) {
        val formattedData = "$type:$name:{$values}"
        sendDataToDevice(
            LED_CONNECTION_SERVICE,
            PARAMETER_CONTROL_UUID, // Using the command/control characteristic.
            formattedData,
            clearBuffer = true,
            appendNullTerminator = false
        )
    }

    /**
     * Use case 1: Set LED mode.
     */
    fun setLedMode(modeName: String) {
        sendCommandToDevice(
            "Mode",
            "None",
            modeName
        )
    }

    /**
     * Use case 2: Set LED parameter (e.g., color as list of Ints).
     */
    fun setLedParameter(parameterName: String, parameterValues: List<Int>) {
        val valuesString = parameterValues.joinToString(",") { it.toString() }
        sendCommandToDevice(
            "Parameter",
            parameterName,
            valuesString
        )
    }

    /**
     * Use case 3: Send power state to Arduino.
     */
    fun sendPowerStateToArduino(state: Boolean) {
        val powerState = if (state) 1 else 0
        sendCommandToDevice(
            "Power",
            "None",
            powerState.toString()
        )
    }

    /**
     * Use case 4: Set LED brightness.
     */
    fun setBrightness(brightness: Int) {
        sendCommandToDevice(
            "Brightness",
            "None",
            brightness.toString()
        )
    }

    /**
     * Returns a mode information instance from modeInfoList.
     */
    fun getModeInfo(modeName: String): ModeInfo? {
        return modeInfoList.find { it.name == modeName }
    }

    /**
     * Requests current values from the device.
     */
    suspend fun requestValues(context: Context, type: String, modeName: String) {
        val (prefsConnected, _, _) = prefs.loadConnectionState()
        if (!prefsConnected) {
            log(Log.WARN, "Not connected (according to preferences). Cannot send data.")
            return
        }

        // Check for BLUETOOTH_CONNECT permission (adjust if your target permission is different)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            log(Log.WARN, "BLUETOOTH_CONNECT permission not granted")
            return
        }

        // Discover services on the connected device.
        val services = clientBleGatt?.discoverServices()
        // Find our target service.
        val service = services?.findService(LED_CONNECTION_SERVICE)
        // Find the parameter request characteristic.
        val characteristic = service?.findCharacteristic(PARAMETER_REQUEST_UUID)

        if (characteristic != null) {
            val requestString = "$type:$modeName:{}"
            val requestBytes = requestString.toByteArray(Charsets.UTF_8)
            try {
                // Use the spread operator (*) to pass the ByteArray as vararg.
                characteristic.write(DataByteArray.from(*requestBytes))
                log(Log.DEBUG, "Requested $type values for mode: $modeName")
            } catch (e: Exception) {
                log(Log.WARN, "Failed to request values: ${e.message}")
            }
        } else {
            log(Log.WARN, "Parameter request characteristic not found")
        }
    }

//    private fun sendDisconnectCommand() {
//        // Assuming you have defined a UUID for a disconnect command characteristic
//        val disconnectCommand = "disconnect".toByteArray(Charsets.UTF_8)
//        val disconnectCharacteristic = clientBleGatt?.getService(LED_CONNECTION_SERVICE)
//            ?.findCharacteristic(DISCONNECT_UUID) // Define DISCONNECT_UUID accordingly
//        disconnectCharacteristic?.let {
//            it.value = disconnectCommand
//            clientBleGatt?.writeCharacteristic(it)
//        }
//    }
//
//
//    /**
//     * Disconnects from the device.
//     */
//    @SuppressLint("MissingPermission")
//    fun disconnect(context: Context) {
//        log(Log.DEBUG, "disconnect called")
//        try {
//            // Step 1: Disable notifications
//            clientBleGatt?.let { gatt ->
//                ledModeListCharacteristic?.let { gatt.setCharacteristicNotification(it, false) }
//                parameterResponseCharacteristic?.let { gatt.setCharacteristicNotification(it, false) }
//            }
//
//            // Step 2: Optionally send a disconnect command to the Arduino
//            sendDisconnectCommand()
//
//            // Step 3: Disconnect the BLE connection
//            clientBleGatt?.disconnect()
//
//            // Depending on your BLE library, you may want to wait for the onConnectionStateChange callback
//            // before calling close(). If needed, implement a callback that calls clientBleGatt?.close().
//            clientBleGatt?.close()
//
//            // Clear local state
//            currentGatt = null
//            deviceAddress = null
//            deviceName = null
//            modeInfoList.clear()
//            setConnectionState(false, null, null, context)
//            prefs.saveDeviceAddress("")
//        } catch (e: IOException) {
//            log(Log.ERROR, "Error during disconnect: ${e.message}")
//        } finally {
//            shouldLoadSavedStatus = false
//            log(Log.DEBUG, "Disconnected and resources cleared")
//        }
//    }


    /**
     * Helper: Retrieves a BluetoothDevice from an address.
     */
    @SuppressLint("MissingPermission")
    fun getDeviceFromAddress(deviceAddress: String): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return adapter?.getRemoteDevice(deviceAddress)
    }

    /**
     * Stub: Stops periodic connection checking.
     */

    /**
     * High-level API: turnLedOn().
     *
     * Instead of writing directly to the characteristic, this suspend function abstracts
     * the operation to "turn LED on." It writes a predefined command (here, "LED_ON") to the
     * parameter control characteristic.
     *
     * Note: This uses the ble-ktx suspend extension (see Nordic documentation).
     */

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalStdlibApi::class)
    fun connectToDevice(
        context: Context,
        device: ServerDevice, // Now expects a ServerDevice instead of BluetoothDevice
        handler: Handler,
        callback: (Boolean) -> Unit
    ) {
        Log.d(TAG, "connectToDevice called with device: ${device.name}")
        this.handler = handler
        discoveredUUIDs.clear() // Reset the discovered UUIDs list

        // Launch the connection procedure on a background dispatcher.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clientBleGatt = ClientBleGatt.connect(
                    context = context,
                    device = device, // device is already a ServerDevice
                    scope = this // CoroutineScope for the connection.
                )

                clientBleGatt?.apply {
                    // Request an increased MTU.
                    requestMtu(517)
                    log(Log.DEBUG, "MTU request successful, MTU size: $mtu")

                    // Discover services.
                    val bleServices = discoverServices() // Returns ClientBleGattServices
                    log(Log.DEBUG, "Services discovered: $bleServices")

                    cachedBleGattServices = bleServices

                    // Iterate over all services and their characteristics using for-loops.
                    for (service in bleServices.services) {
                        log(Log.DEBUG, "Discovered Service: ${service.uuid}")
                        // Add the service's UUID.
                        discoveredUUIDs.add(service.uuid)

                        for (characteristic in service.characteristics) {
                            log(
                                Log.DEBUG,
                                "Characteristic found: ${characteristic.uuid}, properties: ${characteristic.properties}"
                            )
                            discoveredUUIDs.add(characteristic.uuid)

                            // Iterate over descriptors if available.
                            for (descriptor in characteristic.descriptors) {
                                log(Log.DEBUG, "Descriptor found: ${descriptor.uuid}")
                                discoveredUUIDs.add(descriptor.uuid)
                            }
                        }
                    }

                    // When caching the LED service and its characteristics:
                    val foundService = bleServices.findService(LED_CONNECTION_SERVICE)
                    if (foundService != null) {
                        ledService = foundService
                        log(Log.DEBUG, "Cached LED service: ${foundService.uuid}")
                        // Save the service UUID in preferences.
                        prefs.saveUUID("led_service_uuid", foundService.uuid.toString())

                        parameterResponseCharacteristic = foundService.findCharacteristic(PARAMETER_RESPONSE_UUID)
                        if (parameterResponseCharacteristic != null) {
                            log(Log.DEBUG, "Cached parameterResponseCharacteristic: ${parameterResponseCharacteristic?.uuid}")
                            prefs.saveUUID("parameter_response_char_uuid", parameterResponseCharacteristic!!.uuid.toString())
                        } else {
                            log(Log.WARN, "Parameter response characteristic with UUID $PARAMETER_RESPONSE_UUID not found")
                        }

                        ledModeListCharacteristic = foundService.findCharacteristic(LED_MODE_LIST_UUID)
                        if (ledModeListCharacteristic != null) {
                            log(Log.DEBUG, "Cached ledModeListCharacteristic: ${ledModeListCharacteristic?.uuid}")
                            prefs.saveUUID("led_mode_list_char_uuid", ledModeListCharacteristic!!.uuid.toString())
                        } else {
                            log(Log.WARN, "LED mode list characteristic with UUID $LED_MODE_LIST_UUID not found")
                        }
                    } else {
                        log(
                            Log.WARN,
                            "LED service with UUID $LED_CONNECTION_SERVICE not found among discovered services: ${
                                bleServices.services.map { it.uuid }
                            }"
                        )
                    }

                    parameterResponseCharacteristic?.getNotifications()?.onEach { data ->
                        log(
                            Log.DEBUG,
                            "Notification received for parameter response: ${data.value.toHexString()}"
                        )
                    }?.launchIn(this@launch) ?: log(Log.WARN, "Parameter response characteristic not found")

                    // Optional delay to allow notifications to settle.
                    delay(2000)

                    // Read LED mode list (assumed to be a suspend function returning Boolean).
                    val success = readLedModeList(context)

                    withContext(Dispatchers.Main) {
                        if (success) {
                            if (device is RealServerDevice) {
                                setConnectionState(true, device.address, device.name, context)
                                Log.d(TAG, "Setting connection state to true for device: ${device.name}")
                            } else {
                                // Fallback in case the device type is different.
                                setConnectionState(true, null, device.name, context)
                            }
                            Log.d(TAG, "Successfully read and processed LED mode list")
                        } else {
                            Log.e(TAG, "Failed to read LED mode list")
                        }
                        callback(success)
                    }
                } ?: run {
                    log(Log.ERROR, "Failed to connect using ClientBleGatt")
                    withContext(Dispatchers.Main) { callback(false) }
                }
            } catch (e: Exception) {
                log(Log.ERROR, "Error during connection: ${e.message}")
                withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }

    // Command Queue remains available if you need to serialize GATT operations.
    private val gattQueue = LinkedList<suspend () -> Unit>()

    private fun enqueueGattOperation(operation: suspend () -> Unit) {
        log(Log.DEBUG, "enqueueGattOperation() called")
        synchronized(gattQueue) {
            Log.d(TAG, "Attempting to enqueue GATT operation")
            gattQueue.add(operation)
            Log.d(TAG, "Enqueued GATT operation, queue size: ${gattQueue.size}")

            if (gattQueue.size == 1) { // If the queue was previously empty, start processing.
                executeNextGattOperation()
                Log.d(TAG, "Started executing GATT operation")
            }
        }
    }

    private fun executeNextGattOperation() {
        log(Log.DEBUG, "executeNextGattOperation() called") // Add this!
        synchronized(gattQueue) {
            val operation = gattQueue.poll()
            if (operation != null) {
                log(Log.DEBUG, "Executing GATT operation, queue size before execution: ${gattQueue.size + 1}")

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        operation.invoke()
                        log(Log.DEBUG, "GATT operation successful")
                    } catch (e: Exception) {
                        log(Log.ERROR, "GATT operation failed: ${e.message}")
                    } finally {
                        delay(100) // Small delay to prevent stack overflow in case of rapid recursion
                        executeNextGattOperation()
                    }
                }
            } else {
                log(Log.DEBUG, "GATT operation queue is empty")
            }
        }
    }

    private suspend fun readLedModeList(context: Context): Boolean {
        return try {
            val ledChar = ledModeListCharacteristic ?: run {
                log(Log.ERROR, "ledModeListCharacteristic is null!")
                return false
            }
            log(Log.DEBUG, "Attempting to read LED mode list from characteristic: ${ledChar.uuid}")

            // Verify the characteristic supports reading using the property list.
            if (!ledChar.properties.contains(BleGattProperty.PROPERTY_READ)) {
                log(Log.ERROR, "LED Mode List characteristic does NOT support READ property!")
                return false
            }

            // Check for required BLUETOOTH_CONNECT permission.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                log(Log.ERROR, "BLUETOOTH_CONNECT permission not granted")
                return false
            }

            // Optionally delay to ensure the service is fully initialized.
            delay(500)

            // Perform the read operation using the suspend function.
            val data = ledChar.read()

            // Use the size property instead of calling size() as a function.
            if (data.size == 0) {
                log(Log.ERROR, "LED mode list response is empty or null!")
                return false
            }

            val rawData = data.value.toString(Charsets.UTF_8) // Convert data to string.
            log(Log.DEBUG, "Raw LED mode list data received: $rawData")

            processLedModeList(rawData) // Process the received data.
            true
        } catch (e: Exception) {
            log(Log.ERROR, "Error in readLedModeList operation: ${e.message}")
            false
        }
    }

    // Define an enum or constants for your expected data formats.
    enum class DataFormat {
        UTF8, HEX, ASCII, // Add other formats as needed.
    }

    // ... and then somewhere in your class, initialize it:
    private val expectedDataFormat = DataFormat.UTF8 // Or whatever your format is.

    fun getDiscoveredUUIDs(): List<UUID> {
        return discoveredUUIDs
    }

    // Periodic connection state check.
    fun startConnectionCheck(context: Context) {
        handler = Handler(Looper.getMainLooper()) // Initialize here.
        val connectionCheckRunnable = object : Runnable {
            override fun run() {
                synchronizeConnectionState(context)
                handler.postDelayed(this, 5000) // Check every 5 seconds.
            }
        }
        handler.post(connectionCheckRunnable)
    }

    // Stop the periodic connection check.
    fun stopConnectionCheck() {
        if (::handler.isInitialized) { // Check if initialized before using.
            handler.removeCallbacksAndMessages(null)
        }
    }

    // Method to update the connection state.
    fun setConnectionState(
        isConnected: Boolean,
        deviceAddress: String?,
        deviceName: String?,
        context: Context
    ) {
        Log.d(
            TAG,
            "setConnectionState called with isConnected = $isConnected, deviceAddress = $deviceAddress, deviceName = $deviceName"
        )

        if (_isConnected && !isConnected) {
            Log.d(TAG, "Connection lost. Starting reconnection service...")
            val serviceIntent = Intent(context, BluetoothReconnectionService::class.java)
            context.startForegroundService(serviceIntent)
        }

        // Prevent unnecessary updates.
        if (_isConnected == isConnected && this.deviceAddress == deviceAddress) {
            Log.d(TAG, "No change in connection state. Skipping update.")
            return
        }

        _isConnected = isConnected
        this.deviceAddress = deviceAddress
        this.deviceName = deviceName

        // Save state to preferences.
        prefs.saveConnectionState(isConnected, deviceAddress, deviceName)

        Log.d(
            TAG,
            "Connection state updated: isConnected = $_isConnected, deviceAddress = $deviceAddress, deviceName = $deviceName"
        )
    }

    // Method to synchronize the connection state.
    @SuppressLint("MissingPermission")
    fun synchronizeConnectionState(context: Context) {
        // Retrieve the BLE-connected devices from the system.
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)

        // Only check if we have a known device address.
        val isCurrentlyConnected = deviceAddress?.let { address ->
            connectedDevices.any { it.address == address }
        } ?: false

        // Load the connection state stored in PreferenceUtils.
        val (prefsConnected, prefsDeviceAddress, prefsDeviceName) = prefs.loadConnectionState()

        // If the locally maintained connection state (_isConnected) is not the same as the
        // connection state stored in preferences, update it.
        if (prefsConnected != _isConnected) {
            setConnectionState(
                prefsConnected,
                if (prefsConnected) prefsDeviceAddress else null,
                if (prefsConnected) prefsDeviceName else null,
                context
            )
            Log.d(TAG, "Synchronized connection state: local _isConnected=$_isConnected, " +
                    "prefsConnected=$prefsConnected, system-connected=$isCurrentlyConnected")
        }
    }
}

@SuppressLint("StaticFieldLeak")
object GlobalBluetoothManagerSingleton {
    @Volatile
    private var instance: GlobalBluetoothManager? = null

    fun getInstance(context: Context): GlobalBluetoothManager {
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: GlobalBluetoothManager(appContext).also { instance = it }
        }
    }

    fun reset() {
        instance = null
    }
}