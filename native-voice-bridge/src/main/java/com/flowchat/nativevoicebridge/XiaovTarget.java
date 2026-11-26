package com.flowchat.nativevoicebridge;

import android.content.pm.PackageManager;

final class XiaovTarget {
    static final String PACKAGE = VoiceBridgeWire.XIAOV_PACKAGE;
    static final int VERSION_CODE = VoiceBridgeWire.XIAOV_VERSION_CODE;

    static boolean matches(PackageManager packages, int uid) {
        return VoiceBridgeWire.matchesXiaov(packages, uid);
    }

    private XiaovTarget() {}
}
