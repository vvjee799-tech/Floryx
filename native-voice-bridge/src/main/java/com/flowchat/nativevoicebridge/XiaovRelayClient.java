package com.flowchat.nativevoicebridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.util.Log;

import java.util.UUID;

import static com.flowchat.nativevoicebridge.VoiceBridgeWire.*;
import static com.flowchat.nativevoicebridge.XiaovModule.TAG;

final class XiaovRelayClient {
    private final Context context;

    XiaovRelayClient(Context context) {
        this.context = context;
    }

    boolean ready() {
        return READY.equals(call(PROBE, null, null, null).getString(STATE));
    }

    Bundle call(String method, UUID session, UUID event, String text) {
        return call(method, session, event, text, null);
    }

    Bundle stop(UUID session, UUID event, String source) {
        return call(STOP, session, event, null, source);
    }

    private Bundle call(String method, UUID session, UUID event, String text, String stopSource) {
        try {
            PackageManager packages = context.getPackageManager();
            String hostPackage = resolveHostPackage(packages);
            if (hostPackage == null) {
                ProviderInfo releaseProvider = packages.resolveContentProvider(authorityFor(RELEASE_PACKAGE), 0);
                ProviderInfo devProvider = packages.resolveContentProvider(authorityFor(DEV_PACKAGE), 0);
                int releaseSig = packages.checkSignatures(BRIDGE_PACKAGE, RELEASE_PACKAGE);
                int devSig = packages.checkSignatures(BRIDGE_PACKAGE, DEV_PACKAGE);
                Log.w(TAG, "host_endpoint_unavailable method=" + method +
                        " releaseProvider=" + releaseProvider + " releaseSig=" + releaseSig +
                        " devProvider=" + devProvider + " devSig=" + devSig);
                return state(UNKNOWN);
            }
            Bundle input = new Bundle();
            input.putInt(PROTOCOL_VERSION, VERSION);
            if (session != null) input.putString(SESSION_ID, session.toString());
            if (event != null) input.putString(REQUEST_ID, event.toString());
            if (text != null) input.putString(TRANSCRIPT, text);
            if (stopSource != null) input.putString(STOP_SOURCE, stopSource);
            Bundle result = context.getContentResolver().call(endpointFor(hostPackage), method, null, input);
            if (result == null) {
                Log.w(TAG, "dev_call_null_reply method=" + method);
                return state(UNKNOWN);
            }
            if (result.getInt(PROTOCOL_VERSION) != VERSION) {
                Log.w(TAG, "dev_call_incompatible method=" + method + " version=" + result.getInt(PROTOCOL_VERSION));
                return state(INCOMPATIBLE);
            }
            return result;
        } catch (RuntimeException transportError) {
            Log.w(TAG, "dev_call_failed method=" + method + " error=" + transportError.getClass().getSimpleName() +
                    ": " + transportError.getMessage());
            return state(UNKNOWN);
        }
    }
}
