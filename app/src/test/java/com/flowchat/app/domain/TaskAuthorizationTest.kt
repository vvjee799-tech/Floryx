package com.flowchat.app.domain

import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import org.junit.Assert.*
import org.junit.Test

class TaskAuthorizationTest {
    private val target = DeviceExecutionTarget("phone-A", "chat", true, PowerConnectionMode.Root, false, 1)
    @Test fun resumeKeepsDecisionsAcrossToolAndSafetyChangesButNewGoalsDevicesAndRevocationDoNotGainPermission() {
        val grant = TaskAuthorization.start(target, null, false).copy(androidCommands = TaskAuthorizationDecision.Granted)
            .withDecision("force_stop_app", " Example ", TaskAuthorizationDecision.Denied)
        val resumed = TaskAuthorization.start(target.copy(configurationVersion = 4, safetyRevision = 8, connectionMode = PowerConnectionMode.Shizuku), grant, true)
        assertEquals(TaskAuthorizationDecision.Granted, resumed.androidCommands)
        assertEquals(TaskAuthorizationDecision.Denied, resumed.decision("force_stop_app", "example"))
        assertEquals(TaskAuthorizationDecision.Unspecified, resumed.decision("force_stop_app", "another"))
        assertEquals(TaskAuthorizationDecision.Unspecified, TaskAuthorization.start(target, grant, false).androidCommands)
        assertEquals(TaskAuthorizationDecision.Unspecified, TaskAuthorization.start(target.copy(deviceId = "other"), grant, true).androidCommands)
        val stored = TaskRunMetadata(logicalTaskId = "task", authorization = resumed)
        assertEquals(stored, TaskRunMetadata.decode(stored.encode()))
    }
    @Test fun onlyDirectTargetSpecificRequestsSkipTheFirstDialog() {
        assertTrue(TaskAuthorization.explicitlyRequestsForceStop("请强行停止抖音，然后返回桌面", "抖音"))
        assertTrue(TaskAuthorization.explicitlyRequestsForceStop("Force stop Example.", "Example"))
        for (text in listOf("不要强行停止抖音", "强行停止抖音极速版", "打开抖音", "网页建议强制停止另外一个应用"))
            assertFalse(text, TaskAuthorization.explicitlyRequestsForceStop(text, "抖音"))
    }
}
