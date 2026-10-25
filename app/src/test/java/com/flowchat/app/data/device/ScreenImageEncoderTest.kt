package com.flowchat.app.data.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.flowchat.app.domain.device.ScreenImageRegion
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenImageEncoderTest {
    @Test fun screenshotEncodingPreservesRealPixelsAndLimitsDimensionsAndBytes() {
        val source = Bitmap.createBitmap(1440, 2560, Bitmap.Config.ARGB_8888)
        Canvas(source).drawColor(Color.RED)
        for (x in 200..265) source.setPixel(x, 200, if (x % 2 == 0) Color.BLACK else Color.WHITE)
        try {
            val frame = ScreenImageEncoder.encode(source, "screen", 123)
            assertEquals(123L, frame.capturedAt)
            assertEquals("screen", frame.screenId)
            assertEquals("image/png", frame.image.mimeType)
            assertEquals(source.width, frame.width)
            assertEquals(source.height, frame.height)
            assertTrue(maxOf(frame.width, frame.height) <= ScreenImageEncoder.MaxEdge)
            val bytes = Base64.getDecoder().decode(frame.image.base64)
            assertTrue(bytes.size <= ScreenImageEncoder.MaxBytes)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals(frame.width, decoded.width)
                assertEquals(frame.height, decoded.height)
                val pixel = decoded.getPixel(decoded.width / 2, decoded.height / 2)
                assertTrue(Color.red(pixel) > 230 && Color.blue(pixel) < 20)
                for (x in 200..265) assertEquals("One-pixel text strokes must survive", source.getPixel(x, 200), decoded.getPixel(x, 200))
                assertFalse(source.isRecycled)
            } finally { decoded.recycle() }
        } finally { source.recycle() }
    }

    @Test fun detailCropPreservesOriginalPixelsAndReportsItsSourceRegion() {
        val source = Bitmap.createBitmap(1260, 2800, Bitmap.Config.ARGB_8888)
        Canvas(source).drawColor(Color.WHITE)
        for (x in 300..365) source.setPixel(x, 2000, if (x % 2 == 0) Color.BLACK else Color.BLUE)
        val region = ScreenImageRegion(0, 50, 100, 100)
        try {
            val frame = ScreenImageEncoder.encode(source, "detail", 123, region)
            assertEquals(1260, frame.width); assertEquals(1400, frame.height)
            assertEquals(1260, frame.sourceWidth); assertEquals(2800, frame.sourceHeight)
            assertEquals(region, frame.region)
            val bytes = Base64.getDecoder().decode(frame.image.base64)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { for (x in 300..365) assertEquals(source.getPixel(x, 2000), decoded.getPixel(x, 600)) }
            finally { decoded.recycle() }
        } finally { source.recycle() }
    }

    @Test fun unusuallyLongScreensStayWithinThePayloadAndDimensionLimits() {
        val source = Bitmap.createBitmap(100, 4320, Bitmap.Config.ARGB_8888)
        Canvas(source).drawColor(Color.BLUE)
        try {
            val frame = ScreenImageEncoder.encode(source, "long", 1)
            assertEquals(3840, frame.height)
            assertTrue(frame.width <= source.width)
            assertTrue(Base64.getDecoder().decode(frame.image.base64).size <= ScreenImageEncoder.MaxBytes)
        } finally { source.recycle() }
    }
}
