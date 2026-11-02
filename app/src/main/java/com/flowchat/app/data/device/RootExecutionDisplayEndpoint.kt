package com.flowchat.app.data.device

import android.content.ComponentName
import android.os.Binder
import android.os.Parcel
import com.flowchat.app.shizuku.IFlowChatExecutionDisplay
import org.json.JSONObject

internal class RootExecutionDisplayEndpoint(
    private val screen: RootExecutionDisplay,
    private val checkOwner: () -> Unit
) : IFlowChatExecutionDisplay.Stub(), AutoCloseable {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        checkOwner()
        return super.onTransact(code, data, reply, flags)
    }

    private fun <T> authorized(action: () -> T): T {
        checkOwner()
        val identity = Binder.clearCallingIdentity()
        return try { action() } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun getInfo(): String = authorized { screen.info() }
    override fun launchApp(packageName: String, activityName: String): String = authorized {
        val result = screen.launch(ComponentName(packageName, activityName))
        JSONObject().put("success", result.success).put("output", result.output).toString()
    }
    override fun tap(x: Int, y: Int, longPress: Boolean): Boolean = authorized {
        (if (longPress) screen.swipe(x, y, x, y, 650) else screen.tap(x, y)).success
    }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Int): Boolean = authorized {
        screen.swipe(x1, y1, x2, y2, durationMillis).success
    }
    override fun pressBack(): Boolean = authorized { screen.back().success }
    override fun clearApps(): Boolean = authorized { screen.clearApps() }
    override fun close() = screen.close()
}
