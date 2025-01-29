package com.example.ledwallgui.ui.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.example.ledwallgui.ui.AlarmAlertActivity

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("AlarmReceiver", "Alarm triggered!")

        val alarmTime = intent?.getStringExtra("alarmTime") ?: "Unknown Time"
        val alarmName = intent?.getStringExtra("alarmName") ?: "No Name"

        Log.d("AlarmReceiver", "Alarm details - Time: $alarmTime, Name: $alarmName")

        Toast.makeText(context, "Alarm Triggered: $alarmName at $alarmTime", Toast.LENGTH_LONG).show()

        val alarmIntent = Intent(context, AlarmAlertActivity::class.java).apply {
            putExtra("alarmTime", alarmTime)
            putExtra("alarmName", alarmName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context?.startActivity(alarmIntent)
    }
}

