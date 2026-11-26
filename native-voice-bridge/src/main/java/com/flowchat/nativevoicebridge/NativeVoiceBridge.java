package com.flowchat.nativevoicebridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;

import java.util.UUID;

import static com.flowchat.nativevoicebridge.VoiceBridgeWire.*;

/** Synchronous and intended for a worker thread. Keep source IDs across IPC and panel recreation. */
public final class NativeVoiceBridge {
    private static final Object PANEL_LOCK = new Object();
    private final Context context;

    public NativeVoiceBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    /** A new final input gets a new event ID. Retries keep the original event ID. */
    public Bundle onTranscript(UUID sessionId, UUID eventId, String text, boolean isFinal) {
        if (!isFinal) return state(IGNORED);
        if (text == null || text.length() > MAX_TRANSCRIPT_LENGTH) return state(REJECTED);
        return call(SUBMIT, sessionId, eventId, text);
    }

    public Bundle status(UUID sessionId, UUID eventId) {
        return call(STATUS, sessionId, eventId, null);
    }

    Bundle claimPlayback(UUID sessionId, UUID eventId) {
        return call(CLAIM_PLAYBACK, sessionId, eventId, null);
    }

    public Bundle ackPanelYield(UUID sessionId, UUID eventId) {
        return call(ACK_PANEL_YIELD, sessionId, eventId, null);
    }

    boolean isDevAvailable() {
        PackageManager packages = context.getPackageManager();
        ProviderInfo endpoint = packages.resolveContentProvider(AUTHORITY, 0);
        return endpoint != null && DEV_PACKAGE.equals(endpoint.packageName) &&
                packages.checkSignatures(BRIDGE_PACKAGE, DEV_PACKAGE) == PackageManager.SIGNATURE_MATCH;
    }

    /** Re-query with the session ID even when the original acceptance receipt was lost. */
    public Bundle presentLatest(UUID sessionId, PanelOutput panel) {
        synchronized (PANEL_LOCK) {
            Bundle latest = call(LATEST, sessionId, null, null);
            if (!COMPLETED.equals(latest.getString(STATE))) return latest;

            UUID requestId = UUID.fromString(latest.getString(REQUEST_ID));
            // Claim against the current task before projecting any reply from a prior IPC result.
            Bundle current = claimPlayback(sessionId, requestId);
            if (COMPLETED.equals(current.getString(STATE))) {
                String reply = current.getString(REPLY);
                panel.showReply(reply);
                if (current.getBoolean(PLAYBACK_CLAIMED)) panel.speak(reply);
            }
            return current;
        }
    }

    public Bundle stop(UUID sessionId, UUID eventId, PanelOutput panel) {
        synchronized (PANEL_LOCK) {
            panel.stopPlayback();
            return call(STOP, sessionId, eventId, null);
        }
    }

    public Bundle resume(UUID sessionId, UUID eventId) {
        return call(RESUME, sessionId, eventId, null);
    }

    private Bundle call(String operation, UUID sessionId, UUID eventId, String text) {
        if (!isDevAvailable()) return state(UNKNOWN);
        Bundle input = new Bundle();
        input.putInt(PROTOCOL_VERSION, VERSION);
        input.putString(SESSION_ID, sessionId.toString());
        if (eventId != null) input.putString(REQUEST_ID, eventId.toString());
        if (text != null) input.putString(TRANSCRIPT, text);
        try {
            Bundle result = context.getContentResolver().call(ENDPOINT, operation, null, input);
            if (result == null) return state(UNKNOWN);
            if (result.getInt(PROTOCOL_VERSION) != VERSION) return state(INCOMPATIBLE);
            return result;
        } catch (RuntimeException error) {
            // Acceptance may have committed before the transport failed. Re-query by ID.
            return state(UNKNOWN);
        }
    }

    public interface PanelOutput {
        void showReply(String reply);
        void speak(String reply);
        void stopPlayback();
    }
}
