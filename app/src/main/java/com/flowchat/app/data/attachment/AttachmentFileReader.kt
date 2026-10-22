package com.flowchat.app.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

object AttachmentFileReader {
    fun read(context: Context, uri: Uri): ExtractedAttachment {
        return try {
            val resolver = context.contentResolver
            val metadata = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                AttachmentMetadata(
                    name = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                    size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                )
            }
            if (metadata?.size != null && metadata.size > AttachmentDocumentParser.MaxSourceBytes) {
                throw AttachmentReadException(AttachmentReadFailure.TooLarge)
            }
            val name = metadata?.name?.takeIf { it.isNotBlank() } ?: "attachment"
            val mimeType = resolver.getType(uri).orEmpty()
            if (!ImageAttachmentStore.isImage(name, mimeType) && !AttachmentDocumentParser.isSupported(name, mimeType)) {
                throw AttachmentReadException(AttachmentReadFailure.UnsupportedType)
            }
            val bytes = resolver.openInputStream(uri)?.use(::readLimited)
                ?: throw AttachmentReadException(AttachmentReadFailure.ReadFailed)
            if (ImageAttachmentStore.isImage(name, mimeType)) return ImageAttachmentStore.save(context, name, bytes)
            val text = AttachmentDocumentParser.extract(name, mimeType, bytes) { pdfBytes ->
                extractPdfText(context, pdfBytes)
            }
            ExtractedAttachment(name = name, text = text)
        } catch (error: AttachmentReadException) {
            throw error
        } catch (error: Exception) {
            throw AttachmentReadException(AttachmentReadFailure.ReadFailed, error)
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > AttachmentDocumentParser.MaxSourceBytes) {
                throw AttachmentReadException(AttachmentReadFailure.TooLarge)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun extractPdfText(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(bytes).use { document ->
            PDFTextStripper().getText(document)
        }
    }

    private data class AttachmentMetadata(
        val name: String?,
        val size: Long?
    )
}
