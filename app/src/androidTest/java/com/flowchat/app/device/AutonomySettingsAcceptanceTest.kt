package com.flowchat.app.device

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.roundToInt

class AutonomySettingsAcceptanceTest : ScrollFixtureDiagnostics() {
    @Test fun findSoundSettingsAdjustAndRestoreVolumeAcrossThreeStartingPositions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = AutonomyRuntimeFixture(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val original = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var passed = false
        try {
            check(!audio.isMusicActive) { "Playback is active; preserve the user's volume and defer this acceptance" }
            fixture.connect(); fixture.create()
            repeat(3) { sample ->
                withContext(Dispatchers.Main) { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                var current = requireNotNull(fixture.gateway.observeScreen().screen)
                withTimeout(10_000) { while (current.interactionContext?.complete != true || current.packageName != "com.android.settings") {
                    delay(150); current = requireNotNull(fixture.gateway.observeScreen().screen)
                } }
                if (sample > 0) {
                    val initialList = current.elements.filter { it.scrollable }.maxBy { it.bounds.width.toLong() * it.bounds.height }
                    val shifted = fixture.gateway.swipeScreen(DeviceScrollRequest(DeviceSwipeDirection.Up, 15 * sample, 600, current.screenId, initialList.index))
                    current = requireNotNull(shifted.screen)
                }
                val started = SystemClock.elapsedRealtime()
                var found = false; var actions = 0
                for (step in 0..9) {
                    val list = current.elements.filter { it.scrollable }.maxBy { it.bounds.width.toLong() * it.bounds.height }
                    val result = fixture.gateway.scrollContainer(DeviceContainerScrollRequest(list.index, DeviceScrollDirection.Down,
                        current.screenId, target = DeviceUiSelector(text = "声音与振动")))
                    assertTrue(result.asToolContent(), result.success)
                    actions += result.scrollFeedback?.actions ?: 0
                    current = requireNotNull(result.screen)
                    if (result.scrollFeedback?.targetState == "ready") {
                        var target = current.elements.single { it.text == "声音与振动" }
                        while (!target.clickable) target = current.elements.single { it.index == target.parentIndex }
                        val opened = fixture.gateway.tapUiElement(target.index, false, current.screenId)
                        assertTrue(opened.asToolContent(), opened.success)
                        val detail = requireNotNull(opened.screen)
                        assertTrue(detail.elements.any { it.text == "声音与振动" })
                        assertTrue(detail.elements.any { it.displayText.contains("媒体") })
                        found = true; break
                    }
                }
                assertTrue("Sound settings target not found", found)
                val percent = if (original == (maximum * .33).roundToInt()) 47 else 33
                val changed = fixture.gateway.setMediaVolume(percent)
                assertTrue(changed.asToolContent(), changed.success)
                assertEquals((maximum * percent / 100.0).roundToInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                assertTrue(fixture.gateway.setMediaVolume((original * 100.0 / maximum).roundToInt()).success)
                assertEquals(original, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                report("task=real-settings sample=$sample completed=true interruptions=0 manual_steps=0 actions=$actions elapsed_ms=${SystemClock.elapsedRealtime() - started} original_volume_restored=true")
            }
            passed = true
        } finally {
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != original) {
                fixture.gateway.setMediaVolume((original * 100.0 / maximum).roundToInt())
                check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == original) { "Original volume is not restored" }
            }
            fixture.close(passed)
        }
    }
}
