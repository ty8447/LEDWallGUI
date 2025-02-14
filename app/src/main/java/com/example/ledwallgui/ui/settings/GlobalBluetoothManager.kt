package com.example.ledwallgui.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.services.BluetoothReconnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import kotlinx.coroutines.* // Import necessary coroutine functions
import java.util.* // Import for LinkedList
import kotlinx.coroutines.suspendCancellableCoroutine // Import this!
import kotlin.coroutines.resume
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID
import no.nordicsemi.android.ble.response.ReadResponse
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList


// Define your custom response type.
class RawReadResponse(val value: ByteArray) : ReadResponse()


// A simple data class to hold mode information.
data class ModeInfo(
    val name: String,
    val parameterNames: List<String>, // List of parameter *names*
    var parameterValues: MutableMap<String, String> = mutableMapOf() // Map of parameter name to value
)

class GlobalBluetoothManager(context: android.content.Context) : BleManager(context) {

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
    var deviceAddress: String? = null
    var deviceName: String? = null
    var shouldLoadSavedStatus: Boolean = false
    private lateinit var bleManager: BleManager
    val discoveredUUIDs = CopyOnWriteArrayList<UUID>()
    private var currentGatt: BluetoothGatt? = null
    private val prefs = PreferenceUtils(context)

    // References to characteristics acquired during service discovery.
    private var ledModeListCharacteristic: BluetoothGattCharacteristic? = null
    private var parameterResponseCharacteristic: BluetoothGattCharacteristic? = null
    private var parameterControlCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var handler: Handler
    private var ledParameters: Map<String, Any> = mutableMapOf()
    private var isRetrying: Boolean = false
    private var nrTries: Int = 0
    private val MAX_TRIES = 5

    // List of parsed mode information, maintained for high-level use.
    val modeInfoList: MutableList<ModeInfo> = mutableListOf()

    // (Optional) Track the current mode if needed.
    private var currentMode: String? = null

    // Logging methods – using Log.VERBOSE for detailed output.
    override fun getMinLogPriority(): Int = Log.VERBOSE

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    init {
        // Load the saved connection state when the manager is initialized
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
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        currentGatt = gatt
        val service = gatt.getService(LED_CONNECTION_SERVICE)
        if (service != null) {
            ledModeListCharacteristic = service.getCharacteristic(LED_MODE_LIST_UUID)
            parameterResponseCharacteristic = service.getCharacteristic(PARAMETER_RESPONSE_UUID)
            parameterControlCharacteristic = service.getCharacteristic(PARAMETER_CONTROL_UUID)

            log(Log.DEBUG, "Found Service: ${service.uuid}")
            service.characteristics.forEach {
                log(Log.DEBUG, "Characteristic Found: ${it.uuid}")
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
    override fun initialize() {
        log(Log.DEBUG, "Initializing device")
        parameterResponseCharacteristic?.let { characteristic ->
            setNotificationCallback(characteristic).with { _, data ->
//                val response = String(data.value, Charsets.UTF_8).trim()
//                log(Log.DEBUG, "Notification received: $response")
//                processParameterResponse(response)
            }
            enableNotifications(characteristic).enqueue()
        }
        requestMtu(517).enqueue()
    }

    /**
     * onServicesInvalidated() is called when the device disconnects or services are no longer valid.
     * Clear the characteristic references and reset internal state.
     */
    override fun onServicesInvalidated() {
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
        log(Log.DEBUG, "Split mode data: $modeData")

        for (mode in modeData) {
            log(Log.DEBUG, "Processing mode entry: $mode")

            val modeParts = mode.split(":")
            if (modeParts.size >= 2) {
                val modeName = modeParts[0].trim()
                val parametersString = modeParts[1].trim()

                log(Log.DEBUG, "Extracted Mode Name: $modeName")
                log(Log.DEBUG, "Raw Parameters String: $parametersString")

                val parameterNames = parametersString.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                log(Log.DEBUG, "Extracted Parameters: $parameterNames")

                val parameterValues = mutableMapOf<String, String>()

                val modeInfo = ModeInfo(modeName, parameterNames, parameterValues)
                modeInfoList.add(modeInfo)

                log(Log.DEBUG, "Successfully parsed mode: $modeInfo")
            } else {
                log(Log.ERROR, "Invalid mode format: $mode")
            }
        }

        val modeNames = modeInfoList.map { it.name }
        log(Log.DEBUG, "Final Parsed Mode Names: ${modeNames.joinToString(", ")}")
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

    private fun sendDataToDevice(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        data: String,
        clearBuffer: Boolean = true,
        appendNullTerminator: Boolean = false
    ) {
        if (!isConnected) {
            log(Log.WARN, "Not connected. Cannot send data.")
            return
        }
        val currentGattLocal: BluetoothGatt? = currentGatt
        if (currentGattLocal == null) {
            log(Log.WARN, "GATT is null. Cannot send data.")
            return
        }
        // Retrieve the service and characteristic using the provided UUIDs.
        val service = currentGattLocal.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            log(Log.WARN, "Characteristic not found for UUID: $characteristicUUID")
            return
        }
        if (clearBuffer) {
            characteristic.value = byteArrayOf()
            log(Log.DEBUG, "Buffer cleared for characteristic.")
        }
        // Convert the string data to a byte array.
        var message = data.toByteArray(Charsets.UTF_8)
        if (appendNullTerminator) {
            message += 0.toByte()
        }
//        // Use the new non-deprecated writeCharacteristic() method by explicitly passing the write type.
//        writeCharacteristic(characteristic, message, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
//            .enqueue { result: WriteResult ->
//                if (result.isSuccessful) {
//                    log(Log.DEBUG, "Data sent successfully: $data")
//                } else {
//                    log(Log.WARN, "Failed to send data")
//                }
//                0L // Return a Long to satisfy the lambda's return type.
//            }
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
    fun requestValues(type: String, modeName: String) {
        if (!isConnected) {
            log(Log.WARN, "Not connected to device")
            return
        }
        val currentGattLocal: BluetoothGatt? = currentGatt
        if (currentGattLocal == null) {
            log(Log.WARN, "GATT is null. Cannot request values.")
            return
        }
        val characteristic = currentGattLocal.getService(LED_CONNECTION_SERVICE)
            ?.getCharacteristic(PARAMETER_REQUEST_UUID)
        if (characteristic != null) {
            val requestString = "$type:$modeName:{}"
            characteristic.value = requestString.toByteArray(Charsets.UTF_8)
//            writeCharacteristic(characteristic, characteristic.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
//                .enqueue { result: WriteResult ->
//                    if (result.isSuccessful) {
//                        log(Log.DEBUG, "Requested $type values for mode: $modeName")
//                    } else {
//                        log(Log.WARN, "Failed to request values")
//                    }
//                    0L // Return a Long.
//                }
        } else {
            log(Log.WARN, "Parameter request characteristic not found")
        }
    }

    /**
     * Disconnects from the device.
     */
    @SuppressLint("MissingPermission")
    fun disconnect(context: Context) {
        log(Log.DEBUG, "disconnect called")
        try {
            currentGatt?.let { gattLocal ->
                gattLocal.disconnect()
                gattLocal.close()
            }
            currentGatt = null
            deviceAddress = null
            deviceName = null
            modeInfoList.clear()
            setConnectionState(false, null, null, context)
            prefs.saveDeviceAddress("")
        } catch (e: IOException) {
            log(Log.ERROR, "Error during disconnect: ${e.message}")
        } finally {
            shouldLoadSavedStatus = false
            log(Log.DEBUG, "Disconnected and resources cleared")
        }
    }

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
    fun connectToDevice(
        context: Context,
        device: BluetoothDevice,
        handler: Handler,
        callback: (Boolean) -> Unit
    ) {
        Log.d(TAG, "connectToDevice called with device: ${device.name}")

        this.handler = handler
        discoveredUUIDs.clear() // Reset the discovered UUIDs list

        bleManager = object : BleManager(context) {
            override fun getGattCallback(): BleManagerGattCallback {
                return object : BleManagerGattCallback() {
                    override fun initialize() {
                        Log.d(TAG, "Initializing BLE Manager")
                        enableNotifications(parameterResponseCharacteristic).enqueue()
                    }

                    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                        Log.d(TAG, "Checking required service and characteristic support")

                        val service = gatt.getService(LED_CONNECTION_SERVICE)
                        parameterResponseCharacteristic =
                            service?.getCharacteristic(PARAMETER_RESPONSE_UUID)
                        ledModeListCharacteristic =
                            service?.getCharacteristic(LED_MODE_LIST_UUID) // Ensure LED mode list characteristic is retrieved

                        if (service == null) {
                            Log.e(TAG, "Required service ($LED_CONNECTION_SERVICE) not found")
                        }

                        if (parameterResponseCharacteristic == null) {
                            Log.e(
                                TAG,
                                "Required characteristic ($PARAMETER_RESPONSE_UUID) not found in service"
                            )
                        }

                        if (ledModeListCharacteristic == null) {
                            Log.e(
                                TAG,
                                "LED Mode List characteristic ($LED_MODE_LIST_UUID) not found"
                            )
                        }

                        val isSupported =
                            parameterResponseCharacteristic != null && ledModeListCharacteristic != null
                        if (isSupported) {
                            Log.d(
                                TAG,
                                "Required service and characteristic found, retrieving all discovered UUIDs"
                            )

                            discoveredUUIDs.clear()
                            gatt.services?.forEach { service ->
                                Log.d(TAG, "Discovered service: ${service.uuid}")
                                service.characteristics.forEach { characteristic ->
                                    discoveredUUIDs.add(characteristic.uuid)
                                    Log.d(TAG, "Discovered characteristic: ${characteristic.uuid}")
                                }
                            }

                            Log.d(TAG, "Final Discovered UUIDs: $discoveredUUIDs")
                        } else {
                            Log.e(TAG, "Device does not support required service or characteristic")
                        }

                        return isSupported
                    }

                    override fun onServicesInvalidated() {
                        Log.d(TAG, "Services invalidated, clearing UUIDs")
                        discoveredUUIDs.clear()
                    }
                }
            }

            override fun log(priority: Int, message: String) {
                Log.d(TAG, "Nordic BLE Log: $message")
            }
        }

        bleManager.connect(device)
            .retry(3, 100)
            .useAutoConnect(false)
            .done {
                setConnectionState(true, device.address, device.name, context)
                val preferenceUtils = PreferenceUtils(context)
                preferenceUtils.saveDeviceAddress(device.address)
                Log.d(TAG, "BLE device connected, waiting for services...")

                handler.postDelayed({
                    Log.d(TAG, "Final Discovered UUIDs: $discoveredUUIDs")

                    if (discoveredUUIDs.isNotEmpty()) {
                        parameterResponseCharacteristic?.let { characteristic ->
                            setNotificationCallback(characteristic).with { _, data ->
                                val byteArray = data.value
                                if (byteArray == null || byteArray.isEmpty()) {
                                    Log.e(TAG, "Notification data is null or empty!")
                                    return@with
                                }

                                val response = String(byteArray, Charsets.UTF_8).trim()
                                Log.d(
                                    TAG,
                                    "Notification received on parameterResponseCharacteristic: $response"
                                )
                            }
                            enableNotifications(characteristic).enqueue() // Enable notifications
                            // *** Read LED Mode List and process data ***
                                CoroutineScope(Dispatchers.IO).launch {
                                    val success = readLedModeList()
                                    if (success) {
                                        Log.d(TAG, "Successfully read and processed LED mode list")
                                    } else {
                                        Log.e(TAG, "Failed to read LED mode list")
                                    }
                                    callback(success) // Call callback after attempting read
                                }
                        } ?: run {
                            Log.e(
                                TAG,
                                "parameterResponseCharacteristic is null. Cannot set notification callback."
                            )
                            callback(false)
                        }
                    } else {
                        callback(false)
                    }
                }, 1000)
            }
            .enqueue()
    }

    private val gattQueue = LinkedList<suspend () -> Unit>() // Command Queue

    private fun enqueueGattOperation(operation: suspend () -> Unit) {
        synchronized(gattQueue) {
            gattQueue.add(operation)
            if (gattQueue.size == 1) { // Start the queue if it's empty
                executeNextGattOperation()
            }
        }
    }

    private fun executeNextGattOperation() {
        synchronized(gattQueue) {
            val operation = gattQueue.poll()
            if (operation != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        operation.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "GATT operation failed: ${e.message}")
                    } finally {
                        executeNextGattOperation() // Process next item in the queue
                    }
                }
            }
        }
    }

    private suspend fun readLedModeList(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val job = CoroutineScope(Dispatchers.IO).launch {
                enqueueGattOperation { // Enqueue the read operation
                    try {
                        ledModeListCharacteristic?.let { characteristic ->
                            log(
                                Log.DEBUG,
                                "Attempting to read LED mode list from characteristic: ${characteristic.uuid}"
                            )

                            // 1. Check Characteristic Properties
                            val properties = characteristic.properties
                            val canRead =
                                (properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                            if (!canRead) {
                                log(
                                    Log.ERROR,
                                    "LED Mode List characteristic DOES NOT have READ property!"
                                )
                                continuation.resume(false) // Resume with failure
                                return@let // Important: Exit the lambda after resuming
                            }

                            // 2. Delay (If still necessary, try increasing if needed)
                            delay(500) // 500ms delay

                            val data = readCharacteristic(characteristic).suspend()

                            if (data == null || data.size() == 0) {
                                log(Log.ERROR, "LED mode list response is empty or null!")
                                continuation.resume(false)
                                return@let
                            }

                            val byteArray = data.value!!

                            // *** CONVERT BYTE ARRAY TO STRING BASED ON EXPECTED FORMAT ***
                            val rawData = when (expectedDataFormat) {
                                DataFormat.UTF8 -> String(byteArray, Charsets.UTF_8)
                                DataFormat.HEX -> byteArray.joinToString("") {
                                    String.format(
                                        "%02X",
                                        it
                                    )
                                }

                                DataFormat.ASCII -> String(byteArray, Charsets.US_ASCII)
                                else -> {
                                    log(Log.ERROR, "Unknown data format: $expectedDataFormat")
                                    continuation.resume(false)
                                    return@let
                                }
                            }

                            log(Log.DEBUG, "Raw LED mode list data received: $rawData")

                            processLedModeList(rawData)
                            continuation.resume(true)

                        } ?: run {
                            log(Log.ERROR, "ledModeListCharacteristic is null!")
                            continuation.resume(false)
                        }
                    } catch (e: Exception) {
                        log(Log.ERROR, "Error in readLedModeList operation: ${e.message}")
                        continuation.resume(false)
                    }
                }
            }

            // CORRECT PLACEMENT OF invokeOnCancellation: INSIDE suspendCancellableCoroutine
            continuation.invokeOnCancellation { // Handle cancellation
                job.cancel()
                Log.w(TAG, "readLedModeList coroutine cancelled")
            }
        } // End of suspendCancellableCoroutine block
    }

    // Define an enum or constants for your expected data formats
    enum class DataFormat {
        UTF8, HEX, ASCII, // Add other formats as needed
    }

    // ... and then somewhere in your class, initialize it:
    private val expectedDataFormat = DataFormat.UTF8 // Or whatever your format is

    fun getDiscoveredUUIDs(): List<UUID> {
        return discoveredUUIDs
    }

    // Periodic connection state check
    fun startConnectionCheck(context: Context) {
        handler = Handler(Looper.getMainLooper()) // Initialize here
        val connectionCheckRunnable = object : Runnable {
            override fun run() {
                synchronizeConnectionState(context)
                handler.postDelayed(this, 5000) // Check every 5 seconds
            }
        }
        handler.post(connectionCheckRunnable)
    }

    // Stop the periodic connection check
    fun stopConnectionCheck() {
        if (::handler.isInitialized) { // Check if initialized before using
            handler.removeCallbacksAndMessages(null)
        }
    }

    // Method to update the connection state
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

        // Prevent unnecessary updates
        if (_isConnected == isConnected && this.deviceAddress == deviceAddress) {
            Log.d(TAG, "No change in connection state. Skipping update.")
            return
        }

        _isConnected = isConnected
        this.deviceAddress = deviceAddress
        this.deviceName = deviceName

        // Save state to preferences
        prefs.saveConnectionState(isConnected, deviceAddress, deviceName)

        Log.d(
            TAG,
            "Connection state updated: isConnected = $_isConnected, deviceAddress = $deviceAddress, deviceName = $deviceName"
        )
    }

    // Method to synchronize the connection state
    @SuppressLint("MissingPermission")
    fun synchronizeConnectionState(context: Context) {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)

        // Only check if we have a known device address
        val isCurrentlyConnected = deviceAddress?.let { address ->
            connectedDevices.any { it.address == address }
        } ?: false

        // Update connection state if there's a change
        if (_isConnected != isCurrentlyConnected) {
            setConnectionState(
                isCurrentlyConnected,
                if (isCurrentlyConnected) deviceAddress else null,
                if (isCurrentlyConnected) deviceName else null,
                context
            )
        }

        Log.d(TAG, "Synchronized connection state: $_isConnected")
    }

    // Additional high-level methods (e.g. turnLedOff(), setLedColor(), etc.) can be added here.
}
