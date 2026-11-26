package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Read-only probe of the same explicitly authorized public article; never a model task. */
class DocumentTreeProbeTest {
    private fun hasDocumentText(node: AccessibilityNodeInfo, inside: Boolean = false, depth: Int = 0): Boolean {
        if (depth > 128) return false
        val document = inside || node.className?.endsWith("WebView") == true
        if (document && !node.text.isNullOrBlank()) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (hasDocumentText(child, document, depth + 1)) return true }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return false
    }

    @Test fun inspectExposedOffscreenArticleText() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDocumentTreeProbe") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        ActionOutcomeServiceReadiness().ensureService()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val command = "am start -W -a android.intent.action.VIEW -d https://developer.android.google.cn/guide/topics/ui/accessibility/principles?hl=zh-cn -p com.android.chrome --ez create_new_tab true"
        val started = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        check(started.contains("Status: ok"))
        var root: AccessibilityNodeInfo? = null
        withTimeout(20_000) {
            while (true) {
                root = automation.rootInActiveWindow
                if (root?.packageName == "com.android.chrome" && hasDocumentText(root!!)) break
                @Suppress("DEPRECATION") root?.recycle()
                delay(100)
            }
        }
        // The condition above requires readable document content, not only the browser toolbar.
        @Suppress("DEPRECATION") root?.recycle()
        root = requireNotNull(automation.rootInActiveWindow)
        check(root!!.packageName == "com.android.chrome")
        val viewport = Rect().also(root!!::getBoundsInScreen)
        val rows = JSONArray()
        var visited = 0; var missing = 0; var limit = false
        val deadline = SystemClock.elapsedRealtime() + 15_000
        fun visit(node: AccessibilityNodeInfo, insideDocument: Boolean, depth: Int) {
            if (visited >= 12_000 || depth > 192 || SystemClock.elapsedRealtime() > deadline) { limit = true; return }
            visited++
            val document = insideDocument || node.className?.endsWith("WebView") == true
            val bounds = Rect().also(node::getBoundsInScreen)
            val text = node.text?.toString().orEmpty()
            if (document && text.isNotBlank() && !node.isPassword && !node.isEditable) rows.put(JSONObject()
                .put("text", text.take(4000)).put("visible", node.isVisibleToUser && Rect.intersects(viewport, bounds))
                .put("bounds", bounds.toShortString()))
            for (index in 0 until node.childCount) {
                val child = node.getChild(index)
                if (child == null) { missing++; continue }
                try { visit(child, document, depth + 1) }
                finally { @Suppress("DEPRECATION") child.recycle() }
                if (limit) break
            }
        }
        try { visit(root!!, false, 0) }
        finally { @Suppress("DEPRECATION") root?.recycle() }
        val result = JSONObject().put("visited", visited).put("missing_children", missing)
            .put("limit_reached", limit).put("rows", rows)
        File(instrumentation.targetContext.getExternalFilesDir(null), "document-tree-probe.json").writeText(result.toString(2))
    }

    @Test fun runtimeDocumentObservationReadsTheExposedEndingWithoutScrollingOrOffscreenTargets() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDocumentTreeProbe") == "true")
        inspectExposedOffscreenArticleText()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = AutonomyRuntimeFixture(instrumentation.targetContext)
        var passed = false
        try {
            fixture.connect(); fixture.create()
            fixture.await {
                val result = fixture.real.observeDocument()
                File(instrumentation.targetContext.getExternalFilesDir(null), "document-runtime-initial.txt")
                    .writeText("fixture_database=${fixture.name}\n" + result.asToolContent())
                result.screen?.documentText?.text?.contains("转写内容") == true
            }
            val before = requireNotNull(fixture.real.observeScreen().screen)
            var observed: String? = null
            fixture.responder = { request, round ->
                if (round == 1) listOf(ChatDelta.ToolCalls(listOf(ChatToolCall(UUID.randomUUID().toString(),
                    "observe_screen", """{"include_document_text":true,"include_image":false}"""))))
                else {
                    observed = request.messages.last { it.role == "tool" }.content
                    listOf(ChatDelta.Content("Synthetic document observation completed"), ChatDelta.Done)
                }
            }
            withContext(Dispatchers.Main) { fixture.runtime.updateInput("Read the exposed public document text"); fixture.runtime.send() }
            fixture.await { fixture.journal.latest(fixture.owner) != null && fixture.owner !in fixture.runtime.activeConversations.value }
            val after = requireNotNull(fixture.real.observeScreen().screen)
            val output = observed.orEmpty()
            assertTrue(output.contains("document_tree_complete=true"))
            assertTrue(output.contains("document_text_truncated=false"))
            assertTrue(output.contains("转写内容")); assertTrue(output.contains("字幕"))
            assertEquals(before.elements, after.elements)
            assertNull(after.documentText)
            assertEquals(1, fixture.observationCalls.get())
            assertEquals(0, fixture.waitCalls.get())
            assertTrue(fixture.confirmations.isEmpty())
            File(instrumentation.targetContext.getExternalFilesDir(null), "document-mode-diagnostic.txt").writeText(output)
            passed = true
        } finally { fixture.close(passed) }
    }
}
