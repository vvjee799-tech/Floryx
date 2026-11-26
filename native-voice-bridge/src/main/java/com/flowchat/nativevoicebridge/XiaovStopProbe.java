package com.flowchat.nativevoicebridge;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Diagnostic build only. Observe the verified native engine without changing its inputs or results. */
final class XiaovStopProbe {
    private static final String ROOT = "com.vivo.ai.copilot.";

    private XiaovStopProbe() {}

    static void install(XiaovModule module, ClassLoader loader) {
        try {
            Method configure = Class.forName(ROOT + "base.voice.engine.logic.EngineCore", false, loader)
                    .getDeclaredMethod("n", Bundle.class, String.class);
            Method capability = Class.forName(ROOT + "base.voice.VoiceManager", false, loader)
                    .getDeclaredMethod("d", int.class);
            Method nativeStop = Class.forName(ROOT + "gateway.DuplexSimulator", false, loader)
                    .getDeclaredMethod("c", String.class, boolean.class);

            module.hook(configure).intercept(chain -> {
                Object input = chain.getArg(0);
                if (input instanceof Bundle bundle) {
                    Bundle gpt = bundle.getBundle("key_gpt_bundle");
                    if (gpt != null) log("asr_config type=" + gpt.getInt("key_asr_type", -1) +
                            " full_duplex=" + gpt.getBoolean("key_full_duplex") +
                            " sco=" + gpt.getBoolean("key_recognize_by_sco"));
                }
                return chain.proceed();
            });
            AtomicIntegerArray observedCapabilities = new AtomicIntegerArray(6);
            module.hook(capability).intercept(chain -> {
                Object result = chain.proceed();
                int feature = (Integer) chain.getArg(0);
                if (feature >= 3 && feature <= 5) {
                    int value = Boolean.TRUE.equals(result) ? 2 : 1;
                    if (observedCapabilities.getAndSet(feature, value) != value) {
                        log("voice_capability feature=" + feature + " enabled=" + (value == 2));
                    }
                }
                return result;
            });
            module.hook(nativeStop).intercept(chain -> {
                Object type = chain.getArg(0);
                if ("stop".equals(type) || "stop_dialog".equals(type)) {
                    log("duplex_stop type=" + type + " restart=" + chain.getArg(1));
                }
                return chain.proceed();
            });
            log("installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            Log.w(XiaovModule.TAG, "stop_probe_unavailable", unavailable);
        }
    }

    private static void log(String message) {
        Log.i(XiaovModule.TAG, "stop_probe " + message + " elapsed_ms=" + SystemClock.elapsedRealtime());
    }
}
