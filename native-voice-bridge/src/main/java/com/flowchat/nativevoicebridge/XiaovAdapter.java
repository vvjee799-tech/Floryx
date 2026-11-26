package com.flowchat.nativevoicebridge;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface.HookHandle;

import static com.flowchat.nativevoicebridge.VoiceBridgeWire.*;
import static com.flowchat.nativevoicebridge.XiaovModule.TAG;

/** Reflection is restricted to the installed Xiaov 6.11.1.1 conversation gateway. */
final class XiaovAdapter {
    private static final String ROOT = "com.vivo.ai.copilot.";
    private static final String NATIVE_VOICE_WAKE = "com.vivo.agent.action.wakeup_by_voice";
    private static final String WAKE_STOP = "native_interrupt:wake_word";
    private static final int ASR_WITH_UI = 2;
    private static final int DIALOG_SPEAK = 1045;
    private static final int RECENT_TURNS = 32;
    private static final long REPLY_TIMEOUT_MS = 180_000;
    private static final long UNLOCK_WAIT_TIMEOUT_MS = 60_000;
    private static final long RECENT_SPEECH_WINDOW_MS = 60_000;
    private static final long POLL_INTERVAL_MS = 100;
    private static final long PANEL_SETTLE_MS = 150;
    private static final long UNLOCK_SETTLE_MS = 200;
    private static final int MAX_MISSING_POLLS = 30;
    private static final int MAX_UNKNOWN_POLLS = 50;
    static final int MAX_SPOKEN_CHARS = 110;
    static final String TRUNCATED_SPEECH_SUFFIX = "完整内容请看卡片。";
    private static final String DEFAULT_UNLOCK_PROMPT = "请先解锁手机，解锁后我继续帮你处理。";
    private static final String DEFAULT_WAITING_USER_PROMPT = "这一步需要你在 Floryx 应用内确认后才能继续，请打开 Floryx 处理。";
    private static final String PUNCTUATION_TRIM = " \t\n\r。！？.!?，,、~～";
    private static final Pattern MARKDOWN_CODE_FENCE = Pattern.compile("(?m)^\\s*```[^\\r\\n]*$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^\\s*#{1,6}\\s+");
    private static final Pattern MARKDOWN_BULLET = Pattern.compile("^\\s*[-*•]\\s+");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00A0]+");
    private static final String SENTENCE_END_CHARS = "。！？!?";
    private static final String CLAUSE_PAUSE_CHARS = "；，;,：: ";
    private static final String LINE_JOIN_PUNCTUATION = "。！？.!?;；:：,，、";
    private static final String TRAILING_TRIM_CHARS = " \t\n\r，,;；:：、";
    private static final Set<String> LOCAL_STOP_COMMANDS = new HashSet<>(Arrays.asList(
            "停止", "停", "停下", "暂停", "别说了", "不要说了", "别读了", "不要读了",
            "闭嘴", "取消", "算了", "停止播报", "停止任务", "stop", "cancel"
    ));

    private final XiaovModule module;
    private final Context appContext;
    private final ClassLoader loader;
    private final XiaovRelayClient relay;
    private final KeyguardManager keyguardManager;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(4, task -> new Thread(task, "Floryx-Xiaov"));
    private final ExecutorService stopWorker = Executors.newSingleThreadExecutor(task -> new Thread(task, "Floryx-Xiaov-Stop"));
    private final List<WeakReference<View>> panelViews = new ArrayList<>();
    private final Map<String, Turn> recent = new LinkedHashMap<>();
    private final Map<String, Turn> latest = new LinkedHashMap<>();
    private final Map<String, Long> latestSubmittedSeq = new LinkedHashMap<>();
    private final AtomicLong nextTurnSeq = new AtomicLong(0L);
    private final List<HookHandle> hooks = new ArrayList<>();
    private final AtomicInteger activeRuns = new AtomicInteger(0);
    private final AtomicBoolean panelYielded = new AtomicBoolean(false);
    private final List<WeakReference<Object>> dismissTimers = new ArrayList<>();
    private volatile Turn activeTurn;
    private volatile Turn lastSpokenTurn;
    private volatile long lastSpokenElapsedMs;
    private Object audioModeListener;

    private final Class<?> voiceRequest;
    private final Class<?> voiceQuery;
    private final Class<?> onlineAsr;
    private final Class<?> gateway;
    private final Class<?> gatewayQuery;
    private final Class<?> remoteQuery;
    private final Class<?> offlineAsr;
    private final Class<?> continuation;
    private final Method findDialog;
    private final Method gatewayInterrupt;
    private final Method startConversation;
    private final Method startDialog;
    private final Method waitDialog;
    private final Method endDialog;
    private final Method textQuestion;
    private final Method textAnswer;
    private final Method emitCard;
    private final Method emitSignal;
    private final Method speak;
    private final Method stopTts;
    private final Constructor<?> speakRequest;
    private final Field defaultSpeakConfig;
    private final Field queryInterceptor;
    private final Field offlineHandler;
    private final Field emitter;
    private final Field skipRemote;
    private final Field renderText;
    private final Field handledLocally;
    private final Field asrMode;
    private final Class<?> dismissTimerClass;
    private final Class<?> dismissFinishedClass;
    private final Class<?> panelViewModelClass;
    private final Class<?> assistantActivityClass;
    private final Class<?> vIntentClass;
    private final Method cancelDismissTimer;

    XiaovAdapter(XiaovModule module, Context context, ClassLoader loader) throws ReflectiveOperationException {
        this.module = module;
        this.appContext = context.getApplicationContext();
        this.loader = loader;
        relay = new XiaovRelayClient(this.appContext);
        keyguardManager = this.appContext.getSystemService(KeyguardManager.class);
        audioManager = this.appContext.getSystemService(AudioManager.class);
        voiceRequest = type("gateway.model.RemoteVoiceQueryRequest");
        voiceQuery = type("gateway.model.VoiceQueryModel");
        onlineAsr = type("common.agent.event.LinkEvent$VoiceEvent$OnlineAsrResult");
        gateway = type("gateway.GatewayManager");
        gatewayQuery = type("gateway.model.Query");
        remoteQuery = type("gateway.model.Query$Remote");
        offlineAsr = type("gateway.model.Query$OfflineAsrResult");
        continuation = Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl", false, loader);

        Class<?> blockEmitter = type("gateway.BlockEmitter");
        Class<?> linkServer = type("framework.LinkServer");
        Class<?> request = type("framework.voice.SpeakRequest");
        Class<?> speakConfig = type("framework.llm.cloud.logic.voice.bean.StreamControlConfig");
        findDialog = gateway.getDeclaredMethod("e", String.class);
        gatewayInterrupt = gateway.getDeclaredMethod("d", String.class, String.class, String.class);
        startConversation = type("gateway.dialogop.StartConversation").getDeclaredMethod("a", String.class);
        startDialog = type("gateway.dialogop.StartDialog").getDeclaredMethod("a", String.class);
        waitDialog = type("gateway.dialogop.WaitDialog").getDeclaredMethod("a", String.class);
        endDialog = type("gateway.dialogop.EndDialog").getDeclaredMethod("a", String.class, Integer.class, String.class, int.class);
        textQuestion = type("gateway.card.TextQueryCard").getDeclaredMethod("a", String.class, String.class, String.class, boolean.class);
        textAnswer = type("gateway.card.TextAnswerCard").getDeclaredMethod("a", String.class, String.class, String.class,
                String.class, String.class, boolean.class, boolean.class, int.class);
        emitCard = blockEmitter.getDeclaredMethod("c", String.class, type("gateway.model.Block"));
        emitSignal = blockEmitter.getDeclaredMethod("g", String.class, type("gateway.model.UiSignal"));
        speak = linkServer.getDeclaredMethod("k", int.class, request, String.class,
                String.class, String.class, String.class, Bundle.class);
        stopTts = linkServer.getDeclaredMethod("t", int.class, String.class, String.class,
                String.class, boolean.class, type("base.tool.vcode.ReportBusinessData"));
        speakRequest = request.getDeclaredConstructor(String.class, String.class, String.class, String.class, speakConfig, boolean.class);
        defaultSpeakConfig = field(request, "g");
        queryInterceptor = field(gateway, "l");
        offlineHandler = field(queryInterceptor.getType(), "b");
        emitter = field(offlineHandler.getType(), "a");
        Class<?> keyboard = type("gateway.model.TextQueryModel");
        skipRemote = field(keyboard, "skipRemote");
        renderText = field(keyboard, "renderText");
        handledLocally = field(offlineAsr, "handledLocally");
        asrMode = field(type("gateway.model.VoiceModel"), "asrResultMode");
        dismissTimerClass = type("assistant.utils.countdown.PanelDismissTimer");
        dismissFinishedClass = type("assistant.utils.countdown.PanelDismissTimer$onCountDownFinished$2");
        panelViewModelClass = type("assistant.viewmodel.AssistantPanelViewModel");
        assistantActivityClass = type("assistant.AssistantActivity");
        vIntentClass = type("common.fragment.ams.model.VIntent");
        cancelDismissTimer = dismissTimerClass.getDeclaredMethod("a");
    }

    void install() throws ReflectiveOperationException {
        try {
            registerAudioModeMonitor();
            hooks.add(module.hook(voiceRequest.getDeclaredConstructor(String.class, String.class,
                    String.class, voiceQuery)).intercept(chain -> {
                try {
                    captureVoice(chain.getArgs());
                } catch (Throwable failure) {
                    Log.w(TAG, "capture_voice_failed", failure);
                }
                return chain.proceed();
            }));
            hooks.add(module.hook(onlineAsr.getDeclaredConstructor(int.class, String.class, String.class,
                    boolean.class, String.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (Boolean.TRUE.equals(chain.getArg(3))) {
                        int mode = (Integer) chain.getArg(0);
                        String chunk = (String) chain.getArg(1);
                        String link = (String) chain.getArg(2);
                        Turn turn = findTurn(link);
                        Log.w(TAG, "online_asr_final link=" + link + " mode=" + mode + " matchedTurn=" +
                                (turn != null) + " chars=" + (chunk == null ? -1 : chunk.length()));
                        if (turn != null && turn.voice) submitOnce(turn, chunk);
                    }
                } catch (Throwable failure) {
                    Log.w(TAG, "online_asr_hook_failed", failure);
                }
                return result;
            }));
            hooks.add(module.hook(gateway.getDeclaredMethod("j", gatewayQuery, continuation)).intercept(chain -> {
                try {
                    Object query = chain.getArg(0);
                    if (remoteQuery.isInstance(query)) captureKeyboard(query);
                    if (offlineAsr.isInstance(query)) captureOffline(query);
                } catch (Throwable failure) {
                    Log.w(TAG, "gateway_query_hook_failed", failure);
                }
                return chain.proceed();
            }));
            hooks.add(module.hook(gatewayInterrupt).intercept(chain -> {
                try {
                    String linkId = (String) chain.getArg(0);
                    String dialogId = (String) chain.getArg(1);
                    String type = (String) chain.getArg(2);
                    Turn target = findInterruptTarget(linkId, dialogId);
                    if (target != null && !target.stopped) {
                        milestone("native_interrupt_hook link=" + linkId + " dialog=" + dialogId + " type=" + type);
                        if (target.awaitingInAppConfirmation && "stop".equals(type)) {
                            stopNativeSpeech(target, "native_interrupt:stop");
                        } else {
                            stopActiveLocally(target, "native_interrupt:" + type, null);
                        }
                    }
                } catch (Throwable failure) {
                    Log.w(TAG, "gateway_interrupt_hook_failed", failure);
                }
                return chain.proceed();
            }));
            // Xiao V's service requires its signature-level BIND_FLOATSERVICE permission.
            Method voiceWake = type("framework.wakeup.WakeupService")
                    .getDeclaredMethod("onStartCommand", Intent.class, int.class, int.class);
            hooks.add(module.hook(voiceWake).intercept(chain -> {
                if (chain.getArg(0) instanceof Intent intent && NATIVE_VOICE_WAKE.equals(intent.getAction())) {
                    try {
                        stopOnNativeVoiceWake();
                    } catch (RuntimeException failure) {
                        Log.w(TAG, "wake_stop_failed", failure);
                    }
                }
                return chain.proceed();
            }));
            Class<?> windowManagerImpl = Class.forName("android.view.WindowManagerImpl", false, loader);
            hooks.add(module.hook(windowManagerImpl.getDeclaredMethod("addView", View.class, ViewGroup.LayoutParams.class))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        trackPanelView(chain.getArg(0), chain.getArg(1));
                        return result;
                    }));
            hooks.add(module.hook(windowManagerImpl.getDeclaredMethod("updateViewLayout", View.class, ViewGroup.LayoutParams.class))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        trackPanelView(chain.getArg(0), chain.getArg(1));
                        return result;
                    }));
            hooks.add(module.hook(windowManagerImpl.getDeclaredMethod("removeView", View.class))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        untrackPanelView(chain.getArg(0));
                        return result;
                    }));
            hooks.add(module.hook(windowManagerImpl.getDeclaredMethod("removeViewImmediate", View.class))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        untrackPanelView(chain.getArg(0));
                        return result;
                    }));
            hooks.add(module.hook(dismissTimerClass.getDeclaredMethod("b", long.class))
                    .intercept(chain -> {
                        Object timer = chain.getThisObject();
                        rememberDismissTimer(timer);
                        if (activeRuns.get() > 0) {
                            cancelTimerInstance(timer);
                            return null;
                        }
                        return chain.proceed();
                    }));
            hooks.add(module.hook(dismissTimerClass.getDeclaredMethod("c", long.class))
                    .intercept(chain -> {
                        Object timer = chain.getThisObject();
                        rememberDismissTimer(timer);
                        if (activeRuns.get() > 0) {
                            cancelTimerInstance(timer);
                            return null;
                        }
                        return chain.proceed();
                    }));
            hooks.add(module.hook(dismissFinishedClass.getDeclaredMethod("invokeSuspend", Object.class))
                    .intercept(chain -> {
                        if (activeRuns.get() > 0) {
                            milestone("dismiss_timer_blocked");
                            return null;
                        }
                        return chain.proceed();
                    }));
            hooks.add(module.hook(panelViewModelClass.getDeclaredMethod("q", String.class, String.class, vIntentClass))
                    .intercept(chain -> {
                        String type = (String) chain.getArg(0);
                        String reason = (String) chain.getArg(1);
                        if (activeRuns.get() > 0 && ("countDown".equals(type) || panelYielded.get())) {
                            milestone("panel_dismiss_blocked type=" + type + " reason=" + reason);
                            return null;
                        }
                        if (activeRuns.get() > 0) {
                            milestone("panel_dismiss_pass type=" + type + " reason=" + reason);
                            if (isExplicitUserCancel(type)) {
                                Turn target = activeTurn;
                                if (target != null && !target.stopped) {
                                    stopActiveLocally(target, "panel_dismiss:" + type, null);
                                }
                            }
                        }
                        return chain.proceed();
                    }));
            hooks.add(module.hook(assistantActivityClass.getDeclaredMethod("finishVActivity", vIntentClass))
                    .intercept(chain -> {
                        if (activeRuns.get() > 0 && panelYielded.get()) {
                            milestone("panel_finish_blocked reason=yielded");
                            return null;
                        }
                        return chain.proceed();
                    }));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            for (HookHandle hook : hooks) hook.unhook();
            throw incompatible;
        }
        milestone("adapter_initialized version=" + XiaovTarget.VERSION_CODE);
    }

    private void registerAudioModeMonitor() {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            AudioManager.OnModeChangedListener listener = mode -> {
                if (isCallAudioMode(mode)) {
                    Turn spoken = lastSpokenTurn;
                    if (spoken != null && SystemClock.elapsedRealtime() - lastSpokenElapsedMs < RECENT_SPEECH_WINDOW_MS) {
                        stopNativeSpeech(spoken, "call_audio_mode:" + mode);
                        lastSpokenTurn = null;
                    }
                }
            };
            audioManager.addOnModeChangedListener(appContext.getMainExecutor(), listener);
            audioModeListener = listener;
        } catch (RuntimeException failure) {
            Log.w(TAG, "audio_mode_monitor_unavailable", failure);
        }
    }

    private void captureVoice(List<Object> args) throws IllegalAccessException {
        Object model = args.get(3);
        Object voice = callMethod(model, "getVoice");
        String agent = string(model, "getAgentId");
        String scene = string(callMethod(voice, "getScene"), "getValue");
        String mode = ((Enum<?>) callMethod(voice, "getMode")).name();
        int currentAsrMode = asrMode.getInt(voice);
        Object attachment = callMethod(model, "getAttachmentQueryModel");
        Log.w(TAG, "voice_seen agent=" + agent + " scene=" + scene + " mode=" + mode +
                " asrMode=" + currentAsrMode + " attach=" + (attachment != null) +
                " locked=" + isDeviceLocked());
        if (!isXiaov(agent)) return;
        Turn spoken = lastSpokenTurn;
        if (spoken != null && SystemClock.elapsedRealtime() - lastSpokenElapsedMs < RECENT_SPEECH_WINDOW_MS && !isCallAudioMode()) {
            stopNativeSpeech(spoken, "barge_in");
        }
        if (!"common".equals(scene) ||
                !("DUPLEX".equals(mode) || "LONG_PRESS".equals(mode) || "RADIO_BALL".equals(mode)) ||
                currentAsrMode != 0 || attachment != null) {
            Log.w(TAG, "voice_filtered agent=" + agent + " scene=" + scene + " mode=" + mode +
                    " asrMode=" + currentAsrMode + " attach=" + (attachment != null));
            return;
        }
        if (!isRelayReady("voice")) return;

        Turn turn = new Turn(nextTurnSeq.incrementAndGet(), (String) args.get(0), (String) args.get(1), (String) args.get(2), agent,
                string(model, "getWindowState"), null, true);
        if (!turn.valid()) return;
        asrMode.setInt(voice, ASR_WITH_UI);
        attachTurn(turn);
        milestone("voice_armed event=" + turn.event + " mode=" + mode + " locked=" + isDeviceLocked());
    }

    private void captureKeyboard(Object query) throws IllegalAccessException {
        Object model = callMethod(query, "getTextQueryModel");
        if (skipRemote.getBoolean(model)) return;
        String agent = string(model, "getAgentId");
        String text = string(model, "getServerQuery");
        boolean hasCamera = callMethod(model, "getCameraContext") != null;
        boolean hasAttachment = callMethod(model, "getAttachmentQueryModel") != null;
        boolean recommend = (boolean) callMethod(model, "getFromRecommend");
        boolean regenerate = (boolean) callMethod(model, "getRegenerate");
        boolean shortcut = (boolean) callMethod(model, "getShortcut");
        Log.w(TAG, "keyboard_seen agent=" + agent + " len=" + (text == null ? -1 : text.length()) +
                " cam=" + hasCamera + " attach=" + hasAttachment + " rec=" + recommend +
                " regen=" + regenerate + " shortcut=" + shortcut);
        if (!isXiaov(agent) || !validText(text) || hasCamera || hasAttachment || recommend || regenerate || shortcut) return;
        if (!isRelayReady("keyboard")) return;

        Turn turn = new Turn(nextTurnSeq.incrementAndGet(), string(query, "getConversationId"), string(query, "getLinkId"), string(query, "getWindowId"),
                agent, string(model, "getWindowState"), string(query, "getDialogId"), false);
        if (!turn.valid() || turn.dialog == null || turn.dialog.isEmpty()) return;
        skipRemote.setBoolean(model, true);
        renderText.setBoolean(model, true);
        submitOnce(attachTurn(turn), text);
    }

    private void captureOffline(Object query) throws IllegalAccessException {
        if (!(boolean) callMethod(query, "isLast") || handledLocally.getBoolean(query)) return;
        String link = string(query, "getLinkId");
        Turn turn = findTurn(link);
        String text = string(query, "getChunk");
        Log.w(TAG, "offline_asr_final link=" + link + " matchedTurn=" + (turn != null) +
                " chars=" + (text == null ? -1 : text.length()));
        if (turn == null || !turn.voice || !validText(text)) return;
        handledLocally.setBoolean(query, true);
        turn.dialog = string(query, "getDialogId");
        submitOnce(turn, text);
    }

    private boolean isRelayReady(String source) {
        Bundle probe = relay.call(PROBE, null, null, null);
        String state = probe.getString(STATE);
        if (READY.equals(state)) return true;
        if (INCOMPATIBLE.equals(state)) {
            milestone("bridge_incompatible_fallback source=" + source);
        } else {
            Log.w(TAG, source + "_skipped reason=dev_not_ready state=" + state);
        }
        return false;
    }

    private synchronized Turn attachTurn(Turn turn) {
        Turn prior = recent.get(turn.link);
        if (prior != null && prior.event.equals(turn.event)) return prior;
        recent.put(turn.link, turn);
        if (recent.size() > RECENT_TURNS) recent.remove(recent.keySet().iterator().next());
        return turn;
    }

    private synchronized Turn findTurn(String link) { return link == null ? null : recent.get(link); }

    private synchronized boolean current(Turn turn) { return latest.get(turn.window) == turn; }

    private synchronized Turn findInterruptTarget(String linkId, String dialogId) {
        Turn active = activeTurn;
        if (matchesTurn(active, linkId, dialogId)) return active;
        Turn byLink = findTurn(linkId);
        if (byLink != null && byLink.submitted) return byLink;
        List<Turn> values = new ArrayList<>(recent.values());
        for (int i = values.size() - 1; i >= 0; i--) {
            Turn candidate = values.get(i);
            if (candidate.submitted && matchesTurn(candidate, linkId, dialogId)) return candidate;
        }
        Turn spoken = lastSpokenTurn;
        if (spoken != null && SystemClock.elapsedRealtime() - lastSpokenElapsedMs < RECENT_SPEECH_WINDOW_MS &&
                matchesTurn(spoken, linkId, dialogId)) {
            return spoken;
        }
        return null;
    }

    private synchronized Turn resolveStopTarget(Turn triggerTurn) {
        Turn active = activeTurn;
        if (active != null && active != triggerTurn && !active.stopped) return active;
        Turn windowLatest = latest.get(triggerTurn.window);
        if (windowLatest != null && windowLatest != triggerTurn && windowLatest.submitted && !windowLatest.stopped) {
            return windowLatest;
        }
        List<Turn> values = new ArrayList<>(recent.values());
        for (int i = values.size() - 1; i >= 0; i--) {
            Turn candidate = values.get(i);
            if (candidate != triggerTurn && candidate.submitted && !candidate.stopped &&
                    triggerTurn.window.equals(candidate.window)) {
                return candidate;
            }
        }
        Turn spoken = lastSpokenTurn;
        if (spoken != null && spoken != triggerTurn && !spoken.stopped &&
                SystemClock.elapsedRealtime() - lastSpokenElapsedMs < RECENT_SPEECH_WINDOW_MS) {
            return spoken;
        }
        return null;
    }

    private static boolean matchesTurn(Turn turn, String linkId, String dialogId) {
        if (turn == null) return false;
        if (linkId != null && !linkId.isEmpty() && linkId.equals(turn.link)) return true;
        return dialogId != null && !dialogId.isEmpty() && dialogId.equals(turn.dialog);
    }

    private synchronized void submitOnce(Turn turn, String text) {
        Long committedSeq = latestSubmittedSeq.get(turn.window);
        if (!validText(text) || turn.submitted || (committedSeq != null && turn.seq < committedSeq)) return;
        latestSubmittedSeq.put(turn.window, turn.seq);
        if (latestSubmittedSeq.size() > RECENT_TURNS) {
            latestSubmittedSeq.remove(latestSubmittedSeq.keySet().iterator().next());
        }
        if (isLocalStopCommand(text)) {
            turn.submitted = true;
            Turn target = resolveStopTarget(turn);
            milestone("local_stop_command event=" + turn.event + " target=" + (target != null ? target.event : "session"));
            stopActiveLocally(target, turn.voice ? "voice_stop" : "keyboard_stop", turn);
            return;
        }
        turn.submitted = true;
        latest.put(turn.window, turn);
        if (latest.size() > RECENT_TURNS) latest.remove(latest.keySet().iterator().next());
        activeTurn = turn;
        activeRuns.incrementAndGet();
        cancelDismissTimers();
        milestone("input source=" + (turn.voice ? "voice" : "keyboard") + " event=" + turn.event + " chars=" + text.length());
        try {
            worker.execute(() -> receive(turn, text));
        } catch (RuntimeException rejected) {
            if (activeTurn == turn) activeTurn = null;
            activeRuns.decrementAndGet();
            throw rejected;
        }
    }

    private synchronized void stopOnNativeVoiceWake() {
        Turn turn = activeTurn;
        if (turn == null || turn.stopped || !current(turn)) return;
        // The native wake already owns the next panel and recorder transition.
        turn.dialogEnded.set(true);
        stopActiveLocally(turn, WAKE_STOP, null);
        milestone("native_wake_stop event=" + turn.event + " elapsed_ms=" + SystemClock.elapsedRealtime());
    }

    private synchronized void stopActiveLocally(Turn target, String reason, Turn triggerTurn) {
        if (target != null) target.stopped = true;
        if (triggerTurn != null) triggerTurn.stopped = true;
        if (activeTurn == target || activeTurn == triggerTurn) activeTurn = null;
        stopWorker.execute(() -> {
            if (target != null && !WAKE_STOP.equals(reason)) stopNativeSpeech(target, reason);
            if (triggerTurn != null && triggerTurn != target) stopNativeSpeech(triggerTurn, reason);
            Turn spoken = lastSpokenTurn;
            if (spoken != null && (spoken == target || spoken == triggerTurn)) lastSpokenTurn = null;

            Bundle stopped = target != null ? relay.stop(target.session, target.event, reason) :
                    relay.stop(triggerTurn.session, null, reason);
            milestone("stop_receipt reason=" + reason + " target=" + (target != null ? target.event : "session") +
                    " state=" + stopped.getString(STATE));

            if (target != null) {
                try {
                    finishStopped(target);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    Log.w(TAG, "finish_stopped_target_failed event=" + target.event, failure);
                }
            }
            if (triggerTurn != null && triggerTurn != target) {
                try {
                    finishStopped(triggerTurn);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    Log.w(TAG, "finish_stopped_trigger_failed event=" + triggerTurn.event, failure);
                }
            }
        });
    }

    private void receive(Turn turn, String text) {
        try {
            if (turn.stopped) return;
            long start = SystemClock.elapsedRealtime();
            Bundle result = relay.call(SUBMIT, turn.session, turn.event, text);
            milestone("submit_accepted event=" + turn.event + " state=" + result.getString(STATE) +
                    " newly=" + result.getBoolean(NEWLY_ACCEPTED) + " task=" + result.getString(TASK_ID) +
                    " runtime=" + result.getString(RUNTIME_MODE) +
                    " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
            if (turn.voice && pending(result) && !turn.stopped) {
                try {
                    ensureDialogStarted(turn, text);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    Log.w(TAG, "dialog_start_failed event=" + turn.event, failure);
                }
            }
            final List<View> yieldedViews = new CopyOnWriteArrayList<>();
            boolean yieldAcked = false;
            int missingPolls = 0;
            int unknownPolls = 0;
            try {
                // A timeout may mean the service already committed the receipt. Continue by exact ID.
                while (!turn.stopped && current(turn) && pending(result) &&
                        SystemClock.elapsedRealtime() - start < REPLY_TIMEOUT_MS) {
                    String currentState = result.getString(STATE);
                    if (WAITING_UNLOCK.equals(currentState)) {
                        result = waitForUnlockAndResume(turn, result, start, yieldedViews);
                        missingPolls = 0;
                        unknownPolls = 0;
                        continue;
                    }
                    if (MISSING.equals(currentState)) {
                        if (++missingPolls >= MAX_MISSING_POLLS) break;
                    } else {
                        missingPolls = 0;
                    }
                    if (UNKNOWN.equals(currentState)) {
                        if (++unknownPolls >= MAX_UNKNOWN_POLLS) break;
                    } else {
                        unknownPolls = 0;
                    }
                    if (result.getBoolean(YIELD_PANEL) && !turn.stopped) {
                        panelYielded.set(true);
                        int hiddenNow = hidePanels(yieldedViews);
                        if (!yieldAcked) {
                            if (hiddenNow > 0) SystemClock.sleep(PANEL_SETTLE_MS);
                            if (turn.stopped || !current(turn)) break;
                            relay.call(ACK_PANEL_YIELD, turn.session, turn.event, null);
                            yieldAcked = true;
                            milestone("panel_yielded event=" + turn.event + " views=" + yieldedViews.size() +
                                    " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
                        } else if (hiddenNow > 0) {
                            milestone("panel_rehidden event=" + turn.event + " views=" + hiddenNow +
                                    " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
                        }
                    }
                    SystemClock.sleep(POLL_INTERVAL_MS);
                    if (turn.stopped || !current(turn)) break;
                    result = relay.call(STATUS, turn.session, turn.event, null);
                }
            } finally {
                restorePanels(yieldedViews, turn, start);
                panelYielded.set(false);
            }
            if (turn.stopped) {
                try {
                    finishStopped(turn);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    Log.w(TAG, "native_stop_finish_failed event=" + turn.event, failure);
                }
                return;
            }
            if (!current(turn)) return;
            String finalState = result.getString(STATE);
            if (STOPPED.equals(finalState)) {
                turn.stopped = true;
                milestone("task_ended event=" + turn.event + " state=" + STOPPED +
                        " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
                try {
                    finishStopped(turn);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    Log.w(TAG, "native_stop_finish_failed event=" + turn.event, failure);
                }
                return;
            }
            if (WAITING_USER.equals(finalState)) {
                turn.awaitingInAppConfirmation = true;
                String prompt = result.getString(STATUS_TEXT);
                if (!hasText(prompt)) prompt = DEFAULT_WAITING_USER_PROMPT;
                milestone("waiting_user_confirm event=" + turn.event + " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
                try {
                    present(turn, text, prompt, start);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    Log.w(TAG, "waiting_user_prompt_failed event=" + turn.event, failure);
                }
                return;
            }
            if (!COMPLETED.equals(finalState)) {
                milestone("task_unavailable event=" + turn.event + " state=" + finalState +
                        " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
                try {
                    presentFailure(turn, UNKNOWN.equals(finalState) ? "Floryx 暂时不可用，请重试。" : "任务未能完成，请重试。");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    Log.w(TAG, "native_failure_reply_failed event=" + turn.event, failure);
                }
                return;
            }
            String task = result.getString(TASK_ID);
            Bundle claimed = relay.call(CLAIM_PLAYBACK, turn.session, turn.event, null);
            String reply = claimed.getString(REPLY);
            if (turn.stopped || !current(turn) || !COMPLETED.equals(claimed.getString(STATE)) ||
                    !claimed.getBoolean(PLAYBACK_CLAIMED) || !hasText(reply)) {
                Log.w(TAG, "playback_claim_skipped event=" + turn.event + " state=" + claimed.getString(STATE) +
                        " claimed=" + claimed.getBoolean(PLAYBACK_CLAIMED));
                return;
            }
            milestone("task_completed event=" + turn.event + " task=" + task +
                    " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
            try {
                present(turn, text, reply, start);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                Log.w(TAG, "native_reply_failed event=" + turn.event, failure);
            }
        } finally {
            if (activeTurn == turn) activeTurn = null;
            activeRuns.decrementAndGet();
        }
    }

    private Bundle waitForUnlockAndResume(Turn turn, Bundle currentResult, long start, List<View> yieldedViews) {
        if (!turn.unlockPrompted) {
            turn.unlockPrompted = true;
            String prompt = currentResult.getString(STATUS_TEXT);
            if (!hasText(prompt)) prompt = DEFAULT_UNLOCK_PROMPT;
            milestone("waiting_unlock event=" + turn.event + " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
            try {
                presentInterimPrompt(turn, prompt);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                Log.w(TAG, "unlock_prompt_failed event=" + turn.event, failure);
            }
        }
        long unlockDeadline = SystemClock.elapsedRealtime() + UNLOCK_WAIT_TIMEOUT_MS;
        Bundle status = currentResult;
        while (!turn.stopped && current(turn) && SystemClock.elapsedRealtime() < unlockDeadline) {
            if (!isDeviceLocked()) {
                SystemClock.sleep(UNLOCK_SETTLE_MS);
                if (!turn.stopped && current(turn) && !isDeviceLocked()) {
                    Bundle resumed = relay.call(RESUME, turn.session, turn.event, null);
                    milestone("unlock_detected_resuming event=" + turn.event + " state=" + resumed.getString(STATE) +
                            " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
                    return resumed;
                }
            } else {
                // Keep the native dialog alive while making the system unlock controls reachable.
                // Dismissing the overlay with a blank touch would stop the pending task.
                panelYielded.set(true);
                int hidden = hidePanels(yieldedViews);
                if (hidden > 0) {
                    milestone("panel_yielded_for_unlock event=" + turn.event + " views=" + hidden);
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MS);
            if (turn.stopped || !current(turn)) return status;
            status = relay.call(STATUS, turn.session, turn.event, null);
            String state = status.getString(STATE);
            if (!WAITING_UNLOCK.equals(state)) return status;
        }
        if (!turn.stopped && current(turn) && WAITING_UNLOCK.equals(status.getString(STATE))) {
            milestone("waiting_unlock_timeout event=" + turn.event);
            relay.stop(turn.session, turn.event, "unlock_timeout");
            return state(FAILED);
        }
        return status;
    }

    private void ensureDialogStarted(Turn turn, String question) throws ReflectiveOperationException {
        if (turn.dialog != null && !turn.dialog.isEmpty()) return;
        String existing = (String) findDialog.invoke(null, turn.link);
        if (existing != null && !existing.isEmpty()) {
            Object nativeEmitter = emitter.get(offlineHandler.get(queryInterceptor.get(null)));
            emitSignal.invoke(nativeEmitter, turn.link, waitDialog.invoke(null, existing));
            turn.dialog = existing;
            return;
        }
        Object nativeEmitter = emitter.get(offlineHandler.get(queryInterceptor.get(null)));
        String dialog = turn.event.toString();
        String conversation = turn.conversation.isEmpty() ? turn.session.toString() : turn.conversation;
        emitSignal.invoke(nativeEmitter, turn.link, startConversation.invoke(null, conversation));
        emitSignal.invoke(nativeEmitter, turn.link, startDialog.invoke(null, dialog));
        emitCard.invoke(nativeEmitter, turn.link, textQuestion.invoke(null, dialog, UUID.randomUUID().toString(), question, false));
        emitSignal.invoke(nativeEmitter, turn.link, waitDialog.invoke(null, dialog));
        turn.dialog = dialog;
    }

    private String resolveExistingDialog(Turn turn) throws ReflectiveOperationException {
        String dialog = turn.dialog;
        if (dialog == null || dialog.isEmpty()) {
            dialog = (String) findDialog.invoke(null, turn.link);
            if (dialog != null && !dialog.isEmpty()) turn.dialog = dialog;
        }
        return dialog;
    }

    private void presentInterimPrompt(Turn turn, String prompt) throws ReflectiveOperationException {
        if (turn.stopped || !current(turn)) return;
        String dialog = resolveExistingDialog(turn);
        if (dialog == null || dialog.isEmpty()) return;
        Object nativeEmitter = emitter.get(offlineHandler.get(queryInterceptor.get(null)));
        String block = turn.event.toString() + "-unlock";
        Object card = textAnswer.invoke(null, dialog, block, prompt, "", "", false, false, 8);
        emitCard.invoke(nativeEmitter, turn.link, card);
        emitSignal.invoke(nativeEmitter, turn.link, waitDialog.invoke(null, dialog));
        if (turn.voice && !turn.stopped && !isCallAudioMode()) {
            String spoken = prepareSpokenText(prompt);
            if (!hasText(spoken)) return;
            Object request = speakRequest.newInstance(spoken, UUID.randomUUID().toString(), dialog, block,
                    defaultSpeakConfig.get(null), false);
            speak.invoke(null, DIALOG_SPEAK, request, turn.link, turn.agent, turn.windowState, "none", null);
            lastSpokenTurn = turn;
            lastSpokenElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    private void present(Turn turn, String question, String reply, long start) throws ReflectiveOperationException {
        if (turn.stopped || !current(turn)) return;
        ensureDialogStarted(turn, question);
        String dialog = turn.dialog;
        Object nativeEmitter = emitter.get(offlineHandler.get(queryInterceptor.get(null)));
        String block = turn.event.toString();
        Object card = textAnswer.invoke(null, dialog, block, reply, "", "", false, false, 8);
        emitCard.invoke(nativeEmitter, turn.link, card);
        emitSignal.invoke(nativeEmitter, turn.link, endDialog.invoke(null, dialog, null, null, 14));
        milestone("native_card_enqueued event=" + turn.event + " chars=" + reply.length() +
                " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
        if (turn.voice && !turn.stopped && current(turn)) {
            if (isCallAudioMode()) {
                milestone("native_speech_skipped reason=call_audio_mode event=" + turn.event);
                return;
            }
            String spoken = prepareSpokenText(reply);
            if (!hasText(spoken)) return;
            if (spoken.length() < reply.length()) {
                milestone("native_speech_compacted event=" + turn.event + " card_chars=" + reply.length() +
                        " spoken_chars=" + spoken.length());
            }
            Object request = speakRequest.newInstance(spoken, UUID.randomUUID().toString(), dialog, block,
                    defaultSpeakConfig.get(null), false);
            speak.invoke(null, DIALOG_SPEAK, request, turn.link, turn.agent, turn.windowState, "none", null);
            lastSpokenTurn = turn;
            lastSpokenElapsedMs = SystemClock.elapsedRealtime();
            milestone("native_speech_requested event=" + turn.event + " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
        }
    }

    private void presentFailure(Turn turn, String message) throws ReflectiveOperationException {
        if (turn.stopped) return;
        String dialog = resolveExistingDialog(turn);
        if (dialog == null || dialog.isEmpty()) return;
        Object nativeEmitter = emitter.get(offlineHandler.get(queryInterceptor.get(null)));
        String block = turn.event.toString();
        Object card = textAnswer.invoke(null, dialog, block, message, "", "", false, false, 8);
        emitCard.invoke(nativeEmitter, turn.link, card);
        emitSignal.invoke(nativeEmitter, turn.link, endDialog.invoke(null, dialog, null, null, 14));
        if (turn.voice && !turn.stopped && !isCallAudioMode()) {
            String spoken = prepareSpokenText(message);
            if (!hasText(spoken)) return;
            Object request = speakRequest.newInstance(spoken, UUID.randomUUID().toString(), dialog, block,
                    defaultSpeakConfig.get(null), false);
            speak.invoke(null, DIALOG_SPEAK, request, turn.link, turn.agent, turn.windowState, "none", null);
            lastSpokenTurn = turn;
            lastSpokenElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    private void finishStopped(Turn turn) throws ReflectiveOperationException {
        if (!turn.dialogEnded.compareAndSet(false, true)) return;
        String dialog = resolveExistingDialog(turn);
        if (dialog == null || dialog.isEmpty()) return;
        Object nativeEmitter = emitter.get(offlineHandler.get(queryInterceptor.get(null)));
        emitSignal.invoke(nativeEmitter, turn.link, endDialog.invoke(null, dialog, null, null, 14));
    }

    private void stopNativeSpeech(Turn target, String reason) {
        if (target == null || target.link == null || target.link.isEmpty()) return;
        String dialog = target.dialog != null ? target.dialog : "";
        String source = (reason != null && !reason.isEmpty()) ? reason : "floryx_stop";
        try {
            stopTts.invoke(null, DIALOG_SPEAK, target.link, dialog, source, true, null);
            milestone("native_tts_stop_requested event=" + target.event + " reason=" + source);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Log.w(TAG, "stop_tts_failed reason=" + source, failure);
        }
    }

    private boolean isDeviceLocked() {
        return keyguardManager != null && (keyguardManager.isKeyguardLocked() || keyguardManager.isDeviceLocked());
    }

    private boolean isCallAudioMode() {
        return audioManager != null && isCallAudioMode(audioManager.getMode());
    }

    private static boolean isCallAudioMode(int mode) {
        return mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_RINGTONE ||
                mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    static boolean isLocalStopCommand(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        int start = 0;
        int end = trimmed.length();
        while (start < end && PUNCTUATION_TRIM.indexOf(trimmed.charAt(start)) >= 0) start++;
        while (end > start && PUNCTUATION_TRIM.indexOf(trimmed.charAt(end - 1)) >= 0) end--;
        return LOCAL_STOP_COMMANDS.contains(trimmed.substring(start, end).toLowerCase(Locale.ROOT));
    }

    static String prepareSpokenText(String reply) {
        if (reply == null) return "";
        String strippedFences = MARKDOWN_CODE_FENCE.matcher(reply).replaceAll("");
        String[] rawLines = strippedFences.split("\\r?\\n+");
        StringBuilder joined = new StringBuilder();
        for (String rawLine : rawLines) {
            String line = MARKDOWN_HEADING.matcher(rawLine).replaceFirst("");
            line = MARKDOWN_BULLET.matcher(line).replaceFirst("");
            line = line.replace("**", "").replace("__", "").replace("`", "");
            line = MULTI_SPACE.matcher(line).replaceAll(" ").trim();
            if (line.isEmpty()) continue;
            if (joined.length() > 0) {
                char prev = joined.charAt(joined.length() - 1);
                if (LINE_JOIN_PUNCTUATION.indexOf(prev) < 0) {
                    joined.append('。');
                } else if (prev == '.' || prev == '!' || prev == '?' || prev == ',' || prev == ';' || prev == ':') {
                    joined.append(' ');
                }
            }
            joined.append(line);
        }
        String cleaned = joined.toString().trim();
        if (cleaned.isEmpty()) return "";
        if (cleaned.length() <= MAX_SPOKEN_CHARS) return cleaned;

        int budget = MAX_SPOKEN_CHARS - TRUNCATED_SPEECH_SUFFIX.length();
        if (budget <= 1) return TRUNCATED_SPEECH_SUFFIX;

        int sentenceEnd = -1;
        for (int i = budget - 1; i >= budget / 2; i--) {
            if (isSentenceBoundary(cleaned, i)) {
                sentenceEnd = i + 1;
                break;
            }
        }
        if (sentenceEnd > 0) {
            String prefix = cleaned.substring(0, sentenceEnd).trim();
            if (!prefix.isEmpty() && prefix.length() <= budget) {
                return prefix + TRUNCATED_SPEECH_SUFFIX;
            }
        }

        int clauseEnd = -1;
        for (int i = budget - 1; i >= budget / 3; i--) {
            char c = cleaned.charAt(i);
            if (CLAUSE_PAUSE_CHARS.indexOf(c) >= 0) {
                if (c == ' ' && i >= 2 && cleaned.charAt(i - 1) == '.' && Character.isDigit(cleaned.charAt(i - 2))) {
                    continue;
                }
                clauseEnd = i;
                break;
            }
        }
        if (clauseEnd > 0) {
            String prefix = trimTrailingChars(cleaned.substring(0, clauseEnd), TRAILING_TRIM_CHARS);
            if (!prefix.isEmpty() && prefix.length() <= budget - 1) {
                return appendPeriodIfNeeded(prefix) + TRUNCATED_SPEECH_SUFFIX;
            }
        }

        int cut = Math.min(cleaned.length(), budget - 1);
        if (cut > 0 && Character.isHighSurrogate(cleaned.charAt(cut - 1)) && Character.isLowSurrogate(cleaned.charAt(cut))) {
            cut--;
        }
        String prefix = trimTrailingChars(cleaned.substring(0, cut), TRAILING_TRIM_CHARS);
        if (prefix.isEmpty()) return TRUNCATED_SPEECH_SUFFIX;
        return appendPeriodIfNeeded(prefix) + TRUNCATED_SPEECH_SUFFIX;
    }

    private static boolean isSentenceBoundary(String text, int index) {
        char c = text.charAt(index);
        if (SENTENCE_END_CHARS.indexOf(c) >= 0) return true;
        if (c != '.') return false;
        boolean precededByDigit = index > 0 && Character.isDigit(text.charAt(index - 1));
        boolean followedByWordChar = index + 1 < text.length() && Character.isLetterOrDigit(text.charAt(index + 1));
        return !precededByDigit && !followedByWordChar;
    }

    private static String appendPeriodIfNeeded(String prefix) {
        if (prefix.isEmpty()) return "";
        char last = prefix.charAt(prefix.length() - 1);
        if (SENTENCE_END_CHARS.indexOf(last) >= 0 || last == '.') return prefix;
        return prefix + "。";
    }

    private static String trimTrailingChars(String value, String chars) {
        int end = value.length();
        while (end > 0 && chars.indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }

    private static boolean isExplicitUserCancel(String type) {
        if (type == null) return false;
        String normalized = type.toLowerCase(Locale.ROOT);
        if (normalized.contains("countdown")) return false;
        return normalized.contains("back") || normalized.contains("click") ||
                normalized.contains("close") || normalized.contains("touch") ||
                normalized.contains("cancel") || normalized.contains("user") ||
                normalized.contains("outside");
    }

    private void milestone(String message) {
        module.log(Log.INFO, TAG, message);
        Log.w(TAG, message);
    }

    private void rememberDismissTimer(Object timer) {
        if (timer == null) return;
        synchronized (dismissTimers) {
            Iterator<WeakReference<Object>> iterator = dismissTimers.iterator();
            while (iterator.hasNext()) {
                Object existing = iterator.next().get();
                if (existing == null) iterator.remove();
                else if (existing == timer) return;
            }
            dismissTimers.add(new WeakReference<>(timer));
        }
    }

    private void cancelDismissTimers() {
        List<Object> targets = new ArrayList<>();
        synchronized (dismissTimers) {
            Iterator<WeakReference<Object>> iterator = dismissTimers.iterator();
            while (iterator.hasNext()) {
                Object existing = iterator.next().get();
                if (existing == null) iterator.remove();
                else targets.add(existing);
            }
        }
        for (Object timer : targets) cancelTimerInstance(timer);
    }

    private void cancelTimerInstance(Object timer) {
        if (timer == null) return;
        try {
            cancelDismissTimer.invoke(timer);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall back to blocking the countdown completion and countDown dismissal hooks.
        }
    }

    private void trackPanelView(Object candidate, Object params) {
        if (candidate instanceof View && isCopilotPanelParams(params)) {
            addPanelView((View) candidate);
        }
    }

    private void addPanelView(View view) {
        synchronized (panelViews) {
            Iterator<WeakReference<View>> iterator = panelViews.iterator();
            while (iterator.hasNext()) {
                View existing = iterator.next().get();
                if (existing == null) {
                    iterator.remove();
                } else if (existing == view) {
                    return;
                }
            }
            panelViews.add(new WeakReference<>(view));
        }
    }

    private void untrackPanelView(Object candidate) {
        if (!(candidate instanceof View)) return;
        synchronized (panelViews) {
            Iterator<WeakReference<View>> iterator = panelViews.iterator();
            while (iterator.hasNext()) {
                View existing = iterator.next().get();
                if (existing == null || existing == candidate) iterator.remove();
            }
        }
    }

    private List<View> snapshotPanelViews() {
        List<View> live = new ArrayList<>();
        synchronized (panelViews) {
            Iterator<WeakReference<View>> iterator = panelViews.iterator();
            while (iterator.hasNext()) {
                View view = iterator.next().get();
                if (view == null) iterator.remove();
                else live.add(view);
            }
        }
        return live;
    }

    private void scanWindowManagerGlobal() {
        try {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal", false, loader);
            Object global = globalClass.getDeclaredMethod("getInstance").invoke(null);
            if (global == null) return;
            Object lock = field(globalClass, "mLock").get(global);
            Field viewsField = field(globalClass, "mViews");
            Field paramsField = field(globalClass, "mParams");
            if (lock == null) return;
            synchronized (lock) {
                Object rawViews = viewsField.get(global);
                Object rawParams = paramsField.get(global);
                if (!(rawViews instanceof List<?>) || !(rawParams instanceof List<?>)) return;
                List<?> views = (List<?>) rawViews;
                List<?> paramsList = (List<?>) rawParams;
                int count = Math.min(views.size(), paramsList.size());
                for (int i = 0; i < count; i++) {
                    trackPanelView(views.get(i), paramsList.get(i));
                }
            }
        } catch (Throwable ignored) {
            // Hidden API restrictions fall back to WindowManagerImpl hooks.
        }
    }

    private int hidePanels(List<View> yieldedViews) {
        List<View> candidates = snapshotPanelViews();
        if (candidates.isEmpty()) {
            scanWindowManagerGlobal();
            candidates = snapshotPanelViews();
        }
        if (candidates.isEmpty()) return 0;
        final List<View> targets = candidates;
        final int[] hidden = {0};
        runOnMainSync(() -> {
            for (View view : targets) {
                if (view.getVisibility() != View.GONE) {
                    view.setVisibility(View.GONE);
                    if (!yieldedViews.contains(view)) yieldedViews.add(view);
                    hidden[0]++;
                }
            }
        });
        return hidden[0];
    }

    private void restorePanels(List<View> yieldedViews, Turn turn, long start) {
        if (yieldedViews.isEmpty()) return;
        final int[] restored = {0};
        runOnMainSync(() -> {
            for (View view : yieldedViews) {
                if (view.isAttachedToWindow() && view.getVisibility() != View.VISIBLE) {
                    view.setVisibility(View.VISIBLE);
                    restored[0]++;
                }
            }
        });
        if (restored[0] > 0) SystemClock.sleep(PANEL_SETTLE_MS);
        milestone("panel_restored event=" + turn.event + " views=" + restored[0] +
                " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
    }

    private void runOnMainSync(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        if (!mainHandler.post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        })) {
            return;
        }
        try {
            latch.await(1_500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isCopilotPanelParams(Object params) {
        if (!(params instanceof WindowManager.LayoutParams)) return false;
        CharSequence title = ((WindowManager.LayoutParams) params).getTitle();
        return title != null && title.toString().contains("CopilotPanelWindow");
    }

    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private static boolean pending(Bundle result) {
        String state = result.getString(STATE);
        return RUNNING.equals(state) || WAITING_UNLOCK.equals(state) || UNKNOWN.equals(state) || MISSING.equals(state);
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(ROOT + name, false, loader);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static boolean isXiaov(String agent) {
        return "copilot".equals(agent) || "little_v".equals(agent);
    }

    private static boolean validText(String text) {
        return text != null && !text.trim().isEmpty() && text.length() <= MAX_TRANSCRIPT_LENGTH;
    }

    private static String string(Object target, String method) {
        return (String) callMethod(target, method);
    }

    private static Object callMethod(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException mismatch) {
            throw new IllegalStateException("Unexpected Xiaov method " + name, mismatch);
        }
    }

    private static UUID id(String type, String source) {
        return UUID.nameUUIDFromBytes((XiaovTarget.PACKAGE + ":" + type + ":" + source).getBytes(StandardCharsets.UTF_8));
    }

    private static final class Turn {
        final long seq;
        final String conversation;
        final String link;
        final String window;
        final String agent;
        final String windowState;
        final boolean voice;
        final UUID session;
        final UUID event;
        final AtomicBoolean dialogEnded = new AtomicBoolean(false);
        volatile String dialog;
        volatile boolean submitted;
        volatile boolean stopped;
        volatile boolean unlockPrompted;
        volatile boolean awaitingInAppConfirmation;

        Turn(long seq, String conversation, String link, String window, String agent, String windowState, String dialog, boolean voice) {
            this.seq = seq;
            this.conversation = conversation;
            this.link = link;
            this.window = window;
            this.agent = agent;
            this.windowState = windowState;
            this.dialog = dialog;
            this.voice = voice;
            session = id("window", window);
            event = voice ? id("voice", link) : id("keyboard", link + ":" + dialog);
        }

        boolean valid() { return !link.isEmpty() && !window.isEmpty(); }
    }
}
