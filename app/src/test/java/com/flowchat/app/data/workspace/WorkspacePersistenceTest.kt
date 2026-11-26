package com.flowchat.app.data.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.data.repository.RoomChatRepository
import com.flowchat.app.domain.workspace.WorkspaceLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspacePersistenceTest {
    @Test fun defaultsAreCopiedAtCreationAndExistingChatsKeepTheirBinding() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FlowChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            val first = WorkspaceLocation("content://test/tree/a", "A")
            val second = WorkspaceLocation("content://test/tree/b", "B")
            val settings = AppSettingsStore(AppSettings(defaultWorkspace = first))
            val repository = RoomChatRepository(database.conversationDao(), database.messageDao(), appSettingsStore = settings)
            val chatA = repository.createConversation("p", "model")
            settings.setDefaultWorkspace(second)
            val chatB = repository.createConversation("p", "model")
            assertEquals(first, repository.getConversation(chatA.id)!!.workspace)
            assertEquals(second, repository.getConversation(chatB.id)!!.workspace)
            repository.updateConversationWorkspace(chatA.id, null)
            assertNull(repository.getConversation(chatA.id)!!.workspace)
            assertEquals(second, repository.getConversation(chatB.id)!!.workspace)
            assertEquals(second, settings.state.value.defaultWorkspace)
        } finally { database.close() }
    }

    @Test fun defaultWorkspaceSurvivesSettingsStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("app_controls", Context.MODE_PRIVATE)
        val original = preferences.getString("default_workspace", null)
        try {
            val selected = WorkspaceLocation("content://test/tree/中文", "中文项目", "Documents/中文项目")
            AppSettingsStore(context).setDefaultWorkspace(selected)
            assertEquals(selected, AppSettingsStore(context).state.value.defaultWorkspace)
            AppSettingsStore(context).setDefaultWorkspace(null)
            assertNull(AppSettingsStore(context).state.value.defaultWorkspace)
        } finally { preferences.edit().putString("default_workspace", original).commit() }
    }
}
