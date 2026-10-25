package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class ScreenImageRegionTest {
    @Test fun absentRegionKeepsFullScreenAndValidPercentBoundsRoundOutwards() {
        assertNull(ScreenImageRegion.fromToolArguments("{}"))
        val region = ScreenImageRegion.fromToolArguments("""{"image_region":{"left":10,"top":50,"right":90,"bottom":100}}""")!!
        assertEquals(DeviceBounds(12, 140, 114, 281), region.pixels(126, 281))
        assertEquals(DeviceBounds(0, 0, 1, 1), ScreenImageRegion(1, 1, 2, 2).pixels(1, 1))
    }

    @Test fun malformedOrOutOfBoundsRegionsCannotBecomeACapture() {
        for (arguments in listOf(
            """{"image_region":[]}""",
            """{"image_region":{"left":0,"top":0,"right":100}}""",
            """{"image_region":{"left":-1,"top":0,"right":100,"bottom":100}}""",
            """{"image_region":{"left":60,"top":0,"right":50,"bottom":100}}""",
            """{"image_region":{"left":0,"top":50,"right":100,"bottom":50}}""",
            """{"image_region":{"left":"0","top":0,"right":100,"bottom":100}}""")) {
            assertTrue(arguments, runCatching { ScreenImageRegion.fromToolArguments(arguments) }.isFailure)
        }
    }
}
