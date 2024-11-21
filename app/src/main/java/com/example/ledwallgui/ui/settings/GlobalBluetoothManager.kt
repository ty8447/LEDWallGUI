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
import java.io.OutputStream
import java.util.UUID

object GlobalBluetoothManager {
    // The connection status, device address, and device name are encapsulated to prevent direct modification.
    private val LED_MODE_LIST_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2") // Replace with actual UUID
    // UUID for the Client Characteristic Configuration descriptor (for enabling notifications)
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
    var bluetoothSocket: BluetoothSocket? = null
    private var _isConnected: Boolean = false
    private var _deviceAddress: String? = null
    private var _deviceName: String? = null  // Added device name
    private var gatt: BluetoothGatt? = null
    var shouldLoadSavedStatus = false
    private val discoveredUUIDs = mutableListOf<UUID>()
    private val ledModeData = mutableListOf<String>()
    var modeNames: MutableList<String> = mutableListOf()

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
                    Log.d(TAG, "Parsed Mode Names: $modeNames")
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

                        // Parse mode names: split by newline, and extract the first part before ':'
                        modeNames = rawData.split("\n")
                            .mapNotNull { line ->
                                line.split(":").firstOrNull()?.trim()
                            }
                            .filter { it.isNotEmpty() }
                            .toMutableList()

                        // Log the parsed mode names
                        Log.d(TAG, "Parsed Mode Names: $modeNames")
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
                    val message = characteristic.value?.toString(Charsets.UTF_8) ?: "null"
                    Log.d(TAG, "Characteristic ${characteristic.uuid} successfully written. Message: $message")
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

//    private fun writeToCharacteristic(uuid: UUID, value: ByteArray) {
//        gatt?.let { bluetoothGatt ->
//            val characteristic = bluetoothGatt.services
//                ?.flatMap { it.characteristics }
//                ?.find { it.uuid == uuid }
//
//            if (characteristic != null) {
//                // Log the message and byte array to verify it before writing
//                val message = value.toString(Charsets.UTF_8)
//                Log.d(TAG, "Write request for characteristic ${characteristic.uuid} initiated. Message: $message, Byte array: ${value.joinToString(", ")}")
//
//                characteristic.value = value
//                val success = bluetoothGatt.writeCharacteristic(characteristic)
//
//                // Log the result of the write operation
//                Log.d(TAG, "Write request for characteristic ${characteristic.uuid} initiated: $success")
//            } else {
//                Log.w(TAG, "Characteristic with UUID $uuid not found.")
//            }
//        } ?: Log.w(TAG, "BluetoothGatt is null, cannot write to characteristic.")
//    }


//    fun sendGattCommand(serviceUUID: UUID, characteristicUUID: UUID, value: ByteArray): Boolean {
//        gatt?.let {
//            val characteristic = it.getService(serviceUUID)?.getCharacteristic(characteristicUUID)
//            if (characteristic != null) {
//                characteristic.value = value
//                return it.writeCharacteristic(characteristic)
//            }
//            Log.w(TAG, "Characteristic not found for UUID: $characteristicUUID")
//        }
//        Log.w(TAG, "GATT is null, cannot send command.")
//        return false
//    }

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

    // Method to send a serial command
//    fun sendSerialCommand(command: String) {
//        Log.d(TAG, "Sending serial command: $command")
//        try {
//            outputStream?.let {
//                it.write(command.toByteArray())
//                it.flush()
//            }
//        } catch (e: IOException) {
//            Log.e(TAG, "Error sending serial command", e)
//        }
//    }

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
}