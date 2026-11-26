package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.model.ChatDelta
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamingReplyWriterTest {
    @Test fun batchesFastFragmentsWithoutLosingFinalTextOrReasoning() = runTest {
        val published = mutableListOf<ReplySnapshot>()
        val saved = mutableListOf<ReplySnapshot>()
        val writer = StreamingReplyWriter("", "", clock = { testScheduler.currentTime })
        val result = writer.collect(flow {
            repeat(200) { emit(ChatDelta.Reasoning("思")); delay(5) }
            repeat(200) { emit(ChatDelta.Content("文")); delay(5) }
            emit(ChatDelta.Done)
        }, { value, persist -> published += value; if (persist) saved += value }, {})
        assertEquals("文".repeat(200), result.content)
        assertEquals("思".repeat(200), result.reasoning)
        assertEquals(result, saved.last())
        println("400 fragments: ${published.size} UI snapshots, ${saved.size} database writes")
        assertTrue("writes=${saved.size}", saved.size <= 9)
        assertTrue("updates=${published.size}", published.size < 60)
        assertTrue(published.zipWithNext().all { (a, b) -> a.content.length <= b.content.length })
    }

    @Test fun cancellationFlushesTheTrailingFragmentBeforeReturning() = runTest {
        val saved = mutableListOf<String>()
        val writer = StreamingReplyWriter("", "", clock = { testScheduler.currentTime })
        val job = launch {
            writer.collect(flow {
                emit(ChatDelta.Content("first")); delay(10)
                emit(ChatDelta.Content("-tail")); awaitCancellation()
            }, { value, persist -> if (persist) saved += value.content }, {})
        }
        advanceTimeBy(20); runCurrent()
        job.cancelAndJoin()
        assertEquals("first-tail", saved.last())
        advanceTimeBy(1_000)
        assertEquals("first-tail", saved.last())
    }

    @Test fun partialFailurePreservesEvidenceAndPassesTheErrorThrough() = runTest {
        var event: ChatDelta? = null
        var saved = ""
        StreamingReplyWriter("prefix", "").collect(flow {
            emit(ChatDelta.Content("-partial"))
            emit(ChatDelta.Error("offline"))
        }, { value, persist -> if (persist) saved = value.content }, { event = it })
        assertEquals("prefix-partial", saved)
        assertEquals(ChatDelta.Error("offline"), event)
    }
}
