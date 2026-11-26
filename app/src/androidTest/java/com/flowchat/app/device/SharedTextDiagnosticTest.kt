package com.flowchat.app.device

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.R
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.locale.AppLocale
import com.flowchat.app.presentation.sharing.SharedTextActivity
import com.flowchat.app.presentation.sharing.SharedTextPreviewModel
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real entry/UI verification. Only a GUID provider at a loopback model may be submitted. */
class SharedTextDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val entry get() = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
    private val data get() = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
    private val marker get() = context.getFileStreamPath("r6-share-fixture.json")
    private val activities = mutableListOf<Activity>()
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runSharedTextDiagnostics") == "true")
    private suspend fun await(label: String, check: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!check()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        report(label)
    }
    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nR6_SHARE $value\n") })
    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }
    private suspend fun openShare(text: String): SharedTextActivity = captureShare {
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
                .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private suspend fun openExternalShare(summary: Boolean = false): SharedTextActivity = captureShare {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val source = "${instrumentation.context.packageName}/${SharedTextSourceFixtureActivity::class.java.name}"
        check(shell("am start -W -f 0x10008000 -n $source --ez summary $summary").contains("Status: ok"))
        await("source shares through Android chooser") { clickText("R6_SHARE_SYNTHETIC_TEXT") }
        val labels = listOf(context.getString(R.string.share_text_title),
            AppLocale.wrapContext(context).getString(R.string.share_text_title), context.getString(R.string.app_name)).distinct()
        await("Floryx chosen from actual sharesheet") { labels.any { clickText(it, outsideFloryx = true) } }
    }

    private suspend fun selectionSourceRecord() = JSONObject(shell(
        "run-as ${instrumentation.context.packageName} cat files/r6-selection-result.json"))

    private suspend fun openExternalSelection(readOnly: Boolean): SharedTextActivity = captureShare {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val previous = runCatching { selectionSourceRecord().getString("instance") }.getOrNull()
        check(shell("am start -W -f 0x10008000 -n ${instrumentation.context.packageName}/${ProcessTextSourceFixtureActivity::class.java.name}").contains("Status: ok"))
        var sourceInstance = ""
        await("fresh selection source instance recorded") {
            selectionSourceRecord().let {
                sourceInstance = it.getString("instance")
                sourceInstance != previous && it.getString("stage") == "ready"
            }
        }
        await("explicit standard selection request from separate source") {
            // A unique label rejects a stale accessibility node from the previous source task.
            clickText((if (readOnly) "R6_PROCESS_READONLY_" else "R6_PROCESS_EDITABLE_") + sourceInstance)
        }
        await("source recorded exactly this request before launch") {
            selectionSourceRecord().let { it.getString("instance") == sourceInstance &&
                it.getString("stage") == "launched" && it.getBoolean("readOnly") == readOnly }
        }
    }

    private suspend fun verifySourceReturn(instance: String) {
        // singleTask brings the SAME source back to receive its pending Activity result.
        check(shell("am start -W -n ${instrumentation.context.packageName}/${ProcessTextSourceFixtureActivity::class.java.name}").contains("Status: ok"))
        await("same source received cancellation without replacement") {
            selectionSourceRecord().let {
                it.getString("instance") == instance && it.getString("stage") == "returned" &&
                    it.getInt("resultCode") == Activity.RESULT_CANCELED && !it.getBoolean("returnedText") &&
                    it.getBoolean("readOnlyUnchanged") && it.getBoolean("editableUnchanged")
            }
        }
    }
    private suspend fun captureShare(launch: suspend () -> Unit): SharedTextActivity {
        val result = AtomicReference<SharedTextActivity>()
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { if (activity is SharedTextActivity) result.set(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        withContext(Dispatchers.Main) { application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            launch()
            await("share activity resumed") { result.get() != null }
            return result.get().also { activities += it }
        } finally { withContext(Dispatchers.Main) { application.unregisterActivityLifecycleCallbacks(callbacks) } }
    }
    private suspend fun choose(activity: Activity, providerName: String) {
        await("destination available") { withContext(Dispatchers.Main) {
            val spinner = activity.findViewById<Spinner>(R.id.share_text_destination)
            (0 until spinner.count).any { spinner.getItemAtPosition(it).toString().contains(providerName) }
        } }
        withContext(Dispatchers.Main) {
            val spinner = activity.findViewById<Spinner>(R.id.share_text_destination)
            val index = (1 until spinner.count).single { spinner.getItemAtPosition(it).toString().contains(providerName) }
            spinner.setSelection(index)
        }
        await("explicit choice enables add") { withContext(Dispatchers.Main) { activity.findViewById<Button>(R.id.share_text_add).isEnabled } }
    }
    private fun clickText(text: String, outsideFloryx: Boolean = false): Boolean {
        for (node in automation.windows.mapNotNull { it.root }.flatMap { it.findAccessibilityNodeInfosByText(text) }) {
            if (!node.isVisibleToUser || (outsideFloryx && node.packageName == context.packageName)) continue
            var current: AccessibilityNodeInfo? = node
            repeat(8) {
                val value = current
                if (value != null && value.isClickable && value.isEnabled) return value.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = value?.parent
            }
        }
        return false
    }

    @Test fun externalSystemSharesheetPreviewCancelAndRotationNeverSend() = runBlocking<Unit> {
        enabled()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect {} }
        try {
            await("runtime list ready") { runtime.uiState.value.providers.isNotEmpty() }
            val before = data.chats().observeConversations().first().associate { it.id to data.chats().getMessages(it.id).map { row -> row.id } }
            val selected = runtime.uiState.value.currentConversation?.id
            val input = runtime.uiState.value.input
            val attachment = runtime.uiState.value.pendingAttachment
            val activity = openExternalShare()
            await("external text shown") { withContext(Dispatchers.Main) {
                activity.findViewById<TextView>(R.id.share_text_preview).text.toString() == SharedTextSourceFixtureActivity.PLAIN_TEXT
            } }
            withContext(Dispatchers.Main) {
                assertFalse(activity.findViewById<Button>(R.id.share_text_add).isEnabled)
                assertEquals(0, activity.findViewById<Spinner>(R.id.share_text_destination).selectedItemPosition)
                assertFalse(activity.findViewById<TextView>(R.id.share_text_source).text.contains("forged.source"))
                val hierarchy = android.util.SparseArray<android.os.Parcelable>()
                activity.window.decorView.saveHierarchyState(hierarchy)
                assertNull("Shared text must not be placed in saved view state", hierarchy.get(R.id.share_text_preview))
                assertNull("Destination must not be restored outside the current preview", hierarchy.get(R.id.share_text_destination))
            }
            val rotated = captureShare { withContext(Dispatchers.Main) { activity.recreate() } }
            await("recreated preview retained") { withContext(Dispatchers.Main) {
                rotated.findViewById<TextView>(R.id.share_text_preview).text.toString() == SharedTextSourceFixtureActivity.PLAIN_TEXT
            } }
            withContext(Dispatchers.Main) { rotated.findViewById<Button>(R.id.share_text_cancel).performClick() }
            await("cancel closed preview") { rotated.isDestroyed }
            assertEquals(selected, runtime.uiState.value.currentConversation?.id)
            assertEquals(input, runtime.uiState.value.input)
            assertEquals(attachment, runtime.uiState.value.pendingAttachment)
            assertEquals(before, data.chats().observeConversations().first().associate { it.id to data.chats().getMessages(it.id).map { row -> row.id } })
            report("external source + actual chooser; no default destination; recreate/cancel preserved every conversation and editor")
        } finally {
            withContext(Dispatchers.Main) { activities.forEach { if (!it.isDestroyed) it.finish() } }
            collector.cancelAndJoin()
        }
    }

    private suspend fun restoreFixture() {
        if (!marker.exists()) return
        val saved = JSONObject(marker.readText())
        val provider = saved.getString("provider")
        check(provider.startsWith("r6-share-qa-"))
        val providers = listOf(provider) + saved.optString("editorProvider").takeIf { it.isNotBlank() }?.also {
            check(it == "$provider-editor")
        }.let { listOfNotNull(it) }
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val owned = data.chats().observeConversations().first().filter { it.providerId in providers }
        withContext(Dispatchers.Main) { owned.forEach { runtime.stopFromAssistant(it.id) } }
        await("fixture tasks stopped") { owned.none { it.id in runtime.activeConversations.value } }
        withContext(Dispatchers.Main) { owned.forEach { runtime.deleteConversation(it.id) } }
        await("fixture conversations removed") { data.chats().observeConversations().first().none { it.providerId in providers } }
        providers.forEach { data.providers().deleteProvider(it) }
        saved.optString("selection").takeIf { it.isNotBlank() && data.chats().getConversation(it) != null }?.let {
            withContext(Dispatchers.Main) { runtime.selectConversation(it) }
        }
        entry.settings().apply {
            setMemoryEnabled(saved.getBoolean("memory")); setPowerModeEnabled(saved.getBoolean("power"))
            setCommandExecutionEnabled(saved.getBoolean("commands"))
            if (saved.has("assistantSelection")) {
                setAssistantModelSelection(saved.optString("assistantSelection").takeIf { it.isNotEmpty() }
                    ?.let { Json.decodeFromString<AssistantModelSelection>(it) })
            }
        }
        withContext(Dispatchers.IO) { check(context.getSharedPreferences("app_controls", Context.MODE_PRIVATE).edit().commit()) }
        check(marker.delete())
    }

    private suspend fun withSummaryFixture(caseId: String = "I02", block: suspend (AssistantModelSelection, String, TaskRuntimeDiagnosticTest.GatedModel,
        TaskRuntimeDiagnosticTest.GatedModel) -> Unit) {
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect {} }
        val model = TaskRuntimeDiagnosticTest.GatedModel("周六上午10点有手作课", "，须提前预约。")
        val otherModel = TaskRuntimeDiagnosticTest.GatedModel()
        try {
            await("share fixture runtime ready") { runtime.uiState.value.providers.isNotEmpty() }
            check(runtime.activeConversations.value.isEmpty())
            val provider = "r6-share-qa-${UUID.randomUUID()}"
            val editorProvider = "$provider-editor"
            val settings = entry.settings().state.value
            marker.writeText(JSONObject().apply {
                put("provider", provider); put("editorProvider", editorProvider)
                put("selection", runtime.uiState.value.currentConversation?.id.orEmpty())
                put("memory", settings.memoryEnabled); put("power", settings.powerModeEnabled); put("commands", settings.commandExecutionEnabled)
                put("assistantSelection", settings.assistantModelSelection?.let { Json.encodeToString(it) }.orEmpty())
            }.toString())
            data.providers().upsertProvider(ProviderConfig(id = provider, displayName = "$caseId Local Flash",
                baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "summary-provider-default"), null)
            data.providers().upsertProvider(ProviderConfig(id = editorProvider, displayName = "$caseId Other Local Model",
                baseUrl = "http://127.0.0.1:${otherModel.port}/v1", defaultModel = "editor-model"), null)
            val editor = data.chats().createConversation(editorProvider, "editor-model").id
            withContext(Dispatchers.Main) {
                runtime.selectConversation(editor)
                runtime.updateInput("I02_EDITOR_DRAFT")
                runtime.attachTextFile("editor-private.txt", "I02_EDITOR_ATTACHMENT")
            }
            await("independent editor selected") { runtime.uiState.value.currentConversation?.id == editor }
            block(AssistantModelSelection(provider, "deepseek-flash"), editor, model, otherModel)
        } finally {
            withContext(Dispatchers.Main) { activities.forEach { if (!it.isDestroyed) it.finish() } }
            model.close()
            otherModel.close()
            try { restoreFixture() } finally { collector.cancelAndJoin() }
        }
    }

    @Test fun explicitShareSummaryRunsOnceInTheShareActivity() = runBlocking<Unit> {
        verifyShareSummary(ordinaryShare = false)
    }

    @Test fun ordinaryShareRequiresOneChoiceAndKeepsItsOwnTask() = runBlocking<Unit> {
        verifyShareSummary(ordinaryShare = true)
    }

    private suspend fun verifyShareSummary(ordinaryShare: Boolean) {
        enabled()
        val caseId = if (ordinaryShare) "i03" else "i02"
        val official = InstrumentationRegistry.getArguments().getString("${caseId}OfficialFlash") == "true"
        if (official) check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        val sourceText = if (ordinaryShare) SharedTextSourceFixtureActivity.PLAIN_TEXT else SharedTextSourceFixtureActivity.SUMMARY_TEXT
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("${caseId}RunId"))
        require(run.matches(Regex("[a-z0-9-]+")))
        val directory = File(context.getExternalFilesDir(null), "d4-$caseId/$run")
        check(directory.mkdirs())
        val evidence = JSONObject().put("model_endpoint", if (official) "api.deepseek.com" else "loopback").put("case", caseId)
        suspend fun capture(activity: Activity, name: String) {
            // This secure window contains only fixture data. Render its view without changing FLAG_SECURE.
            val bitmap = withContext(Dispatchers.Main) {
                val decor = activity.window.decorView
                Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
            }
            try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        try {
            withSummaryFixture(caseId.uppercase()) { selection, editor, model, otherModel ->
                val runtime = withContext(Dispatchers.Main) { entry.runtime() }
                val selectedSelection = if (official) {
                    requireNotNull(entry.settings().state.value.assistantModelSelection).also {
                        check(it.providerId == "provider-deepseek" && it.modelName == "deepseek-flash")
                    }
                } else {
                    selection.also { entry.settings().setAssistantModelSelection(it) }
                }
                val chosenProvider = requireNotNull(data.providers().getProvider(selectedSelection.providerId))
                if (official) {
                    check(chosenProvider.isEnabled && java.net.URI(chosenProvider.baseUrl).let { it.scheme == "https" && it.host == "api.deepseek.com" })
                    check(!data.providers().getApiKey(chosenProvider).isNullOrBlank())
                }
                val originalIds = if (official) data.chats().observeConversations().first().map { it.id }.toSet() else emptySet()
                val controls = context.getSharedPreferences("app_controls", Context.MODE_PRIVATE)
                val hadUsageSetting = controls.contains("usage_tracking_enabled")
                val priorUsage = entry.settings().state.value.usageTrackingEnabled
                val wire = if (official) OfficialAssistantRequestWire(
                    client = data.client() as KtorOpenAiCompatibleClient,
                    directory = directory,
                    sample = caseId.uppercase(),
                    maxInputBytes = if (ordinaryShare) 24_576 else 40_960
                ) { payload ->
                    check((payload.optJSONArray("tools")?.length() ?: 0) == 0)
                    val wireMessages = payload.getJSONArray("messages")
                    check(wireMessages.length() == 2)
                    val sentContent = wireMessages.getJSONObject(1).getString("content")
                    check(sentContent.contains("untrusted reference content") && sentContent.endsWith(sourceText) &&
                        !sentContent.contains("I02_EDITOR_ATTACHMENT")) { "$caseId official share payload check failed" }
                } else null
                fun requestCount() = wire?.requestCount ?: model.requests.get()
                if (official) entry.settings().setUsageTrackingEnabled(true)
                var officialOwnerId: String? = null
                val localized = AppLocale.wrapContext(context)
                try {
                    val activity = openExternalShare(summary = !ordinaryShare)
                    await("share summary action is visible before any request") { withContext(Dispatchers.Main) {
                        activity.findViewById<Button>(R.id.share_summary_action).run { isShown && isEnabled } &&
                            activity.findViewById<TextView>(R.id.share_text_preview).text.toString() == sourceText
                    } }
                    val sourceLabel = withContext(Dispatchers.Main) {
                        assertEquals(0, activity.findViewById<Spinner>(R.id.share_text_destination).selectedItemPosition)
                        assertFalse(activity.findViewById<Button>(R.id.share_text_add).isEnabled)
                        activity.findViewById<TextView>(R.id.share_text_source).text.toString()
                    }
                    capture(activity, "01-summary-action")
                    assertEquals(0, requestCount())
                    assertEquals(0, otherModel.requests.get())
                    assertTrue(data.chats().observeConversations().first().none { it.providerId == selectedSelection.providerId && it.id !in originalIds })
                    evidence.put("requests_before_choice", 0).put("destination_selected_before_choice", false)
                    val started = SystemClock.elapsedRealtime()
                    await("the explicit summary action was tapped") { clickText(localized.getString(R.string.share_summary_action)) }
                    await("$caseId reached the selected endpoint once") { requestCount() == 1 }
                    evidence.put("tap_to_request_ms", SystemClock.elapsedRealtime() - started)
                    val request = wire?.request ?: JSONObject(model.bodies.single())
                    assertEquals(selectedSelection.modelName, request.getString("model"))
                    assertEquals(0, request.optJSONArray("tools")?.length() ?: 0)
                    val wireMessages = request.getJSONArray("messages")
                    assertEquals(2, wireMessages.length())
                    val sentContent = wireMessages.getJSONObject(1).getString("content")
                    assertTrue(sentContent.contains("untrusted reference content"))
                    assertTrue(sentContent.endsWith(sourceText))
                    assertFalse(sentContent.contains("I02_EDITOR_ATTACHMENT"))
                    if (sourceLabel == localized.getString(R.string.share_text_source_unknown)) {
                        assertTrue(sentContent.contains("Source package: not provided by the system; origin unverified"))
                    } else {
                        assertEquals(localized.getString(R.string.share_text_source, instrumentation.context.packageName), sourceLabel)
                        assertTrue(sentContent.contains("Source package: ${instrumentation.context.packageName}"))
                    }
                    val owner = data.chats().observeConversations().first().single { it.providerId == selectedSelection.providerId && it.id !in originalIds }
                    officialOwnerId = owner.id
                    assertEquals(selectedSelection, AssistantModelSelection.from(owner))
                    assertNotEquals(editor, owner.id)
                    assertEquals(editor, runtime.uiState.value.currentConversation?.id)
                    assertEquals("I02_EDITOR_DRAFT", runtime.uiState.value.input)
                    assertEquals("editor-private.txt", runtime.uiState.value.pendingAttachment?.name)
                    if (!official) {
                        await("progress and stop are visible in the share result") { withContext(Dispatchers.Main) {
                            activity.findViewById<Button>(R.id.share_summary_stop).isShown &&
                                !activity.findViewById<Spinner>(R.id.share_text_destination).isShown
                        } }
                        capture(activity, "02-summary-running")
                    } else if (withContext(Dispatchers.Main) { activity.findViewById<Button>(R.id.share_summary_stop).isShown }) {
                        capture(activity, "02-summary-running")
                    }
                    val releaseTime = SystemClock.elapsedRealtime()
                    if (!official) model.release()
                    val journal = entry.journal()
                    await("$caseId task completed") {
                        val state = journal.latest(owner.id)?.state
                        if (official && state in setOf("Failed", "Stopped", "Paused")) {
                            val last = data.chats().getMessages(owner.id).lastOrNull { it.role == MessageRole.Assistant }
                            error("$caseId ended as $state: ${last?.content.orEmpty().take(300)}")
                        }
                        state == "Completed" && owner.id !in runtime.activeConversations.value
                    }
                    val completed = requireNotNull(journal.latest(owner.id))
                    val messages = data.chats().getMessages(owner.id)
                    val sent = messages.single { it.role == MessageRole.User }
                    val answer = messages.single { it.role == MessageRole.Assistant }
                    if (official) {
                        val facts = listOf("周六|星期六|礼拜六", "10\\s*[点时:：]|十[点时]", "手[作工]", "预约")
                        assertTrue(answer.content, answer.content.isNotBlank() && facts.all { Regex(it).containsMatchIn(answer.content) })
                    } else {
                        assertEquals("周六上午10点有手作课，须提前预约。", answer.content)
                    }
                    await("$caseId answer visible in the original activity") { withContext(Dispatchers.Main) {
                        activity.findViewById<TextView>(R.id.share_text_preview).text.toString() == answer.content &&
                            !activity.findViewById<Button>(R.id.share_summary_stop).isShown
                    } }
                    evidence.put("tap_to_answer_ms", SystemClock.elapsedRealtime() - started)
                    if (!official) evidence.put("model_release_to_answer_ms", SystemClock.elapsedRealtime() - releaseTime)
                    capture(activity, "03-summary-result")
                    assertEquals("Completed", completed.state)
                    assertEquals("", sent.content)
                    assertEquals(localized.getString(R.string.share_summary_title), data.chats().getConversation(owner.id)?.title)
                    assertTrue(sent.attachmentText.orEmpty().endsWith(sourceText))
                    assertEquals(answer.id, completed.assistantMessageId)
                    assertEquals(MessageStatus.Complete, answer.status)
                    val switched = AssistantModelSelection(
                        requireNotNull(data.chats().getConversation(editor)).providerId, "alternate-local-model")
                    entry.settings().setAssistantModelSelection(switched)
                    val recreated = captureShare { withContext(Dispatchers.Main) { activity.recreate() } }
                    await("same answer after recreation and a default model change") { withContext(Dispatchers.Main) {
                        recreated.findViewById<TextView>(R.id.share_text_preview).text.toString() == answer.content
                    } }
                    await("full chat action was tapped") { clickText(localized.getString(R.string.assistant_full_chat)) }
                    await("full chat opens the same completed turn") {
                        runtime.uiState.value.currentConversation?.id == owner.id && runtime.uiState.value.messages.any { it.id == answer.id }
                    }
                    assertEquals(completed.id, entry.journal().latest(owner.id)?.id)
                    assertEquals(messages.map { it.id }, data.chats().getMessages(owner.id).map { it.id })
                    assertEquals(1, requestCount())
                    assertEquals(0, otherModel.requests.get())
                    assertTrue(data.chats().getMessages(editor).isEmpty())
                    evidence.put("source_label", sourceLabel).put("provider", chosenProvider.displayName)
                        .put("model", request.getString("model"))
                        .put("source_characters", sourceText.length).put("source_preserved_in_preview_request_and_attachment", true)
                        .put("state", completed.state).put("run_id", completed.id).put("answer_id", answer.id).put("answer", answer.content)
                        .put("summary_taps", 1).put("local_requests", model.requests.get())
                        .put("official_requests", wire?.requestCount ?: 0)
                        .put("other_model_requests", otherModel.requests.get())
                        .put("extra_confirmations", 0).put("tools", 0).put("result_in_origin", true)
                        .put("same_answer_after_recreation", true).put("same_answer_in_full_chat", true)
                    if (ordinaryShare) {
                        wire?.active = false
                        val next = openExternalShare()
                        await("a fresh share previews the same original source") { withContext(Dispatchers.Main) {
                            next.findViewById<Button>(R.id.share_summary_action).run { isShown && isEnabled } &&
                                next.findViewById<TextView>(R.id.share_text_preview).text.toString() == sourceText
                        } }
                        assertEquals(0, otherModel.requests.get())
                        await("summary selected for the fresh share") { clickText(localized.getString(R.string.share_summary_action)) }
                        await("fresh share routes to the newly selected model") { otherModel.requests.get() == 1 }
                        val nextRequest = JSONObject(otherModel.bodies.single())
                        assertEquals(switched.modelName, nextRequest.getString("model"))
                        val nextOwner = data.chats().observeConversations().first().single { it.providerId == switched.providerId && it.id != editor }
                        assertEquals(switched, AssistantModelSelection.from(nextOwner))
                        assertNotEquals(owner.id, nextOwner.id)
                        entry.settings().setAssistantModelSelection(selectedSelection)
                        val running = captureShare { withContext(Dispatchers.Main) { next.recreate() } }
                        await("the second task is still running after another config change") { withContext(Dispatchers.Main) {
                            running.findViewById<Button>(R.id.share_summary_stop).isShown
                        } }
                        otherModel.release()
                        await("the second answer stays in its own share activity") { withContext(Dispatchers.Main) {
                            running.findViewById<TextView>(R.id.share_text_preview).text.toString() == "LOCAL_PREFIX_LOCAL_FINISHED" &&
                                !running.findViewById<Button>(R.id.share_summary_stop).isShown
                        } }
                        val nextRun = requireNotNull(entry.journal().latest(nextOwner.id))
                        assertEquals("Completed", nextRun.state)
                        val nextMessages = data.chats().getMessages(nextOwner.id)
                        assertEquals(1, nextMessages.count { it.role == MessageRole.User })
                        assertEquals(nextMessages.single { it.role == MessageRole.Assistant }.id, nextRun.assistantMessageId)
                        assertEquals(completed.id, entry.journal().latest(owner.id)?.id)
                        assertEquals(messages, data.chats().getMessages(owner.id))
                        assertTrue(data.chats().getMessages(editor).isEmpty())
                        assertEquals(1, requestCount())
                        assertEquals(1, otherModel.requests.get())
                        evidence.put("configuration_switch", JSONObject()
                            .put("next_model", switched.modelName).put("next_run_id", nextRun.id)
                            .put("first_model_requests", requestCount()).put("next_model_requests", otherModel.requests.get())
                            .put("switch_while_running_kept_owner", true).put("original_task_unchanged", true))
                    }
                } finally {
                    if (wire != null) withContext(NonCancellable) {
                        try {
                            val ownerId = officialOwnerId?.takeUnless(originalIds::contains)
                            if (ownerId != null) {
                                val chat = requireNotNull(data.chats().getConversation(ownerId))
                                check(chat.providerId == chosenProvider.id && chat.modelName == selectedSelection.modelName)
                                val users = data.chats().getMessages(ownerId).filter { it.role == MessageRole.User }
                                check(users.all {
                                    it.content.isEmpty() &&
                                        it.attachmentText.orEmpty().endsWith(sourceText)
                                })
                                withContext(Dispatchers.Main) { runtime.stopFromAssistant(ownerId) }
                                await("official-share-task-idle") { ownerId !in runtime.activeConversations.value }
                                wire.reportUsage(data.usage().observe(ownerId).first())
                                withContext(Dispatchers.Main) { runtime.deleteConversation(ownerId) }
                                await("official-share-conversation-removed") { data.chats().getConversation(ownerId) == null }
                            }
                        } finally {
                            wire.active = false
                            entry.settings().setUsageTrackingEnabled(priorUsage)
                            if (!hadUsageSetting) check(controls.edit().remove("usage_tracking_enabled").commit())
                        }
                    }
                }
            }
            assertFalse(marker.exists())
            evidence.put("fixture_restored", true).put("passed", true)
        } catch (failure: Throwable) {
            evidence.put("failure", failure.toString()).put("passed", false)
            throw failure
        } finally { File(directory, "result.json").writeText(evidence.toString(2)) }
    }

    @Test fun unconfiguredShareSummaryDoesNotFallBackOrSend() = runBlocking<Unit> {
        enabled()
        withSummaryFixture { selection, editor, model, otherModel ->
            entry.settings().setAssistantModelSelection(selection.copy(providerId = "missing-${UUID.randomUUID()}"))
            val activity = openExternalShare()
            val localized = AppLocale.wrapContext(context)
            await("summary action loaded for the share") { withContext(Dispatchers.Main) {
                activity.findViewById<Button>(R.id.share_summary_action).isShown
            } }
            await("the explicit summary action was tapped") { clickText(localized.getString(R.string.share_summary_action)) }
            await("missing config reported in the share activity") { withContext(Dispatchers.Main) {
                activity.findViewById<TextView>(R.id.share_text_error).text.toString() ==
                    localized.getString(R.string.share_summary_choose_configuration)
            } }
            assertEquals(0, model.requests.get())
            assertEquals(0, otherModel.requests.get())
            assertTrue(data.chats().observeConversations().first().none { it.providerId == selection.providerId })
            assertTrue(data.chats().getMessages(editor).isEmpty())
        }
    }

    @Test fun confirmedDraftIsIsolatedAndOnlyExplicitSendUsesChosenLocalProvider() = verifyDraftTransfer(false)

    @Test fun selectedDraftIsIsolatedAndOnlyExplicitSendUsesChosenLocalProvider() = verifyDraftTransfer(true)

    private fun verifyDraftTransfer(selectedText: Boolean) = runBlocking<Unit> {
        enabled()
        // This method must also work first in its suite, without another test making an
        // activity visible. A denied background launch does not test a receiving activity.
        check(shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity").contains("Status: ok"))
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect {} }
        val model = TaskRuntimeDiagnosticTest.GatedModel()
        try {
            await("runtime ready") { runtime.uiState.value.providers.isNotEmpty() }
            val providerId = "r6-share-qa-${UUID.randomUUID()}"
            val settings = entry.settings().state.value
            marker.writeText(JSONObject().apply {
                put("provider", providerId); put("selection", runtime.uiState.value.currentConversation?.id.orEmpty())
                put("memory", settings.memoryEnabled); put("power", settings.powerModeEnabled); put("commands", settings.commandExecutionEnabled)
            }.toString())
            entry.settings().apply { setMemoryEnabled(false); setPowerModeEnabled(false); setCommandExecutionEnabled(false) }
            data.providers().upsertProvider(ProviderConfig(id = providerId, displayName = providerId,
                baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "r6-local"), null)
            val owner = data.chats().createConversation(providerId, "r6-local").id
            // Only this GUID destination is offered under providerId; another synthetic editor belongs to another GUID.
            val editorProvider = "$providerId-editor"
            marker.writeText(JSONObject(marker.readText()).put("editorProvider", editorProvider).toString())
            data.providers().upsertProvider(ProviderConfig(id = editorProvider, displayName = editorProvider,
                baseUrl = "https://r6-editor.invalid/v1", defaultModel = "editor-local"), null)
            val editor = data.chats().createConversation(editorProvider, "editor-local").id
            try {
                withContext(Dispatchers.Main) {
                    runtime.selectConversation(owner); runtime.updateInput("R6_SYNTHETIC_USER_REQUEST")
                    runtime.selectConversation(editor); runtime.updateInput("EDITOR_DRAFT_KEEP")
                    runtime.attachTextFile("editor-keep.txt", "EDITOR_PRIVATE_ATTACHMENT")
                }
                val activity = if (selectedText) openExternalSelection(false)
                    else openShare("R6_SYNTHETIC_CONFIRMED 文字 😀 https://r6.invalid/page")
                val sourceInstance = if (selectedText) selectionSourceRecord().getString("instance") else null
                // Use an exact prefix excluding the other provider, whose name has a suffix.
                await("synthetic destination available") { withContext(Dispatchers.Main) {
                    val spinner = activity.findViewById<Spinner>(R.id.share_text_destination)
                    (1 until spinner.count).any { spinner.getItemAtPosition(it).toString().contains(" · $providerId · ") }
                } }
                withContext(Dispatchers.Main) {
                    val spinner = activity.findViewById<Spinner>(R.id.share_text_destination)
                    spinner.setSelection((1 until spinner.count).single { spinner.getItemAtPosition(it).toString().contains(" · $providerId · ") })
                }
                await("add enabled after explicit choice") { withContext(Dispatchers.Main) { activity.findViewById<Button>(R.id.share_text_add).isEnabled } }
                assertEquals(0, model.requests.get())
                withContext(Dispatchers.Main) { activity.findViewById<Button>(R.id.share_text_add).performClick() }
                await("selected draft opened") { runtime.uiState.value.currentConversation?.id == owner && runtime.uiState.value.pendingAttachment != null }
                assertEquals(0, model.requests.get())
                assertTrue(data.chats().getMessages(owner).isEmpty())
                assertEquals("R6_SYNTHETIC_USER_REQUEST", runtime.uiState.value.input)
                if (sourceInstance != null) {
                    await("successful selection transfer closed preview") { activity.isDestroyed }
                    verifySourceReturn(sourceInstance)
                }
                withContext(Dispatchers.Main) { runtime.selectConversation(editor) }
                await("editor preserved") { runtime.uiState.value.currentConversation?.id == editor }
                assertEquals("EDITOR_DRAFT_KEEP", runtime.uiState.value.input)
                assertEquals("editor-keep.txt", runtime.uiState.value.pendingAttachment?.name)
                withContext(Dispatchers.Main) { runtime.selectConversation(owner) }
                await("owner reselected") { runtime.uiState.value.currentConversation?.id == owner }
                val selected = requireNotNull(data.chats().getConversation(owner))
                val provider = requireNotNull(data.providers().getProvider(selected.providerId))
                check(selected.id == owner && selected.providerId == providerId && selected.modelName == "r6-local")
                check(provider.baseUrl == "http://127.0.0.1:${model.port}/v1" && provider.apiKeyAlias == null)
                check(data.providers().getApiKey(provider) == null)
                withContext(Dispatchers.Main) { runtime.send() }
                await("exactly one loopback request") { model.requests.get() == 1 }
                assertTrue(model.bodies.single().contains(if (selectedText) "R6_SELECTED" else "R6_SYNTHETIC_CONFIRMED"))
                if (selectedText) {
                    assertFalse(model.bodies.single().contains("UNSELECTED_PREFIX"))
                    assertFalse(model.bodies.single().contains("UNSELECTED_SUFFIX"))
                }
                assertFalse(model.bodies.single().contains("EDITOR_PRIVATE_ATTACHMENT"))
                assertTrue(model.bodies.single().contains("untrusted reference content"))
                model.release()
                await("local answer complete") { data.chats().getMessages(owner).lastOrNull()?.status == MessageStatus.Complete && owner !in runtime.activeConversations.value }
                assertEquals(1, model.requests.get())
                assertEquals(1, data.chats().getMessages(owner).count { it.role == MessageRole.User })
                assertTrue(data.chats().getMessages(editor).isEmpty())
                report("explicit draft add=0 requests; explicit Send=1 loopback request; provider/model/owner checked; other editor preserved")
            } finally {
                withContext(Dispatchers.Main) { runtime.deleteConversation(editor) }
                await("editor removed") { data.chats().getConversation(editor) == null }
                data.providers().deleteProvider(editorProvider)
            }
        } finally {
            withContext(Dispatchers.Main) { activities.forEach { if (!it.isDestroyed) it.finish() } }
            model.close(); restoreFixture(); collector.cancelAndJoin()
        }
    }

    @Test fun externalSelectionsPreviewOnlySelectedTextAndCancelWithoutChangingEitherSource() = runBlocking<Unit> {
        enabled()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect {} }
        try {
            await("runtime ready for selection") { runtime.uiState.value.providers.isNotEmpty() }
            val before = data.chats().observeConversations().first().associate { it.id to data.chats().getMessages(it.id).map { row -> row.id } }
            val conversation = runtime.uiState.value.currentConversation?.id
            val input = runtime.uiState.value.input
            val attachment = runtime.uiState.value.pendingAttachment
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            for (mode in listOf("READONLY", "EDITABLE")) {
                val activity = openExternalSelection(mode == "READONLY")
                val sourceInstance = selectionSourceRecord().getString("instance")
                await("$mode selected text only") { withContext(Dispatchers.Main) {
                    activity.findViewById<TextView>(R.id.share_text_preview).text.toString() == ProcessTextSourceFixtureActivity.SELECTED
                } }
                withContext(Dispatchers.Main) {
                    assertFalse(activity.findViewById<Button>(R.id.share_text_add).isEnabled)
                    assertEquals(0, activity.findViewById<Spinner>(R.id.share_text_destination).selectedItemPosition)
                    assertTrue(activity.findViewById<TextView>(R.id.share_text_source).text.contains(instrumentation.context.packageName))
                }
                val recreated = captureShare { withContext(Dispatchers.Main) { activity.recreate() } }
                await("$mode recreated preview") { withContext(Dispatchers.Main) {
                    recreated.findViewById<TextView>(R.id.share_text_preview).text.toString() == ProcessTextSourceFixtureActivity.SELECTED
                } }
                withContext(Dispatchers.Main) {
                    val hierarchy = android.util.SparseArray<android.os.Parcelable>()
                    recreated.window.decorView.saveHierarchyState(hierarchy)
                    assertNull(hierarchy.get(R.id.share_text_preview))
                    assertNull(hierarchy.get(R.id.share_text_destination))
                    recreated.findViewById<Button>(R.id.share_text_cancel).performClick()
                }
                await("cancel closed selection preview") { recreated.isDestroyed }
                verifySourceReturn(sourceInstance)
            }
            assertEquals(conversation, runtime.uiState.value.currentConversation?.id)
            assertEquals(input, runtime.uiState.value.input)
            assertEquals(attachment, runtime.uiState.value.pendingAttachment)
            assertEquals(before, data.chats().observeConversations().first().associate { it.id to data.chats().getMessages(it.id).map { row -> row.id } })
            report("readonly + editable standard protocol; selected substring only; verified caller; same source got RESULT_CANCELED without replacement; all conversations preserved")
        } finally {
            withContext(Dispatchers.Main) { activities.forEach { if (!it.isDestroyed) it.finish() } }
            collector.cancelAndJoin()
        }
    }

    @Test fun selectedTextRecreationRejectsOldIntentAndMalformedSelectionWithoutChangingDraft() = runBlocking<Unit> {
        enabled()
        check(shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity").contains("Status: ok"))
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val model = withContext(Dispatchers.Main) { SharedTextPreviewModel(data.chats(), data.providers(), runtime, entry.settings()) }
        val store = androidx.lifecycle.ViewModelStore().apply { put("r6-selection-recreation", model) }
        val collector = CoroutineScope(Dispatchers.Main).launch { model.state.collect {} }
        val input = runtime.uiState.value.input
        val attachment = runtime.uiState.value.pendingAttachment
        try {
            withContext(Dispatchers.Main) {
                model.initialize(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, "OLD_SELECTION"), instrumentation.context.packageName, true)
            }
            await("new process refuses old selection") { model.state.value.invalid }
            assertNull(model.state.value.payload); assertNull(model.state.value.selected)
            val activity = captureShare {
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(context, SharedTextActivity::class.java).setAction(Intent.ACTION_PROCESS_TEXT)
                        .setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "MUST_NOT_IMPORT")
                        .putExtra(Intent.EXTRA_TEXT, "CONFLICTING_UNPREVIEWED_TEXT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            await("malformed selection visibly rejected") { withContext(Dispatchers.Main) {
                activity.findViewById<TextView>(R.id.share_text_error).visibility == android.view.View.VISIBLE
            } }
            withContext(Dispatchers.Main) {
                assertTrue(activity.findViewById<TextView>(R.id.share_text_preview).text.isEmpty())
                assertFalse(activity.findViewById<Button>(R.id.share_text_add).isEnabled)
                activity.findViewById<Button>(R.id.share_text_cancel).performClick()
            }
            assertEquals(input, runtime.uiState.value.input)
            assertEquals(attachment, runtime.uiState.value.pendingAttachment)
            report("old selection never reimported by new process; conflicting extra rejected without staging")
        } finally { collector.cancelAndJoin(); withContext(Dispatchers.Main) { store.clear() } }
    }

    @Test fun processRecreationAndUnsupportedStreamsCannotRestoreADestinationOrStageText() = runBlocking<Unit> {
        enabled()
        // Make the target visible before an app-originated launch; a background-start denial
        // is not evidence that the receiver rejected the payload.
        check(shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity").contains("Status: ok"))
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val model = withContext(Dispatchers.Main) { SharedTextPreviewModel(data.chats(), data.providers(), runtime, entry.settings()) }
        val store = androidx.lifecycle.ViewModelStore().apply { put("r6-process-recreation", model) }
        val collector = CoroutineScope(Dispatchers.Main).launch { model.state.collect {} }
        try {
            withContext(Dispatchers.Main) {
                model.initialize(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "OLD_PROCESS_TEXT"), null, true)
            }
            await("process recreation rejects old launch Intent") { model.state.value.invalid }
            assertNull(model.state.value.payload); assertNull(model.state.value.selected)
            val activity = captureShare {
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(context, SharedTextActivity::class.java).setAction(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "MUST_NOT_IMPORT")
                        .putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("https://r6.invalid/file"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            await("invalid stream visibly rejected") { withContext(Dispatchers.Main) {
                activity.findViewById<TextView>(R.id.share_text_error).visibility == android.view.View.VISIBLE
            } }
            withContext(Dispatchers.Main) {
                assertTrue(activity.findViewById<TextView>(R.id.share_text_preview).text.isEmpty())
                assertFalse(activity.findViewById<Button>(R.id.share_text_add).isEnabled)
                assertFalse(activity.findViewById<Button>(R.id.share_summary_action).isShown)
                activity.findViewById<Button>(R.id.share_text_cancel).performClick()
            }
            report("new-process model refuses old Intent; stream input rejected without reading URI")
        } finally { collector.cancelAndJoin(); withContext(Dispatchers.Main) { store.clear() } }
    }

    @Test fun sharedSummaryRendersMarkdownWithoutRawMarkers() = runBlocking<Unit> {
        enabled()
        val run = InstrumentationRegistry.getArguments().getString("markdownRunId") ?: "md-default"
        require(run.matches(Regex("[a-z0-9-]+")))
        val directory = File(context.getExternalFilesDir(null), "d4-markdown/$run")
        check(directory.mkdirs())
        val markdownAnswer = "**周六上午10点**有*手作课*\n\n- 须提前预约\n- 地点 `A102`"
        val sourceWithMarkers = "通知原文保留 **原文标记**，周六上午10点有手作课，须提前预约，地点 A102。"
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect {} }
        val model = TaskRuntimeDiagnosticTest.GatedModel("**周六上午10点**有*手作课*\n\n", "- 须提前预约\n- 地点 `A102`")
        try {
            await("markdown share fixture runtime ready") { runtime.uiState.value.providers.isNotEmpty() }
            val provider = "r6-share-qa-${UUID.randomUUID()}"
            val settings = entry.settings().state.value
            marker.writeText(JSONObject().apply {
                put("provider", provider); put("editorProvider", "$provider-editor")
                put("selection", runtime.uiState.value.currentConversation?.id.orEmpty())
                put("memory", settings.memoryEnabled); put("power", settings.powerModeEnabled); put("commands", settings.commandExecutionEnabled)
                put("assistantSelection", settings.assistantModelSelection?.let { Json.encodeToString(it) }.orEmpty())
            }.toString())
            data.providers().upsertProvider(ProviderConfig(id = provider, displayName = "Markdown Local Flash",
                baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "deepseek-flash"), null)
            entry.settings().setAssistantModelSelection(AssistantModelSelection(provider, "deepseek-flash"))
            val localized = AppLocale.wrapContext(context)
            val activity = openShare(sourceWithMarkers)
            await("source preview retains raw asterisks before summary") { withContext(Dispatchers.Main) {
                activity.findViewById<Button>(R.id.share_summary_action).run { isShown && isEnabled } &&
                    activity.findViewById<TextView>(R.id.share_text_preview).text.toString() == sourceWithMarkers
            } }
            await("markdown summary clicked") { clickText(localized.getString(R.string.share_summary_action)) }
            await("markdown model reached") { model.requests.get() == 1 }
            model.release()
            await("markdown summary rendered without raw markers") { withContext(Dispatchers.Main) {
                val preview = activity.findViewById<TextView>(R.id.share_text_preview)
                val rendered = preview.text.toString()
                !activity.findViewById<Button>(R.id.share_summary_stop).isShown &&
                    rendered.contains("周六上午10点") &&
                    rendered.contains("• 须提前预约") &&
                    rendered.contains("A102") &&
                    !rendered.contains("**") &&
                    !rendered.contains("`")
            } }
            suspend fun verifySpans(target: SharedTextActivity) {
                withContext(Dispatchers.Main) {
                    val text = target.findViewById<TextView>(R.id.share_text_preview).text as android.text.Spanned
                    val raw = text.toString()
                    val boldStart = raw.indexOf("周六上午10点")
                    val boldSpans = text.getSpans(boldStart, boldStart + "周六上午10点".length, android.text.style.StyleSpan::class.java)
                    assertTrue(boldSpans.any { it.style == android.graphics.Typeface.BOLD })
                    val codeStart = raw.indexOf("A102")
                    assertTrue(text.getSpans(codeStart, codeStart + 4, android.text.style.TypefaceSpan::class.java).isNotEmpty())
                }
            }
            verifySpans(activity)
            val bitmap = withContext(Dispatchers.Main) {
                val decor = activity.window.decorView
                Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
            }
            try { File(directory, "markdown-summary.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            val recreated = captureShare { withContext(Dispatchers.Main) { activity.recreate() } }
            await("markdown summary survives recreation") { withContext(Dispatchers.Main) {
                val rendered = recreated.findViewById<TextView>(R.id.share_text_preview).text.toString()
                rendered.contains("周六上午10点") && !rendered.contains("**")
            } }
            verifySpans(recreated)
            val evidence = JSONObject()
                .put("markdown_source", markdownAnswer)
                .put("rendered_text", withContext(Dispatchers.Main) { recreated.findViewById<TextView>(R.id.share_text_preview).text.toString() })
                .put("local_requests", model.requests.get())
                .put("official_requests", 0)
            File(directory, "result.json").writeText(evidence.toString(2))
            report("share summary rendered Markdown cleanly without raw markers")
        } finally {
            withContext(Dispatchers.Main) { activities.forEach { if (!it.isDestroyed) it.finish() } }
            model.close()
            try { restoreFixture() } finally { collector.cancelAndJoin() }
        }
    }
}
