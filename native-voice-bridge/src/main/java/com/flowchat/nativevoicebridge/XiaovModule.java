package com.flowchat.nativevoicebridge;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Process;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

public final class XiaovModule extends XposedModule {
    static final String TAG = "FloryxXiaov";
    private boolean mainProcess;
    private boolean initialized;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        mainProcess = XiaovTarget.PACKAGE.equals(param.getProcessName());
        if (mainProcess) {
            log(Log.INFO, TAG, "module_loaded");
            Log.i(TAG, "module_loaded");
        }
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!mainProcess || !param.isFirstPackage() || !XiaovTarget.PACKAGE.equals(param.getPackageName())) return;
        try {
            hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class)).intercept(chain -> {
                initialize((Application) chain.getArg(0), param.getClassLoader());
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            log(Log.WARN, TAG, "package_hook_unavailable", incompatible);
            Log.w(TAG, "package_hook_unavailable", incompatible);
        }
    }

    private synchronized void initialize(Application app, ClassLoader loader) {
        if (initialized || !XiaovTarget.PACKAGE.equals(app.getPackageName())) return;
        initialized = true;
        try {
            if (!XiaovTarget.matches(app.getPackageManager(), Process.myUid())) {
                log(Log.WARN, TAG, "package_identity_mismatch");
                Log.w(TAG, "package_identity_mismatch");
                return;
            }
            new XiaovAdapter(this, app, loader).install();
            if (BuildConfig.NATIVE_VOICE_STOP_PROBE) XiaovStopProbe.install(this, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            log(Log.WARN, TAG, "adapter_unavailable", incompatible);
            Log.w(TAG, "adapter_unavailable", incompatible);
        }
    }
}
