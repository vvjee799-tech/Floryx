package com.flowchat.app.domain.terminal

/** Prevent the observed shell fallback from bypassing frame coordinates, stale checks and result capture. */
object AndroidUiCommandPolicy {
    private val coordinateInput = Regex("""(?:^|[\s;'"|&()])(?:/[^\s;'"|&()]*/)?(?:input|cmd\s+input)\s+(?:-d\s+\d+\s+)?(?:(?:touchscreen|touchpad|mouse|stylus)\s+)?(?:tap|swipe|draganddrop|motionevent)\b""")
    private val appLaunch = Regex("""(?:^|[\s;'"|&()])(?:/[^\s;'"|&()]*/)?am\s+start(?:-activity)?\b""")
    const val Guidance = "Use open_app and the indexed/visual phone tools for UI interaction. Raw input tap/swipe and am start commands bypass image coordinates and result verification. No command was executed. Never retry via another shell or script."

    fun requiresDeviceTools(command: String): Boolean = coordinateInput.containsMatchIn(command) || appLaunch.containsMatchIn(command)
}
