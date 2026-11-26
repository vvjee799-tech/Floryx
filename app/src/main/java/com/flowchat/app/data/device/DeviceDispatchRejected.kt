package com.flowchat.app.data.device

/** Raised only before a writing action is dispatched. Never use for a lost acknowledgement. */
internal class DeviceDispatchRejected(val code: String) : IllegalStateException(code)
