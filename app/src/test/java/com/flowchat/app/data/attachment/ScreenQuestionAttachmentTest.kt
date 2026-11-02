package com.flowchat.app.data.attachment

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.domain.model.ChatImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenQuestionAttachmentTest {
    @Test fun screenshotKeepsOriginalPngBytesAndFullResolutionThroughMessageReload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ImageAttachmentStore(context)
        val bitmap = Bitmap.createBitmap(1260, 2800, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.WHITE)
            for (x in 200..280) bitmap.setPixel(x, 200, if (x % 2 == 0) Color.BLACK else Color.WHITE)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
        val image = ChatImage("image/png", Base64.getEncoder().encodeToString(bytes))
        val saved = store.saveScreenshot(image)
        try {
            assertEquals("image/png", saved.mimeType)
            assertArrayEquals(bytes, ImageAttachmentStore.resolve(context, saved.imagePath!!).readBytes())
            val message = com.flowchat.app.domain.model.Message(conversationId = "qa", role = com.flowchat.app.domain.model.MessageRole.User,
                content = "Answer", attachmentImagePath = saved.imagePath, attachmentMimeType = saved.mimeType)
            assertEquals(image, store.loadRecent(listOf(message))[saved.imagePath])
        } finally { store.discard(saved.imagePath!!) }
    }
}
