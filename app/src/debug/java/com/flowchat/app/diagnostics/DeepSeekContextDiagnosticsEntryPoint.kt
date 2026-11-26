package com.flowchat.app.diagnostics

import com.flowchat.app.domain.repository.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only dependencies for local HTTP/Room/VM long-context diagnostics. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeepSeekContextDiagnosticsEntryPoint {
    fun profiles(): PromptProfileRepository
    fun memories(): MemoryRepository
    fun appUsage(): AppUsageReader
    fun launcher(): AppLauncher
}
