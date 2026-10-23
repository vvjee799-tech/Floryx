package com.flowchat.app.presentation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTransitionContractTest {
    @Test
    fun startupKeepsNativeWindowAndRestoresComposeSplashTransition() {
        val mainActivity = File("src/main/java/com/flowchat/app/MainActivity.kt").readText()
        val splashTransitionFile = File("src/main/java/com/flowchat/app/presentation/SplashTransition.kt")
        val styles = File("src/main/res/values/styles.xml").readText()
        val windowBackgroundFile = File("src/main/res/drawable/splash_window_background.xml")
        val nightWindowBackgroundFile = File("src/main/res/drawable-night/splash_window_background.xml")
        val androidTwelveStylesFile = File("src/main/res/values-v31/styles.xml")
        val androidTwelveNightStylesFile = File("src/main/res/values-night-v31/styles.xml")
        val transparentSplashIconFile = File("src/main/res/drawable/transparent_splash_icon.xml")
        val splashLightImage = File("src/main/res/drawable/splash_transition_light.xml")
        val splashDarkImage = File("src/main/res/drawable/splash_transition_dark.xml")

        assertTrue(splashTransitionFile.exists())
        assertTrue(windowBackgroundFile.exists())
        assertTrue(nightWindowBackgroundFile.exists())
        assertTrue(androidTwelveStylesFile.exists())
        assertTrue(androidTwelveNightStylesFile.exists())
        assertTrue(transparentSplashIconFile.exists())
        assertTrue(splashLightImage.exists())
        assertTrue(splashDarkImage.exists())

        val windowBackground = windowBackgroundFile.readText()
        val nightWindowBackground = nightWindowBackgroundFile.readText()
        val androidTwelveStyles = androidTwelveStylesFile.readText()
        val androidTwelveNightStyles = androidTwelveNightStylesFile.readText()
        val transparentSplashIcon = transparentSplashIconFile.readText()

        val splashTransition = splashTransitionFile.readText()
        assertTrue(mainActivity.contains("import com.flowchat.app.presentation.SplashTransition"))
        assertTrue(mainActivity.contains("SplashTransition(appAppearance = appAppearance)"))
        assertTrue(mainActivity.contains("FlowChatRoot(appAppearance = appAppearance"))
        assertTrue(splashTransition.contains("rememberSaveable { mutableStateOf(true) }"))
        assertTrue(splashTransition.contains("delay(SplashTransitionDurationMillis)"))
        assertTrue(splashTransition.contains("R.drawable.splash_transition_dark"))
        assertTrue(splashTransition.contains("R.drawable.splash_transition_light"))
        assertTrue(splashTransition.contains("contentScale = ContentScale.Crop"))
        assertTrue(splashTransition.contains("fadeOut("))
        assertTrue(styles.contains("<item name=\"android:windowBackground\">@drawable/splash_window_background</item>"))
        assertTrue(windowBackground.contains("<layer-list"))
        assertTrue(windowBackground.contains("<item android:drawable="))
        assertTrue(windowBackground.contains("@drawable/splash_transition_light"))
        assertTrue(nightWindowBackground.contains("<layer-list"))
        assertTrue(nightWindowBackground.contains("<item android:drawable="))
        assertTrue(nightWindowBackground.contains("@drawable/splash_transition_dark"))
        assertTrue(androidTwelveStyles.contains("android:windowSplashScreenAnimatedIcon"))
        assertTrue(androidTwelveStyles.contains("@drawable/transparent_splash_icon"))
        assertTrue(androidTwelveStyles.contains("android:windowSplashScreenBackground"))
        assertTrue(androidTwelveStyles.contains("#FFFFFF"))
        assertTrue(androidTwelveNightStyles.contains("android:windowSplashScreenAnimatedIcon"))
        assertTrue(androidTwelveNightStyles.contains("@drawable/transparent_splash_icon"))
        assertTrue(androidTwelveNightStyles.contains("android:windowSplashScreenBackground"))
        assertTrue(androidTwelveNightStyles.contains("#000000"))
        assertTrue(transparentSplashIcon.contains("android:alpha=\"0\""))

        assertTrue(splashLightImage.readText().contains("<vector"))
        assertTrue(splashDarkImage.readText().contains("<vector"))
    }
}
