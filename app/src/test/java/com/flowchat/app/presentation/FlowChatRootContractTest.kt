package com.flowchat.app.presentation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowChatRootContractTest {
    @Test
    fun rootNavigationUsesStableBackgroundAndNoRouteTransitions() {
        val source = File("src/main/java/com/flowchat/app/presentation/FlowChatRoot.kt").readText()

        assertTrue(source.contains("Box("))
        assertTrue(source.contains("Modifier.fillMaxSize()"))
        assertTrue(source.contains(".background(MaterialTheme.colorScheme.background)"))
        assertTrue(source.contains("enterTransition = { EnterTransition.None }"))
        assertTrue(source.contains("exitTransition = { ExitTransition.None }"))
        assertTrue(source.contains("popEnterTransition = { EnterTransition.None }"))
        assertTrue(source.contains("popExitTransition = { ExitTransition.None }"))
    }

    @Test
    fun rootPassesAppearanceControlsToChatScreen() {
        val source = File("src/main/java/com/flowchat/app/presentation/FlowChatRoot.kt").readText()
        val mainActivity = File("src/main/java/com/flowchat/app/MainActivity.kt").readText()
        val appearanceSource = File("src/main/java/com/flowchat/app/ui/theme/AppAppearance.kt")
        val themeSource = File("src/main/java/com/flowchat/app/ui/theme/Theme.kt").readText()

        assertTrue(appearanceSource.exists())
        val appearance = appearanceSource.readText()
        assertTrue(appearance.contains("enum class AppAppearance"))
        assertTrue(appearance.contains("Light"))
        assertTrue(appearance.contains("Dark"))
        assertTrue(appearance.contains("fun load(context: Context): AppAppearance"))
        assertTrue(appearance.contains("fun save(context: Context, appearance: AppAppearance)"))
        assertTrue(mainActivity.contains("var appAppearance by remember"))
        assertTrue(mainActivity.contains("AppAppearance.load(this)"))
        assertTrue(mainActivity.contains("AppAppearance.save(this, appearance)"))
        assertTrue(mainActivity.contains("FlowChatTheme(appAppearance = appAppearance)"))
        assertTrue(mainActivity.contains("isAppearanceLightStatusBars = !useDarkSystemBars"))
        assertTrue(mainActivity.contains("isAppearanceLightNavigationBars = !useDarkSystemBars"))
        assertTrue(mainActivity.contains("SplashTransition(appAppearance = appAppearance)"))
        assertTrue(mainActivity.contains("FlowChatRoot(appAppearance = appAppearance, onAppAppearanceChange = { appearance ->"))
        assertTrue(source.contains("fun FlowChatRoot("))
        assertTrue(source.contains("appAppearance: AppAppearance"))
        assertTrue(source.contains("onAppAppearanceChange: (AppAppearance) -> Unit"))
        assertTrue(source.contains("ChatScreen("))
        assertTrue(source.contains("appAppearance = appAppearance"))
        assertTrue(source.contains("onAppAppearanceChange = onAppAppearanceChange"))
        assertTrue(themeSource.contains("fun FlowChatTheme("))
        assertFalse(themeSource.contains("CompositionLocalProvider("))
        assertFalse(themeSource.contains("fontScale = 1f"))
        assertTrue(themeSource.contains("appAppearance: AppAppearance"))
        assertTrue(themeSource.contains("AppAppearance.Dark -> true"))
        assertTrue(themeSource.contains("AppAppearance.Light -> false"))
        assertTrue(themeSource.contains("appAppearance: AppAppearance = AppAppearance.Dark"))
        assertTrue(appearance.contains(".getString(AppAppearanceKey, Dark.storageValue)"))
        assertTrue(themeSource.contains("primary = Color(0xFFD8CFAF)"))
        assertTrue(themeSource.contains("background = Color(0xFFF9F7F2)"))
        assertTrue(themeSource.contains("background = Color(0xFF24231F)"))
        assertTrue(themeSource.contains("surface = Color(0xFF24231F)"))
        assertTrue(themeSource.contains("surfaceVariant = Color(0xFF353228)"))
        assertTrue(themeSource.contains("outlineVariant = Color(0xFF484439)"))
        assertTrue(themeSource.contains("onBackground = Color(0xFF38352F)"))
        assertTrue(themeSource.contains("onBackground = Color(0xFFE8E4D9)"))
    }
}
