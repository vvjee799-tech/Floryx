package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DocumentObservationVerifierTest {
    private val bounds = DeviceBounds(0, 0, 500, 900)
    private val screen = DeviceScreenSnapshot("browser", "Article", emptyList(), capturedAt = 1000,
        windowId = 2, geometry = DeviceScreenGeometry(bounds, 500, 900, 0),
        interactionContext = DeviceInteractionContext(listOf(DeviceWindowRegion(2, DeviceWindowKind.Application, 0, bounds, listOf(bounds), true, true))),
        documentText = DeviceDocumentText("0/1", "Article ending", false, false, 10, 14))
    private fun observed(screen: DeviceScreenSnapshot = this.screen) = DeviceToolResult(true, "Read", screen = screen)

    @Test fun transientMissingNodesGetOnlyFreshReadOnlyFollowUps() = runBlocking {
        var reads = 0
        val result = DocumentObservationVerifier.complete(observed(), read = {
            reads++; observed(screen.copy(capturedAt = 1100, documentText = screen.documentText!!.copy(treeComplete = true)))
        }, check = {})
        assertTrue(result.success); assertEquals(1, reads)
        assertTrue(result.summary.contains("document_observation_reads=2"))
        assertTrue(result.summary.contains("document_scroll_dispatches=0"))
        assertEquals("Article ending", result.screen!!.documentText!!.text)
    }

    @Test fun persistentPartialTreesAndTextLimitsNeverExposeAPartialBodyAsASuccess() = runBlocking {
        var reads = 0
        val result = DocumentObservationVerifier.complete(observed(), read = {
            observed(screen.copy(capturedAt = 1100L + ++reads * 100))
        }, check = {})
        assertEquals(2, reads); assertFalse(result.success)
        assertEquals("document_tree_incomplete", result.errorCode)
        assertTrue(result.screen!!.documentText!!.text.isEmpty())
        val capped = DocumentObservationVerifier.complete(observed(screen.copy(documentText = screen.documentText!!.copy(truncated = true))),
            read = { error("Text limits cannot be retried away") }, check = {})
        assertEquals("document_text_limit", capped.errorCode); assertTrue(capped.screen!!.documentText!!.text.isEmpty())
    }

    @Test fun changedSourcesStaleReadsAndSensitiveContentFailClosed() = runBlocking {
        for (bad in listOf(screen.copy(capturedAt = 1100, windowId = 3), screen.copy(capturedAt = 1000),
            screen.copy(capturedAt = 1100, sensitive = true), screen.copy(capturedAt = 1100, documentText = screen.documentText!!.copy(rootPath = "0/2")))) {
            val result = DocumentObservationVerifier.complete(observed(), read = { observed(bad) }, check = {})
            assertFalse(result.success); assertTrue(result.screen!!.documentText!!.text.isEmpty())
        }
    }

    @Test fun cancellationStopsAdditionalDocumentReads() = runBlocking {
        var reads = 0
        assertThrows(CancellationException::class.java) { runBlocking {
            DocumentObservationVerifier.complete(observed(), read = { reads++; observed() }, check = { throw CancellationException("Stopped") })
        } }
        assertEquals(0, reads)
    }
}
