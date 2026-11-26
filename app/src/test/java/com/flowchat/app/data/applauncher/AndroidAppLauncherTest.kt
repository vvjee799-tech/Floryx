package com.flowchat.app.data.applauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppLauncherTest {
    private val browsers = listOf(LaunchableApp("浏览器", "com.vivo.browser", "Vivo"),
        LaunchableApp("Chrome", "com.android.chrome", "Chrome"))

    @Test fun genericBrowserUsesTheAndroidDefaultRatherThanAnExactVendorLabel() {
        for (query in listOf("浏览器", "browser", "Web Browser"))
            assertEquals("com.android.chrome", selectLaunchableApp(browsers, query, "com.android.chrome").packageName)
    }

    @Test fun explicitlyNamedBrowserRemainsExplicitAndMissingDefaultDoesNotGuess() {
        assertEquals("com.vivo.browser", selectLaunchableApp(browsers, "com.vivo.browser", "com.android.chrome").packageName)
        assertEquals("com.android.chrome", selectLaunchableApp(browsers, "Chrome").packageName)
        assertThrows(IllegalArgumentException::class.java) { selectLaunchableApp(browsers, "浏览器") }
        assertThrows(IllegalArgumentException::class.java) { selectLaunchableApp(browsers, "浏览器", "not.installed") }
    }
    private val apps = listOf(
        LaunchableApp("微信", "com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),
        LaunchableApp("DeepSeek", "com.deepseek.chat", "com.deepseek.chat.MainActivity"),
        LaunchableApp("FlowChat", "com.flowchat.app", "com.flowchat.app.MainActivity"),
        LaunchableApp("ChatGPT", "com.openai.chatgpt", "com.openai.chatgpt.MainActivity")
    )

    @Test
    fun takesOnlyTheQuotedObjectOfAnOpeningInstruction() {
        assertEquals("日历", quotedAppName("请打开「日历」，找到『明天』的安排。"))
        assertEquals("微信", quotedAppName("请帮我启动『微信』应用"))
        assertEquals("com.deepseek.chat", quotedAppName("Please open \"com.deepseek.chat\" and show the chat list."))
        assertNull(quotedAppName("在『日历』里，打开『明天』。"))
        assertNull(quotedAppName("请不要打开「微信」"))
        assertNull(quotedAppName("请打开「日历"))
    }

    @Test
    fun verifiesOnlyExactNamesThatTheLauncherCanResolveUniquely() {
        assertTrue(matchesExactLaunchableApp(apps, "deepseek"))
        assertTrue(matchesExactLaunchableApp(apps, "com.tencent.mm"))
        assertFalse(matchesExactLaunchableApp(apps, "Deep"))
        assertFalse(matchesExactLaunchableApp(apps, "Deep Seek"))
        assertFalse(matchesExactLaunchableApp(apps, "日历"))
        assertFalse(matchesExactLaunchableApp(apps + LaunchableApp("微信", "com.example.work", "Main"), "微信"))
        assertFalse(matchesExactLaunchableApp(browsers, "浏览器"))
    }

    @Test
    fun selectsExactLocalizedLabelBeforePartialMatches() {
        assertEquals("com.tencent.mm", selectLaunchableApp(apps, "微信").packageName)
    }

    @Test
    fun selectsExactPackageName() {
        assertEquals("DeepSeek", selectLaunchableApp(apps, "com.deepseek.chat").label)
    }

    @Test
    fun selectsOneUniquePartialLabelIgnoringCaseAndSpaces() {
        assertEquals("DeepSeek", selectLaunchableApp(apps, "deep seek").label)
    }

    @Test
    fun rejectsAmbiguousPartialMatches() {
        assertThrows(IllegalArgumentException::class.java) {
            selectLaunchableApp(apps, "chat")
        }
    }

    @Test
    fun rejectsMissingApps() {
        assertThrows(IllegalArgumentException::class.java) {
            selectLaunchableApp(apps, "not installed")
        }
    }
}
