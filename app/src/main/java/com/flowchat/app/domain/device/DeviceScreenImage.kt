package com.flowchat.app.domain.device

import com.flowchat.app.domain.model.ChatImage

data class DeviceScreenImage(
    val image: ChatImage,
    val screenId: String,
    val width: Int,
    val height: Int,
    val capturedAt: Long = System.currentTimeMillis(),
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
    val region: ScreenImageRegion? = null
) {
    override fun toString(): String = "DeviceScreenImage(screenId=$screenId, width=$width, height=$height)"
}
