package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.model.ChatDelta
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ReplySnapshot(val content: String, val reasoning: String, val reasoningMillis: Long)
internal data class LiveReply(val id: String, val snapshot: ReplySnapshot)

/** Confined to the caller's dispatcher. Network fragments never wait for a database write. */
internal class StreamingReplyWriter(
    content: String,
    reasoning: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val uiIntervalMillis: Long = 50,
    private val persistIntervalMillis: Long = 350
) {
    private val content = StringBuilder(content)
    private val reasoning = StringBuilder(reasoning)
    private var reasoningStarted: Long? = null
    private var reasoningUpdated: Long? = null
    private var revision = 0L
    private var publishedRevision = -1L
    private var persistedRevision = -1L
    private var lastPersist = Long.MIN_VALUE
    private val flushLock = Mutex()

    suspend fun collect(
        source: Flow<ChatDelta>,
        onSnapshot: suspend (ReplySnapshot, Boolean) -> Unit,
        onEvent: suspend (ChatDelta) -> Unit
    ): ReplySnapshot = coroutineScope {
        suspend fun flush(final: Boolean = false) = flushLock.withLock {
            val now = clock()
            val persist = final || lastPersist == Long.MIN_VALUE || now - lastPersist >= persistIntervalMillis
            if (revision == publishedRevision && (!persist || revision == persistedRevision)) return@withLock
            val snapshot = snapshot(now)
            val capturedRevision = revision
            onSnapshot(snapshot, persist)
            publishedRevision = capturedRevision
            if (persist) { persistedRevision = capturedRevision; lastPersist = now }
        }
        var timer: Job? = null
        fun scheduleFlush() {
            if (timer?.isActive == true) return
            timer = launch {
                var waited = 0L
                do {
                    delay(uiIntervalMillis)
                    waited += uiIntervalMillis
                    flush(final = waited >= persistIntervalMillis)
                    if (waited >= persistIntervalMillis) waited = 0L
                }
                while (revision != publishedRevision || revision != persistedRevision)
            }
        }
        try {
            source.buffer(64).collect { delta ->
                when (delta) {
                    is ChatDelta.Content -> { content.append(delta.text); revision++ }
                    is ChatDelta.Reasoning -> { recordReasoning(); reasoning.append(delta.text); revision++ }
                    is ChatDelta.FullResponse -> {
                        recordReasoning()
                        reasoning.append(delta.reasoningText); content.append(delta.contentText); revision++
                    }
                    else -> { flush(final = true); onEvent(delta) }
                }
                if (publishedRevision < 0 && revision > 0) flush()
                if (revision != publishedRevision || revision != persistedRevision) scheduleFlush()
            }
        } finally {
            withContext(NonCancellable) { timer?.cancelAndJoin(); flush(final = true) }
        }
        snapshot(clock())
    }

    private fun recordReasoning() {
        val now = clock()
        if (reasoningStarted == null) reasoningStarted = now
        reasoningUpdated = now
    }
    private fun snapshot(now: Long) = ReplySnapshot(content.toString(), reasoning.toString(),
        reasoningStarted?.let { ((reasoningUpdated ?: now) - it).coerceAtLeast(0) } ?: 0)
}
