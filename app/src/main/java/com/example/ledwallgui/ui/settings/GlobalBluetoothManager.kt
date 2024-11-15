package com.example.ledwallgui.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.io.path.name


object GlobalBluetoothManager {
    // The connection status, device address, and device name are encapsulated to prevent direct modification.
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter() // Get BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var _isConnected: Boolean = false
    private var _deviceAddress: String? = null
    private var _deviceName: String? = null  // Added device name
    private val mmSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val valueRead: String? = null
    private var serverSocket: BluetoothServerSocket? = null

    val TARGET_UUID: UUID = UUID.fromString("0000110a-0000-1000-8000-00805f9b34fb")

    // Public getter for isConnected
    var isConnected: Boolean = false
        get() = _isConnected

    private const val TAG = "GlobalBluetoothManager"
    private const val ERROR_READ = 0
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

    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    fun connectToDevice(address: String, name: String?, callback: (Boolean) -> Unit) {
        Log.d("GlobalBluetoothManager", "connectToDevice called with address = $address, name = $name")

        // Simulate connection logic
        // If connection is successful, call the callback with true
        // You can add your actual Bluetooth connection logic here
        // For now, we simulate it with a delay

        // Simulate a successful connection after a short delay
        Handler(Looper.getMainLooper()).postDelayed({
            _deviceAddress = address
            _deviceName = name // Store the device name
            _isConnected = true

            Log.d("GlobalBluetoothManager", "Connected to device: address = $address, name = $name")

            // Get the BluetoothDevice object
            val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(address)

            // Retrieve UUIDs after successful connection
            if (bluetoothDevice != null) {
                val uuids = bluetoothDevice.uuids
                if (uuids != null) {
                    for (uuid in uuids) {
                        Log.d("GlobalBluetoothManager", "UUID from connected device: ${uuid.uuid}")
                    }
                } else {
                    Log.w("GlobalBluetoothManager", "No UUIDs found on the connected device.")
                }
            } else {
                Log.e("GlobalBluetoothManager", "BluetoothDevice is null")
            }

            callback(true) // Notify success
        }, 2000) // 2-second delay to simulate connection time
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
    }
}