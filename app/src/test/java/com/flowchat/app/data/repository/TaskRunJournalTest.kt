package com.flowchat.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.task.TaskRunJournal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskRunJournalTest {
    @Test fun reopeningRecoversOnlyUnfinishedRunsAndNeverRestartsThem() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "task-journal-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, FlowChatDatabase::class.java, name).allowMainThreadQueries().build()
        var database = open()
        try {
            val conversation = RoomChatRepository(database.conversationDao(), database.messageDao()).createConversation("p", "model")
            val journal = TaskRunJournal(database.taskRunDao())
            journal.recoverInterrupted()
            journal.begin("completed", conversation.id, "Finished instruction", "model")
            journal.state("completed", "Completed")
            journal.state("completed", "Running")
            assertEquals("Completed", journal.get("completed")!!.state)
            journal.begin("pending", conversation.id, "Unfinished instruction", "model")
            journal.attachMessage("pending", "assistant-evidence")
            journal.state("pending", "WaitingForUser")
            journal.recoverInterrupted()
            assertEquals("WaitingForUser", journal.get("pending")!!.state)
            database.close()
            database = open()
            val recovered = TaskRunJournal(database.taskRunDao())
            recovered.recoverInterrupted()
            assertEquals("Interrupted", recovered.get("pending")!!.state)
            assertEquals("assistant-evidence", recovered.get("pending")!!.assistantMessageId)
            assertEquals("Unfinished instruction", recovered.get("pending")!!.instruction)
            assertEquals("Completed", recovered.get("completed")!!.state)
            recovered.state("pending", "Running")
            assertEquals("Interrupted", recovered.get("pending")!!.state)
            RoomChatRepository(database.conversationDao(), database.messageDao()).deleteConversation(conversation.id)
            assertNull(recovered.get("pending"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
