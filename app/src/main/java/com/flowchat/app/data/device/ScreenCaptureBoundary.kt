package com.flowchat.app.data.device

import kotlinx.coroutines.withTimeoutOrNull

/** A missed screenshot callback is a read failure; cancellation of the caller still propagates. */
internal suspend fun <T : Any> captureWithinBoundary(timeoutMillis: Long = 3_000, capture: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { capture() } ?: throw ScreenCaptureFailure("screen_capture_timeout")
