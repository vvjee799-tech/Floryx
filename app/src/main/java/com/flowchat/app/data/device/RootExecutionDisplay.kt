package com.flowchat.app.data.device

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Root-owned, own-content, non-secure display. Never mirrors or falls back to display zero. */
@android.annotation.TargetApi(35)
internal class RootExecutionDisplay private constructor(
    private val context: Context, val width: Int, val height: Int, val density: Int
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val frameLock = Object()
    private var latest: Image? = null
    private var sequence = 0L
    private val frames = HandlerThread("floryx-execution-frames").apply { start() }
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    private val commands = ProcessDeviceCommandExecutor(processGroup = true)
    private var display: VirtualDisplay? = null
    val id: Int get() = requireNotNull(display).display.displayId

    init {
        try {
            // Android excludes private virtual displays from accessibility window tracking.
            // PUBLIC + OWN_CONTENT_ONLY enables controls without ever mirroring the phone.
            val flags = listOf("VIRTUAL_DISPLAY_FLAG_PUBLIC", "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", "VIRTUAL_DISPLAY_FLAG_TRUSTED",
                "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED",
                "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
                .fold(0) { value, name -> value or DisplayManager::class.java.getField(name).getInt(null) }
            display = requireNotNull(context.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay(VirtualDisplayConfig.Builder("Floryx execution display", width, height, density)
                    .setSurface(reader.surface).setFlags(flags).setRequestedRefreshRate(30f).build()))
            check(id > 0) { "Execution display must not be the physical display." }
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
            val hideIme = Class.forName("android.view.WindowManager").getField("DISPLAY_IME_POLICY_HIDE").getInt(null)
            Class.forName("android.view.IWindowManager").getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).invoke(wm, id, hideIme)
            reader.setOnImageAvailableListener({ source ->
                runCatching { source.acquireLatestImage() }.getOrNull()?.let { image ->
                    synchronized(frameLock) {
                        if (closed.get()) image.close() else {
                            latest?.close(); latest = image; sequence++; frameLock.notifyAll()
                        }
                    }
                }
            }, Handler(frames.looper))
        } catch (error: Throwable) {
            display?.release()
            reader.close(); frames.quitSafely(); commands.close()
            throw error
        }
    }

    fun info(): String = JSONObject().apply {
        check(!closed.get()) { "Execution display has closed." }
        put("displayId", id); put("width", width); put("height", height); put("density", density)
        put("frameSequence", synchronized(frameLock) { sequence }); put("closed", closed.get())
        put("refreshRateHz", requireNotNull(display).display.refreshRate)
        val task = rootTasks().firstOrNull { it.javaClass.getField("displayId").getInt(it) == id &&
            it.javaClass.getField("topActivity").get(it) != null }
        val component = task?.javaClass?.getField("topActivity")?.get(task) as? ComponentName
        put("packageName", component?.packageName.orEmpty())
        put("activityName", component?.className.orEmpty())
    }.toString()

    private fun rootTasks(): List<Any> {
        val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        return (Class.forName("android.app.IActivityTaskManager").getMethod("getAllRootTaskInfos").invoke(manager) as List<*>).filterNotNull()
    }

    fun launch(component: ComponentName): DeviceCommandResult {
        require(component.packageName != context.packageName) { "Floryx cannot launch itself on the execution display." }
        @Suppress("DEPRECATION")
        val activity = context.packageManager.getActivityInfo(component, 0)
        require(activity.enabled && activity.exported) { "The activity is unavailable for background execution." }
        require(activity.launchMode == ActivityInfo.LAUNCH_MULTIPLE || activity.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) {
            "This app uses a shared activity task and cannot safely launch on the execution display."
        }
        require(activity.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING == 0) { "This activity can move to the phone screen." }
        return execute(listOf("am", "start", "-W", "--display", id.toString(),
            "-f", "0x18800000", "-n", component.flattenToString()))
    }

    fun tap(x: Int, y: Int): DeviceCommandResult {
        require(x in 0 until width && y in 0 until height)
        return execute(listOf("input", "-d", id.toString(), "tap", x.toString(), y.toString()))
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): DeviceCommandResult {
        require(x1 in 0 until width && x2 in 0 until width && y1 in 0 until height && y2 in 0 until height)
        require(duration in 100..1500)
        return execute(listOf("input", "-d", id.toString(), "swipe", x1.toString(), y1.toString(),
            x2.toString(), y2.toString(), duration.toString()))
    }

    fun back(): DeviceCommandResult = execute(listOf("input", "-d", id.toString(), "keyevent", "4"))

    fun clearApps(): Boolean {
        check(!closed.get() && id > 0)
        val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val remove = Class.forName("android.app.IActivityTaskManager").getMethod("removeTask", Int::class.javaPrimitiveType)
        return rootTasks().filter { it.javaClass.getField("displayId").getInt(it) == id }.all {
            remove.invoke(manager, it.javaClass.getField("taskId").getInt(it)) == true
        }
    }

    fun asciiText(text: String): DeviceCommandResult {
        require(text.length in 1..2000 && text.all { it.code in 32..126 && it != '%' })
        return execute(listOf("input", "-d", id.toString(), "text", text.replace(" ", "%s")))
    }

    private fun execute(argv: List<String>): DeviceCommandResult {
        check(!closed.get()) { "Execution display has closed." }
        check(id > 0)
        return commands.execute(argv)
    }

    fun bitmap(timeoutMillis: Long = 2_000): Bitmap = synchronized(frameLock) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (latest == null && !closed.get()) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            check(remaining > 0) { "No execution display frame arrived." }
            frameLock.wait(remaining.coerceAtLeast(1))
        }
        check(!closed.get()) { "Execution display has closed." }
        val image = requireNotNull(latest)
        val plane = image.planes[0]
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        commands.close()
        onMain {
            reader.setOnImageAvailableListener(null, null)
            display?.release()
            synchronized(frameLock) { latest?.close(); latest = null; frameLock.notifyAll() }
            reader.close(); frames.quitSafely()
        }
    }

    companion object {
        fun create(context: Context, width: Int = 360, height: Int = 720, density: Int = 160): RootExecutionDisplay {
            require(width in 320..1080 && height in 480..1920 && density in 120..480)
            val abandoned = AtomicBoolean(false)
            val created = java.util.concurrent.atomic.AtomicReference<RootExecutionDisplay?>(null)
            val task = FutureTask {
                RootExecutionDisplay(context, width, height, density).also {
                    created.set(it)
                    if (abandoned.get()) { it.close(); error("Execution display creation cancelled.") }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else Handler(Looper.getMainLooper()).post(task)
            return try { task.get(5, TimeUnit.SECONDS) } catch (error: Throwable) {
                abandoned.set(true); task.cancel(false); created.get()?.close(); throw error
            }
        }

        private fun <T> onMain(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            val task = FutureTask(block)
            Handler(Looper.getMainLooper()).post(task)
            return task.get(5, TimeUnit.SECONDS)
        }
    }
}
