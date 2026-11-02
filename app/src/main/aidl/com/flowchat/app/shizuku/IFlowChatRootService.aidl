package com.flowchat.app.shizuku;

import com.flowchat.app.shizuku.IFlowChatShizukuService;
import com.flowchat.app.shizuku.IFlowChatExecutionDisplay;

/** A request is never replayed after a transport failure. */
interface IFlowChatRootService {
    String getServiceInfo() = 1;
    IFlowChatShizukuService openRequest(String requestId) = 2;
    void finishRequest(String requestId, boolean cancelled) = 3;
    IFlowChatExecutionDisplay openExecutionDisplay(String ownerId) = 4;
    void closeExecutionDisplay(String ownerId) = 5;
}
