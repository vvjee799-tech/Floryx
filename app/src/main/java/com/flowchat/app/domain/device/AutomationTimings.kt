package com.flowchat.app.domain.device

/** Bounded, process-local diagnostics. Never records screen text, images, arguments or credentials. */
object AutomationTimings {
    data class Measurement(val stage: String, val startedNanos: Long, val durationNanos: Long, val completed: Boolean) {
        val durationMillis: Double get() = durationNanos / 1_000_000.0
    }

    private const val Capacity = 512
    private val measurements = ArrayDeque<Measurement>()

    suspend fun <T> measure(stage: String, action: suspend () -> T): T {
        val started = System.nanoTime()
        var completed = false
        return try { action().also { completed = true } }
        finally { record(Measurement(stage, started, System.nanoTime() - started, completed)) }
    }

    @Synchronized private fun record(measurement: Measurement) {
        if (measurements.size == Capacity) measurements.removeFirst()
        measurements.addLast(measurement)
    }

    @Synchronized fun snapshot(): List<Measurement> = measurements.toList()
    @Synchronized fun clear() = measurements.clear()
}
