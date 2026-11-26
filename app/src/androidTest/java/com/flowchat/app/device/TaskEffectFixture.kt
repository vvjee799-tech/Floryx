package com.flowchat.app.device

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.data.repository.RoomChatRepository
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.repository.*
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject

/** Test-only source. No real account, network, production Room database or Android UI action exists. */
internal enum class EffectScenario(val argument: String, val toolName: String, val kind: String, val target: String) {
    Message("message", "task_effect_submit_message", "submit_message", "fixture-recipient-alice"),
    Record("record", "task_effect_create_record", "create_record", "fixture-collection-projects");

    val content get() = "Synthetic $argument payload; namespace isolation is verified by the source."
    fun arguments(targetId: String = target, text: String = content): String = JSONObject()
        .put("target_id", targetId).put("content", text).toString()

    companion object {
        fun parse(value: String): EffectScenario = entries.single { it.argument == value }
    }
}

internal enum class EffectSourceMode { Normal, WrongTarget, WrongContent, CallbackLost, MissingReceipt, AmbiguousReceipt }

internal class EffectFixtureFiles(val context: Context, val namespace: String) {
    init {
        require(namespace.startsWith(Prefix) && UUID.fromString(namespace.removePrefix(Prefix)).toString() == namespace.removePrefix(Prefix))
    }
    val runtimeDatabase = "$namespace-runtime.db"
    val sourceDatabase = "$namespace-source.db"
    val marker = AtomicFile(context.getFileStreamPath("$namespace-effect.json"))

    fun readMarker(): JSONObject = marker.openRead().bufferedReader().use { JSONObject(it.readText()) }
    fun persistMarker(value: JSONObject) {
        check(value.getString("namespace") == namespace)
        val stream = marker.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); marker.finishWrite(stream) }
        catch (error: Throwable) { marker.failWrite(stream); throw error }
    }
    fun deleteOnlyOwnFiles() {
        if (marker.baseFile.exists()) check(readMarker().getString("namespace") == namespace)
        context.deleteDatabase(runtimeDatabase)
        context.deleteDatabase(sourceDatabase)
        marker.delete()
        check(!context.getDatabasePath(runtimeDatabase).exists() && !context.getDatabasePath(sourceDatabase).exists() && !marker.baseFile.exists())
    }
    companion object {
        const val Prefix = "r4-effect-"
        fun fresh(context: Context) = EffectFixtureFiles(context, Prefix + UUID.randomUUID())
    }
}

internal data class EffectSourceRow(val receiptId: String, val effectId: String, val intent: TaskEffectIntent,
    val content: String, val revision: Long)

/**
 * Authoritative receipt DB is separate from the runtime's Room DB. Receipt fields come from
 * stored operations, never from the expected record passed to inspect. A fence is a source
 * transaction that permanently excludes a later commit for the token. inspect only SELECTs.
 */
internal class EffectFixtureSource(private val files: EffectFixtureFiles) : SQLiteOpenHelper(
    files.context, files.sourceDatabase, null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE source_identity (namespace TEXT NOT NULL)")
        db.execSQL("INSERT INTO source_identity VALUES (?)", arrayOf(files.namespace))
        db.execSQL("CREATE TABLE receipts (revision INTEGER PRIMARY KEY AUTOINCREMENT, receipt_id TEXT NOT NULL UNIQUE, " +
            "effect_id TEXT NOT NULL, adapter_id TEXT NOT NULL, adapter_version INTEGER NOT NULL, kind TEXT NOT NULL, " +
            "source_id TEXT NOT NULL, target_id TEXT NOT NULL, content TEXT NOT NULL)")
        db.execSQL("CREATE TABLE fences (effect_id TEXT PRIMARY KEY, adapter_id TEXT NOT NULL, adapter_version INTEGER NOT NULL, " +
            "kind TEXT NOT NULL, source_id TEXT NOT NULL, target_id TEXT NOT NULL, content_sha256 TEXT NOT NULL)")
        db.execSQL("CREATE TABLE dispatch_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, effect_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE device_calls (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Fixture schema must not migrate")
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.rawQuery("SELECT namespace FROM source_identity", null).use {
            check(it.moveToFirst() && it.getString(0) == files.namespace && !it.moveToNext())
        }
    }

    @Synchronized fun dispatch(effectId: String, intent: TaskEffectIntent, content: String, mode: EffectSourceMode) {
        check(intent.sourceId == files.namespace && intent.valid() && intent.contentSha256 == effectContentSha256(content))
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO dispatch_attempts(effect_id) VALUES (?)", arrayOf(effectId))
            check(!hasFence(db, effectId)) { "Source token is permanently fenced: $effectId" }
            if (rows(effectId).isEmpty() && mode != EffectSourceMode.MissingReceipt) {
                val target = if (mode == EffectSourceMode.WrongTarget) "fixture-other-target" else intent.targetId
                val actualContent = if (mode == EffectSourceMode.WrongContent) "$content altered-at-source" else content
                insertReceipt(db, effectId, intent.copy(targetId = target), actualContent)
                if (mode == EffectSourceMode.AmbiguousReceipt) insertReceipt(db, effectId, intent, content)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (mode == EffectSourceMode.CallbackLost) error("Synthetic callback disconnected after authoritative commit")
    }
    private fun insertReceipt(db: SQLiteDatabase, effectId: String, intent: TaskEffectIntent, content: String) {
        db.insertOrThrow("receipts", null, ContentValues().apply {
            put("receipt_id", UUID.randomUUID().toString()); put("effect_id", effectId)
            put("adapter_id", intent.adapterId); put("adapter_version", intent.adapterVersion); put("kind", intent.kind)
            put("source_id", files.namespace); put("target_id", intent.targetId); put("content", content)
        })
    }

    @Synchronized fun rows(effectId: String? = null): List<EffectSourceRow> = readableDatabase.rawQuery(
        "SELECT receipt_id,effect_id,adapter_id,adapter_version,kind,source_id,target_id,content,revision FROM receipts" +
            if (effectId == null) " ORDER BY revision" else " WHERE effect_id = ? ORDER BY revision",
        effectId?.let { arrayOf(it) }
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val content = cursor.getString(7)
            add(EffectSourceRow(cursor.getString(0), cursor.getString(1), TaskEffectIntent(cursor.getString(2),
                cursor.getInt(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), effectContentSha256(content)),
                content, cursor.getLong(8)))
        }
    } }

    @Synchronized fun inspect(effectId: String): TaskEffectProof? {
        val receipts = rows(effectId)
        if (receipts.size > 1) return null
        receipts.singleOrNull()?.let { row ->
            return TaskEffectProof(row.effectId, row.intent, TaskEffectResolution.Applied, row.receiptId,
                "receipt:${row.revision}", System.currentTimeMillis())
        }
        return readableDatabase.rawQuery("SELECT effect_id,adapter_id,adapter_version,kind,source_id,target_id,content_sha256 " +
            "FROM fences WHERE effect_id = ?", arrayOf(effectId)).use { cursor ->
            if (!cursor.moveToFirst()) null else TaskEffectProof(cursor.getString(0), TaskEffectIntent(cursor.getString(1),
                cursor.getInt(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6)),
                TaskEffectResolution.NotApplied, "fence:${cursor.getString(0)}", "fenced-v1", System.currentTimeMillis())
        }
    }

    /** Source control operation, deliberately separate from read-only inspect(). */
    @Synchronized fun fence(record: TaskEffectRecord) {
        check(record.intent.sourceId == files.namespace)
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(rows(record.effectId).isEmpty()) { "Cannot certify absence after a committed receipt" }
            val intent = record.intent
            db.insertOrThrow("fences", null, ContentValues().apply {
                put("effect_id", record.effectId); put("adapter_id", intent.adapterId); put("adapter_version", intent.adapterVersion)
                put("kind", intent.kind); put("source_id", files.namespace); put("target_id", intent.targetId)
                put("content_sha256", intent.contentSha256)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun hasFence(db: SQLiteDatabase, effectId: String): Boolean = db.rawQuery(
        "SELECT 1 FROM fences WHERE effect_id = ?", arrayOf(effectId)).use { it.moveToFirst() }
    @Synchronized fun dispatchCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM dispatch_attempts", null).use {
        check(it.moveToFirst()); it.getInt(0)
    }
    @Synchronized fun deviceCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM device_calls", null).use {
        check(it.moveToFirst()); it.getInt(0)
    }
    @Synchronized fun recordDeviceCall(kind: String) {
        writableDatabase.execSQL("INSERT INTO device_calls(kind) VALUES (?)", arrayOf(kind))
    }
}

internal fun effectContentSha256(content: String): String = TaskEffectRegistry.contentSha256(content)

internal class EffectFixtureAdapter(val scenario: EffectScenario, private val namespace: String,
    private val source: EffectFixtureSource, override val version: Int = 1) : TaskEffectAdapter {
    override val id = "fixture.${scenario.argument}"
    override val supportsAuthoritativeAbsence = true
    var mode = EffectSourceMode.Normal
    var inspectionEnabled = true
    val inspectionCalls = AtomicInteger()
    override val tool = ChatToolDefinition(scenario.toolName,
        "Synthetic isolated fixture operation. target_id is the exact fixture recipient or collection; content is the exact payload.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("target_id") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
            }
            putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("target_id")); add(kotlinx.serialization.json.JsonPrimitive("content")) }
            put("additionalProperties", false)
        })
    override fun describe(arguments: String): TaskEffectIntent {
        val values = JSONObject(arguments)
        check(values.keys().asSequence().toSet() == setOf("target_id", "content"))
        val target = values.getString("target_id")
        val content = values.getString("content")
        check(target.isNotBlank() && target.length <= 512 && content.length <= 16_384)
        return TaskEffectIntent(id, version, scenario.kind, namespace, target, effectContentSha256(content))
    }
    override suspend fun dispatch(effectId: String, arguments: String) {
        source.dispatch(effectId, describe(arguments), JSONObject(arguments).getString("content"), mode)
    }
    override suspend fun inspect(record: TaskEffectRecord): TaskEffectProof? {
        inspectionCalls.incrementAndGet()
        return if (inspectionEnabled) source.inspect(record.effectId) else null
    }
}

internal class EffectScriptedModel : ChatCompletionClient {
    val requests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
    private val responses = mutableListOf<suspend FlowCollector<ChatDelta>.() -> Unit>()
    @Synchronized fun enqueue(vararg deltas: ChatDelta) { responses += { deltas.forEach { emit(it) } } }
    @Synchronized fun enqueueBlocked() { responses += { awaitCancellation() } }
    fun enqueueTurn(vararg calls: ChatToolCall) {
        enqueue(ChatDelta.ToolCalls(calls.toList()))
        enqueue(ChatDelta.Content("SYNTHETIC_EFFECT_TURN_FINISHED"), ChatDelta.Done)
    }
    override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> {
        check(provider.id.startsWith(EffectFixtureFiles.Prefix) && provider.baseUrl == BaseUrl && apiKey == null)
        val response = synchronized(this) {
            val index = requests.size
            requests += request
            responses.getOrNull(index) ?: error("Unexpected synthetic effect model request #$index")
        }
        return flow(response)
    }
    companion object { const val BaseUrl = "https://r4-effect.invalid/v1" }
}

internal class TaskEffectFixture(val files: EffectFixtureFiles, val scenario: EffectScenario,
    val client: EffectScriptedModel = EffectScriptedModel(),
    observer: TaskPersistenceObserver = TaskPersistenceObserver(),
    limits: TaskExecutionBudgetLimits = effectFixtureLimits(), adapterVersion: Int = 1, adapterAvailable: Boolean = true) {
    val database = Room.databaseBuilder(files.context, FlowChatDatabase::class.java, files.runtimeDatabase).build()
    val chats = RoomChatRepository(database.conversationDao(), database.messageDao())
    val journal = TaskRunJournal(database.taskRunDao())
    val source = EffectFixtureSource(files)
    val adapter = EffectFixtureAdapter(scenario, files.namespace, source, adapterVersion)
    val registry = TaskEffectRegistry(if (adapterAvailable) listOf(adapter) else emptyList())
    private val provider = ProviderConfig(id = files.namespace, displayName = "R4 isolated synthetic fixture", baseUrl = EffectScriptedModel.BaseUrl,
        defaultModel = Model)
    lateinit var runtime: ChatTaskRuntime
    private var collector: Job? = null
    private val persistenceObserver = observer
    private val budgetLimits = limits

    suspend fun open(owner: String) {
        // Opening the source verifies its GUID identity before the runtime can use it.
        source.readableDatabase
        runtime = withContext(Dispatchers.Main) {
            ChatTaskRuntime(chats, Providers(provider), client, Usage, object : AppLauncher {
                override suspend fun openApp(appName: String): String = error("Fixture cannot launch real apps")
            }, Gateway(source), Prompts, Memory,
                appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false,
                    commandExecutionEnabled = false, installId = files.namespace)), taskJournal = journal,
                budgetLimits = budgetLimits, budgetClockMillis = { android.os.SystemClock.elapsedRealtime() },
                effectRegistry = registry, persistenceObserver = persistenceObserver)
        }
        collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect {} }
        withContext(Dispatchers.Main) { runtime.selectConversation(owner) }
        effectAwait("isolated-conversation-selected") { runtime.uiState.value.currentConversation?.id == owner }
    }
    suspend fun newConversation(): String = chats.createConversation(provider.id, Model).id.also { open(it) }
    suspend fun send(text: String) = withContext(Dispatchers.Main) { runtime.updateInput(text); runtime.send() }
    suspend fun awaitIdle(owner: String, minimumRequests: Int) = effectAwait("synthetic-turn-idle") {
        client.requests.size >= minimumRequests && !runtime.uiState.value.isStreaming &&
            !runtime.activeConversations.value.contains(owner) && journal.latest(owner)?.state !in setOf("Running", "WaitingForUser")
    }
    suspend fun metadata(owner: String): TaskRunMetadata = requireNotNull(TaskRunMetadata.decode(journal.latest(owner)?.metadataJson))
    suspend fun close() = withContext(NonCancellable) {
        collector?.cancelAndJoin()
        if (::runtime.isInitialized) {
            withContext(Dispatchers.Main) { runtime.close() }
            withTimeout(10_000) { runtime.scope.coroutineContext[Job]?.join() }
        }
        withContext(Dispatchers.IO) { source.close(); database.close() }
    }
    private class Providers(private val provider: ProviderConfig) : ProviderRepository {
        override fun observeProviders() = flowOf(listOf(provider))
        override suspend fun getProvider(id: String) = provider.takeIf { it.id == id }
        override suspend fun getProvidersOnce() = listOf(provider)
        override suspend fun getApiKey(config: ProviderConfig): String? = null
        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = error("Synthetic provider is read-only")
        override suspend fun deleteProvider(id: String) = error("Synthetic provider is read-only")
        override suspend fun ensureTemplates() = Unit
    }
    private class Gateway(private val source: EffectFixtureSource) : DeviceAssistantGateway {
        override val safetyState = MutableStateFlow(DeviceSafetyState(screenLocked = false, screenInteractive = true))
        override val connectionState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.ConnectedAdb, DeviceCapability.entries.toSet()))
        override val accessibilityState = MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Connected))
        override fun refreshConnection() = Unit
        override fun requestPermission() = Unit
        override suspend fun getDeviceStatus() = DeviceToolResult(true, "synthetic source ready")
        override suspend fun getForegroundApp() = DeviceToolResult(true, "synthetic foreground")
        override suspend fun setScreenBrightness(percent: Int): DeviceToolResult = error("No physical brightness operation is permitted")
        override suspend fun setMediaVolume(percent: Int): DeviceToolResult = error("No physical volume operation is permitted")
        override suspend fun forceStopApp(appName: String): DeviceToolResult {
            check(appName == "R4 synthetic confirmation fixture")
            source.recordDeviceCall("force-stop")
            return DeviceToolResult(true, "synthetic confirmation action", actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        override suspend fun enablePowerMode() = DeviceToolResult(true, "synthetic power")
        override suspend fun observeScreen() = DeviceToolResult(true, "Synthetic fixture current state", screen = DeviceScreenSnapshot(
            "com.flowchat.synthetic.effect", "SyntheticEffectScreen", emptyList()))
    }
    private object Usage : AppUsageReader {
        override fun hasUsageAccess() = false
        override suspend fun getUsageSummary(range: String) = AppUsageSummary(range, 0, 0, 0, emptyList())
        override suspend fun getRecentActivity(hours: Int) = RecentAppActivity(hours, emptyList())
    }
    private object Prompts : PromptProfileRepository {
        override suspend fun getActiveProfile() = PromptProfileConfig()
        override suspend fun getActiveThinkingFormat(): String? = null
    }
    private object Memory : MemoryRepository {
        override suspend fun retrieve(userMessage: String, topN: Int) = emptyList<MemoryRecord>()
        override suspend fun snapshot() = MemorySnapshot(emptyList(), 0)
        override suspend fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean) = false
        override suspend fun update(id: String, summary: String) = Unit
        override suspend fun getAll() = emptyList<MemoryRecord>()
        override suspend fun delete(id: String) = Unit
        override suspend fun clear() = Unit
    }
    companion object { const val Model = "r4-effect-synthetic-model" }
}

internal fun effectFixtureLimits(checkpoint: Boolean = false) = TaskExecutionBudgetLimits(
    stageToolCalls = if (checkpoint) 1 else 16, stageModelCalls = 24, stageActiveMillis = 180_000,
    stageEstimatedTokens = 10_000_000, totalToolCalls = 64, totalModelCalls = 100, totalActiveMillis = 600_000,
    totalEstimatedTokens = 40_000_000, maxStages = 8, maxNoProgress = 12)

internal fun effectCall(scenario: EffectScenario, id: String = UUID.randomUUID().toString()) = ChatToolCall(id, scenario.toolName, scenario.arguments())
internal fun effectVerifyCall(record: TaskEffectRecord) = ChatToolCall(UUID.randomUUID().toString(), TaskEffectRegistry.VerifyToolName,
    JSONObject().put("effect_id", record.effectId).toString())
internal fun effectSteps(message: Message): List<ExecutionStep> = message.executionContext?.let {
    Json.decodeFromString<List<ExecutionStep>>(it)
}.orEmpty()
internal fun effectReport(label: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
    putString("stream", "\nTASK_EFFECT_R4 $label\n")
})
internal suspend fun effectAwait(label: String, condition: suspend () -> Boolean) {
    try { withTimeout(25_000) { while (!condition()) delay(25) } }
    catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
    effectReport(label)
}
