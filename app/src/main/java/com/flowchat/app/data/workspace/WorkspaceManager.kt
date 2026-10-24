package com.flowchat.app.data.workspace

import android.content.Context
import android.net.Uri
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.workspace.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class WorkspaceBatch(val saved: List<WorkspaceSave> = emptyList(), val errors: List<String> = emptyList())

@Singleton
class WorkspaceManager @Inject constructor(@ApplicationContext context: Context) {
    private val access = AndroidWorkspaceFileAccess(context)
    private val engine = WorkspaceEngine(access, File(context.filesDir, "terminal/workspaces"), File(context.filesDir, "workspace-transfers"))
    private val mutex = Mutex()
    private val epoch = MutableStateFlow(0L)
    val changes = epoch.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun <T> io(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { try { block() } finally { epoch.value++ } }
    }
    suspend fun select(uri: String, flags: Int): WorkspaceLocation = io { access.select(Uri.parse(uri), flags) }
    suspend fun inspect(owner: String?, location: WorkspaceLocation?): WorkspaceUiState = mutex.withLock {
        withContext(Dispatchers.IO) { WorkspaceUiState(location, location?.let(engine::status) ?: WorkspaceStatus(),
            pending = owner?.let(engine::pending).orEmpty()) }
    }
    fun refresh() { epoch.value++ }
    suspend fun executionDirectory(owner: String, location: WorkspaceLocation): String = io { engine.executionDirectory(owner, location) }
    suspend fun prepareCommand(owner: String, location: WorkspaceLocation, inputs: List<String>, outputs: List<String>) = io {
        if (inputs.isNotEmpty()) engine.importFiles(owner, location, inputs)
        engine.prepareOutputs(owner, location, outputs)
    }
    suspend fun publish(owner: String, location: WorkspaceLocation, paths: List<String>): WorkspaceBatch = io {
        val saved = mutableListOf<WorkspaceSave>()
        val errors = mutableListOf<String>()
        try { engine.validatePublishBatch(owner, location, paths) }
        catch (error: Exception) { return@io WorkspaceBatch(errors = listOf(error.message ?: "Could not prepare output files.")) }
        for (path in paths.distinct()) try { saved += engine.publishFiles(owner, location, listOf(path)) }
        catch (error: Exception) { errors += "$path: ${error.message}" }
        WorkspaceBatch(saved, errors)
    }
    suspend fun retry(owner: String, localPath: String): WorkspaceSave = io { engine.publishPending(owner, localPath) }

    suspend fun execute(call: ChatToolCall, owner: String, location: WorkspaceLocation): String = io {
        val args = json.parseToJsonElement(call.arguments).jsonObject
        fun string(key: String) = args[key]?.jsonPrimitive?.contentOrNull
        fun required(key: String) = requireNotNull(string(key)) { "$key is required." }
        when (call.name) {
            WorkspaceToolDefinitions.Status -> buildJsonObject {
                put("name", location.name); put("access", engine.status(location).access.name)
                put("linux_directory", engine.executionDirectory(owner, location))
                put("pending_outputs", buildJsonArray { engine.pending(owner).forEach { item -> add(buildJsonObject {
                    put("path", item.path); put("workspace", item.location.name); item.error?.let { put("error", it) }
                }) } })
            }
            WorkspaceToolDefinitions.ListFiles -> {
                val page = engine.list(location, string("path").orEmpty(), string("offset")?.toIntOrNull() ?: 0)
                buildJsonObject {
                    put("files", buildJsonArray { page.files.forEach { file -> add(buildJsonObject {
                        put("path", file.path); put("directory", file.directory); file.size?.let { put("bytes", it) }
                    }) } }); page.nextOffset?.let { put("next_offset", it) }
                }
            }
            WorkspaceToolDefinitions.Read -> engine.read(location, required("path")).let { read -> buildJsonObject {
                val offset = string("offset")?.toIntOrNull() ?: 0
                require(offset in 0..read.text.length) { "Invalid text offset." }
                val end = minOf(offset + 12000, read.text.length)
                put("text", read.text.substring(offset, end)); put("revision", read.revision); put("bytes", read.bytes)
                if (end < read.text.length) put("next_offset", end)
            } }
            WorkspaceToolDefinitions.Write -> saveJson(engine.write(owner, location, required("path"), required("text"), string("expected_revision")))
            WorkspaceToolDefinitions.Rename -> buildJsonObject {
                put("path", engine.rename(location, required("path"), required("new_name"), required("expected_revision"))); put("renamed", true)
            }
            WorkspaceToolDefinitions.Import -> {
                val paths = argumentPaths(args, "paths")
                buildJsonObject {
                    put("imported", buildJsonArray { engine.importFiles(owner, location, paths).forEach { add(it) } })
                    put("linux_directory", engine.executionDirectory(owner, location))
                }
            }
            WorkspaceToolDefinitions.Publish -> {
                val paths = argumentPaths(args, "paths")
                require(paths.isNotEmpty()) { "paths is required." }
                buildJsonObject { put("workspace_saved", buildJsonArray { engine.publishFiles(owner, location, paths).forEach { add(saveJson(it)) } }) }
            }
            else -> error("Unsupported workspace tool.")
        }.toString()
    }

    companion object {
        fun argumentPaths(args: JsonObject, name: String): List<String> {
            val element = args[name] ?: return emptyList()
            val paths = element.jsonArray.map { it.jsonPrimitive.content }
            require(paths.size in 1..32) { "Choose between 1 and 32 files." }
            return paths.map { WorkspacePaths.relative(it) }.distinct()
        }
        fun saveJson(save: WorkspaceSave) = buildJsonObject {
            put("path", save.path); put("revision", save.revision); put("saved", true); put("conflict_copy", save.conflict)
            save.workspaceName?.let { put("workspace", it) }
        }
    }
}
