package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.BuildConfig
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.nativevoice.NativeVoiceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.nativevoice.NativeVoiceWorkspaceProvider
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskPowerWork
import com.flowchat.app.domain.chat.TaskServiceState
import com.flowchat.app.domain.device.AutomationTimings
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ReplyPhase
import com.flowchat.app.domain.terminal.CommandSnapshot
import com.flowchat.app.domain.terminal.CommandStatus
import com.flowchat.app.domain.terminal.TerminalToolDefinitions
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import com.flowchat.nativevoicebridge.VoiceBridgeWire.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Drives one hundred consecutive mixed task turns in one process and one isolated app data profile. */
class ContinuousReliabilityMuMuTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val app by lazy { EntryPointAccessors.fromApplication(context, NativeVoiceDiagnosticsEntryPoint::class.java) }
    private val task by lazy { EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java) }
    private val terminal by lazy { EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).terminal() }
    private val journal get() = task.journal()
    private val chats get() = app.chats()
    private val settings get() = app.settings()
    private val workspace by lazy { NativeVoiceWorkspaceProvider.workspaceDir(context) }
    private val location by lazy { NativeVoiceWorkspaceProvider.workspaceLocation(context) }
    private val evidence by lazy { File(context.cacheDir, "s1-mixed").apply { mkdirs() } }
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val json = Json { ignoreUnknownKeys = true }
    private val results = mutableMapOf<Int, TaskIdentity>()
    private lateinit var model: ContinuousReliabilityModel

    @Test fun continuousMixedTasks() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runS1Mixed") == "true")
        check(BuildConfig.NATIVE_VOICE_REAL_RUNTIME) { "Build the real native voice endpoint" }
        check(chats.observeConversations().first().isEmpty()) { "S1 requires the runner's isolated app profile" }
        check(app.providers().getProvidersOnce().none { app.providers().getApiKey(it) != null }) {
            "S1 requires an app profile with no stored provider credentials"
        }
        check(!workspace.exists() || workspace.listFiles().orEmpty().isEmpty()) { "S1 requires an empty fixture workspace" }

        val runtime = withContext(Dispatchers.Main) { app.runtime() }
        val states = launch { runtime.uiState.collect() }
        val samples = mutableListOf<JsonObject>()
        val records = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        val scenarios = s1Scenarios()
        model = ContinuousReliabilityModel()
        try {
            prepare()
            val resourceProbe = ContinuousReliabilityResources(context, automation, app.database(), workspace)
            samples += sample(0, resourceProbe)
            for (scenario in scenarios) {
                val record = runScenario(scenario)
                records += record
                if (!record.getValue("passed").jsonPrimitive.boolean) {
                    failures += scenario.id.toString() + " " + record.getValue("failures").toString()
                }
                File(evidence, "tasks.jsonl").appendText("$record\n")
                samples += sample(scenario.id, resourceProbe)
                log("S1 " + scenario.id + " " + scenario.name + " " + record.getValue("passed") +
                    " total_ms=" + record["to_usable_result_ms"])
            }
            val drainStarted = SystemClock.elapsedRealtime()
            until("final task service stop", 6_000) {
                task.taskPower().state.value?.serviceState == TaskServiceState.Destroyed
            }
            until("final command service stop", 17_000) {
                "CommandExecutionService" !in shell("dumpsys activity services " + context.packageName)
            }
            val finalDrainMs = SystemClock.elapsedRealtime() - drainStarted
            samples += sample(scenarios.size + 1, resourceProbe)
            failures += checkSeries(samples)
            val conversations = chats.observeConversations().first()
            val usageRows = conversations.sumOf { app.database().usageDao().observe(it.id).first().size }
            if (usageRows != 0) failures += "Unexpected usage records: $usageRows"
            val taskRuns = app.database().openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM task_runs").use { rows ->
                    check(rows.moveToFirst())
                    rows.getInt(0)
                }
            if (taskRuns != scenarios.size || results.values.map { it.run }.toSet().size != scenarios.size) {
                failures += "Expected exactly ${scenarios.size} independently identified task runs, got $taskRuns"
            }
            val last = results[scenarios.size - 1]?.takeIf { !it.reply.isNullOrBlank() }
            if (last != null) {
                launchChat()
                withContext(Dispatchers.Main) { runtime.selectConversation(last.owner) }
                until("last chat reply rendered") {
                    runtime.uiState.value.currentConversation?.id == last.owner &&
                        visibleText(automation.rootInActiveWindow, requireNotNull(last.reply))
                }
                saveScreenshot("chat-result.png")
            }
            val allStaleReads = records.flatMap { it["stale_receipt_reads"]?.jsonArray.orEmpty() }.map { it.jsonObject }
            val summary = buildJsonObject {
                put("device", "MuMu #1 Floryx")
                put("sdk", android.os.Build.VERSION.SDK_INT)
                put("process_id", android.os.Process.myPid())
                put("model", ContinuousReliabilityModel.ModelName)
                put("provider", model.baseUrl)
                put("model_requests_local", model.requests.get())
                put("official_flash_requests", 0)
                put("usage_rows", usageRows)
                put("scenario_count", scenarios.size)
                put("task_runs_in_room", taskRuns)
                put("resource_samples", samples.size)
                put("final_drain_elapsed_ms", finalDrainMs)
                putJsonObject("multi_receipt_attribution") {
                    put("total_reads", allStaleReads.size)
                    put("matched_reads", allStaleReads.count {
                        it["expected_run_id"] == it["received_run_id"] &&
                            it["expected_state"] == it["received_state"] &&
                            it["answer_matches"]?.jsonPrimitive?.booleanOrNull == true
                    })
                    put("duplicate_playback_claims", allStaleReads.count {
                        it["duplicate_playback_claimed"]?.jsonPrimitive?.booleanOrNull == true
                    })
                }
                putJsonArray("failures") { failures.forEach { add(it) } }
            }
            File(evidence, "summary.json").writeText("$summary\n")
            check(failures.isEmpty()) { failures.joinToString("\n") }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { runtime.stopAllTasks() }
            model.close()
            states.cancelAndJoin()
        }
    }

    private suspend fun prepare() {
        val providerId = "s1-local"
        app.providers().upsertProvider(
            ProviderConfig(id = providerId, displayName = "S1 loopback", baseUrl = model.baseUrl,
                defaultModel = ContinuousReliabilityModel.ModelName),
            ContinuousReliabilityModel.Key
        )
        check(app.providers().getProvidersOnce().filter { app.providers().getApiKey(it) != null }.map { it.id } == listOf(providerId))
        settings.setAssistantModelSelection(AssistantModelSelection(providerId, ContinuousReliabilityModel.ModelName, enableThinking = false))
        settings.setMemoryEnabled(false)
        settings.setUsageTrackingEnabled(false)
        settings.setPowerConnectionMode(PowerConnectionMode.Shizuku)
        settings.setPowerModeEnabled(true)
        settings.setBackgroundExecutionEnabled(false)
        settings.setCommandExecutionEnabled(true)
        check(workspace.mkdirs() || workspace.isDirectory)
        File(workspace, S1Memo).writeText(S1MemoText)
        File(workspace, S1LongFile).writeText(S1LongText)
        settings.setDefaultWorkspace(location)
        launchChat()
        ActionOutcomeServiceReadiness().ensureService()
        app.gateway().refreshAccessibilityConnection()
        until("Floryx accessibility bound") { app.gateway().accessibilityState.value.isConnected }
        val probe = voice(PROBE)
        check(probe.getString(STATE) == READY && probe.getString(RUNTIME_MODE) == "real")
    }

    private data class VoiceIdentity(val session: UUID, val request: UUID)
    private data class TaskIdentity(
        val id: Int,
        val owner: String,
        val run: String,
        val state: String,
        val reply: String?,
        val voice: VoiceIdentity?,
        val prior: Int?,
    )

    private fun priorChain(startId: Int?): List<TaskIdentity> = buildList {
        var cursor = startId
        while (cursor != null) {
            val item = requireNotNull(results[cursor]) { "Earlier task $cursor receipt is unavailable" }
            add(item)
            cursor = item.prior
        }
    }.reversed()

    private suspend fun runScenario(scenario: S1Scenario): JsonObject = coroutineScope {
        val runtime = app.runtime()
        val previous = scenario.prior?.let(results::get)
        val priorTasks = priorChain(scenario.prior)
        val priorVoiceTasks = priorTasks.filter { it.voice != null }
        val checkLatestVoiceSession = scenario.entry != S1Entry.Voice || scenario.newVoiceSession
        val failures = mutableListOf<String>()
        var owner: String? = null
        var runId: String? = null
        var status: String? = null
        var answer: String? = null
        var voiceIdentity: VoiceIdentity? = null
        var stopAt: Long? = null
        var stopNanos: Long? = null
        var resultAt: Long? = null
        var nativeResultAt: Long? = null
        var cleanupAt: Long? = null
        var firstFailureAt: Long? = null
        var confirmations = 0
        var confirmedWaiting = false
        val staleReceiptReads = mutableListOf<JsonObject>()
        var playbackClaims = 0
        var command: CommandSnapshot? = null
        var steps = emptyList<ExecutionStep>()
        var lateActions: Int? = null
        val enteredAt = SystemClock.elapsedRealtime()
        var submittedAt: Long? = null
        var submittedNanos: Long? = null
        fun problem(text: String) {
            if (firstFailureAt == null) firstFailureAt = SystemClock.elapsedRealtime()
            failures += text
        }
        fun checkOldReceipt(phase: String, prior: TaskIdentity, statusBundle: Bundle? = null) {
            val voiceId = requireNotNull(prior.voice)
            val old = statusBundle ?: voice(STATUS, voiceId)
            val matches = matchesReceipt(old, prior)
            val duplicateClaimed = if (phase == "after_follow_up") {
                voice(CLAIM_PLAYBACK, voiceId).getBoolean(PLAYBACK_CLAIMED)
            } else false
            staleReceiptReads += buildJsonObject {
                put("phase", phase)
                put("prior_scenario_id", prior.id)
                put("expected_run_id", prior.run)
                put("expected_state", prior.state)
                put("received_run_id", old.getString(TASK_ID))
                put("received_state", old.getString(STATE))
                put("answer_matches", old.getString(REPLY) == prior.reply)
                put("duplicate_playback_claimed", duplicateClaimed)
            }
            if (!matches) problem("Old voice receipt (task ${prior.id}) was misattributed in $phase")
            if (duplicateClaimed) problem("Old voice receipt (task ${prior.id}) allowed duplicate playback claim in $phase")
        }
        fun verifyOldReceipts(phase: String) {
            for (prior in priorVoiceTasks) {
                checkOldReceipt(phase, prior)
            }
            if (checkLatestVoiceSession && priorVoiceTasks.isNotEmpty()) {
                val latestPrior = priorVoiceTasks.last()
                val sessionQuery = VoiceIdentity(requireNotNull(latestPrior.voice).session, UUID.randomUUID())
                checkOldReceipt("latest_$phase", latestPrior, voice(LATEST, sessionQuery))
            }
        }
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.taskPowerOwners.collect { work ->
                val waiting = work.any { it.conversationId == owner && it.work == TaskPowerWork.WaitingForUser }
                if (waiting && !confirmedWaiting) confirmations++
                confirmedWaiting = waiting
            }
        }
        model.plan = scenario.plan
        AutomationTimings.clear()
        val previousModelError = model.failure.getAndSet(null)
        if (previousModelError != null) problem("Stray model request before task: " + previousModelError.message)
        try {
            if (scenario.prior != null) checkNotNull(previous) { "Earlier task receipt is unavailable" }
            if (scenario.settingsAction != null) {
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(scenario.settingsAction)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                until("Settings in foreground") {
                    val root = automation.rootInActiveWindow
                    root?.packageName == "com.android.settings" &&
                        listOf("Sound & vibration", "提示音和振动", "声音与振动", "声音和振动", "声音与触感", "Media volume", "媒体音量")
                            .any { visibleText(root, it) }
                }
            }
            if (scenario.entry != S1Entry.Voice) {
                owner = if (scenario.entry == S1Entry.Retry || scenario.prior != null) requireNotNull(previous).owner else
                    chats.createConversation("s1-local", ContinuousReliabilityModel.ModelName).id.also {
                        chats.updateConversationWorkspace(it, location)
                    }
                withContext(Dispatchers.Main) { runtime.selectConversation(requireNotNull(owner)) }
            }
            val beforeRun = owner?.let { journal.latest(it)?.id }
            submittedAt = SystemClock.elapsedRealtime()
            submittedNanos = System.nanoTime()
            if (scenario.entry == S1Entry.Voice) {
                val inheritedSession = if (scenario.newVoiceSession) null else priorVoiceTasks.lastOrNull()?.voice?.session
                val id = VoiceIdentity(inheritedSession ?: UUID.randomUUID(), UUID.randomUUID())
                voiceIdentity = id
                val submitted = voice(SUBMIT, id, scenario.instruction)
                owner = requireNotNull(submitted.getString(CONVERSATION_ID)) { submitted.toString() }
                check(submitted.getBoolean(NEWLY_ACCEPTED)) { "Voice event was not accepted: $submitted" }
                val duplicate = voice(SUBMIT, id, scenario.instruction)
                check(!duplicate.getBoolean(NEWLY_ACCEPTED) && duplicate.getString(CONVERSATION_ID) == owner)
                if (previous != null && !scenario.expectNewConversation && previous.owner != owner) {
                    problem("The voice follow-up did not reuse its conversation")
                }
                if (previous != null && scenario.expectNewConversation && previous.owner == owner) {
                    problem("A stopped or new-session voice conversation was reused")
                }
            } else {
                withContext(Dispatchers.Main) {
                    if (scenario.entry == S1Entry.Retry) runtime.retryLastFailed()
                    else {
                        runtime.updateInput(scenario.instruction)
                        runtime.send()
                    }
                }
            }
            val conversation = requireNotNull(owner)
            val gate = scenario.gate
            if (gate != null) until("model gate") { gate.entered }
            if (scenario.verifyPriorChain && gate != null) {
                verifyOldReceipts("during_follow_up")
            }
            if (scenario.stop == S1Stop.RunningCommand) until("owned command started") {
                command = terminal.state.value.commands.firstOrNull {
                    terminal.owns(conversation, it.id) && it.isRunning && "S1-COMMAND-START" in it.output
                }
                command != null
            }
            if (scenario.stop != S1Stop.None) {
                stopAt = SystemClock.elapsedRealtime()
                stopNanos = System.nanoTime()
                if (voiceIdentity != null) {
                    check(voice(STOP, voiceIdentity, stopSource = scenario.stopSource).getString(STATE) == STOPPED)
                } else withContext(Dispatchers.Main) { runtime.stop() }
            }
            gate?.open()

            val oldRun = beforeRun ?: previous?.takeIf { it.owner == conversation }?.run
            val terminalRun = untilValue("new run reached a terminal state") {
                journal.latest(conversation)?.takeIf { it.id != oldRun && it.state !in setOf(RUNNING, WAITING_USER) }
            }
            runId = terminalRun.id
            status = terminalRun.state
            resultAt = SystemClock.elapsedRealtime()
            if (status != scenario.state) problem("Expected " + scenario.state + ", got " + status)
            awaitIdle()
            cleanupAt = SystemClock.elapsedRealtime()
            val messages = chats.getMessages(conversation)
            val final = messages.singleOrNull { it.id == terminalRun.assistantMessageId }
            answer = final?.takeIf { it.status == MessageStatus.Complete && it.replyPhase == ReplyPhase.Answer }?.content
            if (answer != scenario.answer) problem("Unexpected answer status or content")
            steps = messages.flatMap { it.executionSteps() }.filter { it.runId == runId }
            if (steps.map { it.tool } != scenario.toolOrder) problem("Tool order/count differs from the observed plan")
            if (scenario.stop != S1Stop.None) {
                // A late reply is emitted after STOP. No tool may start in the stopped turn.
                val stopped = requireNotNull(stopNanos)
                lateActions = AutomationTimings.snapshot().count {
                    it.startedNanos > stopped && it.stage.startsWith("tool.")
                }
                if (lateActions != 0 || answer != null) problem("Stopped task executed a late action or emitted a final answer")
            }
            if (command != null) {
                command = terminal.state.value.commands.single { it.id == command?.id }
                if (command?.status != CommandStatus.Cancelled || command?.cleanup?.blocksReuse != false ||
                    command?.output.orEmpty().contains("S1-LATE")) problem("Cancelled process group did not settle")
            }
            if (voiceIdentity != null) {
                val first = voice(CLAIM_PLAYBACK, voiceIdentity)
                nativeResultAt = SystemClock.elapsedRealtime()
                val second = voice(CLAIM_PLAYBACK, voiceIdentity)
                playbackClaims = listOf(first, second).count { it.getBoolean(PLAYBACK_CLAIMED) }
                if (first.getString(REPLY) != scenario.answer || first.getString(STATE) != scenario.state ||
                    playbackClaims != if (scenario.state == COMPLETED) 1 else 0) {
                    problem("Unexpected native voice card or playback claim")
                }
                if (scenario.state == COMPLETED && first.getString(TASK_ID) != runId) problem("Voice card belongs to another task")
            }
            if (scenario.verifyPriorChain) {
                verifyOldReceipts("after_follow_up")
                val prefs = context.getSharedPreferences("native_voice_receipts", 0)
                for (prior in priorVoiceTasks) {
                    val key = "request:" + requireNotNull(prior.voice).request
                    val original = requireNotNull(prefs.getString(key, null))
                    val legacy = JSONObject(original).apply { remove("runId") }
                    try {
                        check(prefs.edit().putString(key, legacy.toString()).commit())
                        checkOldReceipt("legacy_format_after_follow_up", prior)
                    } finally {
                        check(prefs.edit().putString(key, original).commit())
                    }
                }
            }
            results[scenario.id] = TaskIdentity(scenario.id, conversation, runId, status, answer, voiceIdentity, scenario.prior)
            if (scenario.id == 3 && failures.isEmpty()) saveScreenshot("settings-result.png")
        } catch (error: Exception) {
            problem(error.message ?: error.javaClass.simpleName)
            val failedOwner = owner
            if (failedOwner != null && failedOwner in runtime.activeConversations.value) {
                stopAt = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main) { runtime.stopFromAssistant(failedOwner) }
            }
        } finally {
            scenario.gate?.open()
            observer.cancelAndJoin()
        }
        model.failure.getAndSet(null)?.let { problem("Model fixture assertion: " + (it.message ?: it.javaClass.simpleName)) }
        if (confirmations != 0) problem("Unexpected confirmation prompt")
        val timings = AutomationTimings.snapshot()
        val blockedTool = steps.firstOrNull {
            it.result.startsWith("failed:") || it.result.startsWith("{\"error\":") ||
                it.result.contains("\"error_category\":\"command_timeout\"")
        }
        val observedBlocker = blockedTool?.let { step ->
            timings.firstOrNull { it.stage == "tool." + step.tool }
        } ?: if (status == FAILED) timings.lastOrNull { it.stage == "model.response" } else null
        buildJsonObject {
            put("id", scenario.id); put("scenario", scenario.name); put("entry", scenario.entry.name)
            put("expected_state", scenario.state); put("state", status); put("passed", failures.isEmpty())
            put("business_goal_completed", status == COMPLETED && answer == scenario.answer && failures.isEmpty())
            put("conversation_id", owner); put("run_id", runId); put("answer_chars", answer?.length)
            val started = submittedAt ?: enteredAt
            val blockerMs = submittedNanos?.let { begin ->
                observedBlocker?.let { (it.startedNanos + it.durationNanos - begin) / 1_000_000 }
            }
            put("to_first_blocker_ms", blockerMs ?: firstFailureAt?.minus(started) ?: resultAt?.minus(started)?.takeIf { status == FAILED })
            put("to_stopped_ms", resultAt?.minus(started)?.takeIf { status == STOPPED })
            put("to_usable_result_ms", (nativeResultAt ?: resultAt)?.minus(started))
            put("cleanup_after_result_ms", if (resultAt == null) null else cleanupAt?.minus(resultAt))
            put("stop_to_idle_ms", if (stopAt == null) null else cleanupAt?.minus(stopAt))
            put("model_wait_ms", total(timings) { it == "model.response" })
            put("files_ms", total(timings) { it.removePrefix("tool.") in WorkspaceToolDefinitions.Names })
            put("command_ms", total(timings) { it.removePrefix("tool.") in TerminalToolDefinitions.Names })
            put("screen_observation_ms", total(timings) { it in ReadTools })
            put("action_and_settle_ms", total(timings) { it in ActionTools })
            put("speech_recognition_ms", JsonNull); put("audio_playback_ms", JsonNull)
            put("model_requests", scenario.plan.requests.get())
            put("checkpoint_requests", scenario.plan.checkpointRequests.get())
            put("extra_confirmations", confirmations)
            put("manual_supplement_steps", 0)
            put("user_triggered_retry", scenario.entry == S1Entry.Retry)
            put("user_triggered_stop", scenario.stop != S1Stop.None)
            put("wrong_target", if (scenario.settingsAction == null || resultAt == null) JsonNull else JsonPrimitive(answer != scenario.answer))
            put("repeat_actions", steps.filter { "tool." + it.tool in ActionTools || it.tool == TerminalToolDefinitions.Run }
                .groupingBy { it.tool to it.arguments }.eachCount().values.sumOf { it - 1 })
            put("actions_after_stop", lateActions)
            put("stale_receipt_reads", JsonArray(staleReceiptReads))
            put("native_playback_claims", playbackClaims)
            put("command_status_after_stop", command?.status?.name)
            put("command_cleanup_after_stop", command?.cleanup?.state?.name)
            putJsonArray("failures") { failures.forEach { add(it) } }
            putJsonArray("tool_steps") { steps.forEach { add(json.encodeToJsonElement(ExecutionStep.serializer(), it)) } }
            putJsonArray("timings") { timings.forEach { timing ->
                add(buildJsonObject {
                    put("stage", timing.stage); put("duration_ms", timing.durationMillis); put("completed", timing.completed)
                    put("start_offset_ms", submittedNanos?.let { (timing.startedNanos - it) / 1_000_000.0 })
                })
            } }
        }
    }

    private fun Message.executionSteps(): List<ExecutionStep> =
        executionContext?.let { json.decodeFromString<List<ExecutionStep>>(it) }.orEmpty()

    private fun matchesReceipt(status: Bundle, identity: TaskIdentity) =
        status.getString(STATE) == identity.state && status.getString(TASK_ID) == identity.run &&
            status.getString(REPLY) == identity.reply

    private suspend fun awaitIdle() = until("task, tool and command resources idle", 8_000) {
        val runtime = app.runtime()
        runtime.activeConversations.value.isEmpty() && runtime.taskPowerOwners.value.isEmpty() &&
            task.deviceTasks().currentTask == null && model.inFlight.get() == 0 &&
            terminal.state.value.commands.none { it.isRunning || it.cleanup?.blocksReuse == true }
    }

    private suspend fun sample(index: Int, probe: ContinuousReliabilityResources): JsonObject {
        val runtime = app.runtime()
        val jobState = withContext(Dispatchers.Main) {
            val persistence = runtime.field<Map<String, Job>>("stoppedTurnPersistence").values
            buildJsonObject {
                put("runtime_children", runtime.scope.coroutineContext.job.children.count())
                put("terminal_children", terminal.field<CoroutineScope>("scope").coroutineContext.job.children.count())
                put("active_tasks", runtime.activeConversations.value.size)
                put("active_device_task", task.deviceTasks().currentTask != null)
                put("active_model_handlers", model.inFlight.get())
                put("active_task_power_owners", runtime.taskPowerOwners.value.size)
                put("completed_stop_jobs_retained", persistence.count { it.isCompleted })
                put("pending_stop_jobs", persistence.count { !it.isCompleted })
                put("in_memory_task_run_records", runtime.field<kotlinx.coroutines.flow.StateFlow<Map<*, *>>>("taskRunRecords").value.size)
                put("active_command_snapshots", terminal.state.value.commands.count { it.isRunning })
                put("unverified_command_cleanup", terminal.state.value.commands.count { it.cleanup?.blocksReuse == true })
                put("task_service_state", task.taskPower().state.value?.serviceState?.name)
                put("task_lease_held", task.taskPower().state.value?.leaseHeld)
                put("unverified_prior_leases", task.taskPower().state.value?.otherUnverifiedLeases)
                put("gateway_accessibility_connected", app.gateway().accessibilityState.value.isConnected)
            }
        }
        val record = JsonObject(probe.sample() + mapOf("after" to JsonPrimitive(index), "jobs" to jobState))
        File(evidence, "resources.jsonl").appendText("$record\n")
        return record
    }

    private fun checkSeries(samples: List<JsonObject>): List<String> = buildList {
        val warm = samples.drop(4)
        for (record in warm) {
            val index = record.getValue("after")
            val jobs = record.getValue("jobs").jsonObject
            for (key in listOf("active_tasks", "active_model_handlers", "active_task_power_owners",
                "completed_stop_jobs_retained", "pending_stop_jobs", "in_memory_task_run_records",
                "active_command_snapshots", "unverified_command_cleanup")) {
                if (jobs.getValue(key).jsonPrimitive.int != 0) add("After $index: $key = " + jobs[key])
            }
            if (jobs.getValue("active_device_task").jsonPrimitive.boolean) add("After $index: device task still owned")
            if (jobs["task_lease_held"] == JsonPrimitive(true) || jobs["unverified_prior_leases"] != JsonPrimitive(0)) {
                add("After $index: remaining task wake lease")
            }
            val android = record.getValue("android").jsonObject
            val accessibility = android.getValue("accessibility").jsonObject
            if (accessibility["bound"] != JsonPrimitive(true) || accessibility["crashed"] != JsonPrimitive(false)) {
                add("After $index: accessibility binding/crash evidence = $accessibility")
            }
            if (record.getValue("workspace").jsonObject["temporary_files"] != JsonPrimitive(0) ||
                record.getValue("command_scratch").jsonObject["files"] != JsonPrimitive(0)) {
                add("After $index: temporary workspace files remain")
            }
        }
        if (samples.map { it["pid"] }.distinct().size != 1) add("Process restarted within the mixed run")
        if (samples.last().getValue("jobs").jsonObject["task_lease_held"] != JsonPrimitive(false)) add("Final wake lease did not release")
        val finalServices = samples.last().getValue("android").jsonObject.getValue("active_service_components").jsonArray
        if (finalServices.any {
            it.jsonPrimitive.content.endsWith("TaskExecutionService") || it.jsonPrimitive.content.endsWith("CommandExecutionService")
        }) add("Final task or command service remained active")
        // ART, Binder and coroutine executor worker pools can grow to their first simultaneous workload.
        // Keep their raw thread series and assert task/job ownership and I/O growth separately.
        for ((section, metric) in listOf("fds" to "total", "fds" to "database", "database" to "pool_connections",
            "android" to "registered_receivers", "android" to "notifications",
            "jobs" to "runtime_children", "jobs" to "terminal_children")) {
            val series = warm.mapNotNull { it[section]?.jsonObject?.get(metric)?.jsonPrimitive?.intOrNull }
            if (series.size == warm.size && series.last() - series.first() >= 3 &&
                series.zipWithNext().all { (a, b) -> b >= a } && series.distinct().size >= 4) {
                add("Monotonic growth in $section.$metric: $series")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.field(name: String): T = javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(this) as T
    }

    private fun voice(
        method: String,
        id: VoiceIdentity? = null,
        transcript: String? = null,
        stopSource: String = "voice_stop",
    ): Bundle {
        val arguments = Bundle().apply {
            putInt(PROTOCOL_VERSION, VERSION)
            id?.let { putString(SESSION_ID, it.session.toString()); putString(REQUEST_ID, it.request.toString()) }
            transcript?.let { putString(TRANSCRIPT, it) }
            if (method == STOP) putString(STOP_SOURCE, stopSource)
        }
        return requireNotNull(context.contentResolver.call(ENDPOINT, method, null, arguments))
    }

    private suspend fun until(description: String, ms: Long = 20_000, predicate: suspend () -> Boolean) {
        untilValue(description, ms) { Unit.takeIf { predicate() } }
    }

    private suspend fun <T : Any> untilValue(description: String, ms: Long = 20_000, value: suspend () -> T?): T =
        withTimeoutOrNull(ms) {
            var observed = value()
            while (observed == null) {
                delay(25)
                observed = value()
            }
            observed
        } ?: error("Timed out waiting for $description")

    private fun launchChat() {
        val launched = shell("am start -W -n " + context.packageName + "/com.flowchat.app.MainActivity")
        check(!launched.contains("Error:")) { launched }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun saveScreenshot(filename: String) {
        val screenshot = checkNotNull(automation.takeScreenshot())
        try {
            File(evidence, filename).outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun visibleText(node: AccessibilityNodeInfo?, expected: String): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser && node.text?.contains(expected) == true) return true
        return (0 until node.childCount).any { visibleText(node.getChild(it), expected) }
    }

    private fun log(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })

    private fun total(timings: List<AutomationTimings.Measurement>, stage: (String) -> Boolean) =
        timings.filter { stage(it.stage) }.sumOf { it.durationMillis }

    private companion object {
        val ReadTools = setOf("tool." + AgentToolDefinitions.ObserveScreenToolName, "tool." + AgentToolDefinitions.WaitForScreenToolName)
        val ActionTools = setOf("tool." + AgentToolDefinitions.TapUiTargetToolName, "tool." + AgentToolDefinitions.PressBackToolName)
    }
}
