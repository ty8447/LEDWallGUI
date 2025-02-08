package com.example.ledwallgui.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.util.Log
import com.example.ledwallgui.MainActivity
import com.example.ledwallgui.PreferenceUtils
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.ktx.suspendForResponse
import java.util.UUID
import no.nordicsemi.android.ble.response.ReadResponse
import java.io.IOException


// Define your custom response type.
class RawReadResponse(val value: ByteArray) : ReadResponse()


// A simple data class to hold mode information.
data class ModeInfo(
    val name: String,
    val parameterNames: List<String>, // List of parameter *names*
    var parameterValues: MutableMap<String, String> = mutableMapOf() // Map of parameter name to value
)

/**
 * GlobalBluetoothManager is a BleManager implementation that handles
 * connection and BLE operations for our LED device.
 *
 * It implements:
 * - isRequiredServiceSupported: Acquires references to required characteristics.
 * - initialize: Enables notifications and optionally requests an MTU.
 * - onServicesInvalidated: Clears references when services are no longer valid.
 *
 * Additionally, it exposes high-level API functions such as turnLedOn().
 */
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

    var deviceAddress: String? = null
    var deviceName: String? = null
    var shouldLoadSavedStatus: Boolean = false

    // References to characteristics acquired during service discovery.
    private var ledModeListCharacteristic: BluetoothGattCharacteristic? = null
    private var parameterResponseCharacteristic: BluetoothGattCharacteristic? = null
    private var parameterControlCharacteristic: BluetoothGattCharacteristic? = null

    // List of parsed mode information, maintained for high-level use.
    val modeInfoList: MutableList<ModeInfo> = mutableListOf()

    // (Optional) Track the current mode if needed.
    private var currentMode: String? = null

    val connectionStatus: Boolean
        get() {
            log(Log.DEBUG, "connectionStatus called - stub returns false")
            return false
        }

    // We'll store the current BluetoothGatt here.
    private var currentGatt: BluetoothGatt? = null

    // Logging methods – using Log.VERBOSE for detailed output.
    override fun getMinLogPriority(): Int = Log.VERBOSE

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
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
        log(Log.DEBUG, "Processing LED mode list: $rawData")
        val modeData = rawData.split(";").filter { it.isNotBlank() }
        for (mode in modeData) {
            val modeParts = mode.split(":")
            if (modeParts.size >= 2) {
                val modeName = modeParts[0].trim()
                val parametersString = modeParts[1].trim()
                val parameterNames = parametersString.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val parameterValues = mutableMapOf<String, String>()
                val modeInfo = ModeInfo(modeName, parameterNames, parameterValues)
                modeInfoList.add(modeInfo)
                log(Log.DEBUG, "Parsed mode: $modeInfo")
            } else {
                log(Log.ERROR, "Invalid mode format: $mode")
            }
        }
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
                    log(Log.DEBUG, "Updated parameters for current mode $modeName: ${modeInfo.parameterValues}")
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
        if (!connectionStatus) {
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
        if (!connectionStatus) {
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

            val preferenceUtils = PreferenceUtils(context)
            preferenceUtils.saveDeviceAddress("")
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
     * Stub: Starts periodic connection checking.
     */
    fun startConnectionCheck(mainActivity: MainActivity) {
        log(Log.DEBUG, "startConnectionCheck() called - stub")
    }

    /**
     * Stub: Stops periodic connection checking.
     */
    fun stopConnectionCheck() {
        log(Log.DEBUG, "stopConnectionCheck() called - stub")
    }

    /**
     * High-level API: turnLedOn().
     *
     * Instead of writing directly to the characteristic, this suspend function abstracts
     * the operation to "turn LED on." It writes a predefined command (here, "LED_ON") to the
     * parameter control characteristic.
     *
     * Note: This uses the ble-ktx suspend extension (see Nordic documentation).
     */
    suspend fun turnLedOn() {
        // "LED_ON" is the command your device expects.
        val commandData = "LED_ON".toByteArray(Charsets.UTF_8)
        parameterControlCharacteristic?.let { characteristic ->
            // Here we use a suspend extension on the write operation.
            writeCharacteristic(characteristic, commandData, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                .suspend()
            log(Log.DEBUG, "LED on command sent")
        } ?: run {
            log(Log.ERROR, "Parameter control characteristic not available")
        }
    }


    /**
     * (Optional) Example: Read the LED mode list from the device.
     * This suspend function reads the characteristic and processes the raw response.
     */
    suspend fun readLedModeList(): Boolean {
        return try {
            ledModeListCharacteristic?.let { characteristic ->
                val responseData = readCharacteristic(characteristic)
                    .suspendForResponse<RawReadResponse>()
                val rawData = String(responseData.value, Charsets.UTF_8)
                processLedModeList(rawData)
            }
            true
        } catch (e: Exception) {
            log(Log.ERROR, "Error reading LED mode list: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(context: Context, device: BluetoothDevice, handler: Handler, callback: (Boolean) -> Unit) {
        log(Log.DEBUG, "connectToDevice() stub called")
        // Stub: Immediately call callback(true)
        callback(true)
    }

    fun getDiscoveredUUIDs(): List<UUID> {
        // TODO: Return the actual list of discovered UUIDs.
        return emptyList()
    }


    // Additional high-level methods (e.g. turnLedOff(), setLedColor(), etc.) can be added here.
}
