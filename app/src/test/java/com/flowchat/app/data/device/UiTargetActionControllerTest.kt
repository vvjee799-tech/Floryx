package com.flowchat.app.data.device

import com.flowchat.app.domain.chat.TaskRecoveryGate
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UiTargetActionControllerTest {
    private val bounds = DeviceBounds(0, 0, 500, 900)
    private val selector = DeviceUiSelector(text = "Exact goal")
    private val node = DeviceUiElement(7, "Button", "Exact goal", null, "id/goal", DeviceBounds(20, 100, 300, 180),
        true, false, false, true, supportedActions = listOf("click"))
    private val initial = DeviceScreenSnapshot("synthetic.app", "Screen", listOf(node), screenId = "initial", windowId = 2,
        geometry = DeviceScreenGeometry(bounds, 500, 900, 0), interactionContext = DeviceInteractionContext(listOf(
            DeviceWindowRegion(2, DeviceWindowKind.Application, 1, bounds, listOf(bounds), true, true))))
    private val fresh = initial.copy(screenId = "fresh", elements = listOf(node.copy(index = 0)), elementQuery = selector)
    private class Gateway : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
        var reads = 0; var taps = 0
        var observer: suspend (Int) -> DeviceToolResult = { error("Configure observer") }
        var tapper: suspend (Int, String?) -> DeviceToolResult = { _, _ -> DeviceToolResult(true,"Changed",actionOutcome=DeviceActionOutcome.ProgressObserved) }
        var full: DeviceToolResult? = null
        override suspend fun waitForScreen(request: DeviceScreenWaitRequest): DeviceToolResult = observer(++reads).let {
            if(request.observationScope==null) it else it.copy(screen=it.screen?.copy(observationScope=request.observationScope))
        }
        override suspend fun observeScreen(): DeviceToolResult = full ?: observer(reads).let { it.copy(screen=it.screen?.copy(elementQuery=null)) }
        override suspend fun observeScreen(scope: DeviceObservationScope): DeviceToolResult = observeScreen().let { it.copy(screen=it.screen?.copy(observationScope=scope)) }
        override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult { taps++; return tapper(index,screenId) }
        override suspend fun tapLocatedUiTarget(index: Int, target: DeviceUiSelector, screenId: String) = tapUiElement(index,false,screenId)
    }
    private fun observed(screen: DeviceScreenSnapshot = fresh) = DeviceToolResult(true, "Observed", screen = screen, conditionMatched = true)

    @Test fun freshSemanticTargetUsesItsNewIndexAndOnlyRetriesExplicitlyUndispatchedChanges() = runBlocking {
        val gateway = Gateway().apply {
            observer = { n -> observed(fresh.copy(screenId="fresh-$n", elements=listOf(node.copy(index=n)))) }
            tapper = { index,id ->
                assertEquals("fresh-$index",id)
                if (index==1) DeviceToolResult(false,"Layout changed",errorCode="stale_screen",actionOutcome=DeviceActionOutcome.NotDispatched)
                else DeviceToolResult(true,"Changed",actionOutcome=DeviceActionOutcome.ProgressObserved)
            }
        }
        val result = UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector),initial)
        assertEquals(DeviceActionOutcome.ProgressObserved,result.actionOutcome); assertEquals(2,gateway.taps)
    }

    @Test fun locateAccountingChargesActualReadsAndRetainsFailedAndCancelledTimeWithoutChargingDispatch() = runBlocking {
        var time = 0L
        val gateway = Gateway().apply {
            observer = { time += 40; observed() }
            tapper = { _, _ -> time += 1400; DeviceToolResult(true, "Changed", actionOutcome = DeviceActionOutcome.ProgressObserved) }
        }
        val controller = UiTargetActionController(gateway) { time }
        repeat(8) {
            assertTrue(controller.tap(DeviceUiTargetRequest("initial", selector, 5000), initial).success)
            assertEquals(80L, controller.locateElapsedMillis)
        }
        gateway.observer = { time += 600; awaitCancellation() }
        val job = launch { controller.tap(DeviceUiTargetRequest("initial", selector, 5000), initial) }
        yield(); job.cancelAndJoin()
        assertEquals(600L, controller.locateElapsedMillis)
    }

    @Test fun unknownDispatchedAndMissingOutcomesAreNeverRepeated() = runBlocking {
        for (outcome in listOf(null, DeviceActionOutcome.Unverified, DeviceActionOutcome.Dispatched, DeviceActionOutcome.ProgressObserved)) {
            val gateway = Gateway().apply { observer = { observed() }; tapper = { _,_ -> DeviceToolResult(false,"Unknown",actionOutcome=outcome) } }
            val result = UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector),initial)
            assertEquals(outcome,result.actionOutcome); assertEquals(1,gateway.taps); assertEquals(1,gateway.reads)
        }
    }

    @Test fun duplicateIncompleteSensitiveChangedWindowAndMissingBaselineCannotClick() = runBlocking {
        for (screen in listOf(fresh.copy(elements=fresh.elements+fresh.elements.single().copy(index=1)), fresh.copy(searchComplete=false),
            fresh.copy(truncated=true), fresh.copy(sensitive=true), fresh.copy(windowId=3), fresh.copy(packageName="another.app"),
            fresh.copy(observationScope=DeviceObservationScope(DeviceUiSelector(description="other"))))) {
            val gateway=Gateway().apply { observer={ observed(screen) } }
            assertEquals(DeviceActionOutcome.NotDispatched,UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector),initial).actionOutcome)
            assertEquals(0,gateway.taps)
        }
        val gateway=Gateway()
        assertEquals(DeviceActionOutcome.NotDispatched,UiTargetActionController(gateway).tap(DeviceUiTargetRequest("old",selector),initial).actionOutcome)
        assertEquals(0,gateway.reads)
        val pane=DeviceObservationScope(DeviceUiSelector(description="pane"))
        assertEquals("ui_target_scope_change_requires_observation",UiTargetActionController(gateway).tap(
            DeviceUiTargetRequest("initial",selector,observationScope=DeviceObservationScope(DeviceUiSelector(description="another-pane"))),
            initial.copy(observationScope=pane)).errorCode)
    }

    @Test fun disabledOrCoveredTargetWaitsForActualReadinessInsteadOfAttemptingATap() = runBlocking {
        val gateway=Gateway().apply { observer={ n -> observed(if(n==1) fresh.copy(elements=listOf(node.copy(index=0,enabled=false))) else fresh) } }
        assertTrue(UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector),initial).success)
        assertEquals(2,gateway.reads); assertEquals(1,gateway.taps)
    }

    @Test fun nonClickableLabelUsesOnlyAProvenParentInACompleteSamePane() = runBlocking {
        val label=node.copy(index=1,clickable=false,supportedActions=emptyList(),parentIndex=0)
        val parent=node.copy(index=0,text=null,resourceId="id/row")
        val gateway=Gateway().apply {
            observer={ observed(fresh.copy(elements=listOf(label.copy(index=0,parentIndex=null)))) }
            full=observed(initial.copy(screenId="whole",elements=listOf(parent,label)))
            tapper={ index,id -> assertEquals(0,index);assertEquals("whole",id);DeviceToolResult(true,"Changed",actionOutcome=DeviceActionOutcome.ProgressObserved) }
        }
        assertTrue(UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector),initial).success)
        assertEquals(1,gateway.taps)
        gateway.taps=0;gateway.full=observed(initial.copy(truncated=true))
        assertEquals(DeviceActionOutcome.NotDispatched,UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector),initial).actionOutcome)
        assertEquals(0,gateway.taps)
    }

    @Test fun cancellationAndRelocationLimitCannotProduceAnUnboundedRetry() = runBlocking {
        val gateway=Gateway().apply { observer={ observed() };tapper={ _,_ -> DeviceToolResult(false,"Moved",errorCode="stale_screen",actionOutcome=DeviceActionOutcome.NotDispatched) } }
        val result=UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector),initial)
        assertEquals("ui_target_relocation_limit",result.errorCode);assertEquals(3,gateway.taps)
        val waiting=Gateway().apply { observer={ awaitCancellation() } }
        val job=launch { UiTargetActionController(waiting).tap(DeviceUiTargetRequest("initial",selector),initial) }
        yield();job.cancelAndJoin();assertEquals(0,waiting.taps)
    }

    @Test fun requestRejectsUnknownFieldsIndicesAndUnboundedWaitsAndReplayIgnoresOnlyWaitAllowance() {
        for (arguments in listOf("{}", """{"screen_id":"s","target":{}}""", """{"screen_id":"s","target":{"text":"x"},"index":1}""",
            """{"screen_id":"s","target":{"text":"x"},"timeout_ms":6000}""", """{"screen_id":"s","target":{"text":"x"},"timeout_ms":"1000"}"""))
            assertThrows(Exception::class.java) { DeviceUiTargetRequest.fromToolArguments(arguments) }
        assertEquals(TaskRecoveryGate.replayFingerprint("""{"screen_id":"s","target":{"text":"x"},"timeout_ms":1000}"""),
            TaskRecoveryGate.replayFingerprint("""{"screen_id":"new","target":{"text":"x"},"timeout_ms":3000}"""))
    }

    @Test fun semanticLabelLeavingAnOtherwiseUnchangedRowInvalidatesThatRowsAuthorityToClick() {
        val oldRow=node.copy(index=0,text=null,resourceId="id/row")
        val newRow=oldRow.copy(index=2,resourceId="id/other-row")
        val label=node.copy(index=1,clickable=false,supportedActions=emptyList(),parentIndex=0)
        val before=initial.copy(elements=listOf(oldRow,label,newRow))
        assertEquals(0,DeviceUiTargetPolicy.resolve(before,selector)?.index)
        val after=before.copy(elements=listOf(oldRow,label.copy(parentIndex=2),newRow))
        assertEquals(oldRow,after.elements.first())
        assertEquals(2,DeviceUiTargetPolicy.resolve(after,selector)?.index)
    }

    @Test fun narrowedPaneRetainsSiblingFeedbackWhileAnIncompletePaneIsRejectedBeforeTap() = runBlocking {
        val pane=DeviceObservationScope(DeviceUiSelector(description="pane"))
        val gateway=Gateway().apply { observer={ observed() } }
        val result=UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector,observationScope=pane),initial.copy(truncated=true))
        assertTrue(result.success);assertEquals(1,gateway.taps)
        gateway.taps=0;gateway.full=observed(initial.copy(truncated=true))
        val rejected=UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial",selector),initial)
        assertEquals(DeviceActionOutcome.NotDispatched,rejected.actionOutcome);assertEquals(0,gateway.taps)
    }

    @Test fun searchEditTextEchoDoesNotBlockSingleClickableResultRow() = runBlocking {
        val searchBox = DeviceUiElement(
            index = 2, className = "android.widget.EditText", text = "Exact goal", contentDescription = null,
            resourceId = "id/search_edit", bounds = DeviceBounds(20, 20, 480, 90),
            clickable = true, editable = true, scrollable = false, enabled = true, focused = true,
            supportedActions = listOf("click", "set_text", "ime_enter")
        )
        val row = node.copy(index = 0, className = "android.widget.RelativeLayout", text = null, resourceId = "id/search_item")
        val rowTitle = DeviceUiElement(
            index = 1, className = "android.widget.TextView", text = "Exact goal", contentDescription = null,
            resourceId = "id/tv_title", bounds = DeviceBounds(20, 100, 300, 180),
            clickable = false, editable = false, scrollable = false, enabled = true, parentIndex = 0,
            supportedActions = listOf("show_on_screen")
        )
        var tappedIndex = -1
        val gateway = Gateway().apply {
            observer = {
                observed(fresh.copy(elements = listOf(rowTitle.copy(index = 0, parentIndex = null), searchBox.copy(index = 1))))
            }
            full = observed(initial.copy(screenId = "whole", elements = listOf(row, rowTitle, searchBox)))
            tapper = { index, id ->
                tappedIndex = index
                assertEquals("whole", id)
                DeviceToolResult(true, "Opened result", actionOutcome = DeviceActionOutcome.ProgressObserved)
            }
        }
        val result = UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial", selector), initial)
        assertTrue(result.asToolContent(), result.success)
        assertEquals(1, gateway.taps)
        assertEquals(0, tappedIndex)
        assertEquals(0, DeviceUiTargetPolicy.resolve(initial.copy(elements = listOf(row, rowTitle, searchBox)), selector)?.index)
    }

    @Test fun searchEditTextAndStaticHeaderStillResolveSingleClickableResultRow() = runBlocking {
        val searchBox = DeviceUiElement(
            index = 3, className = "android.widget.EditText", text = "Exact goal", contentDescription = null,
            resourceId = "id/search_edit", bounds = DeviceBounds(20, 20, 480, 90),
            clickable = true, editable = true, scrollable = false, enabled = true, focused = true,
            supportedActions = listOf("click", "set_text")
        )
        val staticHeader = DeviceUiElement(
            index = 0, className = "android.widget.TextView", text = "Exact goal", contentDescription = null,
            resourceId = "id/header", bounds = DeviceBounds(20, 95, 300, 130),
            clickable = false, editable = false, scrollable = false, enabled = true,
            supportedActions = listOf("show_on_screen")
        )
        val row = node.copy(index = 1, className = "android.widget.RelativeLayout", text = null, resourceId = "id/search_item")
        val rowTitle = DeviceUiElement(
            index = 2, className = "android.widget.TextView", text = "Exact goal", contentDescription = null,
            resourceId = "id/tv_title", bounds = DeviceBounds(20, 140, 300, 220),
            clickable = false, editable = false, scrollable = false, enabled = true, parentIndex = 1,
            supportedActions = listOf("show_on_screen")
        )
        var tappedIndex = -1
        val gateway = Gateway().apply {
            observer = {
                observed(fresh.copy(elements = listOf(
                    staticHeader.copy(index = 0, parentIndex = null),
                    rowTitle.copy(index = 1, parentIndex = null),
                    searchBox.copy(index = 2)
                )))
            }
            full = observed(initial.copy(screenId = "whole", elements = listOf(staticHeader, row, rowTitle, searchBox)))
            tapper = { index, _ ->
                tappedIndex = index
                DeviceToolResult(true, "Opened result", actionOutcome = DeviceActionOutcome.ProgressObserved)
            }
        }
        val result = UiTargetActionController(gateway).tap(DeviceUiTargetRequest("initial", selector), initial)
        assertTrue(result.asToolContent(), result.success)
        assertEquals(1, gateway.taps)
        assertEquals(1, tappedIndex)
    }

    @Test fun duplicateClickableRowsWithSearchEditTextOrDisabledPeerStrictlyRejectAmbiguity() = runBlocking {
        val searchBox = DeviceUiElement(
            index = 4, className = "android.widget.EditText", text = "Exact goal", contentDescription = null,
            resourceId = "id/search_edit", bounds = DeviceBounds(20, 20, 480, 90),
            clickable = true, editable = true, scrollable = false, enabled = true, focused = true,
            supportedActions = listOf("click", "set_text")
        )
        val row1 = node.copy(index = 0, text = null, resourceId = "id/row_1")
        val title1 = node.copy(index = 1, className = "TextView", clickable = false, supportedActions = emptyList(), parentIndex = 0)
        val row2 = node.copy(index = 2, text = null, resourceId = "id/row_2")
        val title2 = node.copy(index = 3, className = "TextView", clickable = false, supportedActions = emptyList(), parentIndex = 2)
        val twoRowsGateway = Gateway().apply {
            observer = {
                observed(fresh.copy(elements = listOf(
                    title1.copy(index = 0, parentIndex = null),
                    title2.copy(index = 1, parentIndex = null),
                    searchBox.copy(index = 2)
                )))
            }
            full = observed(initial.copy(screenId = "whole", elements = listOf(row1, title1, row2, title2, searchBox)))
        }
        val ambiguousTwoRows = UiTargetActionController(twoRowsGateway).tap(DeviceUiTargetRequest("initial", selector), initial)
        assertEquals("ui_target_ambiguous", ambiguousTwoRows.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, ambiguousTwoRows.actionOutcome)
        assertEquals(0, twoRowsGateway.taps)
        assertTrue(ambiguousTwoRows.summary.contains("resource_id"))

        val disabledRow1 = row1.copy(enabled = false)
        val disabledPeerGateway = Gateway().apply {
            observer = {
                observed(fresh.copy(elements = listOf(
                    title1.copy(index = 0, parentIndex = null),
                    title2.copy(index = 1, parentIndex = null)
                )))
            }
            full = observed(initial.copy(screenId = "whole", elements = listOf(disabledRow1, title1, row2, title2)))
        }
        val ambiguousDisabledPeer = UiTargetActionController(disabledPeerGateway).tap(DeviceUiTargetRequest("initial", selector), initial)
        assertEquals("ui_target_ambiguous", ambiguousDisabledPeer.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, ambiguousDisabledPeer.actionOutcome)
        assertEquals(0, disabledPeerGateway.taps)
    }

    @Test fun soleEditTextAndExplicitEditTextSelectorRemainClickableWhileDuplicateInputsAndStaticOnlyReject() = runBlocking {
        val searchBox = DeviceUiElement(
            index = 0, className = "android.widget.EditText", text = "Exact goal", contentDescription = null,
            resourceId = "id/search_edit", bounds = DeviceBounds(20, 20, 480, 90),
            clickable = true, editable = true, scrollable = false, enabled = true, focused = true,
            supportedActions = listOf("click", "set_text")
        )
        val staticLabel = node.copy(index = 1, className = "TextView", clickable = false, supportedActions = emptyList(), parentIndex = null)

        val soleGateway = Gateway().apply { observer = { observed(fresh.copy(elements = listOf(searchBox))) } }
        val soleResult = UiTargetActionController(soleGateway).tap(DeviceUiTargetRequest("initial", selector), initial)
        assertTrue(soleResult.success)
        assertEquals(1, soleGateway.taps)

        val explicitSelector = DeviceUiSelector(resourceId = "id/search_edit", text = "Exact goal")
        var tappedIndex = -1
        val explicitGateway = Gateway().apply {
            observer = { observed(fresh.copy(elementQuery = explicitSelector, elements = listOf(searchBox))) }
            full = observed(initial.copy(screenId = "whole", elements = listOf(searchBox, staticLabel)))
            tapper = { index, _ -> tappedIndex = index; DeviceToolResult(true, "Focused box", actionOutcome = DeviceActionOutcome.ProgressObserved) }
        }
        val explicitResult = UiTargetActionController(explicitGateway).tap(DeviceUiTargetRequest("initial", explicitSelector), initial)
        assertTrue(explicitResult.success)
        assertEquals(1, explicitGateway.taps)
        assertEquals(0, tappedIndex)

        val twoEditsGateway = Gateway().apply {
            observer = { observed(fresh.copy(elements = listOf(searchBox, searchBox.copy(index = 1, resourceId = "id/other_edit")))) }
        }
        val twoEditsResult = UiTargetActionController(twoEditsGateway).tap(DeviceUiTargetRequest("initial", selector), initial)
        assertEquals("ui_target_ambiguous", twoEditsResult.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, twoEditsResult.actionOutcome)
        assertEquals(0, twoEditsGateway.taps)

        var time = 0L
        val staticOnlyGateway = Gateway().apply {
            observer = { observed(fresh.copy(elements = listOf(staticLabel.copy(index = 0), searchBox.copy(index = 1)))) }
            full = observed(initial.copy(screenId = "whole", elements = listOf(staticLabel, searchBox))).also { }
        }
        val staticOnlyResult = UiTargetActionController(staticOnlyGateway) { time += 150; time }
            .tap(DeviceUiTargetRequest("initial", selector, timeoutMillis = 200), initial)
        assertEquals(DeviceActionOutcome.NotDispatched, staticOnlyResult.actionOutcome)
        assertEquals(0, staticOnlyGateway.taps)
    }
}
