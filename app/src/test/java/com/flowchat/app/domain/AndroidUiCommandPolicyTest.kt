package com.flowchat.app.domain

import com.flowchat.app.domain.terminal.AndroidUiCommandPolicy
import org.junit.Assert.*
import org.junit.Test

class AndroidUiCommandPolicyTest {
    @Test fun observedRawTapsAndCombinedLaunchInputCommandsRequireTheVerifiedTools() {
        for (command in listOf("input tap 742 238", "input tap 490 442", "sleep 1; input tap 300 442",
            "/system/bin/input touchscreen tap 300 400", "input -d 0 touchscreen swipe 1 2 3 4",
            "cmd input tap 10 20", "sh -c 'input tap 10 20'", "am start -n app/.Main; input tap 30 40")) {
            assertTrue(command, AndroidUiCommandPolicy.requiresDeviceTools(command))
        }
    }

    @Test fun normalAndroidDiagnosticsAndFileCommandsRemainAvailable() {
        for (command in listOf("id -u", "wm size", "pm list packages", "dumpsys window", "input --help",
            "cat /data/local/tmp/input-history", "printf done", "am force-stop example.app")) {
            assertFalse(command, AndroidUiCommandPolicy.requiresDeviceTools(command))
        }
    }
}
