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
    val region: ScreenImageRegion? = null,
    val geometry: DeviceScreenGeometry? = null,
    /** Full source bitmap bounds in display coordinates, before cropping/resizing. */
    val captureBounds: DeviceBounds? = null,
    /** Coarse pixel fingerprint for in-memory progress tracking, not screenshot storage. */
    val visualKey: String? = null
) {
    override fun toString(): String = "DeviceScreenImage(screenId=$screenId, width=$width, height=$height)"
}
