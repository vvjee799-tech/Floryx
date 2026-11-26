package com.flowchat.app.diagnostics.nativevoice

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.flowchat.app.BuildConfig
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ReplyPhase
import com.flowchat.nativevoicebridge.VoiceBridgeWire.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Accepts verified bridge, verified Xiaov host, or same-UID Dev instrumentation calls. */
class NativeVoiceProvider : ContentProvider() {
    private val lock = Mutex()
    private val stopSourcePattern =
        Regex("voice_stop|keyboard_stop|unlock_timeout|(?:native_interrupt|panel_dismiss):[A-Za-z0-9_.:-]{1,64}")
    private val tasks: VoiceTaskBackend by lazy {
        val host = requireNotNull(context)
        if (BuildConfig.NATIVE_VOICE_REAL_RUNTIME) RealVoiceTaskRuntime(host) else LocalVoiceTaskRuntime(host)
    }
    private val receipts by lazy {
        requireNotNull(context).getSharedPreferences("native_voice_receipts", Context.MODE_PRIVATE)
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentProvider.call does not apply method-level permission checks.
        val hostContext = requireNotNull(context)
        verifyCaller(hostContext)
        if (extras?.getInt(PROTOCOL_VERSION) != VERSION) return state(INCOMPATIBLE)
        if (method == PROBE) {
            return state(READY).apply {
                putString(RUNTIME_MODE, if (BuildConfig.NATIVE_VOICE_REAL_RUNTIME) "real" else "local")
            }
        }
        val session = uuid(extras.getString(SESSION_ID)) ?: return state(REJECTED)
        val requestValue = extras.getString(REQUEST_ID)
        val request = uuid(requestValue)
        if (requestValue != null && request == null) return state(REJECTED)
        val identity = Binder.clearCallingIdentity()
        try {
            return runBlocking(Dispatchers.Main.immediate) {
                lock.withLock {
                    if (method == LATEST) {
                        val latest = receipts.getString("latest:$session", null)
                        val receipt = latest?.let(::load) ?: return@withLock state(MISSING)
                        return@withLock snapshot(receipt)
                    }
                    if (request == null) {
                        if (method == STOP) {
                            val latest = receipts.getString("latest:$session", null)
                            val receipt = latest?.let(::load)?.takeIf { it.session == session }
                                ?: return@withLock state(MISSING)
                            return@withLock stop(receipt, extras.getString(STOP_SOURCE))
                        }
                        return@withLock state(REJECTED)
                    }
                    val stoppedSession = receipts.getString("stop-before-submit:$request", null)
                    if (stoppedSession != null) {
                        return@withLock if (stoppedSession == session) preempted(session, request) else state(REJECTED)
                    }
                    val existing = load(request)
                    if (method == SUBMIT) return@withLock submit(session, request, extras.getString(TRANSCRIPT), existing)
                    if (method == STOP && existing == null) {
                        return@withLock stopBeforeSubmit(session, request, extras.getString(STOP_SOURCE))
                    }
                    val receipt = existing?.takeIf { it.session == session } ?: return@withLock state(MISSING)
                    if (method != STATUS && !isLatest(receipt)) return@withLock state(MISSING)
                    when (method) {
                        STATUS -> snapshot(receipt)
                        STOP -> stop(receipt, extras.getString(STOP_SOURCE))
                        RESUME -> resume(receipt)
                        CLAIM_PLAYBACK -> claimPlayback(receipt)
                        ACK_PANEL_YIELD -> ackPanelYield(receipt)
                        else -> state(REJECTED)
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun verifyCaller(hostContext: Context) {
        val caller = callingPackage
        val callerUid = Binder.getCallingUid()
        val pm = hostContext.packageManager
        when {
            callerUid == Process.myUid() && caller == DEV_PACKAGE -> Unit
            caller == BRIDGE_PACKAGE -> {
                hostContext.enforceCallingPermission(PERMISSION, "Native voice bridge required")
                val uidPackages = pm.getPackagesForUid(callerUid)
                if (pm.checkSignatures(BRIDGE_PACKAGE, DEV_PACKAGE) != PackageManager.SIGNATURE_MATCH ||
                    uidPackages?.contains(BRIDGE_PACKAGE) != true
                ) {
                    throw SecurityException("Unexpected native voice bridge caller")
                }
            }
            caller == XIAOV_PACKAGE -> {
                if (pm.checkSignatures(BRIDGE_PACKAGE, DEV_PACKAGE) != PackageManager.SIGNATURE_MATCH ||
                    !matchesXiaov(pm, callerUid)
                ) {
                    throw SecurityException("Verified Xiaov caller required")
                }
            }
            else -> throw SecurityException("Native voice bridge or verified Xiaov required")
        }
    }

    private suspend fun submit(session: String, request: String, text: String?, existing: Receipt?): Bundle {
        if (text.isNullOrBlank() || text.length > MAX_TRANSCRIPT_LENGTH) return state(REJECTED)
        val instruction = text.trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(instruction.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (existing != null) {
            return if (existing.session == session && existing.digest == digest) snapshot(existing) else state(REJECTED)
        }
        val latestPrior = receipts.getString("latest:$session", null)?.let(::load)
            ?.takeIf { it.session == session }
        if (latestPrior != null && !latestPrior.stopped &&
            (latestPrior.owner in tasks.runtime.activeConversations.value ||
                tasks.isWaitingForUnlock(latestPrior.owner) ||
                tasks.isWaitingForUser(latestPrior.owner))
        ) {
            stop(latestPrior, "native_interrupt:wake_word")
        }
        val prior = receipts.getString("latest:$session", null)?.let(::load)
            ?.takeIf { it.session == session && canReuseConversation(it) }
        val reused = prior?.let { tasks.reusableConversation(it.owner) }
        val conversation = reused ?: try {
            tasks.createConversation()
        } catch (error: Exception) {
            Log.w(LocalVoiceTaskRuntime.LOG_TAG, "create_conversation_failed", error)
            return state(FAILED)
        }
        val receipt = Receipt(
            session = session,
            request = request,
            owner = conversation.id,
            digest = digest,
            baselineRunId = tasks.journal.latest(conversation.id)?.id,
        )
        // Reserve before sending. If the process dies in between, the same event cannot start again.
        save(receipt, makeLatest = true)
        val accepted = tasks.submit(conversation, instruction)
        val submitted = if (accepted) bindCurrentRun(receipt) else receipt
        return snapshot(submitted).apply { putBoolean(NEWLY_ACCEPTED, accepted) }
    }

    private suspend fun stop(receipt: Receipt, source: String?): Bundle {
        if (receipt.stopped) return snapshot(receipt)
        val reportedSource = source?.takeIf(stopSourcePattern::matches) ?: "unspecified"
        val stopped = receipt.copy(stopped = true, stopSource = reportedSource)
        Log.i(
            LocalVoiceTaskRuntime.LOG_TAG,
            "stop_received owner=${receipt.owner} event=${receipt.request} source=$reportedSource " +
                "elapsed_ms=${SystemClock.elapsedRealtime()}"
        )
        if (isCurrent(receipt, receipt.runId ?: legacyRun(receipt)?.id)) {
            tasks.runtime.stopFromAssistant(receipt.owner)
            tasks.clearPanelYield(receipt.owner)
        }
        save(stopped)
        Log.i(
            LocalVoiceTaskRuntime.LOG_TAG,
            "stopped owner=${receipt.owner} event=${receipt.request} source=$reportedSource"
        )
        return snapshot(stopped)
    }

    private suspend fun stopBeforeSubmit(session: String, request: String, source: String?): Bundle {
        // A delayed submit must still recognize an explicitly stopped event after process restart.
        withContext(Dispatchers.IO) {
            check(receipts.edit().putString("stop-before-submit:$request", session).commit()) {
                "Could not persist native voice stop"
            }
        }
        val reportedSource = source?.takeIf(stopSourcePattern::matches) ?: "unspecified"
        Log.i(LocalVoiceTaskRuntime.LOG_TAG, "stopped before_submit event=$request source=$reportedSource")
        return preempted(session, request)
    }

    private fun preempted(session: String, request: String): Bundle = state(STOPPED).apply {
        putString(SESSION_ID, session)
        putString(REQUEST_ID, request)
    }

    private fun isLatest(receipt: Receipt): Boolean =
        receipts.getString("latest:${receipt.session}", null) == receipt.request

    private fun canReuseConversation(receipt: Receipt): Boolean =
        !receipt.stopped ||
            receipt.playbackClaimed ||
            receipt.stopSource?.startsWith("native_interrupt:") == true

    private suspend fun resume(receipt: Receipt): Bundle {
        if (receipt.stopped || !isCurrent(receipt, receipt.runId ?: legacyRun(receipt)?.id) ||
            !tasks.isWaitingForUnlock(receipt.owner)) {
            return snapshot(receipt)
        }
        val resumed = if (tasks.resume(receipt.owner)) bindCurrentRun(receipt) else receipt
        return snapshot(resumed)
    }

    private suspend fun ackPanelYield(receipt: Receipt): Bundle {
        if (!receipt.stopped && isCurrent(receipt, receipt.runId ?: legacyRun(receipt)?.id)) {
            tasks.ackPanelYield(receipt.owner)
        }
        return snapshot(receipt)
    }

    private suspend fun claimPlayback(receipt: Receipt): Bundle {
        val current = snapshot(receipt)
        if (current.getString(STATE) == COMPLETED && !receipt.playbackClaimed) {
            save(receipt.copy(playbackClaimed = true))
            current.putBoolean(PLAYBACK_CLAIMED, true)
        }
        return current
    }

    private suspend fun snapshot(receipt: Receipt): Bundle {
        var run = receipt.runId?.let { tasks.journal.get(it) } ?: legacyRun(receipt)
        var runId = receipt.runId ?: run?.id
        var current = isCurrent(receipt, runId)
        var active = current && receipt.owner in tasks.runtime.activeConversations.value
        if (!active && (run == null || run.state == RUNNING)) {
            run = receipt.runId?.let { tasks.journal.get(it) } ?: legacyRun(receipt)
            runId = receipt.runId ?: run?.id
            current = isCurrent(receipt, runId)
            active = current && receipt.owner in tasks.runtime.activeConversations.value
        }
        val waitingUnlock = !receipt.stopped && current && !active && tasks.isWaitingForUnlock(receipt.owner)
        val waitingUser = !receipt.stopped && current &&
            (tasks.isWaitingForUser(receipt.owner) || (active && run?.state == WAITING_USER))
        val conversation = tasks.chats.getConversation(receipt.owner)
        val phase = when {
            receipt.stopped -> STOPPED
            waitingUnlock -> WAITING_UNLOCK
            waitingUser -> WAITING_USER
            active -> RUNNING
            run == null -> INTERRUPTED
            run.state == RUNNING && !active -> INTERRUPTED
            else -> run.state
        }
        val yieldPanel = phase == RUNNING && tasks.isPanelYieldRequested(receipt.owner)
        if (current && phase !in PENDING_STATES && phase != WAITING_UNLOCK && isLatest(receipt)) {
            tasks.clearPanelYield(receipt.owner)
        }
        return state(phase).apply {
            putString(SESSION_ID, receipt.session)
            putString(REQUEST_ID, receipt.request)
            putString(CONVERSATION_ID, receipt.owner)
            putString(TASK_ID, runId)
            putString(TASK_STATE, run?.state)
            putString(RUNTIME_MODE, tasks.runtimeMode)
            conversation?.providerId?.let { putString(PROVIDER_ID, it) }
            (conversation?.modelName ?: run?.modelName)?.let { putString(MODEL_NAME, it) }
            putBoolean(YIELD_PANEL, yieldPanel)
            if (phase == WAITING_UNLOCK) {
                putString(STATUS_TEXT, UNLOCK_PROMPT_TEXT)
            } else if (phase == WAITING_USER) {
                putString(STATUS_TEXT, tasks.waitingForUserPrompt(receipt.owner) ?: WAITING_USER_PROMPT_TEXT)
            }
            if (phase == COMPLETED && run != null) {
                val messages = tasks.chats.getMessages(receipt.owner)
                val reply = run.assistantMessageId?.let { id ->
                    messages.singleOrNull { it.id == id && it.role == MessageRole.Assistant &&
                        it.status == MessageStatus.Complete && it.replyPhase == ReplyPhase.Answer }
                        ?.content
                        ?.takeIf { it.isNotBlank() }
                }
                if (reply.isNullOrBlank()) putString(STATE, FAILED) else putString(REPLY, reply)
            }
        }
    }

    private suspend fun legacyRun(receipt: Receipt) =
        if (receipt.legacy) tasks.journal.firstAfter(receipt.owner, receipt.baselineRunId) else null

    private fun isCurrent(receipt: Receipt, runId: String?) =
        runId != null && tasks.runtime.taskProgress.value[receipt.owner]?.runId == runId

    private suspend fun bindCurrentRun(receipt: Receipt): Receipt {
        val runId = checkNotNull(tasks.runtime.taskProgress.value[receipt.owner]?.runId)
        return receipt.copy(runId = runId, legacy = false).also { save(it) }
    }

    private fun load(request: String): Receipt? = receipts.getString("request:$request", null)?.let { encoded ->
        val json = JSONObject(encoded)
        Receipt(
            session = json.getString("session"),
            request = request,
            owner = json.getString("owner"),
            digest = json.getString("digest"),
            stopped = json.getBoolean("stopped"),
            playbackClaimed = json.getBoolean("playbackClaimed"),
            baselineRunId = json.optString("baselineRunId").takeIf { it.isNotEmpty() },
            stopSource = json.optString("stopSource").takeIf { it.isNotEmpty() },
            runId = if (json.isNull("runId")) null else json.getString("runId"),
            legacy = !json.has("runId"),
        )
    }

    private suspend fun save(receipt: Receipt, makeLatest: Boolean = false) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("session", receipt.session).put("owner", receipt.owner)
            .put("digest", receipt.digest).put("stopped", receipt.stopped).put("playbackClaimed", receipt.playbackClaimed)
        receipt.baselineRunId?.let { json.put("baselineRunId", it) }
        receipt.stopSource?.let { json.put("stopSource", it) }
        if (!receipt.legacy) json.put("runId", receipt.runId ?: JSONObject.NULL)
        val edit = receipts.edit().putString("request:${receipt.request}", json.toString())
        if (makeLatest) edit.putString("latest:${receipt.session}", receipt.request)
        check(edit.commit()) { "Could not persist native voice receipt" }
    }

    private fun uuid(value: String?): String? = value?.takeIf { it.length == 36 &&
        runCatching { UUID.fromString(it).toString() }.getOrNull() == it
    }

    private data class Receipt(
        val session: String,
        val request: String,
        val owner: String,
        val digest: String,
        val stopped: Boolean = false,
        val playbackClaimed: Boolean = false,
        val baselineRunId: String? = null,
        val stopSource: String? = null,
        val runId: String? = null,
        val legacy: Boolean = false,
    )

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        val PENDING_STATES = setOf(RUNNING, UNKNOWN, MISSING)
        const val UNLOCK_PROMPT_TEXT = "请先解锁手机，解锁后我继续帮你处理。"
        const val WAITING_USER_PROMPT_TEXT = "这一步需要你在 Floryx 应用内确认后才能继续，请打开 Floryx 处理。"
    }
}
