package com.flowchat.app.domain.assistant

import com.flowchat.app.domain.assistant.SystemAssistantReadiness.State
import org.junit.Assert.*
import org.junit.Test

class SystemAssistantReadinessTest {
    @Test fun holdingTheRoleWithoutAnActiveVoiceEntryRequiresSetup() {
        val readiness = SystemAssistantReadiness(true, true, false)
        assertEquals(State.VoiceEntryInactive, readiness.state)
        assertFalse(readiness.ready)
        assertEquals(State.VoiceEntryActive, readiness.copy(voiceEntryActive = true).state)
        assertTrue(readiness.copy(voiceEntryActive = true).ready)
    }

    @Test fun olderAndroidKeepsItsActivityEntryWithoutAConfiguredVoiceService() {
        val readiness = SystemAssistantReadiness(true, false, null)
        assertEquals(State.ActivityEntrySelected, readiness.state)
        assertTrue(readiness.ready)
        assertEquals(State.NotSelected, readiness.copy(defaultSelected = false).state)
        assertFalse(readiness.copy(defaultSelected = false).ready)
    }

    @Test fun aMissingDefaultRoleIsNotReadyEvenIfTheVoiceSettingStillPointsToFloryx() {
        for (active in listOf(true, false, null)) {
            val readiness = SystemAssistantReadiness(false, true, active)
            assertEquals(State.NotSelected, readiness.state)
            assertFalse(readiness.ready)
        }
    }

    @Test fun failedStatusReadsAreNotReportedAsInactiveOrReady() {
        for (readiness in listOf(
            SystemAssistantReadiness(null, true, true),
            SystemAssistantReadiness(null, false, null),
            SystemAssistantReadiness(true, true, null)
        )) {
            assertEquals(State.Unverified, readiness.state)
            assertFalse(readiness.ready)
        }
    }
}
