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

// A simple data class to hold mode information.
data class ModeInfo(
    val name: String,
    val parameterNames: List<String>, // List of parameter *names*
    var parameterValues: MutableMap<String, String> = mutableMapOf() // Map of parameter name to value
)

object BluetoothInstance {
    var clientBleGatt: ClientBleGatt? = null
    var cachedBleGattServices: ClientBleGattServices? = null
    var ledService: ClientBleGattService? = null
    var ledModeListCharacteristic: ClientBleGattCharacteristic? = null
    var parameterResponseCharacteristic: ClientBleGattCharacteristic? = null
    var parameterControlCharacteristic: ClientBleGattCharacteristic? = null

    fun clearCache() {
        clientBleGatt = null
        cachedBleGattServices = null
        ledService = null
        ledModeListCharacteristic = null
        parameterResponseCharacteristic = null
        parameterControlCharacteristic = null
        Log.d("BluetoothInstance", "Cache cleared")
    }
}

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
    }

    private var _isConnected: Boolean = false
    private lateinit var handler: Handler
    var deviceAddress: String? = null
    var deviceName: String? = null
    var shouldLoadSavedStatus: Boolean = false
    private val discoveredUUIDs = CopyOnWriteArrayList<UUID>()
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

    // List of parsed mode information, maintained for high-level use.
    private val modeInfoList: MutableList<ModeInfo> = mutableListOf()

    // (Optional) Track the current mode if needed.
    private var currentMode: String? = null

    // Logging methods – using Log.VERBOSE for detailed output.
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
    private fun sendDataToDevice(data: String, clearBuffer: Boolean = true, appendNullTerminator: Boolean = false) {
        val (prefsConnected, savedDeviceAddress, _) = prefs.loadConnectionState()

        if (!prefsConnected || BluetoothInstance.cachedBleGattServices == null) {
            log(Log.WARN, "Not connected or services not discovered. Attempting to connect and discover.")

            CoroutineScope(Dispatchers.IO).launch {
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                val bluetoothDevice = savedDeviceAddress?.let { bluetoothAdapter?.getRemoteDevice(it) }

                if (bluetoothDevice == null) {
                    withContext(Dispatchers.Main) {
                        log(Log.ERROR, "Saved device address not found or invalid. Cannot connect.")
                    }
                    return@launch
                }

                // ***KEY CHANGE: Convert BluetoothDevice to ServerDevice***
                val serverDevice = bluetoothDevice.toServerDevice() // Implement this conversion function

                if (serverDevice == null) { // Handle conversion failure
                    withContext(Dispatchers.Main) {
                        log(Log.ERROR, "Failed to convert BluetoothDevice to ServerDevice.")
                    }
                    return@launch
                }

                val success = reconnectAndDiscoverServices(serverDevice) // Pass the ServerDevice

                withContext(Dispatchers.Main) {
                    if (success) {
                        log(Log.DEBUG, "Reconnect and rediscovery successful. Sending data.")
                        sendDataToDeviceInternal(data, clearBuffer, appendNullTerminator)
                    } else {
                        log(Log.ERROR, "Failed to connect and discover services. Cannot send data.")
                    }
                }
            }
        } else {
            log(Log.DEBUG, "Already connected and services discovered. Sending data directly.")
            sendDataToDeviceInternal(data, clearBuffer, appendNullTerminator)
        }
    }


    // ***IMPLEMENT THIS CONVERSION FUNCTION***
    fun BluetoothDevice.toServerDevice(): ServerDevice? {
        return try {
            // This is how you convert a BluetoothDevice to a RealServerDevice
            // Make sure you have the correct library imported
            RealServerDevice(this)
        } catch (e: Exception) {
            Log.e("ConversionError", "Error converting BluetoothDevice to ServerDevice: ${e.message}")
            null
        }
    }

    private suspend fun reconnectAndDiscoverServices(device: ServerDevice): Boolean {
        log(Log.DEBUG, "reconnectAndDiscoverServices called.")

        clientBleGatt?.disconnect()
        clientBleGatt = null
        log(Log.DEBUG, "clientBleGatt disconnected and set to null.")

        if (device == null) {
            log(Log.ERROR, "Device is null. Cannot reconnect.")
            return false
        }

        var connectSuccess = false
        val connectJob = CoroutineScope(Dispatchers.IO).launch { // Launch in IO dispatcher
            connectSuccess = try {
                BluetoothInstance.clientBleGatt = ClientBleGatt.connect(context, device, this) // Use ServerDevice and correct scope
                val result = BluetoothInstance.clientBleGatt != null
                log(Log.DEBUG, "Connection attempt result: $result")
                result
            } catch (e: Exception) {
                log(Log.ERROR, "Connection error: ${e.message}")
                false
            }
        }
        connectJob.join()

        if (!connectSuccess) {
            log(Log.ERROR, "Connection failed.")
            return false
        }

        log(Log.DEBUG, "Connection successful. Discovering services.")

        return try {
            val bleServices = clientBleGatt?.discoverServices()
            if (bleServices != null) {
                // Cache the services using the singleton
                BluetoothInstance.cachedBleGattServices = bleServices
                log(
                    Log.DEBUG,
                    "Services discovered and cached: ${
                        BluetoothInstance.cachedBleGattServices?.services?.map { service -> service.uuid }
                    }"
                )

                val foundService = bleServices.findService(LED_CONNECTION_SERVICE)
                if (foundService != null) {
                    // (UUID saving logic would go here)
                    ledService = foundService // Cache the service
                    log(Log.DEBUG, "LED Service cached: $ledService")
                } else {
                    log(Log.WARN, "LED service NOT found after discovery!")
                    log(
                        Log.DEBUG,
                        "Available Services: ${
                            bleServices.services.map { service -> service.uuid }
                        }"
                    )
                }
                true
            } else {
                log(Log.ERROR, "Service discovery failed.")
                false
            }
        } catch (e: Exception) {
            log(Log.ERROR, "Error during service discovery: ${e.message}")
            false
        }
    }

    private fun sendDataToDeviceInternal(
        data: String,
        clearBuffer: Boolean = true,
        appendNullTerminator: Boolean = false
    ) {
        // Wrap entire function body in a lambda (labeled "sendData") so we can use qualified returns.
        run sendData@{
            log(Log.DEBUG, "sendDataToDeviceInternal called.")

            val ledServiceUUIDString = prefs.getString("led_service_uuid", null)
            val parameterControlCharUUIDString = prefs.getString("parameter_control_char_uuid", null)

            log(Log.DEBUG, "Retrieved led_service_uuid: $ledServiceUUIDString")
            log(Log.DEBUG, "Retrieved parameter_control_char_uuid: $parameterControlCharUUIDString")

            if (ledServiceUUIDString == null || parameterControlCharUUIDString == null) {
                log(Log.WARN, "LED service or parameter control characteristic UUIDs not found in preferences.")
                return@sendData
            }

            val ledServiceUUID = UUID.fromString(ledServiceUUIDString)
            val parameterControlCharUUID = UUID.fromString(parameterControlCharUUIDString)

            log(Log.DEBUG, "ledServiceUUID: $ledServiceUUID")
            log(Log.DEBUG, "parameterControlCharUUID: $parameterControlCharUUID")

            val bleServices = BluetoothInstance.cachedBleGattServices // Get the services from the singleton
            log(
                Log.DEBUG,
                "cachedBleGattServices before use: ${
                    bleServices?.services?.map { service -> service.uuid }
                }"
            )

            bleServices?.let { nonNullBleServices ->
                val service = nonNullBleServices.services.find { svc -> svc.uuid == ledServiceUUID }
                if (service == null) {
                    log(
                        Log.WARN,
                        "Service $ledServiceUUID not found in cached services. Cached Services: ${
                            nonNullBleServices.services.map { svc -> svc.uuid }
                        }"
                    )
                    return@sendData
                }

                // Assuming an extension function or member function exists:
                val characteristic = service.findCharacteristic(parameterControlCharUUID)
                if (characteristic == null) {
                    log(
                        Log.WARN,
                        "Characteristic $parameterControlCharUUID not found in service $ledServiceUUID. Available Characteristics: ${
                            service.characteristics.map { char -> char.uuid }
                        }"
                    )
                    return@sendData
                }

                log(Log.DEBUG, "Characteristic found: $characteristic")

                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        if (clearBuffer) {
                            val clearResult = characteristic.write(DataByteArray.from(*byteArrayOf()))
                            log(
                                Log.DEBUG,
                                "Buffer cleared for characteristic ${characteristic.uuid}. Result: $clearResult"
                            )
                        }
                        var messageBytes = data.toByteArray(Charsets.UTF_8)
                        if (appendNullTerminator) messageBytes += 0.toByte()
                        val dataByteArray = DataByteArray.from(*messageBytes)

                        val writeResult = characteristic.write(dataByteArray)
                        log(
                            Log.DEBUG,
                            "Data sent. Result: $writeResult"
                        )
                        log(
                            Log.DEBUG,
                            "Data sent successfully: $data to characteristic ${characteristic.uuid}"
                        )
                    } catch (e: Exception) {
                        log(Log.WARN, "Failed to send data: ${e.message}")
                    }
                }
            } ?: run {
                log(Log.WARN, "bleServices is null. Cannot send data.")
                return@sendData
            }
        }
    }


    private fun sendCommandToDevice(type: String, name: String, values: String) {
        val formattedData = "$type:$name:{$values}"
        sendDataToDevice(formattedData, clearBuffer = true, appendNullTerminator = false)
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
        device: ServerDevice,
        handler: Handler,
        callback: (Boolean) -> Unit
    ) {
        val TAG = "connectToDevice"
        Log.d(TAG, "connectToDevice called with device: ${device.name}")
        this.handler = handler
        val discoveredUUIDs = CopyOnWriteArrayList<UUID>()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                clientBleGatt = ClientBleGatt.connect(context, device, this)

                clientBleGatt?.let { gatt ->
                    gatt.requestMtu(517)
                    log(Log.DEBUG, "MTU request successful, MTU size: ${gatt.mtu}")

                    val bleServices = gatt.discoverServices()

                    // Populate BluetoothInstance Immediately After Successful Discovery
                    BluetoothInstance.clientBleGatt = gatt
                    BluetoothInstance.cachedBleGattServices = bleServices

                    bleServices.services.forEach { service ->  // Log all services and characteristics
                        log(Log.DEBUG, "Discovered Service: ${service.uuid}")
                        discoveredUUIDs.add(service.uuid)
                        service.characteristics.forEach { characteristic ->
                            log(Log.DEBUG, "Characteristic: ${characteristic.uuid}, Properties: ${characteristic.properties}")
                            discoveredUUIDs.add(characteristic.uuid)
                            characteristic.descriptors.forEach { descriptor ->
                                log(Log.DEBUG, "Descriptor: ${descriptor.uuid}")
                                discoveredUUIDs.add(descriptor.uuid)
                            }
                        }
                    }

                    val ledService = bleServices.findService(LED_CONNECTION_SERVICE)

                    if (ledService != null) {
                        BluetoothInstance.ledService = ledService

                        val ledModeListCharacteristic = ledService.findCharacteristic(LED_MODE_LIST_UUID)
                        if (ledModeListCharacteristic != null) {
                            BluetoothInstance.ledModeListCharacteristic = ledModeListCharacteristic // Cache DIRECTLY
                            prefs.saveUUID("led_mode_list_char_uuid", ledModeListCharacteristic.uuid.toString())
                        } else {
                            log(Log.ERROR, "LED Mode List Characteristic NOT FOUND! UUID: $LED_MODE_LIST_UUID")
                        }

                        val parameterResponseCharacteristic = ledService.findCharacteristic(PARAMETER_RESPONSE_UUID)
                        if (parameterResponseCharacteristic != null) {
                            BluetoothInstance.parameterResponseCharacteristic = parameterResponseCharacteristic // Cache DIRECTLY
                            prefs.saveUUID("parameter_response_char_uuid", parameterResponseCharacteristic.uuid.toString())
                        } else {
                            log(Log.ERROR, "Parameter Response Characteristic NOT FOUND! UUID: $PARAMETER_RESPONSE_UUID")
                        }

                        val parameterControlCharacteristic = ledService.findCharacteristic(PARAMETER_CONTROL_UUID)
                        if (parameterControlCharacteristic != null) {
                            BluetoothInstance.parameterControlCharacteristic = parameterControlCharacteristic // Cache DIRECTLY
                            prefs.saveUUID("parameter_control_char_uuid", parameterControlCharacteristic.uuid.toString())
                        } else {
                            log(Log.ERROR, "Parameter Control Characteristic NOT FOUND! UUID: $PARAMETER_CONTROL_UUID")
                        }

                    } else {
                        log(Log.ERROR, "LED Service NOT FOUND! UUID: $LED_CONNECTION_SERVICE")
                    }

                    parameterResponseCharacteristic?.getNotifications()?.onEach { data ->
                        log(Log.DEBUG, "Notification received for parameter response: ${data.value.toHexString()}")
                    }?.launchIn(this) ?: log(Log.WARN, "Parameter response characteristic not found")

                    delay(2000)

                    val success = readLedModeList(context) // Assuming this is defined

                    withContext(Dispatchers.Main) {
                        if (success) {
                            setConnectionState(true, device.address, device.name, context)
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
        ledModeListCharacteristic = BluetoothInstance.ledModeListCharacteristic
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