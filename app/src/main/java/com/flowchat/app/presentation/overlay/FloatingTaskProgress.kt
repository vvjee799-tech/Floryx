package com.flowchat.app.presentation.overlay

/** Actual task phase, independent of the assistant's last piece of prose. */
enum class FloatingTaskPhase {
    Ready, Thinking, Responding, PreparingCommand, RunningCommand, UsingTool,
    WaitingForUser, Paused, Finished, Stopped, Failed;

    val isActive: Boolean get() = this in setOf(Thinking, Responding, PreparingCommand, RunningCommand, UsingTool)
}

data class FloatingTaskProgress(
    val phase: FloatingTaskPhase,
    val startedAt: Long? = null
)
