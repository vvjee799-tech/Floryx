package com.flowchat.app.presentation

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SplashTransitionContractTest {
    @Test
    fun animationIsClampedOrderedAndCompletesAtItsEndpoints() {
        assertEquals(1_100f, BloomSplashDurationMillis.toFloat(), 0f)

        val start = bloomSplashFrame(0f)
        assertTrue("The flower needs multiple independently opening petals", start.petalProgress.size > 1)
        assertEquals(0f, start.seedProgress, 0f)
        assertTrue(start.petalProgress.all { it == 0f })
        assertEquals(0f, start.wordmarkProgress, 0f)
        assertEquals(0f, start.taglineProgress, 0f)
        assertEquals(1f, start.splashAlpha, 0f)
        assertFrameEquals(start, bloomSplashFrame(-100f))

        val blooming = bloomSplashFrame(200f)
        assertEquals(1f, blooming.seedProgress, 0.001f)
        assertTrue("Petals should open in sequence", blooming.petalProgress.first() > blooming.petalProgress.last())
        assertEquals(0f, blooming.wordmarkProgress, 0f)
        assertEquals(0f, blooming.taglineProgress, 0f)

        val lettering = bloomSplashFrame(500f)
        assertTrue(lettering.petalProgress.all { it >= 0.999f })
        assertTrue(lettering.wordmarkProgress > 0f && lettering.wordmarkProgress < 1f)
        assertEquals(0f, lettering.taglineProgress, 0f)

        val fullBrand = bloomSplashFrame(740f)
        assertEquals(1f, fullBrand.seedProgress, 0.001f)
        assertTrue(fullBrand.petalProgress.all { it >= 0.999f })
        assertEquals(1f, fullBrand.wordmarkProgress, 0.001f)
        assertEquals(1f, fullBrand.taglineProgress, 0.001f)
        assertEquals(1f, fullBrand.splashAlpha, 0f)
        assertEquals(1f, bloomSplashFrame(880f).splashAlpha, 0.001f)
        assertTrue(bloomSplashFrame(990f).splashAlpha in 0.001f..0.999f)

        val end = bloomSplashFrame(BloomSplashDurationMillis.toFloat())
        assertEquals(0f, end.splashAlpha, 0f)
        assertFrameEquals(end, bloomSplashFrame(BloomSplashDurationMillis.toFloat() + 100f))

        var previous = start
        for (millis in 10..1_100 step 10) {
            val current = bloomSplashFrame(millis.toFloat())
            val allProgress = listOf(current.seedProgress, current.wordmarkProgress, current.taglineProgress, current.splashAlpha) + current.petalProgress
            assertTrue("All values must stay finite and between zero and one at ${millis}ms", allProgress.all { it.isFinite() && it in 0f..1f })
            assertTrue(current.seedProgress >= previous.seedProgress)
            assertTrue(current.wordmarkProgress >= previous.wordmarkProgress)
            assertTrue(current.taglineProgress >= previous.taglineProgress)
            assertTrue(current.splashAlpha <= previous.splashAlpha)
            assertEquals(previous.petalProgress.size, current.petalProgress.size)
            current.petalProgress.zip(previous.petalProgress).forEach { (now, before) -> assertTrue(now >= before) }
            previous = current
        }
    }

    @Test
    fun nativeLaunchUsesVisibleBrandAndMatchingThemeBackgrounds() {
        for (directory in listOf("values-v31", "values-night-v31", "values-v33", "values-night-v33")) {
            val style = xml("$directory/styles.xml").getElementsByTagName("style").item(0) as Element
            val items = style.getElementsByTagName("item")
            fun item(name: String): String = (0 until items.length)
                .map { items.item(it) as Element }
                .single { it.getAttribute("name") == name }
                .textContent.trim()
            assertEquals("@drawable/ic_splash_mark", item("android:windowSplashScreenAnimatedIcon"))
            assertEquals("@color/splash_background", item("android:windowSplashScreenBackground"))
            if (directory.endsWith("v33")) assertEquals("icon_preferred", item("android:windowSplashScreenBehavior"))
        }

        val paths = xml("drawable/ic_splash_mark.xml").getElementsByTagName("path")
        assertTrue("The native window must show a recognizable mark while the process starts", paths.length > 0)
        assertEquals("@color/splash_mark", (paths.item(0) as Element)
            .getAttributeNS("http://schemas.android.com/apk/res/android", "fillColor"))
        assertEquals("#F9F7F2", splashColor("values"))
        assertEquals("#24231F", splashColor("values-night"))
    }

    private fun assertFrameEquals(expected: BloomSplashFrame, actual: BloomSplashFrame) {
        assertEquals(expected.seedProgress, actual.seedProgress, 0f)
        assertEquals(expected.wordmarkProgress, actual.wordmarkProgress, 0f)
        assertEquals(expected.taglineProgress, actual.taglineProgress, 0f)
        assertEquals(expected.splashAlpha, actual.splashAlpha, 0f)
        assertEquals(expected.petalProgress, actual.petalProgress)
    }

    private fun splashColor(directory: String): String {
        val colors = xml("$directory/splash_colors.xml").getElementsByTagName("color")
        return (0 until colors.length).map { colors.item(it) as Element }
            .single { it.getAttribute("name") == "splash_background" }
            .textContent.trim().uppercase(Locale.ROOT)
    }

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File("src/main/res/$path"))
}
