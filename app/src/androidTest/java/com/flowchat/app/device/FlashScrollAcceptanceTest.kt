package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flowchat.app.MainActivity
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.di.AppModule
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.observeF06OutboundBody
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import dagger.hilt.android.EntryPointAccessors
import io.ktor.client.HttpClient
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Starts each D2 Flash acceptance run from normal chat with an independently observed task page. */
class FlashScrollAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val packageLine = Regex("(?m)^package=([A-Za-z0-9_.]+)$")
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private enum class Sample { List, Nested, SettingsList, SettingsSearch, TipsSearch, TipsBackground, TipsNestedBackground }

    @Test fun halfVisibleRowFromNormalChat() = runBlocking { runScrollCase(Sample.List) }

    @Test fun nestedRowFromNormalChat() = runBlocking { runScrollCase(Sample.Nested) }

    @Test fun stockSettingsRowFromNormalChat() = runBlocking { runScrollCase(Sample.SettingsList) }

    @Test fun stockSettingsSearchFromNormalChat() = runBlocking { runScrollCase(Sample.SettingsSearch) }

    @Test fun stockTipsSearchFromNormalChat() = runBlocking { runScrollCase(Sample.TipsSearch) }

    @Test fun stockTipsBackgroundSearchFromNormalChat() = runBlocking { runScrollCase(Sample.TipsBackground) }

    @Test fun stockTipsNestedBackgroundFromNormalChat() = runBlocking { runScrollCase(Sample.TipsNestedBackground) }

    private suspend fun runScrollCase(sample: Sample) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runFlashScroll") == "true")
        val phase = args.getString("flashScrollPhase")
        val local = phase == "local"
        val dry = when (phase) {
            "preflight" -> true
            "real", "local" -> false
            else -> error("Specify the D2 acceptance phase")
        }
        if (!local) {
            check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        }
        val nested = sample == Sample.Nested
        val searchSettings = sample == Sample.SettingsSearch
        val backgroundTips = sample == Sample.TipsBackground
        val nestedTipsBg = sample == Sample.TipsNestedBackground
        val backgroundExecution = backgroundTips || nestedTipsBg
        val searchTips = sample == Sample.TipsSearch || backgroundTips
        val searchPage = searchSettings || searchTips
        val stockPage = sample == Sample.SettingsList || searchPage || nestedTipsBg
        check(!local || !stockPage) { "Local F04 rehearsal supports staged fixture samples only (List/Nested)" }
        val label = args.getString("flashScrollRun").orEmpty()
        check(label.matches(Regex("[a-z0-9-]{1,64}")))
        val caseId = when {
            searchSettings -> "F05-settings-search-return"
            backgroundTips -> "F05-tips-background-search"
            nestedTipsBg -> "F04-tips-nested-background"
            searchTips -> "F05-tips-search-return"
            stockPage -> "F04-stock-settings"
            nested -> "F04-nested"
            else -> args.getString("flashScrollCase", "F04-half-visible").also {
                check(it in setOf("F04-half-visible", "F04-overshoot"))
            }
        }
        val overshoot = caseId == "F04-overshoot"
        val gainTenths = if (overshoot) requireNotNull(args.getString("flashScrollGainTenths", "58")).toInt() else 10
        check(gainTenths in setOf(10, 58) || gainTenths in 64..67)
        val initialY = if (overshoot) args.getString("flashScrollInitialY", "5000").toInt() else -1
        val locateByItem = overshoot && args.getString("flashScrollLocateByItem") == "true"
        val independentMetrics = nested || overshoot
        val target = if (stockPage) null else requireNotNull(args.getString("flashScrollRow")).toInt().also {
            check(it in (if (nested) 15..23 else 24..80))
        }
        val appLabel = "验收样本清单"
        val itemLabel = String.format(java.util.Locale.ROOT, "Item %02d", target)
        val ledgerDirArg = args.getString("flashScrollLedgerDir")
        val createLedger = ledgerDirArg != null || args.getString("flashScrollCreateLedger") == "true"
        val ledgerDirectory = File(context.getExternalFilesDir(null), ledgerDirArg ?: "p0d-flash")
        if (!local) {
            if (createLedger) check(ledgerDirectory.mkdirs() || ledgerDirectory.isDirectory)
            else check(File(ledgerDirectory, "usage-ledger.json").isFile)
        }
        val directory = File(context.getExternalFilesDir(null), "${if (searchPage) "flash-f05" else "flash-f04"}/$label")
        check(directory.mkdirs() || directory.isDirectory)
        val capture: (String) -> Unit = { name ->
            val heightFraction = when {
                searchSettings && name == "initial" -> 0.19 // The public toolbar ends above the account-bearing list.
                searchPage && name in setOf("search-results", "final-list") -> 0.5 // Exclude the IME.
                searchTips && name == "initial" -> 0.5
                else -> 1.0
            }
            screenshot(directory, name, heightFraction)
        }
        val search: StockSearchAcceptanceSample? = when (sample) {
            Sample.SettingsSearch -> StockSettingsSearchSample(automation, context.packageName, capture,
                fromWrongPage = args.getString("flashScrollWrongStart") == "true")
            Sample.TipsSearch -> StockTipsSearchSample(automation, context.packageName, capture)
            Sample.TipsBackground -> StockTipsBackgroundSample(automation, context.packageName, capture)
            else -> null
        }
        val nestedTipsBgSample = if (nestedTipsBg) StockTipsNestedBackgroundSample(automation, context.packageName, capture) else null
        val stock: StockPageAcceptanceSample? = search ?: nestedTipsBgSample ?: if (stockPage) {
            StockSettingsScrollSample(automation, context.packageName, capture)
        } else null
        val prompt = if (stock != null) stock.prompt
        else if (nested)
            "请打开手机上的「${appLabel}」应用，找到左侧内嵌清单里的「${itemLabel}」，等这一项完整显示后打开它。"
        else if (locateByItem)
            "请打开手机上的「${appLabel}」应用。请把列表里的「${itemLabel}」本身作为滑动定位的目标，按这一项的位置自动决定滑动距离。等整项完全显示后打开它，滑过头时请自行找回。"
        else if (overshoot && gainTenths >= 64)
            "请打开手机上的「${appLabel}」应用，围绕「${itemLabel}」定位并滚动列表。如果滑过头，请自己往回微调，等这一项完整露出后再打开它。"
        else if (overshoot)
            "请打开手机上的「${appLabel}」应用，找到列表里的「${itemLabel}」，把这一整项滑到能完整看清的位置，再打开它。"
        else if (args.getString("flashScrollNamedApp") == "true")
            "请打开手机上的「${appLabel}」应用，找到列表里的「${itemLabel}」并打开它。"
        else "请打开「${appLabel}」，找到列表里的「${itemLabel}」并打开它。"
        val samplePackages = setOf(stock?.packageName ?: fixturePackage, context.packageName)

        val record = JSONObject().put("case_id", caseId).put("run", label)
            .put("mode", when {
                local -> "mumu-local-loopback"
                dry -> "preflight_no_network"
                else -> "phone-real-model"
            })
            .put("target_row", target).put("prompt", prompt).put("drag_gain", gainTenths / 10.0)
            .put("manual_recovery_steps", 0).put("official_requests", 0)
        fun save() = File(directory, "result.json").writeText(record.toString(2))
        fun stage(name: String) {
            record.put("stage", name)
            save()
            val batch = if (searchPage) "F05" else "F04"
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nFLASH_$batch $label $name\n") })
        }

        val settings = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java).settings().state.value
        val outbound = JSONArray()
        record.put("outbound", outbound)
        if (stock != null) record.put(if (searchTips || nestedTipsBg) "tips_observations" else "settings_observations", stock.evidence)

        val fixture: AutonomyRuntimeFixture
        val http: HttpClient?
        var reserved = false
        var firstRequestSeen = false
        var localModelCalls = 0
        var localModelMillis = 0L
        var localFirstToolAt: Long? = null
        val meter: FlashBaselineDiagnosticTest.Meter?

        if (local) {
            http = null
            meter = null
            fixture = AutonomyRuntimeFixture(
                context = context,
                settingsOverride = settings.copy(
                    powerModeEnabled = true,
                    backgroundExecutionEnabled = false,
                    defaultWorkspace = null,
                    memoryEnabled = false
                ),
                allowEmptyUsageQueries = false
            )
            record.put("database", fixture.name).put("provider_id", fixture.provider.id)
                .put("model", fixture.provider.defaultModel).put("execution", "foreground")
            var localOpened = false
            fixture.responder = { request, round ->
                localModelCalls = round
                val t0 = SystemClock.elapsedRealtime()
                val next: ChatToolCall? = if (!localOpened) {
                    localOpened = true
                    localFirstToolAt = SystemClock.elapsedRealtime()
                    ChatToolCall(
                        UUID.randomUUID().toString(),
                        AgentToolDefinitions.OpenAppToolName,
                        buildJsonObject { put("app_name", appLabel) }.toString()
                    )
                } else {
                    val last = fixture.lastResult
                    val screen = last?.screen
                    if (screen == null || screen.packageName != fixturePackage) {
                        ChatToolCall(UUID.randomUUID().toString(), AgentToolDefinitions.ObserveScreenToolName, """{"include_image":false}""")
                    } else if (screen.elements.any { it.text == "Opened Item $target" }) {
                        null
                    } else {
                        val listEl = screen.elements.singleOrNull { it.contentDescription == "scroll_list" }
                        val targetEl = screen.elements.singleOrNull {
                            it.contentDescription == "scroll_item_$target" || it.text == itemLabel
                        }
                        if (listEl == null) {
                            ChatToolCall(UUID.randomUUID().toString(), AgentToolDefinitions.ObserveScreenToolName, """{"include_image":false}""")
                        } else if (last.scrollFeedback?.targetState == "ready" && targetEl != null) {
                            ChatToolCall(
                                UUID.randomUUID().toString(),
                                AgentToolDefinitions.TapUiElementToolName,
                                buildJsonObject { put("screen_id", screen.screenId); put("index", targetEl.index) }.toString()
                            )
                        } else if (targetEl != null &&
                            targetEl.bounds.isVisible &&
                            targetEl.bounds.top >= listEl.bounds.top &&
                            targetEl.bounds.bottom <= listEl.bounds.bottom
                        ) {
                            ChatToolCall(
                                UUID.randomUUID().toString(),
                                AgentToolDefinitions.TapUiElementToolName,
                                buildJsonObject { put("screen_id", screen.screenId); put("index", targetEl.index) }.toString()
                            )
                        } else {
                            val direction = when {
                                targetEl != null && targetEl.bounds.top < listEl.bounds.top -> "up"
                                targetEl == null && overshoot && initialY >= 0 -> "up"
                                else -> "down"
                            }
                            ChatToolCall(
                                UUID.randomUUID().toString(),
                                AgentToolDefinitions.ScrollContainerToolName,
                                buildJsonObject {
                                    put("screen_id", screen.screenId)
                                    put("index", listEl.index)
                                    put("direction", direction)
                                    put("target", buildJsonObject { put("text", itemLabel) })
                                }.toString()
                            )
                        }
                    }
                }
                localModelMillis += SystemClock.elapsedRealtime() - t0
                if (next == null) listOf(ChatDelta.Content("已找到并打开「$itemLabel」。"), ChatDelta.Done)
                else {
                    check(request.tools.any { it.name == next.name }) { "Required tool unavailable: ${next.name}" }
                    listOf(ChatDelta.ToolCalls(listOf(next)), ChatDelta.Done)
                }
            }
        } else {
            val providers = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java).providers()
            val selection = requireNotNull(settings.assistantModelSelection)
            check(selection.providerId == "provider-deepseek" && selection.modelName == "deepseek-flash")
            val provider = requireNotNull(providers.getProvider(selection.providerId))
            check(provider.isEnabled && java.net.URI(provider.baseUrl).host == "api.deepseek.com")
            val key = requireNotNull(providers.getApiKey(provider))
            fixture = AutonomyRuntimeFixture(context, provider.copy(defaultModel = selection.modelName), key,
                settingsOverride = settings.copy(backgroundExecutionEnabled = backgroundExecution, defaultWorkspace = null, memoryEnabled = false),
                allowEmptyUsageQueries = searchPage || nestedTipsBg)
            if (search != null) fixture.projectUiResult = search::publicUiResult
            else if (nestedTipsBgSample != null) fixture.projectUiResult = nestedTipsBgSample::publicUiResult
            record.put("database", fixture.name).put("provider_id", provider.id).put("model", selection.modelName)
                .put("thinking", "enabled/auto").put("execution", if (backgroundExecution) "background" else "foreground")
            http = AppModule.provideHttpClient(AppModule.provideJson())
            val client = KtorOpenAiCompatibleClient(http, AppModule.provideJson()).apply {
                usageTracker = UsageTracker(fixture.database, fixture.settings)
            }
            meter = FlashBaselineDiagnosticTest.Meter(fixture, client, key, ledgerDirectory,
                maxInput = requireNotNull(args.getString("flashScrollInputCap")).toLong(),
                maxOutput = requireNotNull(args.getString("flashScrollOutputCap")).toLong(),
                maxRequests = requireNotNull(args.getString("flashScrollRequestCap")).toInt(),
                restrictTools = false,
                inspectRequest = { request, input, output ->
                    stock?.checkActivePage()
                    request.messages.filter { it.role == "tool" }.forEach { message ->
                        val content = message.content.orEmpty()
                        val sources = screenPackages(content)
                        check(samplePackages.containsAll(sources) && (message.images.isEmpty() || sources.isNotEmpty())) {
                            "Only the staged list and isolated chat may be sent to Flash"
                        }
                        if (searchPage || nestedTipsBg) check(message.images.isEmpty()) { "Only control text is sent for this search sample" }
                        check(!independentMetrics || "scroll_metrics:" !in content) { "Fixture measurements must stay outside the model context" }
                    }
                    if (!firstRequestSeen) {
                        firstRequestSeen = true
                        record.put("first_input_reserve", input).put("first_output_reserve", output)
                            .put("normal_tools", JSONArray(request.tools.map { it.name }))
                            .put("open_app_description", request.tools.singleOrNull { it.name == AgentToolDefinitions.OpenAppToolName }?.description)
                        if (searchPage) record.put("press_back_description",
                            request.tools.single { it.name == AgentToolDefinitions.PressBackToolName }.description)
                        if (searchTips) record.put("input_text_description",
                            request.tools.single { it.name == AgentToolDefinitions.InputTextToolName }.description)
                            .put("editor_action_description",
                                request.tools.single { it.name == AgentToolDefinitions.PerformEditorActionToolName }.description)
                        save()
                    }
                    check(!dry) { "Preflight ended before any network request" }
                    reserved = true
                },
                inspectToolCalls = { request, calls ->
                    if (stock != null) stock.checkToolCalls(request, calls)
                    else checkFixtureToolCalls(calls, appLabel, samplePackages)
                })
            meter.sample = "$caseId/$label"
            fixture.externalClient = meter
            http.observeF06OutboundBody { body ->
                check(reserved && !dry) { "D2 cannot dispatch without a new request reservation" }
                reserved = false
                val payload = Json.parseToJsonElement(body).jsonObject
                val functions = payload["tools"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("function").jsonObject }
                val appTool = functions.singleOrNull { it.getValue("name").jsonPrimitive.content == AgentToolDefinitions.OpenAppToolName }
                val sent = JSONObject()
                    .put("tool_choice", payload["tool_choice"]?.toString()?.trim('"'))
                    .put("tool_names", JSONArray(functions.map { it.getValue("name").jsonPrimitive.content }))
                    .put("open_app_description", appTool?.get("description")?.jsonPrimitive?.content)
                if (searchPage) sent.put("press_back_description", functions.singleOrNull {
                    it.getValue("name").jsonPrimitive.content == AgentToolDefinitions.PressBackToolName
                }?.get("description")?.jsonPrimitive?.content)
                if (searchTips) {
                    sent.put("input_text_description", functions.singleOrNull {
                        it.getValue("name").jsonPrimitive.content == AgentToolDefinitions.InputTextToolName
                    }?.get("description")?.jsonPrimitive?.content)
                    sent.put("editor_action_description", functions.singleOrNull {
                        it.getValue("name").jsonPrimitive.content == AgentToolDefinitions.PerformEditorActionToolName
                    }?.get("description")?.jsonPrimitive?.content)
                }
                outbound.put(sent)
                save()
            }
        }

        try {
            stage(when {
                searchSettings -> "staging_stock_settings_search"
                searchTips -> "staging_stock_tips_search"
                nestedTipsBg -> "staging_stock_tips_nested_background"
                stockPage -> "staging_stock_settings"
                nested -> "staging_nested_row"
                else -> "staging_partly_visible_row"
            })
            ActionOutcomeServiceReadiness().ensureService()
            if (local) {
                fixture.real.refreshAccessibilityConnection()
                fixture.await { fixture.real.accessibilityState.value.isConnected }
            } else if (settings.powerModeEnabled) {
                fixture.connect()
            }
            fixture.settings.setUsageTrackingEnabled(true)
            fixture.create()
            val initial = if (stock != null) {
                stock.stage(::shell)
            } else {
                val fixtureOptions = when {
                    nested -> " --es mode nested --ei initial_y $nestedListY --ei initial_outer_y $nestedOuterY --ei initial_sibling_y $nestedSiblingY"
                    overshoot -> buildString {
                        append(" --es mode gain --ei gain_tenths ").append(gainTenths).append(" --ez independent_metrics true")
                        if (initialY >= 0) append(" --ei initial_y ").append(initialY)
                    }
                    else -> ""
                }
                check(shell("am start -W --display 0 -f 0x10008000 -n $fixturePackage/" +
                    "${AdaptiveScrollFixtureActivity::class.java.name} --ei partial_row $target$fixtureOptions").contains("Status: ok"))
                awaitFixtureMetrics(independentMetrics).also {
                    assertInitial(it, nested)
                    if (overshoot) {
                        if (initialY >= 0) check(it.getInt("listY") == initialY)
                        else check(it.getInt("listY") > 0)
                    }
                }
            }
            record.put("initial_fixture", initial)
            capture("initial")
            save()
            var activity = openChat()
            if (dry && backgroundExecution) {
                record.put("preflight_product_launch", initial)
                activity = openChat()
            } else if (dry) {
                val reopened = if (stock != null) {
                    if (searchTips) awaitChatWindow()
                    val back = fixture.gateway.pressBack()
                    check(back.success) { "Production Back did not return to the starting page: ${back.errorCode}" }
                    if (search != null) {
                        record.put("preflight_back_reply", JSONObject().put("package", back.screen?.packageName)
                            .put("activity", back.screen?.activityName)
                            .put("outcome", back.actionOutcome?.name)
                            .put("has_search_field", back.screen?.elements?.any { it.resourceId == search.searchFieldId }))
                        save()
                    }
                    stock.awaitInitial().also {
                        record.put("preflight_product_launch", it)
                        save()
                        check(it.toString() == initial.toString()) { "The starting page changed after Back" }
                    }
                } else {
                    check(AndroidAppLauncher(context, fixture.gateway, fixture.tasks).openApp(appLabel) == appLabel)
                    awaitFixtureMetrics(independentMetrics).also {
                        assertInitial(it, nested)
                        check(it.getInt("listY") == initial.getInt("listY"))
                    }
                }
                record.put("preflight_product_launch", reopened)
                if (search != null) {
                    stage("checking_search_tools_without_network")
                    search.verifyToolPath(if (searchTips) fixture.real else fixture.gateway)
                    check(search.stage(::shell).toString() == initial.toString()) { "Search did not reset to the same starting page" }
                }
                activity = openChat()
            }
            withContext(Dispatchers.Main) {
                val viewModel = ChatViewModel(fixture.runtime)
                activity.setContent {
                    FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) }
                }
                fixture.runtime.updateInput(prompt)
            }
            stock?.startRecording()
            stage("sending_normal_chat")
            val start = SystemClock.elapsedRealtime()
            withTimeout(10_000) {
                while (true) {
                    val button = nodes(automation.rootInActiveWindow).singleOrNull {
                        it.isVisibleToUser && it.isEnabled && it.contentDescription?.toString() in setOf("发送", "Send")
                    }
                    if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                    delay(50)
                }
            }
            record.put("entry", "production_ChatScreen_send_button")
            stage("waiting_for_task")
            withTimeout(150_000) {
                while (fixture.journal.latest(fixture.owner) == null ||
                    fixture.owner in fixture.runtime.activeConversations.value && fixture.confirmations.isEmpty()) delay(100)
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            if (fixture.owner in fixture.runtime.activeConversations.value) {
                withContext(Dispatchers.Main) { fixture.runtime.stopFromAssistant(fixture.owner) }
                fixture.await { fixture.owner !in fixture.runtime.activeConversations.value }
            }
            val finalFixture = if (stock != null) stock.finishObservation() else fixtureMetrics(independentMetrics)
            record.put("final_fixture", finalFixture ?: JSONObject.NULL)
            if (finalFixture != null && !backgroundExecution) capture("final-list")
            val messages = fixture.chats.getMessages(fixture.owner)
            val run = fixture.journal.latest(fixture.owner)
            record.put("elapsed_ms", elapsed)
                .put("model_ms", if (local) localModelMillis else meter!!.modelMillis)
                .put("first_tool_request_ms", (if (local) localFirstToolAt else meter!!.firstToolAt)?.minus(start) ?: JSONObject.NULL)
                .put("app_confirmations", fixture.confirmations.size).put("runtime_state", run?.state)
                .put("task_metadata", run?.metadataJson?.let(::JSONObject))
                .put("answer", messages.lastOrNull { it.role == MessageRole.Assistant }?.content)
                .put("official_requests", if (local) 0 else JSONObject.NULL)
                .put("local_model_calls", localModelCalls)
            File(directory, "messages.json").writeText(JSONArray(messages.map {
                JSONObject().put("role", it.role.name).put("status", it.status.name).put("content", it.content)
                    .put("execution", scopedExecution(it.executionContext, samplePackages))
            }).toString(2))
            openChat()
            val chatVisible = withTimeoutOrNull(4_000) {
                while (true) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName?.toString() == context.packageName && nodes(root).any {
                            it.isVisibleToUser && it.text?.toString() == prompt
                        }) break
                    delay(100)
                }
                true
            } == true
            if (chatVisible) screenshot(directory, "final-chat")
            record.put("final_chat_captured", chatVisible)
            if (local) {
                assertEquals("Completed", run?.state)
                assertEquals(0, fixture.confirmations.size)
                assertEquals(target, finalFixture?.getInt("selected"))
                if (nested && finalFixture != null) {
                    assertEquals(1, finalFixture.getJSONArray("outer_positions").length())
                    assertEquals(1, finalFixture.getJSONArray("sibling_positions").length())
                }
                record.put("passed", true)
            } else {
                check(firstRequestSeen && (!dry || outbound.length() == 0))
            }
            stage("recorded")
        } finally {
            if (stock != null) stock.stopRecording()
            else context.sendBroadcast(Intent("$fixturePackage.SCROLL_CONTROL").setPackage(fixturePackage).putExtra("command", "finish"))
            fixture.close(true)
            http?.close()
            val dbDeleted = !context.getDatabasePath("${fixture.name}.db").exists()
            record.put(
                "cleanup",
                JSONObject()
                    .put("isolated_db_deleted", dbDeleted)
                    .put("fixture_finish_sent", stock == null)
                    .put("cleanup_passed", dbDeleted)
            )
            save()
            assertTrue("F04 isolated DB cleanup failed", dbDeleted)
        }
    }

    private suspend fun openChat(): MainActivity {
        check(shell("am start -W -n ${context.packageName}/${MainActivity::class.java.name}").contains("Status: ok"))
        return withTimeout(10_000) {
            var activity: MainActivity? = null
            while (activity == null) {
                activity = withContext(Dispatchers.Main) {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().singleOrNull()
                }
                if (activity == null) delay(50)
            }
            activity
        }
    }

    private suspend fun awaitChatWindow() = withTimeout(5_000) {
        var packageVisibleSince: Long? = null
        while (true) {
            val now = SystemClock.elapsedRealtime()
            packageVisibleSince = if (automation.rootInActiveWindow?.packageName?.toString() == context.packageName) {
                packageVisibleSince ?: now
            } else null
            // Activity RESUMED can precede the window and IME transition from the staged editor.
            if (packageVisibleSince?.let { now - it >= 400 } == true) break
            delay(50)
        }
    }

    private val nestedListY = 220
    private val nestedOuterY = 84
    private val nestedSiblingY = 224

    private suspend fun awaitFixtureMetrics(independentMetrics: Boolean): JSONObject = withTimeout(10_000) {
        var previous: String? = null
        var stableSince = 0L
        var metrics: JSONObject? = null
        while (metrics == null) {
            val candidate = fixtureMetrics(independentMetrics)?.takeIf { it.has("target_height") && it.optBoolean("nested_staged", true) }
            val current = candidate?.toString()
            val now = SystemClock.elapsedRealtime()
            if (current != previous) {
                previous = current
                stableSince = now
            }
            // OriginOS reports the new window before its launch transition finishes.
            if (candidate != null && now - stableSince >= 400) metrics = candidate
            if (metrics == null) delay(50)
        }
        metrics
    }

    private fun fixtureMetrics(independentMetrics: Boolean): JSONObject? {
        val root = automation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != fixturePackage) return null
        if (independentMetrics) {
            val reply = shell("am broadcast --receiver-foreground -a $fixturePackage.SCROLL_CONTROL -p $fixturePackage --es command measure")
            val data = reply.lineSequence().singleOrNull { it.startsWith("Broadcast completed:") }
                ?.substringAfter(", data=\"", "")?.removeSuffix("\"").orEmpty()
            return data.takeIf { it.isNotEmpty() }?.let(::JSONObject)
        }
        return nodes(root).mapNotNull { it.contentDescription?.toString() }
            .singleOrNull { it.startsWith("scroll_metrics:") }
            ?.substringAfter("scroll_metrics:")?.let(::JSONObject)
    }

    private fun assertInitial(metrics: JSONObject, nested: Boolean) {
        val visibleHeight = metrics.getInt("target_visible_height")
        check(metrics.getInt("selected") == -1 && metrics.getJSONArray("movements").length() == 0) { metrics.toString() }
        if (nested) {
            check(visibleHeight == 0 && metrics.getInt("listY") == nestedListY &&
                metrics.getInt("outerY") == nestedOuterY && metrics.getInt("siblingY") == nestedSiblingY &&
                metrics.getJSONArray("outer_positions").toString() == "[$nestedOuterY]" &&
                metrics.getJSONArray("sibling_positions").toString() == "[$nestedSiblingY]") { metrics.toString() }
        } else {
            check(visibleHeight in 1 until metrics.getInt("target_height")) { metrics.toString() }
        }
    }

    private fun screenPackages(content: String): Set<String> =
        packageLine.findAll(content).map { it.groupValues[1] }.toSet()

    private fun checkFixtureToolCalls(calls: List<ChatToolCall>, appLabel: String, samplePackages: Set<String>) {
        val foreground = automation.rootInActiveWindow?.packageName?.toString()
        check(foreground in samplePackages) { "F04 left the isolated chat and staged list" }
        for (call in calls) {
            with(AgentToolDefinitions) {
                when (call.name) {
                    OpenAppToolName -> check(JSONObject(call.arguments).optString("app_name") in setOf(appLabel, fixturePackage)) {
                        "F04 can launch only the staged list"
                    }
                    ForegroundAppToolName, ObserveScreenToolName, WaitForScreenToolName, PressBackToolName, PressHomeToolName,
                    // Usage queries in AutonomyRuntimeFixture use its isolated, empty usage reader.
                    RecentAppActivityToolName, AppUsageSummaryToolName -> Unit
                    TapUiElementToolName, TapUiTargetToolName, TapVisualTargetToolName, ScrollContainerToolName, SwipeScreenToolName ->
                        check(foreground == fixturePackage) { "F04 can interact only with the staged list" }
                    else -> error("Tool outside the staged F04 list scope: ${call.name}")
                }
            }
        }
    }

    private fun scopedExecution(execution: String?, samplePackages: Set<String>): String? = execution?.let { data ->
        val steps = JSONArray(data)
        repeat(steps.length()) { index ->
            val step = steps.getJSONObject(index)
            val outside = screenPackages(step.getString("result")) - samplePackages
            if (outside.isNotEmpty()) step.put("result", "Observation withheld for packages outside the sample: ${outside.joinToString()}")
        }
        steps.toString()
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else buildList {
        add(root)
        repeat(root.childCount) { addAll(nodes(root.getChild(it))) }
    }

    private fun screenshot(directory: File, label: String, heightFraction: Double = 1.0) {
        val frame = requireNotNull(automation.takeScreenshot())
        val content = if (heightFraction < 1) Bitmap.createBitmap(frame, 0, 0, frame.width, (frame.height * heightFraction).toInt()) else frame
        File(directory, "$label.png").outputStream().use { content.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
}
