package com.flowchat.app.domain.device

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** These registrations open editors only. They do not certify a call or saved calendar event. */
object PublicAppActions {
    const val Dialer = "open_phone_dialer"
    const val Calendar = "open_calendar_editor"
    val Names = setOf(Dialer, Calendar)

    data class Registration(val id: String, val packageName: String, val versionCode: Long,
        val component: String, val action: String, val mimeType: String? = null)

    val Phone = Registration("vivo.dialer.prefill.v1", "com.android.contacts", 1620700,
        "com.android.contacts/com.android.dialer.TwelveKeyDialer", "android.intent.action.DIAL")
    val Event = Registration("vivo.calendar.editor.v1", "com.bbk.calendar", 7540,
        "com.bbk.calendar/.event.EditEventActivity", "android.intent.action.INSERT", "vnd.android.cursor.item/event")

    enum class CalendarCompletion { Draft, Save }
    data class Request(val registration: Registration, val phoneNumber: String? = null,
        val title: String? = null, val beginTimeMillis: Long? = null, val endTimeMillis: Long? = null,
        val completion: CalendarCompletion = CalendarCompletion.Draft)

    fun parse(tool: String, arguments: String): Request {
        val fields = Json.parseToJsonElement(arguments) as? JsonObject ?: error("Arguments must be an object.")
        fun string(key: String): String = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("$key must be a string.")
        return when (tool) {
            Dialer -> {
                require(fields.keys == setOf("phone_number")) { "Only phone_number is supported." }
                val number = string("phone_number")
                require(number.matches(Regex("\\+?[0-9]{3,20}"))) { "Use an explicit number: 3–20 digits, optional leading +; no contacts, USSD or extensions." }
                Request(Phone, phoneNumber = number)
            }
            Calendar -> {
                require(fields.keys.containsAll(setOf("title", "begin_time", "end_time")) &&
                    fields.keys.all { it in setOf("title", "begin_time", "end_time", "completion") }) {
                    "Only title, begin_time, end_time and optional completion are supported."
                }
                val completion = when (if ("completion" in fields) string("completion") else "draft") {
                    "draft" -> CalendarCompletion.Draft; "save" -> CalendarCompletion.Save; else -> error("completion must be draft or save")
                }
                val title = string("title")
                require(title.isNotBlank() && title.length <= 120 && title.none { it.isISOControl() } &&
                    title.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == title) { "Use a nonblank title of at most 120 UTF-16 units, without control characters." }
                fun time(key: String) = OffsetDateTime.parse(string(key)).also {
                    require(it.year in 2000..2099 && it.second == 0 && it.nano == 0) { "Times need an explicit offset and whole minutes, year 2000–2099." }
                }.toInstant().toEpochMilli()
                val begin = time("begin_time"); val end = time("end_time")
                require(end - begin in 60_000L..604_800_000L) { "Event duration must be 1 minute to 7 days." }
                Request(Event, title = title, beginTimeMillis = begin, endTimeMillis = end, completion = completion)
            }
            else -> error("Unknown public app action.")
        }
    }

    fun draftOnlyInstruction(instruction: String): Boolean = Regex(
        "仅预填|只预填|不(?:要)?保存|不要提交|(?:only|just)\\s+(?:prefill|prepare.*draft)|do not save|don't save|leave.*unsaved",
        RegexOption.IGNORE_CASE).containsMatchIn(instruction)

    fun intentArguments(request: Request): List<String> = buildList {
        addAll(listOf("-a", request.registration.action))
        request.registration.mimeType?.let { addAll(listOf("-t", it)) }
        request.phoneNumber?.let { addAll(listOf("-d", "tel:$it")) }
    }

    fun launchArguments(request: Request, userId: Int): List<String> = buildList {
        require(userId == 0) { "Only the verified phone's primary Android user is supported." }
        // A fresh document prevents this ROM's existing editor from silently retaining old extras.
        addAll(listOf("am", "start", "-W", "--user", "$userId", "--display", "0", "-f", "0x18080000"))
        addAll(intentArguments(request))
        addAll(listOf("-n", request.registration.component))
        request.title?.let {
            addAll(listOf("--es", "title", it, "--el", "beginTime", "${request.beginTimeMillis}",
                "--el", "endTime", "${request.endTimeMillis}", "--ez", "allDay", "false"))
        }
    }

    /** Exact fields in a fresh, complete primary-display window; no inference from button state. */
    fun prefillMatches(request: Request, screen: DeviceScreenSnapshot, notBefore: Long, zone: ZoneId,
        expectedCalendarHeading: String = "新建日程"): Boolean {
        if (screen.packageName != request.registration.packageName || screen.sensitive || screen.truncated ||
            !screen.searchComplete || screen.observationErrorCode != null || screen.elementQuery != null ||
            screen.observationScope != null || screen.capturedAt < notBefore || screen.geometry?.displayId != 0 ||
            screen.interactionContext?.complete != true) return false
        val window = screen.interactionContext.windows.singleOrNull { it.id == screen.windowId }
        if (window?.kind != DeviceWindowKind.Application || !window.focused) return false
        fun fields(id: String) = screen.elements.filter { it.resourceId == "${request.registration.packageName}:id/$id" &&
            it.enabled && !it.password && it.bounds.isVisible }
        if (request.phoneNumber != null) {
            val field = fields("digits").singleOrNull { it.editable } ?: return false
            val displayed = field.text.orEmpty()
            return displayed.matches(Regex("[+0-9 ()-]+")) && displayed.filterNot { it == ' ' || it == '(' || it == ')' || it == '-' } == request.phoneNumber
        }
        val allDayRoot = fields("all_day_row").singleOrNull() ?: return false
        fun inAllDayRow(element: DeviceUiElement): Boolean {
            var current: DeviceUiElement? = element
            val visited = mutableSetOf<Int>()
            while (current != null && visited.add(current.index)) {
                if (current.index == allDayRoot.index) return true
                current = current.parentIndex?.let { parent -> screen.elements.singleOrNull { it.index == parent } }
            }
            return false
        }
        // This ROM exposes a labelled, non-checkable parent and one actual child Switch.
        // Unknown or multiple checkable controls remain unverified.
        val allDaySwitch = screen.elements.singleOrNull { inAllDayRow(it) && it.className.endsWith("Switch") &&
            it.checked != null && it.enabled && !it.password && it.bounds.isVisible }
        if (fields("title").singleOrNull { it.editable }?.text != request.title || allDaySwitch?.checked != false ||
            screen.elements.none { it.contentDescription == expectedCalendarHeading || it.text == expectedCalendarHeading }) return false
        fun row(label: String, millis: Long?): Boolean {
            val heading = fields("title").singleOrNull { !it.editable && it.text == label } ?: return false
            val parent = heading.parentIndex ?: return false
            val summary = fields("summary").singleOrNull { it.parentIndex == parent } ?: return false
            val expected = java.time.Instant.ofEpochMilli(requireNotNull(millis)).atZone(zone)
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA))
            return summary.text == expected
        }
        return row("开始", request.beginTimeMillis) && row("结束", request.endTimeMillis)
    }

    fun handoffMessage(result: DeviceToolResult, chinese: Boolean): String {
        val confirmed = result.success && result.actionOutcome == DeviceActionOutcome.ProgressObserved
        val rejected = result.actionOutcome == DeviceActionOutcome.NotDispatched
        return if (chinese) when {
            confirmed -> "已核对预填界面，等待你操作。本次未执行拨号或保存日程；本轮自动操作已暂停。"
            rejected -> "未打开预填界面。" + when (result.errorCode) {
                "invalid_public_app_action" -> "参数不在当前支持范围内。"
                "public_app_version_unsupported" -> "目标应用版本尚未适配。"
                "public_app_entry_unavailable_or_ambiguous" -> "未找到唯一的指定应用编辑入口。"
                "accessibility_unavailable" -> "无障碍服务尚未连接。"
                "public_app_requires_foreground_root" -> "请使用主屏 Root 模式。"
                else -> "当前设备或应用配置不在已验证范围内。"
            } + "本轮已暂停。"
            else -> "已请求打开编辑界面，但预填结果尚未核实。本轮已暂停，请在手机检查；不要直接重复打开、拨号或保存。"
        } else when {
            confirmed -> "Prefilled fields checked. Waiting for you; no call was placed or calendar event saved. Automation is paused."
            rejected -> "The editor was not opened (${result.errorCode ?: "unavailable"}). Automation is paused. Check app support and setup."
            else -> "The editor launch was requested, but its fields remain unverified. Automation is paused. Inspect the phone; do not retry, call or save blindly."
        }
    }
}
