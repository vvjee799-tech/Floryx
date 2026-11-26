package com.flowchat.app.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.attachment.*
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ImageAttachmentStoreDeviceTest {
    @Test fun normalizesImagesAndRejectsBadInputs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runAttachmentDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stored = mutableListOf<File>()
        fun save(name: String, bytes: ByteArray): Bitmap {
            val image = ImageAttachmentStore.save(context, name, bytes)
            assertEquals("", image.text)
            val file = ImageAttachmentStore.resolve(context, requireNotNull(image.imagePath))
            stored += file
            assertTrue(file.length() <= ImageAttachmentStore.MaxImageBytes)
            return requireNotNull(BitmapFactory.decodeFile(file.path))
        }
        fun bytes(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray = ByteArrayOutputStream().use {
            bitmap.compress(format, 90, it); it.toByteArray()
        }
        val source = Bitmap.createBitmap(4200, 2800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val orientationFile = File(context.cacheDir, "image-qa-orientation.jpg")
        try {
            save("large.png", bytes(source, Bitmap.CompressFormat.PNG)).let { image ->
                assertEquals(2048, image.width); assertTrue(image.height < 2048); image.recycle()
            }
            val small = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try {
                save("sample.webp", bytes(small, Bitmap.CompressFormat.WEBP)).let {
                    assertEquals(80, it.width); assertEquals(40, it.height); it.recycle()
                }
                orientationFile.writeBytes(bytes(small, Bitmap.CompressFormat.JPEG))
                ExifInterface(orientationFile.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
                }
                save("rotated.jpg", orientationFile.readBytes()).let {
                    assertEquals(40, it.width); assertEquals(80, it.height); it.recycle()
                }
                small.eraseColor(Color.TRANSPARENT)
                save("transparent.png", bytes(small, Bitmap.CompressFormat.PNG)).let {
                    assertEquals(0, Color.alpha(it.getPixel(0, 0))); it.recycle()
                }
            } finally { small.recycle() }
            val error = runCatching { ImageAttachmentStore.save(context, "bad.png", byteArrayOf(1, 2, 3)) }.exceptionOrNull()
            assertEquals(AttachmentReadFailure.InvalidImage, (error as? AttachmentReadException)?.failure)
            assertTrue(runCatching { ImageAttachmentStore.resolve(context, "../escaped.jpg") }.isFailure)
        } finally {
            source.recycle(); stored.forEach { it.delete() }; orientationFile.delete()
        }
    }
}
