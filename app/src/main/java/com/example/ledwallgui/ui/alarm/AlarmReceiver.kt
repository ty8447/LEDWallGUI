package com.example.ledwallgui.ui.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ledwallgui.ui.AlarmAlertActivity
import com.example.ledwallgui.ui.modes.ModeManager
import com.google.gson.Gson

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("AlarmReceiver", "Alarm triggered!")

        val alarmTime = intent?.getStringExtra("alarmTime") ?: "Unknown Time"
        val alarmName = intent?.getStringExtra("alarmName") ?: "No Name"
        val alarmId = intent?.getIntExtra("alarmId", -1) ?: -1

        Log.d("AlarmReceiver", "Alarm details - Time: $alarmTime, Name: $alarmName, Index: $alarmId")

        // Get the previous mode and parameters
        val previousMode = ModeManager.currentMode
        val previousParameters = ModeManager.getModeParameters(previousMode)
        val previousParamsJson = Gson().toJson(previousParameters)

        val alarmIntent = Intent(context, AlarmAlertActivity::class.java).apply {
            putExtra("alarmTime", alarmTime)
            putExtra("alarmName", alarmName)
            putExtra("alarmId", alarmId)
            putExtra("previousMode", previousMode)
            putExtra("previousParams", previousParamsJson)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context?.startActivity(alarmIntent)
    }
}