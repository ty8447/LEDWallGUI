package com.example.ledwallgui.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
    private var bluetoothSocket: BluetoothSocket? = null
    private var _isConnected: Boolean = false
    private var _deviceAddress: String? = null
    private var _deviceName: String? = null  // Added device name
    private var outputStream: OutputStream? = null
    private var gatt: BluetoothGatt? = null
    var shouldLoadSavedStatus = false
    private val discoveredUUIDs = mutableListOf<UUID>()

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
                    discoveredUUIDs.clear() // Clear previous UUIDs
                    gatt?.services?.forEach { service ->
                        Log.d(TAG, "Service UUID: ${service.uuid}")
                        discoveredUUIDs.add(service.uuid) // Add service UUID to the list
                        service.characteristics.forEach { characteristic ->
                            Log.d(TAG, "  Characteristic UUID: ${characteristic.uuid}")
                            discoveredUUIDs.add(characteristic.uuid) // Add characteristic UUID to the list
                        }
                    }
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
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    Log.d(TAG, "Characteristic ${characteristic.uuid} read with value: ${characteristic.value?.contentToString()}")
                } else {
                    Log.w(TAG, "Failed to read characteristic: ${characteristic?.uuid}")
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    Log.d(TAG, "Characteristic ${characteristic.uuid} successfully written.")
                } else {
                    Log.w(TAG, "Failed to write to characteristic: ${characteristic?.uuid}")
                }
            }
        }

        fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
            gatt?.let {
                val success = it.readCharacteristic(characteristic)
                Log.d(TAG, "Read request for characteristic ${characteristic.uuid} initiated: $success")
            } ?: Log.w(TAG, "BluetoothGatt is null, cannot read characteristic.")
        }

        fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            gatt?.let {
                characteristic.value = value
                val success = it.writeCharacteristic(characteristic)
                Log.d(TAG, "Write request for characteristic ${characteristic.uuid} initiated: $success")
            } ?: Log.w(TAG, "BluetoothGatt is null, cannot write characteristic.")
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
    // Method to send a serial command
    fun sendSerialCommand(command: String) {
        Log.d(TAG, "Sending serial command: $command")
        try {
            outputStream?.let {
                it.write(command.toByteArray())
                it.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error sending serial command", e)
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
}