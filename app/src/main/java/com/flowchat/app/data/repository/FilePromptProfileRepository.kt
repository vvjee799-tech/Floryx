package com.flowchat.app.data.repository

import android.content.Context
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.prompt.PromptProfileParser
import com.flowchat.app.domain.prompt.PromptProfileSource
import com.flowchat.app.domain.repository.PromptProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class FilePromptProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : PromptProfileRepository {
    override suspend fun getActiveProfile(): PromptProfileConfig = withContext(Dispatchers.IO) {
        PromptProfileParser.selectActiveProfile(readPrivateProfiles() + readBundledProfiles())
    }

    override suspend fun getActiveThinkingFormat(): String? = getActiveProfile().thinkingFormat

    private fun readPrivateProfiles(): List<PromptProfileSource> {
        val directory = File(context.filesDir, ProfilesDirectory)
        val files = directory
            .listFiles { file -> file.isFile && file.extension.equals(TomlExtension, ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

        return files.mapNotNull { file ->
            runCatching {
                PromptProfileSource(
                    name = "private:${file.name}",
                    content = file.readText(Charsets.UTF_8)
                )
            }.getOrNull()
        }
    }

    private fun readBundledProfiles(): List<PromptProfileSource> =
        runCatching {
            context.assets.list(ProfilesDirectory)
                .orEmpty()
                .filter { it.endsWith(".$TomlExtension", ignoreCase = true) }
                .sorted()
                .mapNotNull { assetName ->
                    runCatching {
                        context.assets.open("$ProfilesDirectory/$assetName").bufferedReader(Charsets.UTF_8).use { reader ->
                            PromptProfileSource(
                                name = "asset:$assetName",
                                content = reader.readText()
                            )
                        }
                    }.getOrNull()
                }
        }.getOrDefault(emptyList())

    private companion object {
        const val ProfilesDirectory = "profiles"
        const val TomlExtension = "toml"
    }
}
