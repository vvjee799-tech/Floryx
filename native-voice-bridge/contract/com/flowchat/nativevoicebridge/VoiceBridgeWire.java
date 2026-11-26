package com.flowchat.nativevoicebridge;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/** Versioned IPC contract between the independent bridge and the opt-in Dev endpoint. */
public final class VoiceBridgeWire {
    public static final String BRIDGE_PACKAGE = "com.floryx.nativevoicebridge";
    public static final String DEV_PACKAGE = "com.floryx.app.debug";
    public static final String XIAOV_PACKAGE = "com.vivo.ai.copilot";
    public static final int XIAOV_VERSION_CODE = 6110101;
    public static final String XIAOV_CERTIFICATE = "bcc35d4d3606f154f0402ab7634e8490c0b244c2675c3c6238986987024f0c02";
    public static final String PERMISSION = DEV_PACKAGE + ".permission.NATIVE_VOICE_BRIDGE";
    public static final String AUTHORITY = DEV_PACKAGE + ".native_voice";
    public static final Uri ENDPOINT = Uri.parse("content://" + AUTHORITY);
    public static final int VERSION = 1;
    public static final int MAX_TRANSCRIPT_LENGTH = 8_000;

    public static final String PROBE = "probe";
    public static final String SUBMIT = "submit";
    public static final String STATUS = "status";
    public static final String LATEST = "latest";
    public static final String STOP = "stop";
    public static final String RESUME = "resume";
    public static final String CLAIM_PLAYBACK = "claim_playback";
    public static final String ACK_PANEL_YIELD = "ack_panel_yield";

    public static final String PROTOCOL_VERSION = "version";
    public static final String SESSION_ID = "session_id";
    public static final String REQUEST_ID = "request_id";
    public static final String TRANSCRIPT = "transcript";
    public static final String STOP_SOURCE = "stop_source";
    public static final String CONVERSATION_ID = "conversation_id";
    public static final String TASK_ID = "task_id";
    public static final String TASK_STATE = "task_state";
    public static final String STATE = "state";
    public static final String REPLY = "reply";
    public static final String STATUS_TEXT = "status_text";
    public static final String NEWLY_ACCEPTED = "newly_accepted";
    public static final String PLAYBACK_CLAIMED = "playback_claimed";
    public static final String YIELD_PANEL = "yield_panel";
    public static final String PROVIDER_ID = "provider_id";
    public static final String MODEL_NAME = "model_name";
    public static final String RUNTIME_MODE = "runtime_mode";

    public static final String READY = "Ready";
    public static final String IGNORED = "Ignored";
    public static final String MISSING = "Missing";
    public static final String REJECTED = "Rejected";
    public static final String INCOMPATIBLE = "Incompatible";
    public static final String UNKNOWN = "Unknown";
    public static final String RUNNING = "Running";
    public static final String WAITING_UNLOCK = "WaitingUnlock";
    public static final String WAITING_USER = "WaitingForUser";
    public static final String INTERRUPTED = "Interrupted";
    public static final String FAILED = "Failed";
    public static final String STOPPED = "Stopped";
    public static final String COMPLETED = "Completed";

    public static Bundle state(String state) {
        Bundle result = new Bundle();
        result.putInt(PROTOCOL_VERSION, VERSION);
        result.putString(STATE, state);
        return result;
    }

    /** Verifies caller UID belongs exclusively to the expected signed Xiaov build. */
    public static boolean matchesXiaov(PackageManager packages, int uid) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            PackageInfo app = packages.getPackageInfo(XIAOV_PACKAGE, 0);
            String[] uidPackages = packages.getPackagesForUid(uid);
            return app.getLongVersionCode() == XIAOV_VERSION_CODE && app.applicationInfo != null &&
                    app.applicationInfo.uid == uid && uidPackages != null && uidPackages.length == 1 &&
                    XIAOV_PACKAGE.equals(uidPackages[0]) &&
                    packages.hasSigningCertificate(XIAOV_PACKAGE, xiaovCertificateDigest(), PackageManager.CERT_INPUT_SHA256);
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    private static byte[] xiaovCertificateDigest() {
        byte[] digest = new byte[XIAOV_CERTIFICATE.length() / 2];
        for (int i = 0; i < digest.length; i++) {
            digest[i] = (byte) Integer.parseInt(XIAOV_CERTIFICATE.substring(i * 2, i * 2 + 2), 16);
        }
        return digest;
    }

    private VoiceBridgeWire() {}
}
