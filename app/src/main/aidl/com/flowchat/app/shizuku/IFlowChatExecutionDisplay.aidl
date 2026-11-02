package com.flowchat.app.shizuku;

/** All coordinates and navigation are confined to the owned display. */
interface IFlowChatExecutionDisplay {
    String getInfo() = 1;
    String launchApp(String packageName, String activityName) = 2;
    boolean tap(int x, int y, boolean longPress) = 3;
    boolean swipe(int x1, int y1, int x2, int y2, int durationMillis) = 4;
    boolean pressBack() = 5;
    boolean clearApps() = 6;
}
