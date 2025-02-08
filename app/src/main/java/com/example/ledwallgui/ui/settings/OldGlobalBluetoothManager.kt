package com.example.ledwallgui.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.ledwallgui.PreferenceUtils
import com.example.ledwallgui.services.BluetoothReconnectionService
import com.example.ledwallgui.ui.modes.ModeManager.currentMode
import java.io.IOException
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object OldGlobalBluetoothManager {
    private val LED_CONNECTION_SERVICE: UUID =
        UUID.fromString("12345678-1234-5678-1234-56789abcdef0") //Server for Modes
    private val LED_MODE_LIST_UUID: UUID =
        UUID.fromString("12345678-1234-5678-1234-56789abcdef1") //Get List of Saved Modes from Arduino
    private val PARAMETER_REQUEST_UUID: UUID =
        UUID.fromString("12345678-1234-5678-1234-56789abcdef2") //ASK FOR LED Parameter Values
    private val PARAMETER_RESPONSE_UUID: UUID =
        UUID.fromString("12345678-1234-5678-1234-56789abcdef3") //ASK FOR LED Parameter Values
    private val PARAMETER_CONTROL_UUID: UUID =
        UUID.fromString("12345678-1234-5678-1234-56789abcdef4") //Write Current Mode
    private val CLIENT_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")  // Use UUID directly
    var bluetoothSocket: BluetoothSocket? = null
    private var _isConnected: Boolean = false
    private var _deviceAddress: String? = null
    private var _deviceName: String? = null  // Added device name
    private var gatt: BluetoothGatt? = null
    private var parameterResponseCharacteristic: BluetoothGattCharacteristic? = null // Declare it here
    var shouldLoadSavedStatus = false
    private val discoveredUUIDs = CopyOnWriteArrayList<UUID>()
    var modeInfoList: MutableList<ModeInfo> = mutableListOf()
    private var ledParameters: Map<String, Any> = mutableMapOf()

    private val commandQueue: Queue<Runnable> = LinkedList()
    private var commandQueueBusy: Boolean = false
    private var isRetrying: Boolean = false
    private var nrTries: Int = 0
    private const val MAX_TRIES = 5

    private val bleHandler = Handler(Looper.getMainLooper())

    // Public function to get discovered UUIDs
    fun getDiscoveredUUIDs(): List<UUID> {
        return discoveredUUIDs
    }

    // Public getter for isConnected
    var isConnected: Boolean = false
        get() = _isConnected

    private const val TAG = "GlobalBluetoothManager"
    private lateinit var handler: Handler

    // Public getter for deviceAddress
    var deviceAddress: String? = null
        get() = _deviceAddress

    // Public getter for deviceName
    var deviceName: String? = null
        get() = _deviceName

    // Method to update the connection state
    fun setConnectionState(isConnected: Boolean, deviceAddress: String?, deviceName: String?, context: Context) {
        Log.d(TAG, "setConnectionState called with isConnected = $isConnected, deviceAddress = $deviceAddress, deviceName = $deviceName")

        if (_isConnected && !isConnected) {
            Log.d(TAG, "Connection lost. Starting reconnection service...")
            val serviceIntent = Intent(context, BluetoothReconnectionService::class.java)
            context.startForegroundService(serviceIntent) // Start the service
        }

        _isConnected = isConnected
        _deviceAddress = deviceAddress
        _deviceName = deviceName

        Log.d(TAG, "Connection state updated: isConnected = $_isConnected, deviceAddress = $_deviceAddress, deviceName = $_deviceName")
    }

    fun getDeviceFromAddress(address: String): BluetoothDevice? {
        // Retrieve the BluetoothDevice object using the address (implementation depends on your app structure)
        // You can use BluetoothAdapter to get the device from address
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return bluetoothAdapter?.getRemoteDevice(address)
    }

    // Method to synchronize the connection state
    @SuppressLint("MissingPermission")
    fun synchronizeConnectionState(context: Context) {
        // Get a list of connected devices
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)

        // Check if the device is still connected
        val isCurrentlyConnected = connectedDevices.any { it.address == _deviceAddress }
        // Update the connection state
        if (_isConnected != isCurrentlyConnected) {
            _isConnected = isCurrentlyConnected
            Log.d(TAG, "Synchronized connection state: $_isConnected")
        }
    }

    // Periodic connection state check
    fun startConnectionCheck(context: Context) {
        handler = Handler(Looper.getMainLooper())
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
        if (::handler.isInitialized) {
            handler.removeCallbacksAndMessages(null)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(
        context: Context,
        device: BluetoothDevice,
        handler: Handler,
        callback: (Boolean) -> Unit
    ) {
        Log.d(TAG, "connectToDevice called with device: ${device.name}")
        this.handler = handler

        gatt?.close()
        gatt = null

        // Create a BluetoothGattCallback to handle GATT events
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange called with status: $status, newState: $newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server.")
                    modeInfoList.clear()
                    setConnectionState(true, device.address, device.name, context)

                    val preferenceUtils = PreferenceUtils(context)
                    preferenceUtils.saveDeviceAddress(device.address)

                    Log.d(TAG, "Discovering services...")
                    gatt?.discoverServices() ?: run {
                        Log.e(TAG, "Failed to call discoverServices. GATT is null.")
                        callback(false) // Notify failure if gatt is null
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT server.")
                    setConnectionState(false, null, null, context) // Will trigger reconnection attempt if necessary
                    callback(false) // Notify disconnection
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Discovered services:")
                    synchronized(discoveredUUIDs) {
                        discoveredUUIDs.clear()

                        gatt?.services?.forEach { service ->
                            Log.d(TAG, "Service UUID: ${service.uuid}")
                            discoveredUUIDs.add(service.uuid)
                            service.characteristics.forEach { characteristic ->
                                Log.d(TAG, "  Characteristic UUID: ${characteristic.uuid}")
                                discoveredUUIDs.add(characteristic.uuid)

                                if (characteristic.uuid == LED_MODE_LIST_UUID) { // Keep this read
                                    commandQueue.add {
                                        gatt.readCharacteristic(characteristic)
                                    }
                                    nextCommand()
                                }
                            }
                        }
                    }

                    // Enable notifications *AFTER* discovering all characteristics
//                    commandQueue.add {
//                        enableNotifications(gatt!!)
//                    }

                    val service = gatt?.getService(LED_CONNECTION_SERVICE)
                    if (service != null) {
                        parameterResponseCharacteristic =
                            service.getCharacteristic(PARAMETER_RESPONSE_UUID)

                        if (parameterResponseCharacteristic != null) { // Check if it's not null
                            enableNotifications(gatt!!, parameterResponseCharacteristic!!)
                        } else {
                            Log.e(
                                TAG,
                                "parameterResponseCharacteristic is null after service discovery!"
                            )
                            callback(false) // Important: Notify if characteristic is not found.
                            return // Stop here to avoid a NullPointerException
                        }
                    }
                    nextCommand()

                    callback(true) // Notify successful service discovery
                } else {
                    Log.w(TAG, "onServicesDiscovered received: $status")
                    callback(false) // Notify service discovery failure
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                Log.d(TAG, "onCharacteristicRead called with status: $status")

                // Ensure characteristic is non-null and status is successful
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                    Log.e(TAG, "Read failed for characteristic: ${characteristic?.uuid}, status: $status")
                    retryCommand()
                    return
                }

                // Log the characteristic UUID
                Log.d(TAG, "Reading characteristic: ${characteristic.uuid}")

                // Get characteristic value
                val value = characteristic.value
                if (value == null || value.isEmpty()) {
                    Log.e(TAG, "Characteristic value is null or empty for: ${characteristic.uuid}")
                    retryCommand()
                    return
                }

                // Log value in byte array format
                Log.d(TAG, "Characteristic raw value (bytes): ${value.contentToString()}")

                // Attempt to process the value
                try {
                    val valueAsString = String(value, Charsets.UTF_8)
                    Log.i(TAG, "Characteristic value (String): $valueAsString")

                    // Handle specific UUID cases (example: LED_MODE_LIST_UUID)
                    when (characteristic.uuid) {
                        LED_MODE_LIST_UUID -> {
                            Log.d(TAG, "Processing characteristic read response for LED_MODE_LIST_UUID")

                            // Parse raw data
                            val rawData = String(value, Charsets.UTF_8)
                            Log.d(TAG, "Raw data: $rawData")

                            val modeData = rawData.split(";").filter { it.isNotBlank() }
                            modeData.forEach { mode ->
                                // Split mode data by ":" to separate name, description, and parameters
                                val modeParts = mode.split(":")
                                if (modeParts.size >= 2) {
                                    val modeName = modeParts[0].trim()
                                    val parametersString = modeParts[1].trim()

                                    // Extract parameters by splitting by commas
                                    val parameterNames = parametersString.split(",").map { it.trim() }.filter { it.isNotBlank() }

                                    // Initialize parameterValues with empty strings or default values
                                    val parameterValues = mutableMapOf<String, String>()

                                    val modeInfo = ModeInfo(modeName, parameterNames, parameterValues)
                                    modeInfoList.add(modeInfo)

                                    Log.d(TAG, "Parsed ModeInfo: $modeInfo")
                                } else {
                                    Log.e(TAG, "Invalid mode format: $mode")
                                }
                            }

                            val modeNames = modeInfoList.map { it.name }
                            Log.d(TAG, "All Mode Names: ${modeNames.joinToString(", ")}")
                        }
                        else -> {
                            Log.w(TAG, "Unhandled characteristic UUID: ${characteristic.uuid}")
                        }
                    }


                } catch (e: Exception) {
                    Log.e(TAG, "Error processing characteristic value for: ${characteristic.uuid}", e)
                    retryCommand()
                    return
                }

                // Read the descriptor after reading the characteristic (if needed)
//                val descriptor = characteristic.getDescriptor(CLIENT_DESCRIPTOR_UUID)
//                if (descriptor != null) {
//                    Log.d(TAG, "Reading descriptor for characteristic: ${characteristic.uuid}")
//                    if (gatt!!.readDescriptor(descriptor)) {
//                        Log.d(TAG, "Descriptor read successfully for characteristic: ${characteristic.uuid}")
//                    } else {
//                        Log.e(TAG, "Failed to read descriptor for characteristic: ${characteristic.uuid}")
//                    }
//                }

                // Command completed successfully
                Log.d(TAG, "Characteristic ${characteristic.uuid} read successfully and processed.")
                completedCommand()
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Write successful for characteristic: ${characteristic.uuid}")
                } else {
                    Log.e(TAG, "Write failed for characteristic: ${characteristic.uuid}, status: $status")
                    retryCommand()
                }

                completedCommand()
            }

            private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {

                // Log the characteristic UUID for verification
                Log.d(TAG, "Attempting to enable notifications for characteristic: ${characteristic.uuid}")

                if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                    Log.e(TAG, "Characteristic ${characteristic.uuid} does not support notifications.")
                    return // Exit if notifications not supported
                }

                gatt.setCharacteristicNotification(characteristic, true) // Call this *before* writing the descriptor

                val descriptor = characteristic.getDescriptor(CLIENT_DESCRIPTOR_UUID)
                if (descriptor == null) {
                    Log.e(TAG, "CLIENT_CHARACTERISTIC_CONFIG_UUID descriptor not found!")
                    return // Handle null descriptor
                }

                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                val writeResult = gatt.writeDescriptor(descriptor)
                Log.i("Bluetooth", "Descriptor write initiated, result: $writeResult") // Log the result!
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                Log.d(TAG, "onCharacteristicChanged called")

                if (characteristic == null || characteristic.value == null) {
                    Log.e(TAG, "Characteristic or value is null, cannot process update")
                    return
                }

                val response = try {
                    String(characteristic.value, Charsets.UTF_8).trim()
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding characteristic value: ${e.message}")
                    return
                }

                Log.d("BluetoothGatt", "Notification received for characteristic: ${characteristic.uuid}")
                Log.d(TAG, "Received parameter values: $response")

                if (characteristic.uuid == PARAMETER_RESPONSE_UUID) {
                    try {
                        val parametersMap = parseParameterResponse(response)
                        val modeName = parametersMap["Name"] // Get the mode name from the response

                        if (modeName != null) {
                            val modeInfo = modeInfoList.find { it.name == modeName }

                            if (modeInfo != null) { // Check if modeInfo is found
                                modeInfo.parameterValues.clear() // Clear existing values
                                modeInfo.parameterValues.putAll(parametersMap) // Add the new values

                                // Update selectedModeParams if it's the current mode
                                if (currentMode == modeName) {
                                    ledParameters = parametersMap
                                }

                                Log.d(TAG, "Updated parameter values for mode $modeName: ${modeInfo.parameterValues}")
                            } else {
                                Log.w(TAG, "ModeInfo not found for mode: $modeName")
                            }
                        } else {
                            Log.w(TAG, "Mode name not found in the response")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing parameters: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "Notification received from unhandled characteristic: ${characteristic.uuid}")
                }

                Log.d(TAG, "Characteristic ${characteristic.uuid} updated with value: $response")
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                super.onDescriptorWrite(gatt, descriptor, status)

                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(TAG, "Descriptor write successful for ${descriptor?.characteristic?.uuid}")
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e(TAG, "GATT_WRITE_NOT_PERMITTED for ${descriptor?.characteristic?.uuid}! Check permissions and Arduino code.")
                    }
                    else -> {
                        Log.e(TAG, "Descriptor write failed for ${descriptor?.characteristic?.uuid}, status: $status")
                    }
                }
                completedCommand() // Ensure this is called regardless of success or failure
            }

        }

        // Connect to the GATT server
        val newGatt = device.connectGatt(context, false, gattCallback)
        if (newGatt != null) {
            gatt = newGatt
            _deviceName = device.name
            Log.d(TAG, "Device name set to: $_deviceName")
            shouldLoadSavedStatus = true
        } else {
            Log.e(TAG, "Failed to connect to GATT server.")
            callback(false) // Notify failure to connect
        }
    }

    private fun parseParameterResponse(response: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val pairs = response.split(",")

        for (pair in pairs) {
            val parts = pair.split(":").map { it.trim() }

            if (parts.size == 2) {
                val paramName = parts[0]
                val paramValue = parts[1]

                when (paramName) {
                    "Name", "Color", "SColor", "Speed", "Frequency", "Division" -> {
                        params[paramName] = paramValue // Store all values as strings
                    }
                    else -> {
                        Log.w(TAG, "Unknown parameter: $paramName")
                    }
                }
            } else {
                Log.w(TAG, "Skipping malformed parameter: $pair")
            }
        }

        return params
    }

    private fun nextCommand() {
        if (commandQueueBusy) {
            Log.d(TAG, "Queue is busy. Waiting for the current command to complete.")
            return
        }

        if (commandQueue.isEmpty()) {
            Log.d(TAG, "Command queue is empty. Nothing to execute.")
            return
        }

        if (gatt == null) {
            Log.e(TAG, "GATT is null. Clearing command queue.")
            commandQueue.clear()
            commandQueueBusy = false
            return
        }

        val bluetoothCommand = commandQueue.poll()
        if (bluetoothCommand != null) {
            Log.d(TAG, "Dequeuing and executing command. Remaining commands in queue: ${commandQueue.size}")
            commandQueueBusy = true

            bleHandler.post {
                try {
                    bluetoothCommand.run()
                } catch (ex: Exception) {
                    Log.e(TAG, "Error while executing command in queue", ex)
                } finally {
                    commandQueueBusy = false
                    nextCommand() // Proceed to the next command
                }
            }
        } else {
            Log.d(TAG, "No command to dequeue.")
        }
    }

    // Private function to complete the current command
    private fun completedCommand() {
        Log.d(TAG, "Command completed. Removing from queue. Remaining commands: ${commandQueue.size - 1}")
        commandQueueBusy = false
        isRetrying = false
        commandQueue.poll()
        nextCommand()
    }

    // Private function to retry the current command
    private fun retryCommand() {
        commandQueueBusy = false
        val currentCommand = commandQueue.peek()
        if (currentCommand != null) {
            if (nrTries >= MAX_TRIES) {
                Log.w(TAG, "Max retries reached for command. Removing from queue.")
                commandQueue.poll()
            } else {
                Log.d(TAG, "Retrying command. Attempt number: ${nrTries + 1}")
                isRetrying = true
            }
        }
        nextCommand()
    }

    // GlobalBluetoothManager

    fun sendDataToDevice(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        data: String,
        clearBuffer: Boolean = true,
        appendNullTerminator: Boolean = false
    ) {
        val gatt = gatt // Use existing gatt reference

        if (gatt != null && isConnected) {
            val characteristic = gatt.getService(serviceUUID)?.getCharacteristic(characteristicUUID)
            if (characteristic != null) {
                if (clearBuffer) {
                    Log.d(
                        TAG,
                        "sendDataToDevice: Before clearing buffer - characteristic value: ${
                            characteristic.value?.toString(Charsets.UTF_8)
                        }"
                    )
                    characteristic.setValue(byteArrayOf()) // Correct way to clear buffer

                    Log.d(
                        TAG,
                        "sendDataToDevice: After clearing buffer - characteristic value: ${
                            characteristic.value?.toString(Charsets.UTF_8)
                        }"
                    )
                }

                var message = data.toByteArray(Charsets.UTF_8)
                if (appendNullTerminator) {
                    message += 0.toByte()  // Appending null terminator
                }

                characteristic.value = message
                Log.d(
                    TAG,
                    "sendDataToDevice: After setting value - characteristic value: ${
                        characteristic.value?.toString(Charsets.UTF_8)
                    }"
                )
                commandQueue.add() {
                    val success = gatt.writeCharacteristic(characteristic)
                    if (success) {
                        Log.d(TAG, "Data sent successfully: $data")
                    } else {
                        Log.w(TAG, "Failed to send data")
                    }
                }
                if (!commandQueueBusy) {
                    Log.d(TAG, "Queue not busy. Starting command processing.")
                    nextCommand()
                }
            } else {
                Log.w(TAG, "Characteristic not found for UUID: $characteristicUUID")
            }
        } else {
            Log.w(TAG, "Not connected or GATT is null.")
        }
    }

    // Generalized function for sending commands
    fun sendCommandToDevice(
        type: String,
        name: String,
        values: String
    ) {
        val formattedData = "$type:$name:{$values}"

        sendDataToDevice(
            LED_CONNECTION_SERVICE,    // Use the appropriate service UUID
            PARAMETER_CONTROL_UUID,              // Use a generalized command UUID
            formattedData,
            clearBuffer = true,
            appendNullTerminator = false
        )
    }

    // Use case 1: Setting LED Mode
    fun setLedMode(modeName: String) {
        sendCommandToDevice(
            "Mode",      // Command type (Mode)
            "None",    // Mode name (e.g., "Color")
            modeName    // No values for Mode, it's just a name
        )
    }

    // Use case 2: Setting LED Parameters (e.g., Color: RGB)
    fun setLedParameter(parameterName: String, parameterValues: List<Int>) {
        val valuesString = parameterValues.joinToString(",") { it.toString() } // Correctly format the list

        sendCommandToDevice(
            "Parameter",
            parameterName,
            valuesString // Now a comma-separated string like "255,0,0"
        )
    }

    // Use case 3: Sending Power State to Arduino
    fun sendPowerStateToArduino(state: Boolean) {
        val powerState = if (state) 1 else 0 // Use 1 for ON, 0 for OFF
        sendCommandToDevice(
            "Power",     // Command type (Power)
            "None",       // Name (e.g., "LED")
            powerState.toString() // Power state value (1 or 0)
        )
    }

    // Use case 4: Sending Brightness to Arduino
    fun setBrightness(brightness: Int) {
        sendCommandToDevice(
            "Brightness",   // Command type (Brightness)
            "None",          // Name (e.g., "LED")
            brightness.toString() // Brightness value
        )
    }

    fun getModeInfo(modeName: String): ModeInfo? {
        return modeInfoList.find { it.name == modeName }
    }

    fun requestValues(type: String, modeName: String) {
        val gatt = gatt // Use existing gatt reference

        if (gatt != null && isConnected) {
            val characteristic = gatt.getService(LED_CONNECTION_SERVICE)?.getCharacteristic(PARAMETER_REQUEST_UUID)

            characteristic?.let {
                // Construct the request string (using the new format)
                val requestString = "$type:$modeName:{}" // Or whatever format your device expects

                it.value = requestString.toByteArray(Charsets.UTF_8)
                commandQueue.add {
                    gatt.writeCharacteristic(it)
                }
                if (!commandQueueBusy) {
                    Log.d(TAG, "Queue not busy. Starting command processing.")
                    nextCommand()
                }
                Log.d(TAG, "Requested $type values for mode: $modeName")
            } ?: run {
                Log.w(TAG, "Parameter request characteristic not found")
            }
        } else {
            Log.w(TAG, "Not connected to device")
        }
    }

    // Method to disconnect from a device
    fun disconnect(context: Context) {
        Log.d(TAG, "disconnect called")
        try {
            bluetoothSocket?.close()
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            _deviceAddress = null
            _deviceName = null
            _isConnected = false
            modeInfoList.clear()

            // Clear the device address in SharedPreferences when disconnected
            val preferenceUtils = PreferenceUtils(context)
            preferenceUtils.saveDeviceAddress("")
        } catch (e: IOException) {
            Log.e(TAG, "Error during disconnect", e)
        } finally {
            shouldLoadSavedStatus = false
            Log.d(TAG, "Disconnected and resources cleared")
        }
    }



    data class ModeInfo(
        val name: String,
        val parameterNames: List<String>, // List of parameter *names*
        var parameterValues: MutableMap<String, String> = mutableMapOf() // Map of parameter name to value
    )
}