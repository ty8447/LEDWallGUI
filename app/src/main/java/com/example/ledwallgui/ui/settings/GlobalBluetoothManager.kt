package com.example.ledwallgui.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ledwallgui.PreferenceUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import java.util.ArrayDeque
import java.util.UUID

/** Metadata returned by the controller's read-only mode-list characteristic. */
data class ModeInfo(
    val name: String,
    val parameterNames: List<String>,
    var parameterValues: MutableMap<String, String> = mutableMapOf()
)

/** A lightweight facade: all Activities and the foreground service share one real GATT session. */
class GlobalBluetoothManager(context: Context) {
    companion object {
        val LED_CONNECTION_SERVICE: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val LED_MODE_LIST_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        val PARAMETER_REQUEST_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
        val PARAMETER_RESPONSE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")
        val PARAMETER_CONTROL_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef4")
    }
    private val appContext = context.applicationContext
    private val prefs = PreferenceUtils(appContext)
    private val checkHandler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null

    var deviceAddress: String?
        get() = prefs.fetchDeviceAddress()?.takeUnless { it.isBlank() }
        set(value) { if (!value.isNullOrBlank()) prefs.saveDeviceAddress(value) }
    var deviceName: String?
        get() = prefs.loadConnectionState().third
        set(value) {
            if (!value.isNullOrBlank()) prefs.saveKnownDeviceName(deviceAddress, value)
        }
    val liveState: StateFlow<ControllerLiveState?> get() = LedGattSession.state
    val setupAuthenticated: Boolean get() = LedGattSession.authenticated
    fun invalidateSetupAuth() = LedGattSession.invalidateAuth()
    var shouldLoadSavedStatus = false

    fun connectToDevice(context: Context, device: ServerDevice, handler: Handler, callback: (Boolean) -> Unit) {
        // Avoid passing Activity contexts into the persistent transport.
        LedGattSession.connect(appContext, device.address, device.name ?: "LED Controller", callback)
    }

    fun setLedMode(modeName: String) {
        LedGattSession.send(PARAMETER_CONTROL_UUID, "Mode:None:{$modeName}")
        // The state update is authoritative; refresh after the mode command is processed.
        val main = Handler(Looper.getMainLooper())
        main.postDelayed({
            LedGattSession.send(PARAMETER_REQUEST_UUID, "State:Core:{?}")
            LedGattSession.send(PARAMETER_REQUEST_UUID, "State:Params:{?}")
        }, 250)
    }
    fun setLedParameter(parameterName: String, parameterValues: List<Int>) {
        LedGattSession.send(PARAMETER_CONTROL_UUID, "Parameter:$parameterName:{${parameterValues.joinToString(",")}}")
    }
    fun sendPowerStateToArduino(state: Boolean) {
        LedGattSession.send(PARAMETER_CONTROL_UUID, "Power:None:{${if (state) 1 else 0}}")
    }
    fun setBrightness(brightness: Int) {
        LedGattSession.send(PARAMETER_CONTROL_UUID, "Brightness:None:{${brightness.coerceIn(0, 100)}}")
    }
    fun setAudioLevel(level: Int) = LedGattSession.sendCompact(1, level.coerceIn(0, 100))
    fun setWeather(code: Int, windKmh: Int, isDay: Boolean) {
        LedGattSession.sendCompact(3, code.coerceIn(0, 99))
        LedGattSession.sendCompact(4, windKmh.coerceIn(0, 200))
        LedGattSession.sendCompact(5, if (isDay) 1 else 0)
    }
    fun setSunriseLevel(level: Int) = LedGattSession.sendCompact(6,
        if (level in 0..100) level else 255)

    /** Non-blocking live-preview commands, never used for permanent settings. */
    fun sendCommand(type: String, name: String, value: String) {
        LedGattSession.send(PARAMETER_CONTROL_UUID, "$type:$name:{$value}")
    }

    suspend fun ensureConnected(timeoutMs: Long = 10000): Boolean {
        if (LedGattSession.isReady) return true
        val target = deviceAddress ?: return false
        LedGattSession.connect(appContext, target, deviceName ?: "Room LED Controller") { }
        return withTimeoutOrNull(timeoutMs) {
            while (!LedGattSession.isReady) delay(150)
            true
        } == true
    }
    val isConnected: Boolean get() = LedGattSession.isReady

    suspend fun securityStatus(): String? = requestAndAwait("Sec", "Status")
    suspend fun authorize(pin: String): String? {
        val result = controlAndAwait("Sec", "Auth", pin)
        if (result == "OK") LedGattSession.setAuthenticated(true)
        return result
    }
    suspend fun initializePin(pin: String): String? = controlAndAwait("Sec", "Init", pin)
    suspend fun changePin(old: String, fresh: String): String? = controlAndAwait("Sec", "Change", "$old,$fresh")
    suspend fun resetPin(old: String): String? = controlAndAwait("Sec", "Reset", old)
    suspend fun refreshState(): ControllerLiveState? {
        if (!ensureConnected()) return null
        requestAndAwait("State", "Core")
        requestAndAwait("State", "Params")
        return liveState.value
    }
    suspend fun requestAndAwait(type: String, name: String): String? {
        if (!ensureConnected()) return null
        return LedGattSession.awaitReply(type, name, "${type}:${name}:{?}", PARAMETER_REQUEST_UUID)
    }
    suspend fun controlAndAwait(type: String, name: String, value: String): String? {
        if (!ensureConnected()) return null
        return LedGattSession.awaitReply(type, name, "${type}:${name}:{$value}", PARAMETER_CONTROL_UUID)
    }

    suspend fun requestValues(context: Context, type: String, modeName: String) {
        // Existing screens expect responses to update shared preferences, not a return value.
        LedGattSession.awaitReply(type, modeName, "$type:$modeName:{}", PARAMETER_REQUEST_UUID)
    }
    fun getModeInfo(modeName: String): ModeInfo? = prefs.getModeInfoList().firstOrNull { it.name == modeName }
    fun getDeviceFromAddress(address: String): BluetoothDevice? = try {
        val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mgr.adapter?.getRemoteDevice(address)
    } catch (_: Exception) { null }

    fun release(context: Context) { LedGattSession.release(appContext, forgetDevice = true) }
    fun synchronizeConnectionState(context: Context) { LedGattSession.syncSavedConnection(appContext) }
    fun startConnectionCheck(context: Context) {
        stopConnectionCheck()
        val task = object : Runnable {
            override fun run() {
                synchronizeConnectionState(appContext)
                checkHandler.postDelayed(this, 5000)
            }
        }
        checkRunnable = task
        checkHandler.post(task)
    }
    fun stopConnectionCheck() {
        checkRunnable?.let(checkHandler::removeCallbacks)
        checkRunnable = null
    }
}

/**
 * Serialized native BLE client. One connection for the entire process, no unowned CoroutineScopes,
 * no stale characteristic objects after reconnect, no overlapping Android GATT writes.
 */
private object LedGattSession {
    private const val TAG = "LedGattSession"
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var application: Context? = null
    private var address: String? = null
    private var name: String? = null
    @Volatile private var ready = false
    val isReady: Boolean get() = ready
    private var connecting = false
    private var subscribed = false
    private var readingModes = false
    private var generation = 0
    private val connectionCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val writes = ArrayDeque<Pair<UUID, ByteArray>>()
    private var inFlight: Pair<UUID, ByteArray>? = null
    private data class ReplyWaiter(val key: String, val deferred: CompletableDeferred<String>)
    private val replies = mutableListOf<ReplyWaiter>()
    private var modeList: String = ""
    private var compactProtocol = false
    private var delayedParams: ControllerLiveState? = null
    val state = MutableStateFlow<ControllerLiveState?>(null)
    @Volatile var authenticated = false; private set
    fun setAuthenticated(value: Boolean) { authenticated = value }
    fun invalidateAuth() { authenticated = false }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, target: String, displayName: String, callback: (Boolean) -> Unit) {
        main.post {
            val p = PreferenceUtils(context)
            if (!hasPermission(context)) { callback(false); return@post }
            if (ready && address == target && gatt != null) { callback(true); return@post }
            if (connecting && address == target) { connectionCallbacks.add(callback); return@post }
            disconnectInternal(p, keepAddress = true)
            application = context.applicationContext
            address = target
            name = p.knownDeviceName(target) ?: displayName.takeUnless { it == target } ?: "Room LED Controller"
            p.saveKnownDeviceName(target, name ?: "Room LED Controller")
            connectionCallbacks.add(callback)
            connecting = true
            val ticket = ++generation
            try {
                val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = mgr.adapter ?: throw IllegalStateException("Bluetooth unavailable")
                if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is disabled")
                val remote = adapter.getRemoteDevice(target)
                gatt = remote.connectGatt(context.applicationContext, false, callbacks, BluetoothDevice.TRANSPORT_LE)
                main.postDelayed({
                    if (generation == ticket && !ready) {
                        Log.w(TAG, "Connection timed out for $target")
                        disconnectInternal(PreferenceUtils(context), true)
                    }
                }, 15000)
            } catch (e: Exception) {
                Log.w(TAG, "BLE connection failed", e)
                disconnectInternal(p, true)
            }
        }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (connection !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!connection.discoverServices()) disconnectInternal(prefs(), true)
                } else {
                    Log.w(TAG, "BLE disconnected, GATT status $status state $newState")
                    disconnectInternal(prefs(), true)
                }
            }
        }
        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            main.post {
                if (connection !== gatt) return@post
                val svc = connection.getService(GlobalBluetoothManager.LED_CONNECTION_SERVICE)
                if (status != BluetoothGatt.GATT_SUCCESS || svc == null ||
                    svc.getCharacteristic(GlobalBluetoothManager.LED_MODE_LIST_UUID) == null ||
                    svc.getCharacteristic(GlobalBluetoothManager.PARAMETER_REQUEST_UUID) == null ||
                    svc.getCharacteristic(GlobalBluetoothManager.PARAMETER_RESPONSE_UUID) == null ||
                    svc.getCharacteristic(GlobalBluetoothManager.PARAMETER_CONTROL_UUID) == null) {
                    Log.e(TAG, "Controller BLE service/characteristics unavailable")
                    disconnectInternal(prefs(), true)
                    return@post
                }
                if (!connection.requestMtu(247)) subscribe(connection)
                else {
                    val ticket = generation
                    main.postDelayed({ if (ticket == generation && !subscribed && connection === gatt) subscribe(connection) }, 3000)
                }
            }
        }
        override fun onMtuChanged(connection: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (connection === gatt) subscribe(connection) }
        }
        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (connection !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Notification subscription failed: $status")
                    disconnectInternal(prefs(), true)
                } else {
                    subscribed = true
                    readModes(connection)
                }
            }
        }
        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post {
                if (connection !== gatt) return@post
                if (characteristic.uuid == GlobalBluetoothManager.LED_MODE_LIST_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) parseModes(value.toString(Charsets.UTF_8))
                    else Log.w(TAG, "Mode list read returned $status; using cached modes")
                    finishReady()
                }
            }
        }
        @Deprecated("Compatibility callback")
        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onCharacteristicRead(connection, characteristic, characteristic.value ?: byteArrayOf(), status)
        }
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (connection === gatt) processReply(value.toString(Charsets.UTF_8)) }
        }
        @Deprecated("Compatibility callback")
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(connection, characteristic, characteristic.value ?: byteArrayOf())
        }
        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (connection !== gatt) return@post
                if (inFlight?.first == characteristic.uuid) {
                    if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Write failed: status $status")
                    inFlight = null
                    pump()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(connection: BluetoothGatt) {
        if (subscribed || readingModes || connection !== gatt) return
        val char = characteristic(GlobalBluetoothManager.PARAMETER_RESPONSE_UUID) ?: run { disconnectInternal(prefs(), true); return }
        val desc = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (desc == null || !connection.setCharacteristicNotification(char, true)) {
            disconnectInternal(prefs(), true)
            return
        }
        // Suppress a second call while CCCD write is pending.
        readingModes = true
        if (connection.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
            disconnectInternal(prefs(), true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readModes(connection: BluetoothGatt) {
        val char = characteristic(GlobalBluetoothManager.LED_MODE_LIST_UUID)
        if (char == null || !connection.readCharacteristic(char)) { finishReady(); return }
        val ticket = generation
        main.postDelayed({ if (ticket == generation && connection === gatt && !ready) finishReady() }, 5000)
    }

    private fun parseModes(raw: String, mergeExtra: Boolean = false) {
        val parsed = raw.trim('\u0000', '\n', '\r').split(';').mapNotNull { item ->
            val i = item.indexOf(':')
            if (i < 1) null else ModeInfo(item.substring(0, i).trim(),
                item.substring(i + 1).split(',').filter(String::isNotBlank))
        }
        if (parsed.isNotEmpty()) {
            if (!mergeExtra) modeList = raw
            val previous = prefs().getModeInfoList().associateBy { it.name }
            parsed.forEach { it.parameterValues = previous[it.name]?.parameterValues ?: mutableMapOf() }
            val combined = if (mergeExtra) {
                val existing = prefs().getModeInfoList().filterNot { old -> parsed.any { it.name == old.name } }
                existing + parsed
            } else parsed
            prefs().saveModeInfoList(combined)
            Log.i(TAG, "Read ${parsed.size} controller modes")
        }
    }
    private fun finishReady() {
        if (ready || gatt == null) return
        ready = true
        connecting = false
        prefs().saveConnectionState(true, address, name)
        address?.let { prefs().saveKnownDeviceName(it, name ?: "Room LED Controller") }
        drainCallbacks(true)
        // Request one authoritative snapshot as soon as notifications are active.
        send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "State:Core:{?}")
        send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "State:Params:{?}")
        send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "State:Proto:{?}")
        // The legacy mode-list characteristic is 512 bytes. Extra modes are
        // requested as a separate short reply, avoiding GATT truncation.
        send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "Mode:Extra:{?}")
        send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "Mode:Extra2:{?}")
        send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "Mode:Extra3:{?}")
        // Connecting a second tablet must never override the controller's current mode.
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(GlobalBluetoothManager.LED_CONNECTION_SERVICE)?.getCharacteristic(uuid)

    fun send(uuid: UUID, message: String) {
        main.post {
            if (!ready) { Log.w(TAG, "BLE not ready; discarded $message"); return@post }
            // One command per ATT write. Acknowledged write prevents flooding the receiver.
            if (message.toByteArray(Charsets.UTF_8).size > 220) return@post
            // The existing V8.2 text protocol is retained. Coalesce only fire-and-forget
            // real-time controls; never collapse setup writes or commands with read-back waiters.
            val realtimePrefix = if (uuid == GlobalBluetoothManager.PARAMETER_CONTROL_UUID) when {
                message.startsWith("Brightness:None:") -> "Brightness:None:"
                message.startsWith("Map:Preview:") -> "Map:Preview:"
                message.startsWith("Audio:Level:") -> "Audio:Level:"
                else -> null
            } else null
            if (realtimePrefix != null) writes.removeAll {
                it.first == uuid && it.second.toString(Charsets.UTF_8).startsWith(realtimePrefix)
            }
            if (writes.size >= 48) { Log.w(TAG, "BLE queue full, discarding message"); return@post }
            writes.addLast(uuid to message.toByteArray(Charsets.UTF_8))
            pump()
        }
    }
    fun sendCompact(opcode: Int, value: Int) {
        main.post {
            if (!ready) return@post
            val bytes = if (compactProtocol)
                byteArrayOf(0xA5.toByte(), 1, opcode.toByte(), value.coerceIn(0, 255).toByte())
                else when (opcode) {
                    1 -> "Audio:Level:{$value}".toByteArray()
                    2 -> "Brightness:None:{$value}".toByteArray()
                    3 -> "Weather:Code:{$value}".toByteArray()
                    4 -> "Weather:Wind:{$value}".toByteArray()
                    5 -> "Weather:Day:{$value}".toByteArray()
                    else -> return@post
                }
            // Coalesce only the same opcode; configuration commands are never discarded.
            writes.removeAll { it.first == GlobalBluetoothManager.PARAMETER_CONTROL_UUID &&
                (if (compactProtocol) it.second.size == 4 && it.second[0] == 0xA5.toByte() && it.second[2] == opcode.toByte()
                 else it.second.toString(Charsets.UTF_8).startsWith(when (opcode) {
                     1 -> "Audio:Level:"; 2 -> "Brightness:None:"; 3 -> "Weather:Code:"
                     4 -> "Weather:Wind:"; else -> "Weather:Day:" }))
            }
            if (writes.size < 48) {
                writes.addLast(GlobalBluetoothManager.PARAMETER_CONTROL_UUID to bytes)
                pump()
            }
        }
    }
    fun sendAudio(level: Int) {
        main.post {
            if (!ready) return@post
            // Keep only the newest audio sample; never let high-rate mic packets starve controls.
            writes.removeAll { it.first == GlobalBluetoothManager.PARAMETER_CONTROL_UUID &&
                it.second.toString(Charsets.UTF_8).startsWith("Audio:Level:") }
            writes.addLast(GlobalBluetoothManager.PARAMETER_CONTROL_UUID to "Audio:Level:{$level}".toByteArray())
            pump()
        }
    }
    @SuppressLint("MissingPermission")
    private fun pump() {
        if (!ready || inFlight != null || writes.isEmpty()) return
        val c = gatt ?: return
        val next = writes.removeFirst()
        val chr = characteristic(next.first) ?: return
        inFlight = next
        val result = c.writeCharacteristic(chr, next.second, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.w(TAG, "writeCharacteristic rejected: $result")
            inFlight = null
            disconnectInternal(prefs(), true)
            return
        }
        val ticket = generation
        main.postDelayed({ if (generation == ticket && inFlight === next) disconnectInternal(prefs(), true) }, 4000)
    }

    suspend fun awaitReply(type: String, name: String, command: String, target: UUID): String? {
        val deferred = CompletableDeferred<String>()
        val waiter = ReplyWaiter("$type:$name", deferred)
        main.post {
            if (!ready) { deferred.completeExceptionally(IllegalStateException("LED controller disconnected")); return@post }
            replies.add(waiter)
            send(target, command)
        }
        return try { withTimeoutOrNull(6000) { deferred.await() } }
        catch (e: Exception) { Log.w(TAG, "Request failed: $command", e); null }
        finally { main.post { replies.remove(waiter) } }
    }
    private fun processReply(raw: String) {
        // Protocol format: Type:Name:{Value}\n. May contain more than one record.
        for (line in raw.split('\n')) {
            val first = line.indexOf(':')
            val second = if (first >= 0) line.indexOf(':', first + 1) else -1
            if (second <= first) continue
            val type = line.substring(0, first).trim()
            val name = line.substring(first + 1, second).trim()
            val value = line.substring(second + 1).trim().removePrefix("{").removeSuffix("}")
            val match = replies.firstOrNull { it.key == "$type:$name" }
            if (match != null) { replies.remove(match); match.deferred.complete(value) }
            val p = prefs()
            when (type) {
                "Mode" -> if (name == "Extra" || name == "Extra2" || name == "Extra3") parseModes(value, mergeExtra = true)
                "State" -> if (name == "Proto") {
                    compactProtocol = value.toIntOrNull()?.let { it >= 1 } == true
                } else processControllerState(name, value)
                "Power" -> p.savePowerState(value == "1")
                "Brightness" -> p.setBrightness(value)
                "Parameter" -> {
                    val saved = p.getModeInfoList().toMutableList()
                    val modeIndex = saved.indexOfFirst { it.name == name }
                    if (modeIndex >= 0) {
                        // Legacy responses contain the requested parameter value, not its name.
                        saved[modeIndex].parameterValues["Value"] = value
                        p.saveModeInfoList(saved)
                    }
                }
            }
        }
    }

    private fun processControllerState(name: String, value: String) {
        val old = state.value
        when (name) {
            "Core" -> {
                val next = ControllerLiveState.parseCore(value) ?: return
                if (old != null && next.revision < old.revision) return
                // Preserve parameter values for same mode and same revision only.
                val params = if (old?.mode == next.mode && old.revision == next.revision) old.parameters else emptyMap()
                val staged = delayedParams
                val merged = if (staged?.revision == next.revision && staged.mode == next.mode) staged.parameters else params
                if (staged?.revision == next.revision) delayedParams = null
                val updated = next.copy(parameters = merged)
                state.value = updated
                persistControllerState(updated)
            }
            "Params" -> {
                val full = ControllerLiveState.parseParameters(value) ?: return
                if (old != null && full.revision < old.revision) return
                if (old == null || old.revision != full.revision || old.mode != full.mode) {
                    // Keep the snapshot until its matching Core revision arrives.
                    delayedParams = full
                    send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "State:Core:{?}")
                    return
                }
                val updated = old.copy(parameters = full.parameters)
                state.value = updated
                persistControllerState(updated)
            }
            "Param" -> {
                val (triple, result) = ControllerLiveState.parseParameter(value) ?: return
                val (revision, mode, parameter) = triple
                if (old == null || revision != old.revision || mode != old.mode) {
                    if (old == null || revision > old.revision) {
                        send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "State:Core:{?}")
                        send(GlobalBluetoothManager.PARAMETER_REQUEST_UUID, "State:Params:{?}")
                    }
                    return
                }
                val updated = old.copy(parameters = old.parameters + (parameter to result))
                state.value = updated
                persistControllerState(updated)
            }
            "Config" -> {
                // Config changes are signaled to all connected tablets; settings UI can
                // reconcile on demand instead of uploading a stale local layout.
                prefs().markRemoteConfigurationChanged()
            }
        }
    }
    private fun persistControllerState(latest: ControllerLiveState) {
        val p = prefs()
        p.savePowerState(latest.power)
        p.setBrightness(latest.brightness.toString())
        p.saveControllerMode(latest.mode)
        val modes = p.getModeInfoList().toMutableList()
        val i = modes.indexOfFirst { it.name == latest.mode }
        if (i >= 0) {
            modes[i].parameterValues.putAll(latest.parameters)
            p.saveModeInfoList(modes)
        }
    }

    private fun drainCallbacks(ok: Boolean) {
        val pending = connectionCallbacks.toList()
        connectionCallbacks.clear()
        pending.forEach { it(ok) }
    }
    private fun prefs(): PreferenceUtils = PreferenceUtils(requireNotNull(application))

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(p: PreferenceUtils, keepAddress: Boolean) {
        ++generation
        val old = gatt
        gatt = null
        ready = false
        compactProtocol = false
        delayedParams = null
        authenticated = false
        state.value = null
        connecting = false
        subscribed = false
        readingModes = false
        inFlight = null
        writes.clear()
        replies.forEach { it.deferred.completeExceptionally(IllegalStateException("Disconnected")) }
        replies.clear()
        try { old?.disconnect() } catch (_: Exception) {}
        try { old?.close() } catch (_: Exception) {}
        if (keepAddress) p.saveConnectionState(false, address, p.knownDeviceName(address) ?: name)
        else { p.saveConnectionState(false, null, null); address = null; name = null }
        drainCallbacks(false)
    }

    fun release(context: Context, forgetDevice: Boolean) {
        main.post { disconnectInternal(PreferenceUtils(context), !forgetDevice) }
    }
    fun syncSavedConnection(context: Context) {
        main.post {
            val p = PreferenceUtils(context)
            val (savedConnected, savedAddress, savedName) = p.loadConnectionState()
            if (savedConnected != ready) p.saveConnectionState(ready, savedAddress, p.knownDeviceName(savedAddress) ?: savedName)
        }
    }
}
