package com.flowchat.app.data.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FlowChatShizukuServicePasteTest {
    @Test
    fun pasteFocusedTextDispatchesSelectAllDeleteOnlyWhenReplacing() {
        val executed = mutableListOf<List<String>>()
        val service = FlowChatShizukuService(DeviceCommandExecutor { command ->
            executed += command
            DeviceCommandResult(true, "")
        })

        val appendReply = JSONObject(service.pasteFocusedText(false))
        assertTrue(appendReply.getBoolean("success"))
        assertEquals(listOf(listOf("input", "keyevent", "279")), executed)

        executed.clear()
        val replaceReply = JSONObject(service.pasteFocusedText(true))
        assertTrue(replaceReply.getBoolean("success"))
        assertEquals(
            listOf(
                listOf("input", "keycombination", "113", "29"),
                listOf("input", "keyevent", "67"),
                listOf("input", "keyevent", "279")
            ),
            executed
        )
    }
}
