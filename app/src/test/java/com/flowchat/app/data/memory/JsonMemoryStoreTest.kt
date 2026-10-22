package com.flowchat.app.data.memory

import com.flowchat.app.domain.model.MemoryDraft
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class JsonMemoryStoreTest {
    private fun store(limit: Int = 80) = JsonMemoryStore(File.createTempFile("flowchat-memory", ".json").apply { deleteOnExit() }, limit)
    private fun preference(summary: String = "用户希望默认用中文交流。") = MemoryDraft("preference.response_language", "preference", summary)

    @Test fun savesSummaryAndUpdatesExistingFactWithoutAnotherRow() {
        val store = store()
        assertTrue(store.merge(listOf(preference()), store.snapshot().revision, 100))
        val id = store.readAll().single().id
        assertTrue(store.merge(listOf(preference("用户希望默认用英文交流。")), store.snapshot().revision, 200))
        assertEquals(1, store.readAll().size)
        assertEquals(id, store.readAll().single().id)
        assertEquals("用户希望默认用英文交流。", store.readAll().single().summary)
    }

    @Test fun staleExtractionCannotResurrectClearedOrDeletedMemories() {
        val store = store()
        store.merge(listOf(preference()), store.snapshot().revision, 100)
        val pending = store.snapshot()
        store.clear()
        assertFalse(store.merge(listOf(preference()), pending.revision, 200))
        assertTrue(store.readAll().isEmpty())
        store.merge(listOf(preference()), store.snapshot().revision, 300)
        val beforeDelete = store.snapshot()
        store.delete(beforeDelete.records.single().id)
        assertFalse(store.merge(listOf(preference()), beforeDelete.revision, 400))
        assertTrue(store.readAll().isEmpty())
    }

    @Test fun toggleGuardPreventsPendingWriteAndEditingWinsOverLateExtraction() {
        val store = store()
        assertFalse(store.merge(listOf(preference()), store.snapshot().revision, 100) { false })
        assertTrue(store.readAll().isEmpty())
        store.merge(listOf(preference()), store.snapshot().revision, 200)
        val snapshot = store.snapshot()
        store.update(snapshot.records.single().id, "用户希望默认用中英双语交流。")
        assertFalse(store.merge(listOf(preference()), snapshot.revision, 300))
        assertEquals("用户希望默认用中英双语交流。", store.readAll().single().summary)
    }

    @Test fun retrievalUsesSummaryTextAndSkipsUnrelatedProjects() {
        val store = store()
        store.merge(listOf(preference(), MemoryDraft("project.flowchat", "project", "用户正在开发 FlowChat 安卓应用。")), store.snapshot().revision, 100)
        assertEquals(1, store.retrieve("推荐一本书", 5).size)
        assertEquals(2, store.retrieve("FlowChat 界面开发", 5).size)
    }

    @Test fun rejectsCredentialsRawDialogAndInvalidCategories() {
        val store = store()
        assertFalse(store.merge(listOf(preference("我的密码是 secret-password"), preference("用户：你好 / 助手：你好"),
            MemoryDraft("action.swipe", "action", "打开抖音刷几个视频。")), store.snapshot().revision, 100))
        assertTrue(store.readAll().isEmpty())
    }

    @Test fun retentionDeleteAndClearOperateOnSummaries() {
        val store = store(2)
        (1..3).forEach { i -> store.merge(listOf(MemoryDraft("project.item$i", "project", "用户持续维护项目 item$i。")), store.snapshot().revision, i.toLong()) }
        assertEquals(2, store.readAll().size)
        store.delete(store.readAll().first().id)
        assertEquals(1, store.readAll().size)
        store.clear()
        assertTrue(store.readAll().isEmpty())
    }

    @Test fun migratesOnlyDurableUserFactsAndDiscardsAssistantCopies() {
        val file = File.createTempFile("flowchat-legacy", ".json").apply { deleteOnExit() }
        file.writeText("""[
            {"id":"one","goal":"以后默认用中文回复。","summary":"用户：以后默认用中文回复 / 助手：一个冗长的回答","quality":"high","timestamp":100},
            {"id":"two","goal":"打开抖音刷三个视频。","summary":"用户：打开抖音 / 助手：已完成","quality":"high","timestamp":200}
        ]""")
        val records = JsonMemoryStore(file).readAll()
        assertEquals(1, records.size)
        assertEquals("以后默认用中文回复。", records.single().summary)
        assertEquals(2, records.single().formatVersion)
        assertFalse(file.readText().contains("助手："))
        assertEquals(records, JsonMemoryStore(file).readAll())
    }
}
