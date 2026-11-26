package com.flowchat.nativevoicebridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.flowchat.nativevoicebridge.VoiceBridgeWire.*;
import static org.junit.Assert.*;

public final class NativeVoiceRoundOneTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            replaysFinalTextAcrossBothApks();
            result.putString("stream", "\nNative voice round 1 replay passed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError error) {
            result.putString("stream", "\n" + Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void replaysFinalTextAcrossBothApks() throws Exception {
        NativeVoiceBridge bridge = new NativeVoiceBridge(getTargetContext());
        UUID session = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        String instruction = "请给我一句简短回复";
        assertEquals(IGNORED, bridge.onTranscript(session, first, "请给我一句", false).getString(STATE));
        assertEquals(MISSING, bridge.status(session, first).getString(STATE));

        long submittedAt = SystemClock.elapsedRealtime();
        // The source never receives this acceptance receipt. Reopen with only its session ID.
        bridge.onTranscript(session, first, instruction, true);
        NativeVoiceBridge reconnected = new NativeVoiceBridge(getTargetContext());
        RecordingPanel firstPanel = new RecordingPanel();
        Bundle firstReply = await(
                () -> reconnected.presentLatest(session, firstPanel), COMPLETED);
        long firstReplyMillis = SystemClock.elapsedRealtime() - submittedAt;
        assertEquals(first.toString(), firstReply.getString(REQUEST_ID));
        assertShortReply(firstReply, firstPanel, 1);

        Bundle duplicate = bridge.onTranscript(session, first, instruction, true);
        assertEquals(firstReply.getString(TASK_ID), duplicate.getString(TASK_ID));
        assertFalse(duplicate.getBoolean(NEWLY_ACCEPTED));
        assertEquals(REJECTED, bridge.onTranscript(session, first, "另一句话", true).getString(STATE));
        assertEquals(MISSING, bridge.status(UUID.randomUUID(), first).getString(STATE));

        RecordingPanel reopenedPanel = new RecordingPanel();
        Bundle reopened = reconnected.presentLatest(session, reopenedPanel);
        assertEquals(firstReply.getString(TASK_ID), reopened.getString(TASK_ID));
        assertShortReply(reopened, reopenedPanel, 0);
        assertEquals(firstPanel.shown, reopenedPanel.shown);

        UUID deliberateRepeat = UUID.randomUUID();
        Bundle[] duplicatePair = concurrentlySubmit(bridge, session, deliberateRepeat, instruction);
        assertNotEquals(duplicatePair[0].getBoolean(NEWLY_ACCEPTED), duplicatePair[1].getBoolean(NEWLY_ACCEPTED));
        assertEquals(duplicatePair[0].getString(CONVERSATION_ID), duplicatePair[1].getString(CONVERSATION_ID));
        RecordingPanel repeatPanel = new RecordingPanel();
        Bundle repeatReply = await(() -> bridge.presentLatest(session, repeatPanel), COMPLETED);
        assertEquals(deliberateRepeat.toString(), repeatReply.getString(REQUEST_ID));
        assertNotEquals(firstReply.getString(TASK_ID), repeatReply.getString(TASK_ID));
        assertShortReply(repeatReply, repeatPanel, 1);
        assertEquals(STOPPED, bridge.stop(session, deliberateRepeat, repeatPanel).getString(STATE));
        assertEquals(1, repeatPanel.stops);
        assertEquals(STOPPED, reconnected.presentLatest(session, new RecordingPanel()).getString(STATE));
        assertFalse(bridge.onTranscript(session, first, instruction, true).getBoolean(NEWLY_ACCEPTED));
        assertEquals(STOPPED, bridge.presentLatest(session, new RecordingPanel()).getString(STATE));

        UUID stoppedEvent = UUID.randomUUID();
        Bundle accepted = bridge.onTranscript(session, stoppedEvent, "请另外给一句简短回复", true);
        assertTrue(accepted.getBoolean(NEWLY_ACCEPTED));
        String stoppedOwner = accepted.getString(CONVERSATION_ID);
        awaitLog("local_started owner=" + stoppedOwner);
        RecordingPanel stoppedPanel = new RecordingPanel();
        assertEquals(STOPPED, bridge.stop(session, stoppedEvent, stoppedPanel).getString(STATE));
        String stopLog = "stopped owner=" + stoppedOwner;
        String lateLog = "local_reply owner=" + stoppedOwner + " delivered=false";
        String logs = awaitLog(lateLog);
        assertTrue(logs.indexOf(stopLog) >= 0 && logs.indexOf(stopLog) < logs.indexOf(lateLog));
        Bundle stopped = awaitSnapshot(() -> bridge.status(session, stoppedEvent),
                value -> STOPPED.equals(value.getString(TASK_STATE)));
        assertNotNull(stopped.getString(TASK_ID));
        assertNull(stopped.getString(REPLY));
        assertEquals(STOPPED, reconnected.presentLatest(session, stoppedPanel).getString(STATE));
        assertFalse(bridge.onTranscript(session, stoppedEvent, "请另外给一句简短回复", true).getBoolean(NEWLY_ACCEPTED));
        assertNull(stoppedPanel.shown);
        assertEquals(0, stoppedPanel.spoken);
        assertEquals(1, stoppedPanel.stops);
        assertFalse(logs.contains("blocked_device_action"));

        assertEquals("WaitingForUser", WAITING_USER);
        String longMarkdownReply = "## 计算与排查结果\n" +
                "**核心结论**：最终结果是 1280，全部计算与账目明细已经逐项核对完成，没有发现异常问题。\n" +
                "- 第一项明细：先将本月日常开销汇总后加上所有固定账单、交通餐费与临时日常支出，确认各项单据原始数值全部准确无误并且已完成分类归档。\n" +
                "- 第二项明细：随后逐条核对了上周的账户结余金额与本月全部待扣固定费用，同时仔细排查了是否存在重复记账、漏记收支或汇率换算差异等条目。\n" +
                "- 第三项明细：完整数据与每一项详细核算过程都已经在结果卡片中完整保留展示，方便你随时返回或打开卡片继续逐行核对与查看。";
        assertTrue("Expected > 200 chars, got " + longMarkdownReply.length(), longMarkdownReply.length() > 200);
        String spokenCompact = XiaovAdapter.prepareSpokenText(longMarkdownReply);
        assertFalse(spokenCompact.isEmpty());
        assertTrue("Expected compacted speech <= " + XiaovAdapter.MAX_SPOKEN_CHARS + ", got " + spokenCompact.length(),
                spokenCompact.length() <= XiaovAdapter.MAX_SPOKEN_CHARS);
        assertTrue(spokenCompact.endsWith(XiaovAdapter.TRUNCATED_SPEECH_SUFFIX));
        assertFalse(spokenCompact.contains("#"));
        assertFalse(spokenCompact.contains("**"));
        assertTrue(spokenCompact.contains("1280"));

        JSONObject evidence = new JSONObject()
                .put("session_id", session.toString())
                .put("first", entry(firstReply, firstPanel))
                .put("first_final_to_reply_ms", firstReplyMillis)
                .put("partial_creates_task", false)
                .put("simulated_receipt_loss_recovered", true)
                .put("same_event_reused_first_task", true)
                .put("reopened", entry(reopened, reopenedPanel))
                .put("deliberate_repeat", entry(repeatReply, repeatPanel))
                .put("concurrent_repeat_accepts", 1)
                .put("stopped", entry(stopped, stoppedPanel))
                .put("late_reply_delivered", false)
                .put("device_action_attempts", 0)
                .put("waiting_user_wire_state", WAITING_USER)
                .put("spoken_compaction_verified", true)
                .put("official_flash_requests", 0);
        File report = new File(getTargetContext().getCacheDir(), "native-voice-round1.json");
        try (FileOutputStream stream = new FileOutputStream(report)) {
            stream.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private Bundle[] concurrentlySubmit(NativeVoiceBridge bridge, UUID session, UUID request, String text) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Bundle> first = workers.submit(() -> {
                start.await();
                return bridge.onTranscript(session, request, text, true);
            });
            Future<Bundle> second = workers.submit(() -> {
                start.await();
                return bridge.onTranscript(session, request, text, true);
            });
            start.countDown();
            return new Bundle[]{first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)};
        } finally {
            workers.shutdownNow();
        }
    }

    private static Bundle await(Supplier<Bundle> query, String expected) throws Exception {
        return awaitSnapshot(query, value -> expected.equals(value.getString(STATE)));
    }

    private static Bundle awaitSnapshot(Supplier<Bundle> query, Predicate<Bundle> done) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        Bundle observed;
        do {
            observed = query.get();
            if (done.test(observed)) return observed;
            Thread.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Expected task state did not arrive. Last state: " + observed);
    }

    private String awaitLog(String line) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                    getUiAutomation().executeShellCommand("logcat -d -v brief -s NativeVoiceLocal:I"));
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096];
                int read;
                while ((read = input.read(bytes)) != -1) buffer.write(bytes, 0, read);
                String output = buffer.toString(StandardCharsets.UTF_8.name());
                if (output.contains(line)) return output;
            }
            Thread.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Missing device trace: " + line);
    }

    private static void assertShortReply(Bundle result, RecordingPanel panel, int spoken) {
        assertNotNull(result.getString(TASK_ID));
        assertNotNull(panel.shown);
        assertTrue(panel.shown.matches("本地短答 [0-9a-f]{8}"));
        assertEquals(result.getString(REPLY), panel.shown);
        assertEquals(spoken, panel.spoken);
    }

    private static JSONObject entry(Bundle result, RecordingPanel panel) throws Exception {
        return new JSONObject()
                .put("request_id", result.getString(REQUEST_ID))
                .put("conversation_id", result.getString(CONVERSATION_ID))
                .put("task_id", result.getString(TASK_ID))
                .put("state", result.getString(TASK_STATE))
                .put("displayed_reply", panel.shown)
                .put("playback_calls", panel.spoken)
                .put("stop_playback_calls", panel.stops);
    }

    private static final class RecordingPanel implements NativeVoiceBridge.PanelOutput {
        String shown;
        int spoken;
        int stops;

        @Override public void showReply(String reply) { shown = reply; }
        @Override public void speak(String reply) {
            assertEquals(shown, reply);
            spoken++;
        }
        @Override public void stopPlayback() { stops++; }
    }
}
