package com.example.ledwallgui.ui.alarm

import android.os.Parcel
import android.os.Parcelable
import java.util.*

data class Alarm(
    var hours: Int,
    var minutes: Int,
    var alarmName: String,
    var isEnabled: Boolean,
    var showCheckbox: Boolean = false,
    var isSelected: Boolean = false,
    var rampBright: Boolean = false,
    var daysOfWeek: BooleanArray = BooleanArray(7) { false },
    var selectedDays: BooleanArray = BooleanArray(7) { false },
    var isAlarmGoingOff: Boolean = false,
    var id: Int = generateUniqueId()
) : Parcelable {

    fun getTimeAsCalendar(): Calendar {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hours)
        calendar.set(Calendar.MINUTE, minutes)
        return calendar
    }

    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.createBooleanArray() ?: BooleanArray(7) { false },
        parcel.createBooleanArray() ?: BooleanArray(7) { false },
        parcel.readByte() != 0.toByte(),
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(hours)
        parcel.writeInt(minutes)
        parcel.writeString(alarmName)
        parcel.writeByte(if (isEnabled) 1 else 0)
        parcel.writeByte(if (showCheckbox) 1 else 0)
        parcel.writeByte(if (isSelected) 1 else 0)
        parcel.writeByte(if (rampBright) 1 else 0)
        parcel.writeBooleanArray(daysOfWeek)
        parcel.writeBooleanArray(selectedDays)
        parcel.writeByte(if (isAlarmGoingOff) 1 else 0)
        parcel.writeInt(id)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Alarm> {
        override fun createFromParcel(parcel: Parcel): Alarm = Alarm(parcel)
        override fun newArray(size: Int): Array<Alarm?> = arrayOfNulls(size)

        private var nextAlarmId = 0

        private fun generateUniqueId(): Int = nextAlarmId++
    }

    fun startGoingOff() {
        isAlarmGoingOff = true
    }

    fun stopGoingOff() {
        isAlarmGoingOff = false
    }
}
