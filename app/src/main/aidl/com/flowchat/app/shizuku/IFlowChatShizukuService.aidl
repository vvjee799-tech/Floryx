package com.flowchat.app.shizuku;

import com.flowchat.app.shizuku.IFlowChatExecutionDisplay;

interface IFlowChatShizukuService {
    void destroy() = 16777114;
    String probeCapabilities() = 1;
    String getDeviceStatus() = 2;
    String getForegroundApp() = 3;
    String setScreenBrightness(int percent) = 4;
    String setMediaVolume(int percent) = 5;
    String forceStopPackage(String packageName) = 6;
    String enablePowerMode() = 7;
    String launchApp(String packageName, String activityName) = 8;
    String startShellCommand(String command, String workingDirectory, int timeoutSeconds) = 9;
    String readShellCommand(String commandId) = 10;
    String cancelShellCommand(String commandId) = 11;
    String openPublicAppAction(String tool, String arguments, int userId) = 12;
    IFlowChatExecutionDisplay getRequestExecutionDisplay(String ownerId) = 13;
    String inspectCalendarEvents(String arguments, int userId) = 14;
    String openSavedCalendarEvent(String arguments, long eventId, long calendarId, int userId) = 15;
    boolean reconnectSelectedAccessibilityService(int userId) = 16;
    boolean restoreSystemClearedAccessibilityService(int userId) = 17;
    String pasteFocusedText(boolean replaceExisting) = 18;
}
