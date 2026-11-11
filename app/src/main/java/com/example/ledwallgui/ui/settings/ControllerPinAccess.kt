package com.example.ledwallgui.ui.settings

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ledwallgui.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlin.coroutines.resume

/** Reuses the existing lock keypad UI, but controller PIN is NEVER stored on the tablet. */
object ControllerPinAccess {
    /** Close the entire setup flow, even when the wizard was launched from a nested setup page. */
    fun returnToSettings(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val intent = Intent(activity, settingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(intent)
        activity.finish()
    }

    private const val RESET_REQUEST = "__LEDWALL_RESET__"
    private suspend fun prompt(activity: AppCompatActivity, title: String, allowReset: Boolean = false): String? =
        suspendCancellableCoroutine { cont ->
            if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
            val dialog = Dialog(activity)
            dialog.setContentView(R.layout.popup_pin_entry)
            val display = dialog.findViewById<TextView>(R.id.pinDisplayText)
            val input = StringBuilder()
            fun render() { display.text = "$title\n" + "●".repeat(input.length) }
            render()
            val ids = intArrayOf(R.id.num0, R.id.num1, R.id.num2, R.id.num3, R.id.num4,
                R.id.num5, R.id.num6, R.id.num7, R.id.num8, R.id.num9)
            ids.forEachIndexed { number, id ->
                dialog.findViewById<Button>(id).setOnClickListener {
                    if (input.length < 4) { input.append(number); render() }
                }
            }
            dialog.findViewById<Button>(R.id.clearButton).setOnClickListener { input.clear(); render() }
            dialog.findViewById<Button>(R.id.controllerResetButton)?.apply {
                visibility = if (allowReset) View.VISIBLE else View.GONE
                setOnClickListener {
                    if (cont.isActive) cont.resume(RESET_REQUEST)
                    dialog.dismiss()
                }
            }
            dialog.findViewById<Button>(R.id.pinSubmitButton).setOnClickListener {
                if (input.length != 4) { display.text = "Enter exactly four digits"; return@setOnClickListener }
                if (cont.isActive) cont.resume(input.toString())
                dialog.dismiss()
            }
            // OnDismiss also covers outside taps, system Back, and programmatic dismissal.
            // An accepted PIN resumes the continuation before dismiss(), so only an
            // incomplete/cancelled attempt closes the protected setup flow.
            dialog.setOnDismissListener {
                if (cont.isActive) {
                    cont.resume(null)
                    returnToSettings(activity)
                }
            }
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
            // The controller PIN dialog must be usable on both the wall tablet and a phone.
            val metrics = activity.resources.displayMetrics
            val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
            val maxWidth = (shortSide * .80f).toInt()
            dialog.window?.setLayout(maxWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            val buttonHeight = (metrics.heightPixels * .085f).toInt().coerceIn((44 * metrics.density).toInt(), (82 * metrics.density).toInt())
            (ids + intArrayOf(R.id.clearButton, R.id.pinSubmitButton)).forEach { id ->
                dialog.findViewById<Button>(id)?.let { button ->
                    button.minimumHeight = buttonHeight
                }
            }
        }

    private fun message(activity: AppCompatActivity, title: String, detail: String) {
        if (!activity.isFinishing && !activity.isDestroyed)
            AlertDialog.Builder(activity).setTitle(title).setMessage(detail).setPositiveButton("OK", null).show()
    }

    suspend fun ensure(activity: AppCompatActivity, ble: GlobalBluetoothManager): Boolean {
        if (ble.setupAuthenticated && ble.isConnected) return true
        val status = ble.securityStatus()
        if (status == null) {
            message(activity, "Controller unavailable", "Connect to the v4 firmware before changing protected setup settings.")
            return false
        }
        when (status) {
            "UNSET" -> {
                val first = prompt(activity, "Create controller's 4-digit setup PIN") ?: return false
                val second = prompt(activity, "Confirm new setup PIN") ?: return false
                if (first != second) {
                    message(activity, "PIN mismatch", "No code was saved. Try setup again."); return false
                }
                val result = ble.initializePin(first)
                message(activity, "PIN setup", if (result == "OK")
                    "PIN saved to the controller. All BLE devices will disconnect. Reconnect, then enter your code to continue."
                else "PIN could not be saved: ${result ?: "no response"}")
                return false
            }
            "SET" -> {
                val pin = prompt(activity, "Enter controller setup PIN", allowReset = true) ?: return false
                if (pin == RESET_REQUEST) {
                    resetWithBackup(activity, ble)
                    return false
                }
                val result = ble.authorize(pin)
                if (result == "OK") return true
                message(activity, "Access denied", when (result) {
                    "ERR_LOCKED" -> "Too many attempts. Wait one minute before trying again."
                    "ERR_PIN" -> "Incorrect PIN."
                    else -> "Authorization failed: ${result ?: "no response"}"
                })
                return false
            }
            else -> {
                message(activity, "Unsupported controller", "Firmware did not recognize PIN status: $status")
                return false
            }
        }
    }

    /** Backup MUST complete before any destructive command is sent. */
    private suspend fun resetWithBackup(activity: AppCompatActivity, ble: GlobalBluetoothManager): String {
        val old = prompt(activity, "Current PIN required to reset") ?: return "Reset cancelled"
        if (ble.authorize(old) != "OK") {
            message(activity, "Reset denied", "The current controller PIN is required. A forgotten PIN must be recovered physically over USB.")
            return "Incorrect PIN"
        }
        val backup = ControllerBackupTransport.read(ble)
        if (backup == null) {
            message(activity, "Backup failed", "No changes were made because the current controller configuration could not be read.")
            return "Backup failed"
        }
        val name = "LEDWall_BeforeReset_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.json"
        val saved = try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LEDWallBackups")
            }
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create backup")
            try {
                activity.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(backup.json().toByteArray(Charsets.UTF_8))
                } ?: error("Unable to write backup")
                true
            } catch (e: Exception) { activity.contentResolver.delete(uri, null, null); throw e }
        } catch (e: Exception) {
            message(activity, "Backup failed", "No settings were reset. ${e.message}")
            false
        }
        if (!saved) return "Backup failed"
        val approved = suspendCancellableCoroutine<Boolean> { cont ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Reset all controller setup?")
                .setMessage("Backup saved to Downloads/LEDWallBackups/$name. This will erase the room map, LED count, clock settings and setup PIN. Both Bluetooth devices will disconnect. Continue?")
                .setPositiveButton("Erase setup") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }.create()
            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
        if (!approved) return "Backup saved, reset cancelled"
        val reply = ble.controlAndAwait("Sec", "Factory", old)
        if (reply == "OK") {
            ble.invalidateSetupAuth()
            // Firmware disconnects both clients. Try to reconnect before prompting
            // so the new code is never offered against the old authenticated session.
            delay(1200)
            if (ble.ensureConnected(12000) && ble.securityStatus() == "UNSET") {
                val first = prompt(activity, "Create NEW controller PIN")
                if (first == null) return "Setup reset. Reconnect to create a new PIN."
                val confirm = prompt(activity, "Confirm NEW controller PIN")
                if (confirm == null || first != confirm) {
                    message(activity, "PIN not set", "The controller was reset, but the new codes did not match. Reconnect to set a PIN. Backup: Downloads/LEDWallBackups/$name")
                    return "Reset complete. New PIN not saved."
                }
                val initialized = ble.initializePin(first)
                if (initialized == "OK") {
                    message(activity, "Reset completed", "Your new controller PIN is saved. The controller will disconnect once more. Backup: Downloads/LEDWallBackups/$name")
                    return "Reset complete. New PIN saved."
                }
                message(activity, "Reset completed", "Controller reset, but new PIN could not be saved ($initialized). Reconnect and create a new code.")
                return "Reset complete. PIN setup pending."
            }
            message(activity, "Reset completed", "The controller has reset and disconnected both tablets. Reconnect to create and confirm a new PIN. Backup: Downloads/LEDWallBackups/$name")
            return "Controller reset. Reconnect to create a new PIN."
        }
        message(activity, "Reset failed", "Controller rejected the reset (${reply ?: "no response"}). Your backup remains in Downloads/LEDWallBackups.")
        return "Reset failed: ${reply ?: "no response"}"
    }

    suspend fun change(activity: AppCompatActivity, ble: GlobalBluetoothManager): String {
        if (!ble.isConnected) return "Controller disconnected"
        val old = prompt(activity, "Current controller PIN", allowReset = true) ?: return "Cancelled"
        if (old == RESET_REQUEST) return resetWithBackup(activity, ble)
        val fresh = prompt(activity, "New 4-digit PIN") ?: return "Cancelled"
        val confirm = prompt(activity, "Confirm new PIN") ?: return "Cancelled"
        if (fresh != confirm) return "New PINs did not match"
        val result = ble.changePin(old, fresh)
        if (result == "OK") {
            ble.invalidateSetupAuth()
            return "PIN changed. Both devices will disconnect. Reconnect with the new code."
        }
        return "Controller rejected PIN change: ${result ?: "no response"}"
    }
}
