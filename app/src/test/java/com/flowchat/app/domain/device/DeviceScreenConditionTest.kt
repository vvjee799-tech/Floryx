package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class DeviceScreenConditionTest {
    private val selector = DeviceUiSelector(resourceId = "result", text = "Completed")
    private val element = DeviceUiElement(0, "Button", "Completed", null, "result", DeviceBounds(10, 10, 90, 90), true, false, false, true)
    private val screen = DeviceScreenSnapshot("fixture", "", listOf(element), windowId = 1,
        geometry = DeviceScreenGeometry(DeviceBounds(0, 0, 100, 100), 100, 100, 0),
        interactionContext = DeviceInteractionContext(listOf(DeviceWindowRegion(1, DeviceWindowKind.Application, 1,
            DeviceBounds(0, 0, 100, 100), listOf(DeviceBounds(0, 0, 100, 100)), true, true))))

    @Test fun exactCombinedPredicatesDoNotConfuseProgressWithBusinessResult() {
        val condition = DeviceScreenCondition("fixture", 1, selector, DeviceElementState.Actionable)
        assertTrue(condition.matches(screen))
        assertFalse(condition.matches(screen.copy(packageName = "other")))
        assertFalse(condition.matches(screen.copy(elements = listOf(element.copy(text = "Business failed")))))
        assertFalse(condition.matches(screen.copy(elements = listOf(element, element.copy(index = 1)))))
        assertFalse(condition.matches(screen.copy(sensitive = true)))
        assertFalse(condition.matches(screen.copy(elements = listOf(element.copy(enabled = false)))))
        assertFalse(condition.matches(screen.copy(interactionContext = null)))
    }

    @Test fun absenceRequiresACompleteSearchOfTheRightScope() {
        val absent = DeviceScreenCondition(element = selector, state = DeviceElementState.Absent)
        assertTrue(absent.matches(screen.copy(elements = emptyList())))
        assertFalse(absent.matches(screen.copy(elements = emptyList(), truncated = true)))
        assertTrue(absent.matches(screen.copy(elements = emptyList(), truncated = true, elementQuery = selector, searchComplete = true)))
        assertFalse(absent.matches(screen.copy(elements = emptyList(), elementQuery = selector, searchComplete = false)))
        assertFalse(absent.matches(screen.copy(elements = emptyList(), elementQuery = DeviceUiSelector(text = "other"))))
    }

    @Test fun keyboardUnknownAndUncheckedUnknownAreNotFalseEvidence() {
        assertFalse(DeviceScreenCondition(keyboardVisible = false).matches(screen.copy(interactionContext = null)))
        assertFalse(DeviceScreenCondition(keyboardVisible = false).matches(screen.copy(interactionContext = screen.interactionContext!!.copy(complete = false))))
        assertTrue(screen.copy(interactionContext = screen.interactionContext!!.copy(complete = false)).asToolContent().contains("keyboard_visible=unknown"))
        assertTrue(DeviceScreenCondition(keyboardVisible = false).matches(screen))
        assertFalse(DeviceScreenCondition(element = selector, state = DeviceElementState.Unchecked).matches(screen))
        assertTrue(DeviceScreenCondition(element = selector, state = DeviceElementState.Checked)
            .matches(screen.copy(elements = listOf(element.copy(checked = true)))))
    }

    @Test fun validatesArgumentsAndPreservesLegacyEmptyWait() {
        assertNull(DeviceScreenWaitRequest.fromToolArguments("{}"))
        val parsed = DeviceScreenWaitRequest.fromToolArguments("""{"timeout_ms":2200,"condition":{"package_name":"fixture","element":{"text":"Completed"}}}""")!!
        assertEquals(2200L, parsed.timeoutMillis)
        assertEquals("Completed", parsed.condition!!.element!!.text)
        for (args in listOf("""{"timeout_ms":10001}""", """{"timeout_ms":"5000"}""", """{"condition":{}}""",
            """{"condition":{"element":{}}}""", """{"condition":{"keyboard_visible":"false"}}""",
            """{"condition":{"state":"absent","package_name":"fixture"}}""")) {
            assertTrue(args, runCatching { DeviceScreenWaitRequest.fromToolArguments(args) }.isFailure)
        }
    }

    @Test fun parsesDirectedAndRegionWaitsAndRejectsMalformedScopes() {
        val parsed = DeviceScreenWaitRequest.fromToolArguments("""{"observation_scope":{"root":{"description":"list"},"kind":"scroll_container"},"condition":{"region_changed":{"screen_id":"crop"}}}""")!!
        assertEquals(DeviceObservationScope.Kind.ScrollContainer, parsed.observationScope!!.kind)
        assertEquals("crop", parsed.condition!!.regionChangedFrom)
        assertFalse(parsed.condition.matches(screen))
        assertTrue(parsed.condition.matches(screen, regionChanged = true))
        for (args in listOf("""{"observation_scope":{}}""", """{"observation_scope":{"root":{}}}""",
            """{"observation_scope":{"root":{"description":""}}}""", """{"observation_scope":{"root":{"index":2}}}""",
            """{"observation_scope":{"root":{"text":"x"},"kind":"page"}}""", """{"condition":{"region_changed":{"screen_id":3}}}""")) {
            assertTrue(args, runCatching { DeviceScreenWaitRequest.fromToolArguments(args) }.isFailure)
        }
    }

    @Test fun waitForScreenRejectsAmbiguousEditTextPairWhileTapTargetDisambiguationAllowsSingleResultMatch() {
        val label = DeviceUiElement(0, "TextView", "Completed", null, "id/title", DeviceBounds(10, 100, 90, 140),
            false, false, false, true)
        val edit = DeviceUiElement(1, "EditText", "Completed", null, "id/search", DeviceBounds(10, 10, 90, 50),
            true, true, false, true, focused = true)
        val otherEdit = edit.copy(index = 2, resourceId = "id/search_2")
        val button1 = DeviceUiElement(0, "Button", "Completed", null, "id/btn_1", DeviceBounds(10, 100, 90, 140),
            true, false, false, true)
        val button2 = button1.copy(index = 1, resourceId = "id/btn_2")
        val textSelector = DeviceUiSelector(text = "Completed")

        val defaultCondition = DeviceScreenCondition("fixture", 1, textSelector)
        assertFalse(defaultCondition.matches(screen.copy(elements = listOf(label, edit))))

        val disambiguating = DeviceScreenCondition("fixture", 1, textSelector, allowTapTargetDisambiguation = true)
        assertTrue(disambiguating.matches(screen.copy(elements = listOf(label, edit))))
        assertFalse(disambiguating.matches(screen.copy(elements = listOf(button1, button2))))
        assertFalse(disambiguating.matches(screen.copy(elements = listOf(edit, otherEdit))))
    }
}
