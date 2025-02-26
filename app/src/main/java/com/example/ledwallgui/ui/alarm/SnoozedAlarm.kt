package com.example.ledwallgui.ui.alarm

data class SnoozedAlarm(
    val id: Int,  // Snoozed alarm instance ID
    val originalId: Int,
    val originalTime: Long,
    var snoozeTime: Long
)
