package com.example.ledwallgui.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object GlobalBluetoothManager {
    private val LED_CONNECTION_SERVER: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0") //Server for Modes
    private val LED_BRIGHTNESS_CHARACTERISTIC: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1") //Server for Modes
    // The connection status, device address, and device name are encapsulated to prevent direct modification.
    private val LED_MODE_LIST_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2") //Get List from Arduino
    // UUID for the Client Characteristic Configuration descriptor (for enabling notifications)
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3") //Enable Notifications
    private val PARAMETER_REQUEST_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef4") //GET LED Parameter Values
    private val MODE_CONTROL_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef5") //Write Current Mode
    var bluetoothSocket: BluetoothSocket? = null
    private var _isConnected: Boolean = false
    private var _deviceAddress: String? = null
    private var _deviceName: String? = null  // Added device name
    private var gatt: BluetoothGatt? = null
    var shouldLoadSavedStatus = false
    private val discoveredUUIDs = CopyOnWriteArrayList<UUID>()
    private val ledModeData = mutableListOf<String>()
    var modeNames: MutableList<String> = mutableListOf()
    private var modeInfoList: List<ModeInfo> = emptyList()

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
    fun setConnectionState(isConnected: Boolean, deviceAddress: String?, deviceName: String?) {
        Log.d("GlobalBluetoothManager", "setConnectionState called with isConnected = $isConnected, deviceAddress = $deviceAddress, deviceName = $deviceName")

        _isConnected = isConnected
        _deviceAddress = deviceAddress
        _deviceName = deviceName // Update device name

        // Log the updated state
        Log.d("GlobalBluetoothManager", "Connection state updated: isConnected = $_isConnected, deviceAddress = $_deviceAddress, deviceName = $_deviceName")
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(context: Context, device: BluetoothDevice, handler: Handler, callback: (Boolean) -> Unit) {
        Log.d(TAG, "connectToDevice called with device: ${device.name}")
        this.handler = handler

        // Create a BluetoothGattCallback to handle GATT events
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server.")
                    setConnectionState(true, device.address, device.name)
                    gatt?.discoverServices() // Discover services after connection
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT server.")
                    setConnectionState(false, null, null)
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

                                if (characteristic.uuid == LED_MODE_LIST_UUID) {
                                    enableNotificationsForCharacteristic(gatt, characteristic)
                                    gatt.readCharacteristic(characteristic)
                                }
                            }
                        }
                    }
                    callback(true) // Notify successful service discovery
                } else {
                    Log.w(TAG, "onServicesDiscovered received: $status")
                    callback(false) // Notify service discovery failure
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                super.onCharacteristicChanged(gatt, characteristic)
                if (characteristic != null && characteristic.uuid == LED_MODE_LIST_UUID) {
                    val value = characteristic.value
                    val modeList = String(value, Charsets.UTF_8) // Convert the byte array to a string

                    // Log the raw characteristic value
                    Log.d(TAG, "LED Mode List raw value received: $modeList")

                    // Split the string into a list of mode names (assuming they are separated by a delimiter like a comma)
                    modeNames = modeList.split(",").map { it.trim() }.toMutableList()

                    // Log the parsed mode names
                    Log.d(TAG, "Parsed Mode Names 1: $modeNames")
                } else if (characteristic != null && characteristic.uuid == PARAMETER_REQUEST_UUID) {
                    val response = String(characteristic.value, Charsets.UTF_8)
                    Log.d(TAG, "Received parameter values: $response")

                    // Parse the response and update parameter values in your data structures
                    // ... (Your parsing logic here) ...

                    // Update UI with the new parameter values
                    // ... (Your UI update logic here) ...
                }
            }


            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    if (characteristic.uuid == LED_MODE_LIST_UUID) {
                        val value = characteristic.value
                        val rawData = String(value, Charsets.UTF_8) // Convert bytes to string

                        // Log the raw data for debugging
                        Log.d(TAG, "Characteristic ${characteristic.uuid} read with raw data: $rawData")

                        val modeInfoList = rawData.split("\n")
                            .mapNotNull { line ->
                                val parts = line.split(":")
                                if (parts.size >= 3) {
                                    val name = parts[0].trim()
                                    val description = parts[1].trim()
                                    val parameters = parts[2].trim().split(",").map { it.trim() }
                                    ModeInfo(name, description, parameters)
                                } else {
                                    null
                                }
                            }
                            .filter { it.name.isNotEmpty() }

                        // Store modeInfoList in GlobalBluetoothManager (e.g., as a property)
                        this@GlobalBluetoothManager.modeInfoList = modeInfoList

                        modeNames.clear() // Clear existing mode names
                        modeNames.addAll(modeInfoList.map { it.name }) // Add names from modeInfoList

                        // Log the parsed mode names
                        Log.d(TAG, "Parsed Mode Names2: $modeNames")
                    } else {
                        Log.d(TAG, "Characteristic ${characteristic.uuid} read but not the target UUID.")
                    }
                } else {
                    Log.w(TAG, "Failed to read characteristic: ${characteristic?.uuid}, status: $status")
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    // Clear the buffer before sending the message
                    Log.d(TAG, "onCharacteristicWrite: Before clearing buffer - characteristic value: ${characteristic.value?.toString(Charsets.UTF_8)}")
                    characteristic.setValue(ByteArray(0)) // Clear the buffer
                    Log.d(TAG, "onCharacteristicWrite: After clearing buffer - characteristic value: ${characteristic.value?.toString(Charsets.UTF_8)}")
                    val message = characteristic.value?.toString(Charsets.UTF_8) ?: "null"
                    Log.d(TAG, "Characteristic ${characteristic.uuid} successfully written. Message: $message")
                    if (characteristic.uuid == MODE_CONTROL_UUID) {
                        // You can remove this additional buffer clearing if it's redundant
                        characteristic.setValue(ByteArray(0))
                        Log.d(TAG, "onCharacteristicWrite: After clearing buffer for MODE_CONTROL_UUID - characteristic value: ${characteristic.value?.toString(Charsets.UTF_8)}")
                    }
                } else {
                    Log.w(TAG, "Failed to write to characteristic: ${characteristic?.uuid}, Status: $status")
                }
            }
        }

        // Connect to the GATT server
        // Assuming you have an application context available, replace 'context' with it
        val gatt = device.connectGatt(context,false, gattCallback)

        // Store the GATT object for later use
        this.gatt = gatt

        _deviceName = device.name
        Log.d(TAG, "Device name set to: $_deviceName")
        shouldLoadSavedStatus = true
    }

    fun setLedMode(modeName: String) {
        val gatt = getGatt()
        Log.d(TAG, "writeModeName called with modeName: $modeName")
        if (gatt != null && isConnected) {
            val modeControlCharacteristic = gatt.getService(LED_CONNECTION_SERVER)?.getCharacteristic(MODE_CONTROL_UUID)
            Log.d(TAG, "Mode control characteristic: $modeControlCharacteristic")
            if (modeControlCharacteristic != null) {
                modeControlCharacteristic.setValue(modeName.toByteArray())
                Log.d(TAG, "setLedMode: After setting value - characteristic value: ${modeControlCharacteristic.value?.toString(Charsets.UTF_8)}")
                gatt.writeCharacteristic(modeControlCharacteristic)
                modeControlCharacteristic.setValue(ByteArray(0)) // Clear the buffer
                Log.d(TAG, "Mode name written: $modeName")
            } else {
                Log.w(TAG, "Mode control characteristic not found")
            }
        } else {
            Log.w(TAG, "Not connected to device")
        }
    }

    fun setLedParameters(primaryColor: String, speed: Int, frequency: Int, div: Int) {
        val gatt = getGatt()
        if (gatt != null && isConnected) {
            val modeControlCharacteristic = gatt.getService(LED_CONNECTION_SERVER)?.getCharacteristic(MODE_CONTROL_UUID)
            if (modeControlCharacteristic != null) {
                val parametersData = "primaryColor:$primaryColor,speed:$speed,frequency:$frequency,div:$div"
                Log.d(TAG, "setLedParameters: Before clearing buffer - characteristic value: ${modeControlCharacteristic.value?.toString(Charsets.UTF_8)}")
                modeControlCharacteristic.setValue(ByteArray(0)) // Clear the buffer
                Log.d(TAG, "setLedParameters: After clearing buffer - characteristic value: ${modeControlCharacteristic.value?.toString(Charsets.UTF_8)}")
                val parametersDataWithNull = parametersData + '\u0000' // Add null terminator
                modeControlCharacteristic.setValue(parametersDataWithNull.toByteArray(Charsets.UTF_8)) // Specify encoding
                Log.d(TAG, "setLedParameters: After setting value - characteristic value: ${modeControlCharacteristic.value?.toString(Charsets.UTF_8)}")
                gatt.writeCharacteristic(modeControlCharacteristic)
                Log.d(TAG, "Parameters written: $parametersData")
            } else {
                Log.w(TAG, "Mode control characteristic not found")
            }
        } else {
            Log.w(TAG, "Not connected to device")
        }
    }

    fun getModeInfo(modeName: String): ModeInfo? {
        return modeInfoList.find { it.name == modeName }
    }

    private fun enableNotificationsForCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
                Log.d(TAG, "Notifications enabled for characteristic: ${characteristic.uuid}")
            }
        } else if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                gatt.writeDescriptor(it)
                Log.d(TAG, "Indications enabled for characteristic: ${characteristic.uuid}")
            }
        } else {
            Log.w(TAG, "Characteristic ${characteristic.uuid} does not support notifications or indications.")
        }
    }


    fun getGatt(): BluetoothGatt? {
        return gatt
    }

    fun getLedModeData(): List<String> {
        return ledModeData
    }

    fun requestParameterValues(modeName: String) {
        val gatt = getGatt()
        if (gatt != null && isConnected) {
            val characteristic = gatt.getService(LED_CONNECTION_SERVER)?.getCharacteristic(PARAMETER_REQUEST_UUID)
            if (characteristic != null) {
//                characteristic.setValue(ByteArray(0)) // Clear the buffer
//                characteristic.value = modeName.toByteArray() // Send mode name as request
                val modeNameBytes = modeName.toByteArray(Charsets.UTF_8) + 0.toByte()
                characteristic.value = modeNameBytes
                gatt.writeCharacteristic(characteristic)
                Log.d(TAG, "Requested parameter values for mode: $modeName")
            } else {
                Log.w(TAG, "Parameter request characteristic not found")
            }
        } else {
            Log.w(TAG, "Not connected to device")
        }
    }

    // Method to disconnect from a device
    fun disconnect() {
        Log.d("GlobalBluetoothManager", "disconnect called")

        try {
            bluetoothSocket?.close() // Close the socket if it's open
            _deviceAddress = null
            _deviceName = null // Reset device name
            _isConnected = false
        } catch (e: IOException) {
            Log.e("GlobalBluetoothManager", "Error closing Bluetooth socket", e)
        }
        // Log disconnection
        Log.d("GlobalBluetoothManager", "Disconnected from device. Connection state reset.")
        shouldLoadSavedStatus = false
    }

    data class ModeInfo(
        val name: String,
        val description: String,
        val parameters: List<String>
    )
}