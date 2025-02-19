package com.example.ledwallgui.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
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
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.data.BleGattProperty
import no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray
import java.util.*  // e.g. for LinkedList if still needed
import java.util.UUID
import java.io.IOException
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
            // Use the Nordic findCharacteristic() method which returns a ClientBleGattCharacteristic.
            ledModeListCharacteristic = service.findCharacteristic(LED_MODE_LIST_UUID)
            parameterResponseCharacteristic = service.findCharacteristic(PARAMETER_RESPONSE_UUID)
            parameterControlCharacteristic = service.findCharacteristic(PARAMETER_CONTROL_UUID)

            log(Log.DEBUG, "Found Service: ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                log(Log.DEBUG, "Characteristic Found: ${characteristic.uuid}")
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
        // Request an MTU update if needed. (Assuming requestMtu() is a suspend function.)
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
        if (!_isConnected) {
            log(Log.WARN, "Not connected. Cannot send data.")
            return
        }

        // Launch a coroutine on the Main dispatcher for UI-safe operations.
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Discover services using Nordic's API.
                val services = clientBleGatt?.discoverServices()
                val service = services?.findService(serviceUUID)
                if (service == null) {
                    log(Log.WARN, "Service not found for UUID: $serviceUUID")
                    return@launch
                }

                // Retrieve the Nordic characteristic (of type ClientBleGattCharacteristic).
                val characteristic = service.findCharacteristic(characteristicUUID)
                if (characteristic == null) {
                    log(Log.WARN, "Characteristic not found for UUID: $characteristicUUID")
                    return@launch
                }

                // (Optional) Clear the characteristic's buffer.
                if (clearBuffer) {
                    // The Nordic library manages characteristic values internally.
                    // If needed, you could write an empty DataByteArray.
                    characteristic.write(DataByteArray.from(*byteArrayOf()))
                    log(Log.DEBUG, "Buffer cleared for characteristic.")
                }

                // Convert the string data to a byte array.
                var message = data.toByteArray(Charsets.UTF_8)
                if (appendNullTerminator) {
                    message += 0.toByte()
                }

                // Create a DataByteArray from your message.
                val dataByteArray = DataByteArray.from(*message)

                // Write the data to the characteristic using its suspend function.
                characteristic.write(dataByteArray)
                log(Log.DEBUG, "Data sent successfully: $data")
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
        if (!_isConnected) {
            log(Log.WARN, "Not connected to device")
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


    /**
     * Disconnects from the device.
     */
    @SuppressLint("MissingPermission")
    fun disconnect(context: Context) {
        log(Log.DEBUG, "disconnect called")
        try {
            clientBleGatt?.disconnect()
            clientBleGatt?.close()
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
                    val services = discoverServices()
                    log(Log.DEBUG, "Services discovered: $services")

                    // Retrieve required characteristics from the LED connection service.
                    val service = services.findService(LED_CONNECTION_SERVICE)
                    parameterResponseCharacteristic = service?.findCharacteristic(PARAMETER_RESPONSE_UUID)
                    ledModeListCharacteristic = service?.findCharacteristic(LED_MODE_LIST_UUID)

                    parameterResponseCharacteristic?.getNotifications()?.onEach { data ->
                        log(Log.DEBUG, "Notification received for parameter response: ${data.value.toHexString()}")
                    }?.launchIn(this@launch) ?: log(Log.WARN, "Parameter response characteristic not found")

                    // Optionally delay to allow notifications to settle.
                    delay(2000)

                    // Read LED mode list (assumed to be a suspend function returning Boolean).
                    val success = readLedModeList(context)

                    withContext(Dispatchers.Main) {
                        if (success) {
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
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)

        // Only check if we have a known device address.
        val isCurrentlyConnected = deviceAddress?.let { address ->
            connectedDevices.any { it.address == address }
        } ?: false

        // Update connection state if there's a change.
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
