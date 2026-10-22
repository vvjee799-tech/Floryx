package com.flowchat.app.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import com.flowchat.app.domain.model.ChatImage
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ImageAttachmentStore @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun loadRecent(messages: List<Message>): Map<String, ChatImage> = withContext(Dispatchers.IO) {
        messages.filter { it.status !in setOf(MessageStatus.Failed, MessageStatus.Stopped) }
            .filter { it.role == MessageRole.User }.takeLast(5)
            .mapNotNull { it.attachmentImagePath }.distinct().associateWith { path ->
                val file = resolve(context, path)
                check(file.isFile && file.length() in 1L..MaxImageBytes.toLong()) { "Image attachment unavailable. Please attach the image again." }
                val mime = if (file.extension == "png") "image/png" else "image/jpeg"
                ChatImage(mime, Base64.getEncoder().encodeToString(file.readBytes()))
            }
    }

    suspend fun discard(path: String) = withContext(Dispatchers.IO) { runCatching { resolve(context, path).delete() }; Unit }

    fun discardNow(path: String) { runCatching { resolve(context, path).delete() } }

    companion object {
        const val MaxImageBytes = 2 * 1024 * 1024
        const val MaxImageEdge = 2048
        private const val Directory = "attachments/images"

        fun resolve(context: Context, path: String): File {
            val root = File(context.filesDir, Directory).canonicalFile
            val target = File(context.filesDir, path).canonicalFile
            require(target.parentFile == root) { "Image attachment unavailable." }
            return target
        }

        fun isImage(name: String, mimeType: String): Boolean = mimeType.startsWith("image/", true) ||
            name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif")

        fun save(context: Context, name: String, bytes: ByteArray): ExtractedAttachment {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AttachmentReadException(AttachmentReadFailure.InvalidImage)
            if (bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) throw AttachmentReadException(AttachmentReadFailure.ImageDimensionsTooLarge)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MaxImageEdge * 2) sample *= 2
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw AttachmentReadException(AttachmentReadFailure.InvalidImage)
            try {
                val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                    }
                }
                fun replace(next: Bitmap) { if (next !== bitmap) { bitmap.recycle(); bitmap = next } }
                if (!matrix.isIdentity) replace(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true))
                val scale = minOf(1f, MaxImageEdge.toFloat() / maxOf(bitmap.width, bitmap.height))
                if (scale < 1f) replace(Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true))
                val png = if (bitmap.hasAlpha()) ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() else null
                val usePng = png != null && png.size <= MaxImageBytes
                val encoded = if (usePng) requireNotNull(png) else {
                    if (bitmap.hasAlpha()) {
                        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                        replace(opaque)
                    }
                    var output = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                    if (output.size > MaxImageBytes) output = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                    if (output.size > MaxImageBytes) throw AttachmentReadException(AttachmentReadFailure.ImageDimensionsTooLarge)
                    output
                }
                val extension = if (usePng) "png" else "jpg"
                val path = "$Directory/${UUID.randomUUID()}.$extension"
                val file = resolve(context, path)
                file.parentFile?.mkdirs()
                try { file.writeBytes(encoded) } catch (error: Exception) { file.delete(); throw error }
                return ExtractedAttachment(name = name, imagePath = path, mimeType = if (usePng) "image/png" else "image/jpeg")
            } finally { bitmap.recycle() }
        }
    }
}
