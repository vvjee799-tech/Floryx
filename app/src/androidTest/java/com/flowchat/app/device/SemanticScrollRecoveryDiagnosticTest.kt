package com.flowchat.app.device

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SemanticScrollRecoveryDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runSemanticRecovery") == "true")
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun acknowledgedStableScrollContinuesWithoutAUserButCanvasUncertaintyStillBlocksActions() = runBlocking<Unit> {
        enabled(); ActionOutcomeServiceReadiness().ensureService()
        for (mode in listOf("readable", "canvas", "moving", "progress")) {
            val f = AutonomyRuntimeFixture(context); var passed = false
            try {
                f.connect(); f.create()
                shell("am start -W -f 0x10008000 -n ${instrumentation.context.packageName}/${SemanticScrollFixtureActivity::class.java.name} --es mode $mode")
                f.await { f.real.observeScreen().screen?.packageName == instrumentation.context.packageName }
                var stage = 0; var scrollOutcome: String? = null
                f.responder = { request, _ ->
                    fun tool(name: String, args: JsonObject) = listOf(ChatDelta.ToolCalls(listOf(ChatToolCall(UUID.randomUUID().toString(), name, args.toString()))))
                    when (stage++) {
                        0 -> tool("observe_screen", buildJsonObject { put("include_image", false) })
                        1 -> {
                            val screen = requireNotNull(f.lastResult?.screen)
                            tool("scroll_container", buildJsonObject {
                                put("screen_id", screen.screenId); put("index", screen.elements.single { it.contentDescription == "semantic_body" }.index)
                                put("direction", if (mode in setOf("moving", "progress")) "down" else "forward")
                                if (mode in setOf("moving", "progress")) put("distance_percent", 35)
                            })
                        }
                        2 -> {
                            scrollOutcome = request.messages.last { it.role == "tool" }.content
                            if (mode in setOf("moving", "progress")) listOf(ChatDelta.Content("Controlled-scroll observation completed"), ChatDelta.Done)
                            else {
                            val screen = requireNotNull(f.lastResult?.screen)
                            tool("tap_ui_element", buildJsonObject { put("screen_id", screen.screenId); put("index", screen.elements.single { it.contentDescription == "semantic_close" }.index) })
                            }
                        }
                        else -> listOf(ChatDelta.Content("Synthetic recovery driver finished"), ChatDelta.Done)
                    }
                }
                withContext(Dispatchers.Main) { f.runtime.updateInput("Dismiss the fixture blocker and read the article"); f.runtime.send() }
                f.await { f.journal.latest(f.owner) != null && f.owner !in f.runtime.activeConversations.value }
                val screen = requireNotNull(f.real.observeScreen().screen)
                val metrics = screen.elements.single { it.contentDescription?.startsWith("semantic_metrics:") == true }.contentDescription
                if (mode == "readable") {
                    assertTrue(scrollOutcome.orEmpty().contains("action_outcome=StateObserved"))
                    assertEquals("semantic_metrics:1:1:0", metrics)
                    assertTrue(screen.elements.any { it.text.equals("Readable article ready", ignoreCase = true) })
                    assertTrue(f.journal.latest(f.owner)?.metadataJson.orEmpty().let { Json.parseToJsonElement(it).jsonObject["pendingEffects"]?.jsonArray.isNullOrEmpty() })
                } else if (mode == "canvas") {
                    assertTrue(scrollOutcome.orEmpty().contains("action_outcome=Unverified"))
                    assertEquals("semantic_metrics:1:0:0", metrics)
                    assertTrue(f.chats.getMessages(f.owner).any { it.executionContext.orEmpty().contains("recovery_verification_required") })
                } else {
                    assertTrue(scrollOutcome.orEmpty(), scrollOutcome.orEmpty().contains("action_outcome=" +
                        if (mode == "progress") "ProgressObserved" else "StateObserved"))
                    assertTrue(scrollOutcome.orEmpty().contains("settled=true"))
                    assertTrue(scrollOutcome.orEmpty(), scrollOutcome.orEmpty().contains("scroll_observation_reads=" +
                        if (mode == "progress") "2" else "3"))
                    assertTrue(scrollOutcome.orEmpty().contains("actions=1"))
                    assertEquals("semantic_metrics:0:0:1", metrics)
                }
                assertTrue(f.confirmations.isEmpty()); passed = true
            } finally { f.close(passed) }
        }
    }

    @Test fun genericBrowserOpensTheExistingDefaultChromeWithoutTouchingItsTabs() = runBlocking<Unit> {
        enabled(); ActionOutcomeServiceReadiness().ensureService()
        val f = AutonomyRuntimeFixture(context)
        try {
            f.connect()
            AndroidAppLauncher(context, f.gateway, f.tasks).openApp("浏览器")
            f.await { f.real.observeScreen().screen?.packageName == "com.android.chrome" }
        } finally { f.close(false) }
    }
}
