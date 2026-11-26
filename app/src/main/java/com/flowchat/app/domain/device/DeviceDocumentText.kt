package com.flowchat.app.domain.device

/** Text evidence only. Offscreen text never receives an action index or a coordinate. */
data class DeviceDocumentText(
    val rootPath: String?,
    val text: String,
    val treeComplete: Boolean,
    val truncated: Boolean,
    val offscreenTextNodes: Int,
    val totalChars: Long,
    val errorCode: String? = null
) {
    fun asToolContent(): String = buildString {
        append("document_source=exposed_accessibility_webview_tree")
        append("\ndocument_root_path=").append(rootPath ?: "unknown")
        append("\ndocument_tree_complete=").append(treeComplete)
        append("\ndocument_text_truncated=").append(truncated)
        append("\ndocument_read_range_utf16=0:").append(text.length)
        append("\ndocument_exposed_total_utf16=").append(totalChars)
        append("\ndocument_offscreen_text_nodes=").append(offscreenTextNodes)
        errorCode?.let { append("\ndocument_error_code=").append(it) }
        if (errorCode == null) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            append("\ndocument_text_sha256=").append(hash)
            append("\nText evidence only, without action indices. Complete means the exposed tree was scanned, not that network loading or the whole article is complete. Check actual body coverage and its ending; images are not included.")
            append("\n<untrusted_document_text>\n")
            append(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            append("\n</untrusted_document_text>")
        }
    }
}
