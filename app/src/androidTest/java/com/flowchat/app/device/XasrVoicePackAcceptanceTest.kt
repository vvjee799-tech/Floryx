package com.flowchat.app.device

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Debug
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.R
import com.flowchat.app.diagnostics.SystemAssistantDiagnosticsEntryPoint
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.presentation.assistant.AssistantPanelView
import com.flowchat.app.presentation.assistant.XasrModelPack
import com.flowchat.app.presentation.assistant.XasrVoicePack
import com.flowchat.app.presentation.assistant.XasrVoicePackStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class XasrVoicePackAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val harness = SystemAssistantDiagnosticTest()

    private fun evidenceDirectory(): File {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runXasrVoicePack") == "true")
        return File(context.getExternalFilesDir(null), "xasr-voice-pack-acceptance").apply { mkdirs() }
    }

    @Test fun cancelAndCorruptArchiveLeaveTheDownloadRetryable() = runBlocking<Unit> {
        val evidence = evidenceDirectory()
        val root = context.cacheDir.resolve("xasr-voice-pack-failure-test")
        check(!root.exists())
        val attempts = AtomicInteger()
        val store = XasrVoicePackStore(root) { target, progress ->
            if (attempts.incrementAndGet() != 2) {
                target.writeText("partial")
                progress(1)
                awaitCancellation()
            } else {
                RandomAccessFile(target, "rw").use { it.setLength(XasrModelPack.ARCHIVE_BYTES) }
                progress(100)
            }
        }
        try {
            harness.fixture { fixture ->
                val panel = harness.open(AssistantPanelActivity::class.java)
                val entry = EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java)
                val presenter = withContext(Dispatchers.Main) { entry.panels().create() }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                try {
                    val view = withContext(Dispatchers.Main) {
                        AssistantPanelView(panel, presenter, {}, {}, {},
                            requestVoicePermission = { _, _ -> error("Download requested microphone access") },
                            offlineVoicePack = XasrVoicePack(store)).also {
                            panel.setContentView(it)
                            scope.launch { presenter.state.collect(it::render) }
                        }
                    }
                    val action = view.findViewById<Button>(R.id.assistant_voice_download)
                    val voice = view.findViewById<Button>(R.id.assistant_voice)
                    val banner = view.findViewById<TextView>(R.id.assistant_error)
                    suspend fun tap() = withContext(Dispatchers.Main) { assertTrue(action.performClick()) }
                    suspend fun buttonShows(string: Int, vararg args: Any): Boolean = withContext(Dispatchers.Main) {
                        action.isShown && action.isEnabled && action.text.toString() == panel.getString(string, *args)
                    }
                    harness.await("offline-pack-download-ready") { buttonShows(R.string.assistant_voice_download) }
                    tap()
                    harness.await("offline-pack-streaming") { buttonShows(R.string.assistant_voice_download_progress, 1) }
                    withContext(Dispatchers.Main) { assertFalse(voice.isEnabled) }
                    harness.captureVoicePanel(panel, evidence, "01-cancel-download.png")
                    tap()
                    harness.await("offline-pack-cancel-deleted-staging") { !root.resolve(".installing").exists() }
                    assertEquals(null, store.modelDirectory())

                    tap()
                    harness.await("offline-pack-sha256-failure-visible") {
                        withContext(Dispatchers.Main) {
                            banner.isShown && banner.text.toString() == panel.getString(R.string.assistant_voice_download_invalid)
                        }
                    }
                    assertFalse(root.resolve(".installing").exists())
                    assertEquals(null, store.modelDirectory())
                    harness.captureVoicePanel(panel, evidence, "02-invalid-retry.png")
                    assertTrue(buttonShows(R.string.assistant_voice_download))
                    tap()
                    harness.await("offline-pack-retry-started") { buttonShows(R.string.assistant_voice_download_progress, 1) }
                    tap()
                    harness.await("offline-pack-retry-cancel-clean") { !root.resolve(".installing").exists() }
                    assertEquals(3, attempts.get())
                    assertEquals(0, fixture.model.requests.get())
                    evidence.resolve("failure-recovery.json").writeText(JSONObject()
                        .put("cancelRemovesStaging", true)
                        .put("correctLengthWrongDigestRejected", true)
                        .put("retries", attempts.get() - 1)
                        .put("modelRequests", fixture.model.requests.get()).toString(2))
                } finally {
                    withContext(Dispatchers.Main) { panel.finish(); presenter.close(); scope.cancel() }
                }
            }
        } finally {
            check(root.deleteRecursively())
        }
    }

    @Test fun productionPanelDownloadsThenOpensPrivateFileRecognizer() = runBlocking<Unit> {
        val evidence = evidenceDirectory()
        val root = context.noBackupFilesDir.resolve("assistant-voice")
        check(!root.exists()) { "This test requires an empty offline pack directory" }
        val pack = checkNotNull(XasrVoicePack.forDevice(context)) { "Build the Dev with floryxXasrRuntimeAar" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        try {
            harness.fixture { fixture ->
                val panel = harness.open(AssistantPanelActivity::class.java)
                val download = panel.findViewById<Button>(R.id.assistant_voice_download)
                val voice = panel.findViewById<Button>(R.id.assistant_voice)
                val status = panel.findViewById<TextView>(R.id.assistant_status)
                val error = panel.findViewById<TextView>(R.id.assistant_error)
                harness.await("production-offline-pack-download-shown") {
                    withContext(Dispatchers.Main) { download.isShown && download.isEnabled }
                }
                harness.captureVoicePanel(panel, evidence, "03-download-entry.png")
                val started = SystemClock.elapsedRealtime()
                var downloadFinishedAt: Long? = null
                withContext(Dispatchers.Main) { assertTrue(download.performClick()) }
                withTimeout(180_000) {
                    while (!withContext(Dispatchers.Main) {
                            if (error.isShown) throw AssertionError("Voice pack download error: ${error.text}")
                            if (downloadFinishedAt == null && download.text.toString() ==
                                panel.getString(R.string.assistant_voice_download_verifying)) {
                                downloadFinishedAt = SystemClock.elapsedRealtime()
                            }
                            !download.isShown && voice.isEnabled && pack.isReady()
                        }) delay(100)
                }
                val downloadMs = SystemClock.elapsedRealtime() - started
                val engine = checkNotNull(pack.newInputIfReady())
                assertEquals("XasrSpeechInput", engine.javaClass.simpleName)
                engine.close()
                assertFalse(root.resolve(".installing").exists())
                val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
                suspend fun measureCapture(label: String, screenshot: String? = null): JSONObject {
                    val before = processMemory()
                    val voiceStarted = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Main) { assertTrue(voice.performClick()) }
                    harness.await("production-offline-pack-listening-$label") {
                        withContext(Dispatchers.Main) {
                            if (error.isShown) throw AssertionError("Voice startup error: ${error.text}")
                            status.isShown && status.text.toString().startsWith(panel.getString(R.string.assistant_voice_listening))
                        }
                    }
                    val listeningMs = SystemClock.elapsedRealtime() - voiceStarted
                    val listening = processMemory()
                    val activeRecording = audio.activeRecordingConfigurations.isNotEmpty()
                    screenshot?.let { harness.captureVoicePanel(panel, evidence, it) }
                    val cancelled = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Main) {
                        assertTrue(voice.performClick())
                        assertEquals(panel.getString(R.string.assistant_voice_start), voice.text.toString())
                    }
                    harness.await("production-offline-pack-mic-released-$label") {
                        audio.activeRecordingConfigurations.isEmpty()
                    }
                    val releasedMs = SystemClock.elapsedRealtime() - cancelled
                    return JSONObject()
                        .put("clickToListeningMs", listeningMs)
                        .put("cancelToMicReleaseMs", releasedMs)
                        .put("activeRecordingReported", activeRecording)
                        .put("before", before)
                        .put("listening", listening)
                        .put("afterCancel", processMemory())
                }
                val first = measureCapture("first", "04-offline-listening.png")
                val reopened = measureCapture("reopened")
                assertEquals(0, fixture.model.requests.get())
                evidence.resolve("production.json").writeText(JSONObject()
                    .put("archiveBytes", XasrModelPack.ARCHIVE_BYTES)
                    .put("modelBytes", XasrModelPack.files.values.sum())
                    .put("downloadMs", checkNotNull(downloadFinishedAt) - started)
                    .put("downloadAndInstallMs", downloadMs)
                    .put("clickToListeningMs", first.getLong("clickToListeningMs"))
                    .put("firstCapture", first)
                    .put("reopenedCapture", reopened)
                    .put("temporaryStagingRemoved", true)
                    .put("modelRequests", fixture.model.requests.get()).toString(2))
            }
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun processMemory(): JSONObject {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val peakRss = File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }
                ?.removePrefix("VmHWM:")?.trim()?.substringBefore(' ')?.toLongOrNull()
        }
        return JSONObject().put("pssKiB", memory.totalPss).put("peakRssKiB", peakRss ?: JSONObject.NULL)
    }
}
