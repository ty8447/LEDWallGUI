package com.example.ledwallgui.ui.settings

/** Platform-independent constraints for calibrating and refining a closed physical LED loop. */
object RoomWizardLogic {
    fun calibratedCount(lastPhysicalLed: Int): Int? =
        (lastPhysicalLed + 1).takeIf { it in 60..RoomConfiguration.MAX_LEDS }

    fun loopComplete(progress: Int, count: Int, cornerCount: Int): Boolean =
        count in 60..RoomConfiguration.MAX_LEDS && progress == count && cornerCount >= 4

    /** Reject adjustments that cross an adjacent corner or go outside the physical strip. */
    fun movedCorner(config: RoomConfiguration, index: Int, delta: Int): RoomConfiguration? {
        if (config.validate() != null || index !in config.corners.indices) return null
        val updated = config.corners.toMutableList()
        val original = updated[index]
        val target = ((original.led + delta) % config.ledCount + config.ledCount) % config.ledCount
        updated[index] = original.copy(led = target)
        return config.copy(startLed = updated.first().led, corners = updated).takeIf { it.validate() == null }
    }
}
