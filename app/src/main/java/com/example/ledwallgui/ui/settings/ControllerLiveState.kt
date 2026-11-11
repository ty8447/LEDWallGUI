package com.example.ledwallgui.ui.settings

/** Controller is authoritative. Revision helps detect missed notifications. */
data class ControllerLiveState(
    val revision: Long,
    val power: Boolean,
    val brightness: Int,
    val mode: String,
    val parameters: Map<String, String> = emptyMap()
) {
    companion object {
        fun parseCore(text: String): ControllerLiveState? {
            val parts = text.split('|', limit = 4)
            val revision = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val on = when (parts.getOrNull(1)) { "0" -> false; "1" -> true; else -> return null }
            val brightness = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            val mode = parts.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: return null
            return ControllerLiveState(revision, on, brightness, mode)
        }
        fun parseParameters(text: String): ControllerLiveState? {
            val parts = text.split('|')
            val revision = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val mode = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            val parameters = linkedMapOf<String, String>()
            for (part in parts.drop(2)) {
                val i = part.indexOf('=')
                if (i < 1) return null
                parameters[part.substring(0, i)] = part.substring(i + 1)
            }
            return ControllerLiveState(revision, false, 0, mode, parameters)
        }
        /** Live parameter broadcast: revision|mode|parameter|value */
        fun parseParameter(text: String): Pair<Triple<Long, String, String>, String>? {
            val parts = text.split('|', limit = 4)
            if (parts.size != 4) return null
            val revision = parts[0].toLongOrNull() ?: return null
            if (parts[1].isBlank() || parts[2].isBlank()) return null
            return Triple(revision, parts[1], parts[2]) to parts[3]
        }
    }
}
