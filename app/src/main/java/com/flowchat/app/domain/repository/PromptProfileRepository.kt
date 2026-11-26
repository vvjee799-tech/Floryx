package com.flowchat.app.domain.repository

import com.flowchat.app.domain.prompt.PromptProfileConfig

interface PromptProfileRepository {
    suspend fun getActiveProfile(): PromptProfileConfig
    suspend fun getActiveThinkingFormat(): String?
}
