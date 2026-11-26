package com.flowchat.app.presentation.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPermissionModeTest {
    @Test
    fun uiAndRuntimeContractsReflectFinalizedDesign() {
        val compactControls = File("src/main/java/com/flowchat/app/presentation/common/FlowCompactControls.kt").readText()
        assertTrue(compactControls.contains("segmentWidth * 2.0f"))
        assertTrue(compactControls.contains("internal fun FlowBinaryChoice("))
        assertTrue(compactControls.contains("indication = null"))

        val flowComponents = File("src/main/java/com/flowchat/app/presentation/common/FlowComponents.kt").readText()
        assertTrue(flowComponents.contains("fun FlowSwitch(checked: Boolean, enabled: Boolean = true, pressed: Boolean = false)"))
        assertTrue(flowComponents.contains("maxThumbPx = restThumbPx * 1.48f"))
        assertTrue(flowComponents.contains("indication = null"))

        val chatScreen = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        assertTrue(chatScreen.contains("R.string.execution_permission_title"))
        assertTrue(chatScreen.contains("permission-mode-review"))
        assertTrue(chatScreen.contains("permission-mode-full"))
        assertTrue(chatScreen.contains("FlowBinaryChoice("))

        val terminalSettings = File("src/main/java/com/flowchat/app/presentation/chat/TerminalSettings.kt").readText()
        assertTrue(terminalSettings.contains("FlowBinaryChoice("))

        val chatWelcome = File("src/main/java/com/flowchat/app/presentation/chat/ChatWelcome.kt").readText()
        assertTrue(chatWelcome.contains("BloomEmptyChatImprint("))

        val runtime = File("src/main/java/com/flowchat/app/presentation/chat/ChatTaskRuntime.kt").readText()
        assertTrue(runtime.contains("ExecutionPermissionMode.FullAccess"))

        val taskPresentation = File("src/main/java/com/flowchat/app/presentation/chat/TaskPresentation.kt").readText()
        assertTrue(taskPresentation.contains("val showChatStatus: Boolean get() = false"))
        assertFalse(chatScreen.contains("TaskStatusPanel("))
    }
}
